"""FastAPI service for the Dive Into Crypto desktop UI.

Localhost-only. Serves the built UI (if present) and a small JSON API backed by the
Crypcodile-fed scanner. A request-log ring buffer feeds the Network Log screen with
real activity; scan results are cached briefly to respect Binance rate limits.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import asyncio
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
_scan_lock = asyncio.Lock()
_SCAN_TTL = 20.0


def _log(msg: str, status: int = 200, ms: int = 0) -> None:
    _LOG.appendleft({"t": time.strftime("%H:%M:%S"), "m": msg, "s": status, "ms": ms})


def create_app() -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
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
        async with _scan_lock:
            cached = _scan_cache.get(key)
            if cached and time.monotonic() - cached[0] < _SCAN_TTL:
                return cached[1]
            t0 = time.monotonic()
            res = await scanner.scan(size=size, universe_limit=universe_limit)
            ms = int((time.monotonic() - t0) * 1000)
            _log(f"Scan complete · {res['universeCount']} symbols · {len(res['survivors'])} survivors", 200, ms)
            _scan_cache[key] = (time.monotonic(), res)
            return res

    @app.get("/api/symbol/{symbol}")
    async def symbol(symbol: str) -> JSONResponse:
        t0 = time.monotonic()
        try:
            obj = await sb.build_symbol(symbol.upper())
        except Exception as e:  # surface honestly, do not fabricate
            _log(f"GET symbol {symbol} FAILED: {str(e)[:60]}", 502, int((time.monotonic() - t0) * 1000))
            return JSONResponse({"error": "symbol_fetch_failed", "symbol": symbol}, status_code=502)
        _log(f"Built {symbol.upper()} · {obj['finalSignal']} ({obj['confidence']}%)", 200, int((time.monotonic() - t0) * 1000))
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
                try:
                    obj = await sb.build_symbol(symbol)
                    await ws.send_json(obj)
                except Exception:
                    await ws.send_json({"error": "live_fetch_failed", "symbol": symbol})
                await asyncio.sleep(5)
        except WebSocketDisconnect:
            return


    if _UI_DIST.exists():
        app.mount("/", StaticFiles(directory=str(_UI_DIST), html=True), name="ui")

    return app


app = create_app()
