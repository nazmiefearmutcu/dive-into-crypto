"""Binance USDT-M perpetual symbol universe, ranked by 24h quote volume."""

from __future__ import annotations

import asyncio
import time
from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, LoopBoundLock, get_json

# Stablecoin / fiat bases excluded from the scan (no directional edge).
_SKIP_BASES = {"USDC", "BUSD", "TUSD", "DAI", "FDUSD", "USDP", "EUR", "GBP", "USTC"}

# exchangeInfo + all-symbol ticker costs ~41 request weight; cache the full
# universe briefly so /api/universe, /api/leaders AND scans share one fetch.
# The RAW ticker payload + perp map are cached alongside so dependent surfaces
# (sentiment/stablecoin proxy) reuse them with ZERO extra upstream calls.
_UNIVERSE_TTL = 30.0
_cache: list[dict] | None = None
_cache_ts: float = 0.0
_cache_lock = LoopBoundLock()

_raw_tickers: list[dict] | None = None
_raw_tickers_ts: float = 0.0
_perps: dict[str, dict] | None = None
_perps_ts: float = 0.0


def reset_universe_cache() -> None:
    """Drop the cached universe + shared raw payload (test hook)."""
    global _cache, _cache_ts, _raw_tickers, _raw_tickers_ts, _perps, _perps_ts
    _cache = None
    _cache_ts = 0.0
    _raw_tickers = None
    _raw_tickers_ts = 0.0
    _perps = None
    _perps_ts = 0.0


async def perp_symbols() -> dict[str, dict[str, Any]]:
    """Tradable USDT perps ``{symbol: {base, quote}}`` (30s TTL cache)."""
    global _perps, _perps_ts
    now = time.monotonic()
    if _perps is None or now - _perps_ts >= _UNIVERSE_TTL:
        async with _cache_lock:
            now = time.monotonic()
            if _perps is None or now - _perps_ts >= _UNIVERSE_TTL:
                _perps = await _perp_symbols()
                _perps_ts = now
    return _perps or {}


async def all_tickers() -> list[dict]:
    """RAW ``ticker/24hr`` payload (30s TTL cache, shared with the universe)."""
    global _raw_tickers, _raw_tickers_ts
    now = time.monotonic()
    if _raw_tickers is None or now - _raw_tickers_ts >= _UNIVERSE_TTL:
        async with _cache_lock:
            now = time.monotonic()
            if _raw_tickers is None or now - _raw_tickers_ts >= _UNIVERSE_TTL:
                _raw_tickers = await get_json(f"{FAPI_V1}/ticker/24hr")
                _raw_tickers_ts = now
    return _raw_tickers or []


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


async def _fetch_universe() -> list[dict]:
    perps, tickers = await asyncio.gather(
        _perp_symbols(),
        get_json(f"{FAPI_V1}/ticker/24hr"),
    )
    # stash the raw payload + perp map for dependent surfaces (0 extra calls)
    global _raw_tickers, _raw_tickers_ts, _perps, _perps_ts
    _raw_tickers, _raw_tickers_ts = tickers, time.monotonic()
    _perps, _perps_ts = perps, time.monotonic()

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
    return rows


async def list_universe(limit: int | None = None) -> list[dict]:
    """Return ``[{symbol, name, price, ch, quote_volume}]`` sorted by 24h quote
    volume (desc), served from a 30s TTL cache. ``name`` falls back to the base
    asset. Errors are never cached — a failed refresh propagates and the next
    call retries.
    """
    global _cache, _cache_ts
    now = time.monotonic()
    if _cache is None or now - _cache_ts >= _UNIVERSE_TTL:
        async with _cache_lock:
            now = time.monotonic()
            if _cache is None or now - _cache_ts >= _UNIVERSE_TTL:
                _cache = await _fetch_universe()  # raises propagate; cache untouched
                _cache_ts = now
    rows = _cache or []
    return rows[:limit] if limit else rows
