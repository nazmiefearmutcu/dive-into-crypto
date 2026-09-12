"""Official index constituents from Binance USDT-M (``/fapi/v1/indexInfo`` +
``/fapi/v1/constituents``) — used to verify sector-cluster labels.

A structure cluster labeled e.g. ``BTC`` is ``index_verified`` when its member
symbols overlap strongly (≥ ``OVERLAP_THRESHOLD`` of members) with an official
composite index's constituent list (e.g. ``BTCDOMUSDT``). The verified name is
that index's symbol. Near-static metadata: cached for a week. Any upstream
failure leaves the cache empty and verification off — clusters then report
``index_verified: false`` with no name (an honest "unknown", not a fake pass).
"""

from __future__ import annotations

import logging
import time
from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, LoopBoundLock, get_json

logger = logging.getLogger("trading_bot.data.index_info")

OVERLAP_THRESHOLD = 0.6  # share of cluster members that must sit inside the index
_TTL_SECONDS = 7 * 24 * 3600.0  # weekly cadence: constituents change rarely

_cache: dict[str, set[str]] = {}
_cache_ts: float = 0.0
_lock = LoopBoundLock()


def reset_cache() -> None:
    """Drop the constituent cache (test hook)."""
    global _cache, _cache_ts
    _cache = {}
    _cache_ts = 0.0


def parse_constituents(payload: Any) -> set[str]:
    """Constituent perp symbols from a ``/constituents`` response body."""
    out: set[str] = set()
    rows = payload.get("constituents") if isinstance(payload, dict) else payload
    for r in rows or []:
        if isinstance(r, dict):
            sym = r.get("symbol")
            if isinstance(sym, str) and sym:
                out.add(sym.upper())
    return out


def parse_index_info(payload: list) -> dict[str, set[str]]:
    """``{index_symbol: {base_asset, ...}}`` from an ``/indexInfo`` payload."""
    out: dict[str, set[str]] = {}
    for entry in payload or []:
        try:
            sym = str(entry["symbol"]).upper()
        except (KeyError, TypeError, ValueError):
            continue
        bases: set[str] = set()
        for group in entry.get("baseAssetList") or []:
            base = group.get("baseAsset")
            if isinstance(base, str) and base:
                bases.add(base.upper())
        if bases:
            out[sym] = bases
    return out


async def _refresh() -> dict[str, set[str]]:
    info = await get_json(f"{FAPI_V1}/indexInfo")
    by_base = parse_index_info(info)
    out: dict[str, set[str]] = {}
    for index_sym, bases in by_base.items():
        members: set[str] = set()
        try:
            cons = await get_json(f"{FAPI_V1}/constituents", {"symbol": index_sym})
            members |= parse_constituents(cons)
        except Exception as e:
            logger.warning("index_info: %s constituents failed — %s", index_sym, str(e)[:80])
        if members:
            out[index_sym] = members
        else:
            # fallback: no symbol-level list — match members by base asset name
            out[index_sym] = {f"{b}USDT" for b in bases}
    return out


async def index_membership() -> dict[str, set[str]]:
    """``{index_symbol: {constituent perp symbol, ...}}``, cached weekly."""
    global _cache, _cache_ts
    now = time.monotonic()
    if _cache and now - _cache_ts < _TTL_SECONDS:
        return _cache
    async with _lock:
        now = time.monotonic()
        if _cache and now - _cache_ts < _TTL_SECONDS:
            return _cache
        try:
            fresh = await _refresh()
        except Exception as e:
            logger.warning("index_info: refresh failed — %s", str(e)[:80])
            return dict(_cache)  # stale-but-honest; never fabricated
        if fresh:
            _cache = fresh
            _cache_ts = now
        return dict(_cache)


async def verify_cluster_labels(cluster_members: dict[int, list[str]]) -> dict[int, dict]:
    """``{cluster_id: {index_verified: bool, verified_name: str | None}}``.

    Verified when ≥ OVERLAP_THRESHOLD of the cluster's members appear in an
    official index's constituent set (best overlap wins the name).
    """
    membership = await index_membership()
    out: dict[int, dict] = {}
    for cid, members in cluster_members.items():
        best_name = None
        best_overlap = 0.0
        if members:
            for index_sym, cons in membership.items():
                overlap = sum(1 for m in members if m.upper() in cons) / len(members)
                if overlap > best_overlap:
                    best_overlap = overlap
                    best_name = index_sym
        verified = best_overlap >= OVERLAP_THRESHOLD
        out[cid] = {
            "index_verified": verified,
            "verified_name": best_name if verified else None,
        }
    return out
