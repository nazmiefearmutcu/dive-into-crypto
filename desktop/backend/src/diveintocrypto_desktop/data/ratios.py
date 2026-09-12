"""Binance futures/data long-short ratios — the microstructure series the
whale-divergence engine needs. Crypcodile does not store these, so they are
fetched directly from the public ``/futures/data/*`` endpoints (rate-limited).
"""

from __future__ import annotations

import asyncio
from typing import Any

from diveintocrypto_desktop.data.http import FAPI_DATA, get_json

# Binance publishes long-short ratios for these periods only (9 of the 12 TFs).
RATIO_PERIODS = ["5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d"]


async def _series(endpoint: str, symbol: str, period: str, limit: int, key: str) -> list[float]:
    if period not in RATIO_PERIODS:
        period = "5m"
    url = f"{FAPI_DATA}/futures/data/{endpoint}"
    rows: list[dict[str, Any]] = await get_json(
        url, {"symbol": symbol, "period": period, "limit": limit}, rate_limited=True
    )
    out: list[float] = []
    for r in rows:
        try:
            out.append(float(r[key]))
        except (KeyError, TypeError, ValueError):
            continue
    return out


async def global_account_ls(symbol: str, period: str = "5m", limit: int = 48) -> list[float]:
    return await _series("globalLongShortAccountRatio", symbol, period, limit, "longShortRatio")


async def top_account_ls(symbol: str, period: str = "5m", limit: int = 48) -> list[float]:
    return await _series("topLongShortAccountRatio", symbol, period, limit, "longShortRatio")


async def top_position_ls(symbol: str, period: str = "5m", limit: int = 48) -> list[float]:
    return await _series("topLongShortPositionRatio", symbol, period, limit, "longShortRatio")


async def taker_ls(symbol: str, period: str = "5m", limit: int = 48) -> list[float]:
    return await _series("takerlongshortRatio", symbol, period, limit, "buySellRatio")


async def position_ls_timeseries(symbol: str, period: str, limit: int = 60) -> dict[str, list]:
    """Top-trader **position** L/S with timestamps, for divergence alignment.

    Returns ``{"t": [ms...], "v": [ratio...]}`` (oldest→newest).
    """
    if period not in RATIO_PERIODS:
        period = "1h"
    url = f"{FAPI_DATA}/futures/data/topLongShortPositionRatio"
    rows: list[dict[str, Any]] = await get_json(
        url, {"symbol": symbol, "period": period, "limit": limit}, rate_limited=True
    )
    ts: list[int] = []
    vs: list[float] = []
    for r in rows:
        try:
            vs.append(float(r["longShortRatio"]))
            ts.append(int(r["timestamp"]))
        except (KeyError, TypeError, ValueError):
            continue
    return {"t": ts, "v": vs}


async def fetch_ratio_series(symbol: str, period: str = "5m", limit: int = 48) -> dict[str, list[float]]:
    """Fetch all four L/S series concurrently. Keys map onto the data contract:
    ``glob`` (retail crowd), ``acc`` (top-trader accounts), ``pos`` (whale
    positions), ``taker`` (taker buy/sell pressure).
    """
    glob, acc, pos, taker = await asyncio.gather(
        global_account_ls(symbol, period, limit),
        top_account_ls(symbol, period, limit),
        top_position_ls(symbol, period, limit),
        taker_ls(symbol, period, limit),
    )
    return {"glob": glob, "acc": acc, "pos": pos, "taker": taker}


# ── L/S term structure (panel-only: 16 rate-limited calls per symbol) ─────────
TERM_PERIODS = ["5m", "1h", "4h", "1d"]
_TERM_FAMILY_NAMES = ("glob", "acc", "pos", "taker")


def _family_fn(name: str):
    """Resolve the family fetcher AT CALL TIME so test doubles patched onto this
    module (rat.global_account_ls = ...) are honored."""
    fns = {
        "glob": global_account_ls,
        "acc": top_account_ls,
        "pos": top_position_ls,
        "taker": taker_ls,
    }
    return fns[name]


async def ratio_term_structure(symbol: str, periods: list[str] | None = None,
                               min_history: int = 3) -> dict:
    """4 L/S families × TERM_PERIODS — ``{period: {family: latest | None}}``.

    Panel-only (``GET /api/symbol``): 16 futures/data calls per symbol, each on
    the shared rate-limited path. A cell with insufficient history is an honest
    ``None`` (never an imputed level); ``cells_unavailable`` counts them so the
    UI can label partial columns.
    """
    periods = periods or TERM_PERIODS

    async def cell(fn, period: str):
        try:
            series = await fn(symbol, period, limit=48)
        except Exception:
            return None
        if len(series) < min_history:
            return None
        return round(series[-1], 4)

    out: dict[str, dict] = {}
    unavailable = 0
    for period in periods:
        row = await asyncio.gather(
            *(cell(_family_fn(name), period) for name in _TERM_FAMILY_NAMES)
        )
        vals = dict(zip(_TERM_FAMILY_NAMES, row))
        unavailable += sum(1 for v in vals.values() if v is None)
        out[period] = vals
    return {"periods": out, "cells_unavailable": unavailable}
