"""Funding rate + mark/index price from Binance USDT-M public endpoints."""

from __future__ import annotations

from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, get_json


async def premium_index(symbol: str) -> dict[str, float]:
    """Current mark price, index price and last funding rate for one symbol."""
    d: dict[str, Any] = await get_json(f"{FAPI_V1}/premiumIndex", {"symbol": symbol})
    return {
        "mark_price": float(d["markPrice"]),
        "index_price": float(d["indexPrice"]),
        "last_funding_rate": float(d["lastFundingRate"]),
        "next_funding_time": int(d.get("nextFundingTime", 0)),
    }


async def funding_hist(symbol: str, limit: int = 48) -> list[dict]:
    """Recent funding events ``[{t, funding_rate}]`` (t in ms)."""
    rows: list[dict[str, Any]] = await get_json(
        f"{FAPI_V1}/fundingRate", {"symbol": symbol, "limit": limit}
    )
    return [{"t": int(r["fundingTime"]), "funding_rate": float(r["fundingRate"])} for r in rows]
