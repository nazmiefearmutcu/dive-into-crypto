"""FastAPI service for the Dive Into Crypto desktop UI.

Localhost-only. Serves the built UI (if present) and a small JSON API backed by the
Crypcodile-fed scanner. A request-log ring buffer feeds the Network Log screen with
real activity; scan results are cached briefly to respect Binance rate limits.
Logging is configured once at startup so engine/scan log records reach stdout.

Depth surfaces: full-universe scans run as a two-phase sweep with an in-memory
progress record (``/api/scan/progress``) and an optional async mode
(``/api/scan?async=1`` returns a ``scan_id`` immediately). Every verdict is
appended to the evidence archive (``scan/evidence.py``) and market-structure
annotations (BTC beta / correlation / clusters) ride along on scan rows.

v0.3.0 additive surfaces: macro/sentiment (``/api/macro``), Deribit options slice
(``/api/options``), evidence depth (Wilson-gated stats, calibration, Brier,
stability, replay grid, IC + weight suggestions), the claims registry
(``/api/claims``), the thin decisions reader (``/api/evidence/decisions``),
multi-TF / historical symbol views (``?tf=`` / ``?end_ms=``) and the lightweight
watch ticker (``/api/pulse``).
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import asyncio
import logging
import sys
import time
from collections import deque
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import deribit as drb
from diveintocrypto_desktop.data import funding as fnd
from diveintocrypto_desktop.data import open_interest as oi_mod
from diveintocrypto_desktop.data import sentiment as senti
from diveintocrypto_desktop.data import universe as uni
from diveintocrypto_desktop.data.http import close_session
from diveintocrypto_desktop.scan import claims as claims_mod
from diveintocrypto_desktop.scan import evidence
from diveintocrypto_desktop.scan import progress as progress_mod
from diveintocrypto_desktop.scan import replay as replay_mod
from diveintocrypto_desktop.scan import scanner
from diveintocrypto_desktop.scan import structure as st
from diveintocrypto_desktop.scan import symbol_builder as sb

# Frozen-app support (PyInstaller): bundled resources live under ``sys._MEIPASS``
# in onefile builds; in a normal checkout the UI sits 4 levels above this file.
_BASE_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[4]))
_UI_DIST = _BASE_DIR / "ui" / "dist"

VERSION = "0.3.0"

# Real request log (most-recent-first) for the Network Log screen.
_LOG: deque[dict] = deque(maxlen=200)

# Brief scan cache to avoid hammering Binance on rapid refreshes.
_scan_cache: dict[str, tuple[float, dict]] = {}
_SCAN_TTL = 20.0

# Per-symbol detail TTL (10s): /api/symbol costs ~21+ upstream calls.
_SYMBOL_TTL = 10.0
_SYMBOL_CACHE_MAX = 128

# /api/pulse: 10s cache (a watch list refresh must stay cheap).
_PULSE_TTL = 10.0
_PULSE_MAX_SYMBOLS = 20

# Async scans: the fire-and-forget task keeps a strong reference via this set
# (a bare create_task can be garbage-collected mid-flight); results are parked
# here for the progress/result endpoints with a generous TTL.
_ASYNC_RESULT_TTL = 600.0
_ASYNC_RESULT_MAX = 8

# Evidence grading horizon validation pattern.
_HORIZON_PATTERN = r"^(1h|4h|24h)$"

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
    _async_tasks: set[asyncio.Task] = set()
    _async_results: dict[str, tuple[float, dict]] = {}
    _pulse_cache: dict[tuple[str, ...], tuple[float, list[dict]]] = {}

    def _park_result(scan_id: str, res: dict) -> None:
        now = time.monotonic()
        _async_results[scan_id] = (now, res)
        # prune expired / oldest-overflow entries
        for sid in [s for s, (ts, _) in _async_results.items() if now - ts > _ASYNC_RESULT_TTL]:
            _async_results.pop(sid, None)
        while len(_async_results) > _ASYNC_RESULT_MAX:
            oldest = min(_async_results, key=lambda s: _async_results[s][0])
            _async_results.pop(oldest, None)

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
        await drb.close_session()

    app = FastAPI(title="Dive Into Crypto — Desktop", version=VERSION, lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware, allow_origins=["http://127.0.0.1", "http://localhost"],
        allow_origin_regex=r"http://(127\.0\.0\.1|localhost):\d+", allow_methods=["GET", "POST"], allow_headers=["*"],
    )

    @app.get("/api/health")
    async def health() -> dict:
        return {"ok": True, "service": "dive-into-crypto-desktop", "version": VERSION, "ui_built": _UI_DIST.exists()}

    @app.get("/api/universe")
    async def universe(limit: int = 60) -> list[dict]:
        t0 = time.monotonic()
        rows = await uni.list_universe(limit=limit)
        _log(f"GET /fapi/v1/exchangeInfo + ticker/24hr ({len(rows)} perps)", 200, int((time.monotonic() - t0) * 1000))
        return rows

    def _scan_summary(res: dict, ms: int) -> dict:
        return {
            "survivors": len(res.get("survivors") or []),
            "eliminated": len(res.get("eliminated") or []),
            "universeCount": res.get("universeCount"),
            "scannedCount": res.get("scannedCount"),
            "droppedCount": res.get("droppedCount"),
            "duration_ms": ms,
        }

    async def _run_and_archive(
        rec: "progress_mod.ScanProgress", size: int, universe_limit: int, depth_top: int
    ) -> dict:
        t0 = time.monotonic()
        try:
            res = await scanner.scan(size=size, universe_limit=universe_limit, depth_top=depth_top, progress=rec)
        except Exception as e:
            rec.fail(str(e))
            raise
        ms = int((time.monotonic() - t0) * 1000)
        _log(
            f"Scan complete · {res['universeCount']} universe · {res['scannedCount']} scanned · "
            f"{res['droppedCount']} dropped · {len(res['survivors'])} survivors",
            200, ms,
        )
        try:  # evidence must never break scanning
            await asyncio.to_thread(evidence.archive_scan, res)
        except Exception as e:  # pragma: no cover - archive_scan already swallows
            logger.warning("evidence archive hook failed: %s", str(e)[:80])
        rec.finish(_scan_summary(res, ms))
        return res

    @app.get("/api/scan")
    async def scan(
        size: int = Query(10, ge=1, le=100),
        universe_limit: int = Query(30, ge=1, le=500),
        depth_top: int = Query(scanner.DEFAULT_DEPTH_TOP, ge=1, le=scanner.MAX_DEPTH_TOP),
        async_mode: bool = Query(False, alias="async"),
    ) -> dict:
        # Fire-and-forget mode: enqueue the scan, answer with a scan_id immediately.
        # Progress lives at /api/scan/progress, the result at /api/scan/result.
        if async_mode:
            rec = progress_mod.start(meta={
                "mode": "async", "size": size, "universe_limit": universe_limit, "depth_top": depth_top,
            })
            scan_id = rec.scan_id

            async def _task() -> None:
                try:
                    res = await _run_and_archive(rec, size, universe_limit, depth_top)
                    _park_result(scan_id, res)
                except Exception as e:
                    _log(f"Async scan {scan_id} FAILED: {str(e)[:60]}", 502, 0)

            task = asyncio.create_task(_task())
            _async_tasks.add(task)
            task.add_done_callback(_async_tasks.discard)
            return {
                "scan_id": scan_id,
                "status": "started",
                "mode": "async",
                "poll": f"/api/scan/progress?scan_id={scan_id}",
                "result": f"/api/scan/result?scan_id={scan_id}",
            }

        key = f"{size}:{universe_limit}:{depth_top}"
        now = time.monotonic()
        cached = _scan_cache.get(key)
        if cached and now - cached[0] < _SCAN_TTL:
            return cached[1]
        async with _scan_locks.lock(key):  # per-key: different keys never serialize
            cached = _scan_cache.get(key)
            if cached and time.monotonic() - cached[0] < _SCAN_TTL:
                return cached[1]
            rec = progress_mod.start(meta={
                "mode": "sync", "size": size, "universe_limit": universe_limit, "depth_top": depth_top,
            })
            res = await _run_and_archive(rec, size, universe_limit, depth_top)
            _scan_cache[key] = (time.monotonic(), res)
            return res

    @app.get("/api/scan/progress")
    async def scan_progress(scan_id: str | None = None) -> JSONResponse:
        rec = progress_mod.get(scan_id)
        if rec is None:
            return JSONResponse({"error": "scan_not_found"}, status_code=404)
        snap = rec.snapshot()
        snap["result_available"] = rec.scan_id in _async_results
        return JSONResponse(snap)

    @app.get("/api/scan/result")
    async def scan_result(scan_id: str) -> JSONResponse:
        hit = _async_results.get(scan_id)
        if hit is None:
            return JSONResponse({"error": "scan_not_found"}, status_code=404)
        ts, res = hit
        if time.monotonic() - ts > _ASYNC_RESULT_TTL:
            _async_results.pop(scan_id, None)
            return JSONResponse({"error": "scan_expired"}, status_code=410)
        return JSONResponse(res)

    @app.get("/api/evidence")
    async def evidence_summary(horizon: str = Query(evidence.DEFAULT_HORIZON, pattern=_HORIZON_PATTERN)) -> dict:
        try:
            return await asyncio.to_thread(evidence.summary, horizon)
        except Exception as e:  # honest failure, never fabricated stats
            _log(f"GET /api/evidence FAILED: {str(e)[:60]}", 502, 0)
            return JSONResponse({"error": "evidence_unavailable", "detail": str(e)[:80]}, status_code=502)

    @app.post("/api/evidence/grade")
    async def evidence_grade(horizon: str = Query(evidence.DEFAULT_HORIZON, pattern=_HORIZON_PATTERN)) -> dict:
        try:
            out = await evidence.grade(horizon)
        except Exception as e:
            _log(f"POST /api/evidence/grade FAILED: {str(e)[:60]}", 502, 0)
            return JSONResponse({"error": "grading_failed", "detail": str(e)[:80]}, status_code=502)
        _log(f"Graded {out['graded']} verdicts @ {out['horizon']} ({out['remaining']} remaining)", 200, 0)
        try:
            out["summary"] = await asyncio.to_thread(evidence.summary, horizon)
        except Exception:
            pass
        return out

    @app.get("/api/structure")
    async def structure(limit: int = Query(60, ge=1, le=250)) -> dict:
        t0 = time.monotonic()
        try:
            out = await st.structure_summary(limit=limit)
        except Exception as e:
            _log(f"GET /api/structure FAILED: {str(e)[:60]}", 502, int((time.monotonic() - t0) * 1000))
            return JSONResponse({"error": "structure_unavailable", "detail": str(e)[:80]}, status_code=502)
        _log(f"Market structure · {out['count']} symbols · {len(out['clusters'])} clusters", 200,
             int((time.monotonic() - t0) * 1000))
        return out

    @app.get("/api/symbol/{symbol}")
    async def symbol(
        symbol: str,
        tf: str = Query("1h"),
        end_ms: int | None = Query(None, ge=0),
    ) -> JSONResponse:
        """Full data-contract object. ``tf`` selects the primary timeframe (default
        ``1h``); ``end_ms`` requests a HISTORICAL view as of that instant (klines
        are fetched with that ``endTime``)."""
        key = symbol.upper()
        if tf not in kl.TF_LIST:
            raise HTTPException(status_code=422, detail=f"tf must be one of {kl.TF_LIST}")
        cache_key = f"{key}:{tf}:{end_ms or 0}"
        t0 = time.monotonic()
        hit = _symbol_cache.get(cache_key)
        if hit and t0 - hit[0] < _SYMBOL_TTL:
            _log(f"Built {key} · {hit[1].get('finalSignal', '?')} (cache)", 200, int((time.monotonic() - t0) * 1000))
            return JSONResponse(hit[1])
        try:
            obj = await sb.build_symbol(key, primary_tf=tf, end_ms=end_ms)
        except Exception as e:  # surface honestly, do not fabricate (and do not cache)
            _log(f"GET symbol {symbol} FAILED: {str(e)[:60]}", 502, int((time.monotonic() - t0) * 1000))
            return JSONResponse({"error": "symbol_fetch_failed", "symbol": symbol}, status_code=502)
        if len(_symbol_cache) >= _SYMBOL_CACHE_MAX:
            oldest = min(_symbol_cache, key=lambda k: _symbol_cache[k][0])
            _symbol_cache.pop(oldest, None)
        _symbol_cache[cache_key] = (time.monotonic(), obj)
        _log(f"Built {key} · {obj['finalSignal']} ({obj['confidence']}%)", 200, int((time.monotonic() - t0) * 1000))
        return JSONResponse(obj)

    @app.get("/api/pulse")
    async def pulse(symbols: str = Query(..., description="Comma-separated symbols, e.g. BTC,ETH (≤20)")) -> list[dict]:
        """Lightweight watch-list ticker: ``[{s, price, ch, funding_rate,
        next_funding_time_ms, oi_delta_pct}]`` (10s cache, ≤20 symbols).

        ``price``/``funding_rate``/``next_funding_time_ms`` come from ONE batch
        premiumIndex call; ``ch`` rides the cached universe; ``oi_delta_pct`` is
        the 5m open-interest change over the last ~2h (null when unavailable)."""
        raw = [s.strip().upper() for s in symbols.split(",") if s.strip()]
        if not raw:
            raise HTTPException(status_code=422, detail="symbols required")
        if len(raw) > _PULSE_MAX_SYMBOLS:
            raise HTTPException(status_code=422, detail=f"max {_PULSE_MAX_SYMBOLS} symbols")
        syms = [s if s.endswith("USDT") else f"{s}USDT" for s in raw]
        key = tuple(syms)
        now = time.monotonic()
        cached = _pulse_cache.get(key)
        if cached and now - cached[0] < _PULSE_TTL:
            return cached[1]

        try:
            prem_all = await fnd.premium_index_all()
        except Exception as e:
            _log(f"GET /api/pulse FAILED: {str(e)[:60]}", 502, 0)
            return JSONResponse({"error": "pulse_unavailable", "detail": str(e)[:80]}, status_code=502)

        ch_map = {}
        try:
            ch_map = {r["s"]: r.get("ch") for r in await uni.list_universe(limit=None)}
        except Exception:
            pass

        async def one(sym: str) -> dict:
            prem = prem_all.get(sym) or {}
            ch = ch_map.get(sym)
            oi_delta = None
            try:
                hist = await oi_mod.fetch_oi_hist(sym, "5m", limit=24)
                if len(hist) >= 2 and hist[0]["oi"]:
                    oi_delta = round((hist[-1]["oi"] - hist[0]["oi"]) / hist[0]["oi"] * 100.0, 3)
            except Exception:
                pass
            return {
                "s": sym,
                "price": prem.get("mark_price"),
                "ch": ch,
                "funding_rate": prem.get("last_funding_rate"),
                "next_funding_time_ms": prem.get("next_funding_time") or None,
                "oi_delta_pct": oi_delta,
            }

        rows = list(await asyncio.gather(*(one(s) for s in syms)))
        _pulse_cache[key] = (time.monotonic(), rows)
        _log(f"Pulse · {len(rows)} symbols", 200, 0)
        return rows

    @app.get("/api/macro")
    async def macro() -> dict:
        """Sentiment/macro backdrop — independent failure domain from market data.

        ``fng`` (alternative.me Fear & Greed, 1h cache / daily cadence),
        ``stablecoin`` (exchange-native proxy off the shared ticker payload) and
        ``defillama`` (best-effort aggregate) each fail independently."""
        t0 = time.monotonic()
        try:
            out = await senti.macro_snapshot()
        except Exception as e:
            _log(f"GET /api/macro FAILED: {str(e)[:60]}", 502, int((time.monotonic() - t0) * 1000))
            return JSONResponse({"error": "macro_unavailable", "detail": str(e)[:80]}, status_code=502)
        _log("Macro snapshot", 200, int((time.monotonic() - t0) * 1000))
        return out

    @app.get("/api/options")
    async def options() -> dict:
        """Deribit staged slice (BTC + ETH): DVOL level, put/call OI ratio, 30d ATM IV.

        Independent venue: its own timeout + circuit-breaker. Unreachable → the
        currency's block is ``{"unavailable": "deribit_unreachable"}`` — never a
        fabricated number, never a blocker for other data."""
        return await drb.options_overview()

    @app.get("/api/evidence/stability")
    async def evidence_stability() -> list[dict]:
        """Per-symbol verdict self-agreement over the last 8 archived records."""
        return await asyncio.to_thread(evidence.stability)

    @app.get("/api/evidence/decisions")
    async def evidence_decisions(
        symbol: str | None = Query(None),
        from_ms: int | None = Query(None, ge=0),
        to_ms: int | None = Query(None, ge=0),
        limit: int = Query(500, ge=1, le=2000),
    ) -> list[dict]:
        """Thin archive reader: archived verdicts for ``symbol`` in a ts window."""
        sym = symbol.upper() if symbol else None
        return await asyncio.to_thread(evidence.decisions, sym, from_ms, to_ms, limit)

    @app.post("/api/evidence/replay")
    async def evidence_replay(horizon: str = Query(evidence.DEFAULT_HORIZON, pattern=_HORIZON_PATTERN)) -> dict:
        """Counterfactual threshold grid over the archive (bounded, in-thread).

        STRICTLY report-only: nothing writes to engine configuration; the
        response carries ``report_only: true``."""
        return await asyncio.to_thread(replay_mod.replay_grid, horizon)

    @app.get("/api/evidence/ic")
    async def evidence_ic(horizon: str = Query(evidence.DEFAULT_HORIZON, pattern=_HORIZON_PATTERN)) -> dict:
        """Per-indicator Spearman IC vs graded forward return (report-only)."""
        return await asyncio.to_thread(replay_mod.ic_table, None, None, horizon)

    @app.post("/api/evidence/suggest-weights")
    async def evidence_suggest_weights(horizon: str = Query(evidence.DEFAULT_HORIZON, pattern=_HORIZON_PATTERN)) -> dict:
        """IC-based weight suggestions (normalized, capped 3× shipped).

        Written to ``runtime/weight_suggestions.json``; NEVER read by the engine."""
        ic = await asyncio.to_thread(replay_mod.ic_table, None, None, horizon)
        out = await asyncio.to_thread(replay_mod.suggest_weights, ic, None)
        path = replay_mod.write_suggestions(out)
        out["written_to"] = str(path) if path else None
        return out

    @app.get("/api/claims")
    async def claims_list() -> dict:
        """Registered claims + current evaluation (PENDING/CONFIRMED/REFUTED).

        Evaluated ONLY on grades with ts > the claim's registered_at."""
        rows = await asyncio.to_thread(claims_mod.list_evaluations)
        return {"claims": rows, "generated_at": evidence._iso(time.time())}

    @app.post("/api/claims", status_code=201)
    async def claims_register(claim: dict) -> dict:
        """Register a NEW claim (registry files are immutable by convention)."""
        try:
            path = await asyncio.to_thread(claims_mod.register, claim)
        except claims_mod.ClaimExistsError as e:
            return JSONResponse({"error": "claim_exists", "detail": str(e)}, status_code=409)
        except claims_mod.ClaimError as e:
            return JSONResponse({"error": "invalid_claim", "detail": str(e)}, status_code=422)
        return {"registered": True, "file": path.name, "claim": claim}

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
