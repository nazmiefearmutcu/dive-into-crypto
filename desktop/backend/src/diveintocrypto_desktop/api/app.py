"""FastAPI service for the Dive Into Crypto desktop UI.

Localhost-only. Serves the built UI (if present) and a small JSON API backed by the
Crypcodile-fed scanner. A request-log ring buffer feeds the Network Log screen with
real activity; scan results are cached briefly to respect Binance rate limits.
Logging is configured once at startup so engine/scan log records reach stdout.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import asyncio
import logging
import sys
import time
from collections import deque
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from diveintocrypto_desktop.data import universe as uni
from diveintocrypto_desktop.data.http import close_session
from diveintocrypto_desktop.scan import scanner
from diveintocrypto_desktop.scan import symbol_builder as sb

_UI_DIST = Path(__file__).resolve().parents[4] / "ui" / "dist"

# Real request log (most-recent-first) for the Network Log screen.
_LOG: deque[dict] = deque(maxlen=200)

# Brief scan cache to avoid hammering Binance on rapid refreshes.
_scan_cache: dict[str, tuple[float, dict]] = {}
_SCAN_TTL = 20.0

# Per-symbol detail TTL (10s): /api/symbol costs ~21 upstream calls.
_SYMBOL_TTL = 10.0
_SYMBOL_CACHE_MAX = 128

# Live-feed sharing: one rebuild per symbol is shared across all connected
# clients (a fresh object is reused for _LIVE_FRESH_TTL seconds), and a symbol
# whose build keeps failing backs off exponentially instead of retrying every 5s.
_LIVE_FRESH_TTL = 4.0
_LIVE_BACKOFF_BASE = 2.0  # seconds after 1st failure; doubles per extra failure
_LIVE_BACKOFF_CAP = 60.0


class _PerKeyLocks:
    """Per-key asyncio locks, created per running loop.

    Loop-keyed because the TestClient (and ASGI transports in general) may serve
    successive requests on different event loops; a bare ``asyncio.Lock`` would
    stay bound to the first loop that awaited it.
    """

    def __init__(self) -> None:
        self._by_loop: dict[asyncio.AbstractEventLoop, dict[str, asyncio.Lock]] = {}

    def lock(self, key: str) -> asyncio.Lock:
        loop = asyncio.get_running_loop()
        return self._by_loop.setdefault(loop, {}).setdefault(key, asyncio.Lock())


_scan_locks = _PerKeyLocks()
logger = logging.getLogger("trading_bot.api")


def _log(msg: str, status: int = 200, ms: int = 0) -> None:
    _LOG.appendleft({"t": time.strftime("%H:%M:%S"), "m": msg, "s": status, "ms": ms})


def _configure_logging() -> None:
    """Once-per-process stdout logging (no files — the UI owns the log screen)."""
    root = logging.getLogger()
    if not root.handlers:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s | %(levelname)-8s | %(name)-25s | %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
            stream=sys.stdout,
        )
    logging.getLogger("trading_bot").setLevel(logging.INFO)


def create_app() -> FastAPI:
    # Live-feed + symbol state is per-app so tests (and restarts) start clean.
    _live_cache: dict[str, tuple[float, dict]] = {}
    _live_fails: dict[str, int] = {}
    _live_next_attempt: dict[str, float] = {}
    _live_locks = _PerKeyLocks()
    _symbol_cache: dict[str, tuple[float, dict]] = {}

    async def _live_snapshot(symbol: str) -> dict:
        """Build (or reuse) the data-contract object for one /api/live frame.

        Concurrent watchers of the same symbol share a single rebuild via a
        per-symbol lock + freshness window; persistent failures back off
        exponentially (capped at 60s) and the backoff resets on success.
        """
        async with _live_locks.lock(symbol):
            now = time.monotonic()
            cached = _live_cache.get(symbol)
            if cached and "error" not in cached[1] and now - cached[0] < _LIVE_FRESH_TTL:
                return cached[1]
            if now < _live_next_attempt.get(symbol, 0.0):
                # Backoff window: resend the last (error) frame, do NOT re-hit upstream.
                if cached is not None:
                    return cached[1]
            try:
                obj = await sb.build_symbol(symbol)
            except Exception as e:
                streak = _live_fails.get(symbol, 0) + 1
                _live_fails[symbol] = streak
                delay = min(_LIVE_BACKOFF_CAP, _LIVE_BACKOFF_BASE * (2 ** (streak - 1)))
                _live_next_attempt[symbol] = time.monotonic() + delay
                logger.warning("live: %s failed (%s) — backing off %.0fs", symbol, str(e)[:80], delay)
                frame = {"error": "live_fetch_failed", "symbol": symbol}
                _live_cache[symbol] = (time.monotonic(), frame)
                return frame
            _live_fails.pop(symbol, None)
            _live_next_attempt.pop(symbol, None)
            _live_cache[symbol] = (time.monotonic(), obj)
            return obj

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        _configure_logging()
        yield
        await close_session()

    app = FastAPI(title="Dive Into Crypto — Desktop", version="0.1.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware, allow_origins=["http://127.0.0.1", "http://localhost"],
        allow_origin_regex=r"http://(127\.0\.0\.1|localhost):\d+", allow_methods=["GET"], allow_headers=["*"],
    )

    @app.get("/api/health")
    async def health() -> dict:
        return {"ok": True, "service": "dive-into-crypto-desktop", "version": "0.1.0", "ui_built": _UI_DIST.exists()}

    @app.get("/api/universe")
    async def universe(limit: int = 60) -> list[dict]:
        t0 = time.monotonic()
        rows = await uni.list_universe(limit=limit)
        _log(f"GET /fapi/v1/exchangeInfo + ticker/24hr ({len(rows)} perps)", 200, int((time.monotonic() - t0) * 1000))
        return rows

    @app.get("/api/scan")
    async def scan(size: int = 10, universe_limit: int = 30) -> dict:
        key = f"{size}:{universe_limit}"
        now = time.monotonic()
        cached = _scan_cache.get(key)
        if cached and now - cached[0] < _SCAN_TTL:
            return cached[1]
        async with _scan_locks.lock(key):  # per-key: different keys never serialize
            cached = _scan_cache.get(key)
            if cached and time.monotonic() - cached[0] < _SCAN_TTL:
                return cached[1]
            t0 = time.monotonic()
            res = await scanner.scan(size=size, universe_limit=universe_limit)
            ms = int((time.monotonic() - t0) * 1000)
            _log(
                f"Scan complete · {res['universeCount']} universe · {res['scannedCount']} scanned · "
                f"{res['droppedCount']} dropped · {len(res['survivors'])} survivors",
                200, ms,
            )
            _scan_cache[key] = (time.monotonic(), res)
            return res

    @app.get("/api/symbol/{symbol}")
    async def symbol(symbol: str) -> JSONResponse:
        key = symbol.upper()
        t0 = time.monotonic()
        hit = _symbol_cache.get(key)
        if hit and t0 - hit[0] < _SYMBOL_TTL:
            _log(f"Built {key} · {hit[1].get('finalSignal', '?')} (cache)", 200, int((time.monotonic() - t0) * 1000))
            return JSONResponse(hit[1])
        try:
            obj = await sb.build_symbol(key)
        except Exception as e:  # surface honestly, do not fabricate (and do not cache)
            _log(f"GET symbol {symbol} FAILED: {str(e)[:60]}", 502, int((time.monotonic() - t0) * 1000))
            return JSONResponse({"error": "symbol_fetch_failed", "symbol": symbol}, status_code=502)
        if len(_symbol_cache) >= _SYMBOL_CACHE_MAX:
            oldest = min(_symbol_cache, key=lambda k: _symbol_cache[k][0])
            _symbol_cache.pop(oldest, None)
        _symbol_cache[key] = (time.monotonic(), obj)
        _log(f"Built {key} · {obj['finalSignal']} ({obj['confidence']}%)", 200, int((time.monotonic() - t0) * 1000))
        return JSONResponse(obj)

    @app.get("/api/leaders")
    async def leaders(limit: int = 8) -> dict:
        rows = await uni.list_universe(limit=200)
        gainers = sorted(rows, key=lambda r: r["ch"], reverse=True)[:limit]
        losers = sorted(rows, key=lambda r: r["ch"])[:limit]
        return {"gainers": gainers, "losers": losers}

    @app.get("/api/logs")
    async def logs() -> list[dict]:
        return list(_LOG)

    @app.websocket("/api/live")
    async def live(ws: WebSocket) -> None:
        await ws.accept()
        symbol = "BTCUSDT"
        try:
            while True:
                try:
                    msg = await asyncio.wait_for(ws.receive_text(), timeout=0.1)
                    if msg:
                        symbol = msg.strip().upper()
                except asyncio.TimeoutError:
                    pass
                await ws.send_json(await _live_snapshot(symbol))
                await asyncio.sleep(5)
        except WebSocketDisconnect:
            return

    if _UI_DIST.exists():
        app.mount("/", StaticFiles(directory=str(_UI_DIST), html=True), name="ui")

    return app


app = create_app()
