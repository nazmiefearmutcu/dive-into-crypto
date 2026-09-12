"""Order-book snapshot (panel-only) from the public ``/fapi/v1/depth`` endpoint.

``limit=50`` (request weight 2) gives enough depth to measure book pressure at
the 0.5% / 1% / 2% bands around the mid price: each band's imbalance is
``Σbid − Σask`` normalized by the band's total notional (−1 ask-heavy … +1
bid-heavy). ``notional_1pct`` is the summed bid+ask notional (USDT) inside the
1% band — a thin-book honesty read. A book too thin to fill even the 0.5% band
reports ``{"unavailable": "book_too_thin"}`` instead of degenerate numbers.

Panel-only by design: served by ``GET /api/symbol/{symbol}``, never attached to
scan rows (a full scan must not multiply depth calls). Cached 10 seconds.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, get_json

logger = logging.getLogger("trading_bot.data.orderbook")

DEPTH_LIMIT = 50
CACHE_TTL = 10.0
BANDS_PCT = [0.005, 0.01, 0.02]
THIN_BOOK_MIN = 1000.0  # USDT notional inside the 0.5% band below which we refuse to read

_cache: dict[str, tuple[float, dict]] = {}


def reset_cache() -> None:
    """Drop the snapshot cache (test hook)."""
    _cache.clear()


async def fetch_depth(symbol: str, limit: int = DEPTH_LIMIT) -> dict:
    """Raw depth payload ``{bids: [[p, q]…], asks: [[p, q]…]}``."""
    return await get_json(f"{FAPI_V1}/depth", {"symbol": symbol, "limit": limit})


def parse_levels(rows: Any) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    for r in rows or []:
        try:
            p, q = float(r[0]), float(r[1])
        except (IndexError, TypeError, ValueError):
            continue
        if p > 0 and q > 0:
            out.append((p, q))
    return out


def book_panel(bids: list[tuple[float, float]], asks: list[tuple[float, float]],
               now_ms: int | None = None) -> dict:
    """Imbalance bands + 1% notional from parsed level lists (pure)."""
    if not bids or not asks:
        return {"unavailable": "book_too_thin"}
    best_bid, best_ask = bids[0][0], asks[0][0]
    if best_bid <= 0 or best_ask <= 0:
        return {"unavailable": "book_too_thin"}
    mid = (best_bid + best_ask) / 2.0

    bands: dict[str, float] = {}
    notional_1pct = None
    total05 = 0.0
    for band in BANDS_PCT:
        bid_sum = sum(p * q for p, q in bids if p >= mid * (1 - band))
        ask_sum = sum(p * q for p, q in asks if p <= mid * (1 + band))
        total = bid_sum + ask_sum
        if band == 0.005:
            total05 = total
        if band == 0.01:
            notional_1pct = round(total, 2)
        bands[f"{int(band * 1000) / 10:g}%"] = (
            round((bid_sum - ask_sum) / total, 4) if total > 0 else None
        )

    if total05 < THIN_BOOK_MIN:
        return {"unavailable": "book_too_thin"}

    import time as _time

    ts = int(now_ms if now_ms is not None else _time.time() * 1000)
    return {
        "imbalance": bands,
        "notional_1pct": notional_1pct,
        "mid": mid,
        "ts": ts,
    }


async def snapshot(symbol: str) -> dict:
    """Cached ``book`` block (10s TTL on SUCCESS only); ``{"unavailable": reason}``
    on failure. Errors are never cached — a failed fetch is re-attempted on the
    next call instead of serving the stale failure for the TTL window.
    """
    now = time.monotonic()
    cached = _cache.get(symbol)
    if cached and now - cached[0] < CACHE_TTL:
        return cached[1]
    try:
        raw = await fetch_depth(symbol)
        panel = book_panel(parse_levels(raw.get("bids")), parse_levels(raw.get("asks")))
    except Exception as e:
        logger.warning("orderbook: %s fetch failed — %s", symbol, str(e)[:80])
        panel = {"unavailable": str(e)[:120]}
    if "unavailable" in panel:
        return panel  # never cached — re-attempted on the next call
    _cache[symbol] = (time.monotonic(), panel)
    return panel
