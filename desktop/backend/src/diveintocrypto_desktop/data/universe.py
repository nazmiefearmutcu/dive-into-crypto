"""Binance USDT-M perpetual symbol universe, ranked by 24h quote volume."""

from __future__ import annotations

import asyncio
from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, get_json

# Stablecoin / fiat bases excluded from the scan (no directional edge).
_SKIP_BASES = {"USDC", "BUSD", "TUSD", "DAI", "FDUSD", "USDP", "EUR", "GBP", "USTC"}


async def _perp_symbols() -> dict[str, dict[str, Any]]:
    info: dict[str, Any] = await get_json(f"{FAPI_V1}/exchangeInfo")
    out: dict[str, dict[str, Any]] = {}
    for s in info.get("symbols", []):
        if (
            s.get("contractType") == "PERPETUAL"
            and s.get("status") == "TRADING"
            and s.get("quoteAsset") == "USDT"
            and s.get("baseAsset") not in _SKIP_BASES
        ):
            out[s["symbol"]] = {"base": s["baseAsset"], "quote": s["quoteAsset"]}
    return out


async def list_universe(limit: int | None = None) -> list[dict]:
    """Return ``[{symbol, name, price, ch, quote_volume}]`` sorted by 24h quote
    volume (desc). ``name`` falls back to the base asset.
    """
    perps, tickers = await asyncio.gather(
        _perp_symbols(),
        get_json(f"{FAPI_V1}/ticker/24hr"),
    )
    rows: list[dict] = []
    for t in tickers:
        sym = t.get("symbol")
        if sym not in perps:
            continue
        rows.append(
            {
                "s": sym,
                "name": perps[sym]["base"],
                "price": float(t["lastPrice"]),
                "ch": float(t["priceChangePercent"]),
                "quote_volume": float(t["quoteVolume"]),
            }
        )
    rows.sort(key=lambda r: r["quote_volume"], reverse=True)
    return rows[:limit] if limit else rows
