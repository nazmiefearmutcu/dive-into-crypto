"""Sentiment + macro backdrop (separate failure domain from market data).

Sources:
  * **Fear & Greed** — api.alternative.me, no API key. The index updates once a
    day; the snapshot is cached 1h so polling stays polite while the value
    itself moves at daily cadence.
  * **Stablecoin proxy (exchange-native, 0 new upstream calls)** — stable-pair
    perp volume share and the USDC/USDT cross read off the ALREADY-fetched
    all-ticker payload (``GET /fapi/v1/ticker/24hr``) shared with the universe.
  * **DefiLlama stablecoin market cap** (optional, best-effort) — aggregate
    circulating supply; any failure degrades to ``{"unavailable": reason}``.

Every block fails independently; nothing here can break scanning and no block
invents a number.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

import aiohttp

from diveintocrypto_desktop.data import universe as uni
from diveintocrypto_desktop.data.http import get_json

logger = logging.getLogger("trading_bot.data.sentiment")

FNG_URL = "https://api.alternative.me/fng/"
DEFILLAMA_URL = "https://stablecoins.llama.fi/stablecoins?includePrices=false"
FNG_TTL = 3600.0        # 1h cache on a daily-cadence index
DEFILLAMA_TTL = 3600.0
_DEFILLAMA_TIMEOUT = 8.0

# Stablecoin/fiat bases quoted against USDT on the perp exchange.
STABLE_BASES = {"USDC", "BUSD", "TUSD", "DAI", "FDUSD", "USDP", "EUR", "EURI", "AEUR", "USD1"}

_fng_cache: tuple[float, dict] | None = None
_defillama_cache: tuple[float, dict] | None = None


def _iso(ts: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))


def reset_cache() -> None:
    """Drop both fetch caches (test hook)."""
    global _fng_cache, _defillama_cache
    _fng_cache = None
    _defillama_cache = None


# ── Fear & Greed ──────────────────────────────────────────────────────────────
def parse_fng(payload: dict) -> dict:
    """``{value, classification, updated_at}`` from the alternative.me payload."""
    rows = payload.get("data") or []
    if not rows:
        return {"unavailable": "fng_payload_empty"}
    row = rows[0]
    try:
        value = int(row["value"])
    except (KeyError, TypeError, ValueError):
        return {"unavailable": "fng_payload_invalid"}
    return {
        "value": value,
        "classification": str(row.get("value_classification", "")),
        "updated_at": _iso(int(row.get("timestamp", 0)) or time.time()),
        "cadence": "daily",
    }


async def fear_greed() -> dict:
    """Fear & Greed snapshot (1h cache on SUCCESS only); ``{"unavailable": reason}``
    on failure. Errors and unavailable markers are never cached — the next call
    re-attempts upstream instead of serving a poisoned snapshot for an hour.
    """
    global _fng_cache
    now = time.monotonic()
    if _fng_cache and now - _fng_cache[0] < FNG_TTL:
        return _fng_cache[1]
    try:
        payload = await get_json(FNG_URL)
        block = parse_fng(payload)
    except Exception as e:
        logger.warning("sentiment: fng fetch failed — %s", str(e)[:80])
        return {"unavailable": f"fng_unreachable: {str(e)[:80]}"}  # NOT cached
    if "unavailable" in block:
        return block  # payload-level failure — re-attempted on the next call
    _fng_cache = (time.monotonic(), block)
    return block


# ── stablecoin proxy (pure over the shared ticker payload) ────────────────────
def stablecoin_proxy(tickers: list[dict], perp_symbols: set[str]) -> dict:
    """Stable-pair volume share + USDC/USDT cross from raw ticker rows.

    ``tickers``: the full ``ticker/24hr`` payload (dicts with symbol /
    quoteVolume / lastPrice). ``perp_symbols``: the tradable USDT-M perp set —
    only rows inside it count (delivery quarters are excluded). Pure; no fetch.
    """
    stable_vol = 0.0
    total_vol = 0.0
    stable_pairs: list[dict] = []
    usdc_usdt = None
    for t in tickers:
        try:
            sym = str(t.get("symbol") or "")
            vol = float(t.get("quoteVolume") or 0.0)
        except (TypeError, ValueError):
            continue
        if sym not in perp_symbols:
            continue
        total_vol += vol
        base = sym[:-4] if sym.endswith("USDT") else ""
        if base in STABLE_BASES:
            stable_vol += vol
            stable_pairs.append({"s": sym, "quote_volume": round(vol, 2)})
            if base == "USDC":
                try:
                    usdc_usdt = float(t.get("lastPrice"))
                except (TypeError, ValueError):
                    pass
    return {
        "stable_volume_share": round(stable_vol / total_vol, 6) if total_vol > 0 else None,
        "usdc_usdt_ratio": usdc_usdt,
        "stable_pairs": sorted(stable_pairs, key=lambda r: -r["quote_volume"])[:8],
        "note": "derived from the already-fetched ticker payload — 0 extra calls",
    }


# ── DefiLlama (optional, best-effort) ────────────────────────────────────────
def parse_defillama(payload: list) -> dict:
    """Aggregate circulating supply from the DefiLlama stablecoins list."""
    total = 0.0
    by_asset: dict[str, float] = {}
    for row in payload or []:
        circ = row.get("circulating") if isinstance(row, dict) else None
        if isinstance(circ, dict):
            circ = circ.get("peggedUSD")
        try:
            circ = float(circ)
        except (TypeError, ValueError):
            continue
        if circ <= 0:
            continue
        name = str((row or {}).get("name") or "?")
        total += circ
        by_asset[name.upper()] = circ
    if total <= 0:
        return {"unavailable": "defillama_payload_empty"}
    top = sorted(by_asset.items(), key=lambda kv: -kv[1])
    return {
        "total_mcap_usd": round(total, 2),
        "usdt_share": round(by_asset.get("TETHER", 0.0) / total, 4),
        "usdc_share": round(by_asset.get("USDC COIN", by_asset.get("USDC", 0.0)) / total, 4),
        "top": [{"name": n, "mcap_usd": round(v, 2)} for n, v in top[:5]],
    }


async def defillama_stablecoins() -> dict:
    """Best-effort DefiLlama aggregate; ``{"unavailable": reason}`` on failure.
    Errors are never cached — a failed fetch is re-attempted on the next call
    (only successful payloads hold the 1h TTL cache).
    """
    global _defillama_cache
    now = time.monotonic()
    if _defillama_cache and now - _defillama_cache[0] < DEFILLAMA_TTL:
        return _defillama_cache[1]
    try:
        timeout = aiohttp.ClientTimeout(total=_DEFILLAMA_TIMEOUT)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(DEFILLAMA_URL) as resp:
                resp.raise_for_status()
                payload = await resp.json(content_type=None)
        block = parse_defillama(payload)
    except Exception as e:
        logger.warning("sentiment: defillama fetch failed — %s", str(e)[:80])
        return {"unavailable": f"defillama_unreachable: {str(e)[:80]}"}  # NOT cached
    if "unavailable" in block:
        return block  # payload-level failure — re-attempted on the next call
    _defillama_cache = (time.monotonic(), block)
    return block


async def macro_snapshot() -> dict:
    """Aggregate ``/api/macro`` body — each block fails independently."""
    tickers, perps, fng, llama = await asyncio.gather(
        uni.all_tickers(), uni.perp_symbols(), fear_greed(), defillama_stablecoins()
    )
    return {
        "fng": fng,
        "stablecoin": stablecoin_proxy(tickers, set(perps)),
        "defillama": llama,
        "generated_at": _iso(time.time()),
    }
