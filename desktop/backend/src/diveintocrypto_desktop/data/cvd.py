"""Cumulative Volume Delta (CVD) over the public aggTrades feed.

``GET /fapi/v1/aggTrades?symbol=&limit=1000`` is public (no keys). Each aggregate
trade carries the aggressor flag: ``m=true`` means the buyer was the maker, i.e.
a SELL-taker trade; ``m=false`` is a BUY-taker trade. The rolling CVD is
Σ(+qty · buys − qty · sells) over the lookback window (last 15 minutes of the
most recent ≤1000 aggTrades), with the raw buy/sell volume split alongside.

Cached ~10s per symbol. On any upstream failure the snapshot is an honest
``{"unavailable": <reason>}`` — no zeros dressed as data. ``DIVE_FAPI_BASE``
applies (the fetch goes through the shared session + retry plumbing).
"""

from __future__ import annotations

import logging
import time

from diveintocrypto_desktop.data.http import FAPI_V1, get_json

logger = logging.getLogger("trading_bot.data.cvd")

AGG_TRADES_LIMIT = 1000       # Binance max per page
WINDOW_SECONDS = 15 * 60      # rolling lookback
SERIES_POINTS = 60            # downsampled cumulative-delta series cap
CACHE_TTL = 10.0              # seconds a snapshot is reused

_cache: dict[str, tuple[float, dict]] = {}


def reset_cache() -> None:
    """Drop the snapshot cache (test hook)."""
    _cache.clear()


async def fetch_agg_trades(symbol: str, limit: int = AGG_TRADES_LIMIT) -> list:
    """Raw aggTrades page (list of trade dicts) — retries on transient failures."""
    return await get_json(f"{FAPI_V1}/aggTrades", {"symbol": symbol, "limit": limit})


def parse_trades(payload: list) -> list[dict]:
    """Normalise an aggTrades payload to ``[{p, q, t, side}]`` (side +1 buy-taker,
    −1 sell-taker). Handles both the object form ``{p, q, T, m}`` and the legacy
    positional-array form ``[price, qty, tradeId, time, isBuyerMaker]``; corrupt
    rows are skipped, never guessed.
    """
    out: list[dict] = []
    for row in payload or []:
        try:
            if isinstance(row, dict):
                p, q = float(row["p"]), float(row["q"])
                t = int(row["T"])
                buyer_is_maker = bool(row["m"])
            else:
                p, q = float(row[0]), float(row[1])
                t = int(row[3])
                buyer_is_maker = bool(row[4])
        except (KeyError, IndexError, TypeError, ValueError):
            continue
        if not t or q < 0:
            continue
        out.append({"p": p, "q": q, "t": t, "side": -1 if buyer_is_maker else 1})
    out.sort(key=lambda r: r["t"])  # defensive: the window math needs monotonic time
    return out


def rolling_cvd(trades: list[dict], window_seconds: int = WINDOW_SECONDS) -> dict:
    """Rolling CVD + buy/sell split over the trailing ``window_seconds`` of trades."""
    if not trades:
        return {}
    last_t = trades[-1]["t"]
    cutoff = last_t - window_seconds * 1000
    window = [t for t in trades if t["t"] >= cutoff]
    if not window:
        return {}

    buy_vol = sum(t["q"] for t in window if t["side"] > 0)
    sell_vol = sum(t["q"] for t in window if t["side"] < 0)

    cum: list[float] = []
    d = 0.0
    for t in window:
        d += t["side"] * t["q"]
        cum.append(d)

    step = max(1, len(cum) // SERIES_POINTS)
    series = cum[::step]
    if series[-1] != cum[-1]:
        series.append(cum[-1])

    return {
        "window_trades": len(window),
        "cvd": round(d, 6),
        "buy_vol": round(buy_vol, 6),
        "sell_vol": round(sell_vol, 6),
        "delta_series": [round(v, 6) for v in series],
        "first_t": window[0]["t"],
        "last_t": last_t,
        "window_seconds": round((last_t - window[0]["t"]) / 1000, 1),
    }


async def snapshot(symbol: str) -> dict:
    """Cached CVD snapshot for ``symbol``; ``{"unavailable": reason}`` on failure."""
    now = time.monotonic()
    cached = _cache.get(symbol)
    if cached and now - cached[0] < CACHE_TTL:
        return cached[1]
    try:
        raw = await fetch_agg_trades(symbol)
        snap = rolling_cvd(parse_trades(raw))
        if not snap:
            snap = {"unavailable": "no_trades_in_window"}
    except Exception as e:
        logger.warning("cvd: %s fetch failed — %s", symbol, str(e)[:80])
        snap = {"unavailable": str(e)[:120]}
    _cache[symbol] = (time.monotonic(), snap)
    return snap
