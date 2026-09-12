"""Spot-perp lead/lag (Binance SPOT market data — a DIFFERENT host).

Perp prices lead spot for some pairs and trail for others; this module measures
which. From the spot exchange (``https://api.binance.com``, override with
``DIVE_SPOT_BASE``) it pulls the spot 1h kline tail (48 bars) + 24h ticker and
pairs them with the perp data the caller already has:

  * ``premium_pct``  — perp price premium over spot, percent;
  * ``ret_spread_48h`` — perp 48h return minus spot 48h return;
  * ``lead``         — ``spot`` | ``perp`` | ``mixed`` from comparing lag-1
    cross-correlations (spot→perp vs perp→spot); |diff| inside ``LEAD_MARGIN``
    is honest ``mixed``.

Perp-only listings are the NORMAL state here, not an error: the snapshot is
``{"unavailable": "no_spot_market"}`` (Binance answers -1121 Invalid symbol).
"""

from __future__ import annotations

import asyncio
import logging
import math
import os
import time
from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, get_json

logger = logging.getLogger("trading_bot.data.spot")

SPOT_BASE = os.environ.get("DIVE_SPOT_BASE", "https://api.binance.com").rstrip("/")
SPOT_V3 = f"{SPOT_BASE}/api/v3"

KLINE_LIMIT = 48
LEAD_MARGIN = 0.05
CACHE_TTL = 60.0

_cache: dict[str, tuple[float, dict]] = {}


def reset_cache() -> None:
    """Drop the snapshot cache (test hook)."""
    _cache.clear()


async def _spot_json(path: str, params: dict[str, Any]) -> Any:
    return await get_json(f"{SPOT_V3}{path}", params)


def log_returns(closes: list[float]) -> list[float]:
    out: list[float] = []
    for a, b in zip(closes, closes[1:]):
        if a > 0 and b > 0:
            out.append(math.log(b / a))
    return out


def _corr(a: list[float], b: list[float]) -> float | None:
    m = len(a)
    ma, mb = sum(a) / m, sum(b) / m
    da = [x - ma for x in a]
    db = [x - mb for x in b]
    va = sum(x * x for x in da)
    vb = sum(x * x for x in db)
    if va <= 0 or vb <= 0:
        return None
    return sum(x * y for x, y in zip(da, db)) / (va * vb) ** 0.5


def classify_lead(spot_rets: list[float], perp_rets: list[float],
                  margin: float = LEAD_MARGIN) -> str | None:
    """``spot`` | ``perp`` | ``mixed`` from lag-1 cross-correlation asymmetry.

    corr(spot[t], perp[t+1]) > corr(perp[t], spot[t+1]) + margin ⇒ spot moves
    first. Needs ≥ 10 paired returns; degenerate (zero-variance) series → None.
    """
    n = min(len(spot_rets), len(perp_rets)) - 1
    if n < 10:
        return None
    s, p = spot_rets, perp_rets
    c_sp = _corr(s[:n], p[1:n + 1])
    c_ps = _corr(p[:n], s[1:n + 1])
    if c_sp is None or c_ps is None:
        return None
    if c_sp - c_ps > margin:
        return "spot"
    if c_ps - c_sp > margin:
        return "perp"
    return "mixed"


def parse_kline_closes(rows: list) -> list[float]:
    closes: list[float] = []
    for r in rows or []:
        try:
            v = float(r[4])
        except (IndexError, TypeError, ValueError):
            continue
        if v > 0:
            closes.append(v)
    return closes


def spot_perp_block(spot_closes: list[float], spot_price: float | None,
                    perp_price: float, perp_closes: list[float]) -> dict:
    """Assemble the ``spot_perp`` block (pure)."""
    premium = None
    if spot_price and spot_price > 0 and perp_price > 0:
        premium = round((perp_price - spot_price) / spot_price * 100.0, 4)

    spread = None
    lead = None
    s_rets = log_returns(spot_closes)
    p_rets = log_returns(perp_closes)
    if len(spot_closes) >= 2 and spot_closes[0] > 0 and len(perp_closes) >= 2 and perp_closes[0] > 0:
        spread = round(
            (perp_closes[-1] / perp_closes[0] - 1.0) - (spot_closes[-1] / spot_closes[0] - 1.0), 6
        )
        lead = classify_lead(s_rets, p_rets)
    if premium is None and spread is None and lead is None:
        return {"unavailable": "insufficient_history"}
    return {
        "premium_pct": premium,
        "ret_spread_48h": spread,
        "lead": lead,
    }


async def snapshot(symbol: str, perp_price: float, perp_closes: list[float]) -> dict:
    """Cached ``spot_perp`` block for ``symbol`` (60s TTL on SUCCESS only).

    ``{"unavailable": "no_spot_market"}`` for perp-only listings (normal state);
    other failures report their reason. Errors and unavailable states are never
    cached — every call after a failure re-attempts upstream (so a listing that
    gains a spot market is picked up on the very next call, not an hour later).
    """
    now = time.monotonic()
    cached = _cache.get(symbol)
    if cached and now - cached[0] < CACHE_TTL:
        return cached[1]
    try:
        klines, ticker = await asyncio.gather(
            _spot_json("/klines", {"symbol": symbol, "interval": "1h", "limit": KLINE_LIMIT}),
            _spot_json("/ticker/24hr", {"symbol": symbol}),
        )
        closes = parse_kline_closes(klines)
        spot_price = None
        try:
            spot_price = float(ticker.get("lastPrice"))
        except (KeyError, TypeError, ValueError):
            pass
        block = spot_perp_block(closes, spot_price, perp_price, perp_closes)
    except Exception as e:
        text = str(e)
        if "-1121" in text or "Invalid symbol" in text:
            block = {"unavailable": "no_spot_market"}
        else:
            logger.warning("spot: %s fetch failed — %s", symbol, text[:80])
            block = {"unavailable": f"spot_unreachable: {text[:80]}"}
    if "unavailable" in block:
        return block  # never cached — re-attempted on the next call
    _cache[symbol] = (time.monotonic(), block)
    return block
