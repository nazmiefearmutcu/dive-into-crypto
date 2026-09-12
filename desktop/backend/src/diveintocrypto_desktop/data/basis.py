"""Basis + futures term structure from Binance USDT-M public endpoints.

The **basis** is the perp's premium over its index price
(``premiumIndex.markPrice`` vs ``indexPrice``, in basis points). The **term
structure** adds the quarterly delivery contracts (``exchangeInfo``
``contractType`` ``CURRENT_QUARTER`` / ``NEXT_QUARTER``): each contract's
annualized premium vs the same index forms the ``curve`` (perp → cq → nq).
The z-score is computed against the symbol's own trailing
``premiumIndexKlines`` history — no cross-symbol pool, no fitted constants.

Honesty: a pair without delivery contracts still gets its perp leg (the block
is flagged ``partial``); anything unfetchable is ``None``, never imputed.
"""

from __future__ import annotations

import logging
import statistics
import time
from datetime import datetime, timezone
from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, LoopBoundLock, get_json

logger = logging.getLogger("trading_bot.data.basis")

# Label bands on the perp basis (bps). Published constants, not fitted.
LABEL_BANDS_BPS: list[tuple[float, str]] = [
    (-50.0, "deep_discount"),
    (-10.0, "discount"),
    (10.0, "balanced"),
    (50.0, "premium"),
    (float("inf"), "deep_premium"),
]

# Annualization seconds for premium-vs-index: futures converge to the index at
# expiry, so premium/time-to-expiry is the annualized carry (ACT/365).
_YEAR_SECONDS = 365 * 24 * 3600

# Delivery-symbol map (from exchangeInfo) is near-static — cache for an hour.
_DELIVERY_TTL = 3600.0
_delivery_cache: dict[str, dict[str, dict]] | None = None
_delivery_ts: float = 0.0
_delivery_lock = LoopBoundLock()


def basis_label(bps: float) -> str:
    for cut, name in LABEL_BANDS_BPS:
        if bps < cut:
            return name
    return "deep_premium"


def _safe_float(v: Any) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if f == f else None


def _expiry_from_symbol(symbol: str) -> int:
    """Delivery date (ms, 08:00 UTC) parsed from the ``_YYMMDD`` suffix.

    Binance quarterly contracts settle 08:00 UTC on the suffix date; the suffix
    is the only expiry signal exchangeInfo carries. Unparseable → 0 (the
    annualized leg then degrades to None upstream — honest).
    """
    try:
        suffix = symbol.rsplit("_", 1)[1]
        dt = datetime.strptime(suffix, "%y%m%d").replace(
            hour=8, tzinfo=timezone.utc
        )
        return int(dt.timestamp() * 1000)
    except (IndexError, ValueError):
        return 0


async def delivery_symbols() -> dict[str, dict[str, dict]]:
    """``{perp_symbol: {cq: {...}, nq: {...}}}`` quarterly delivery contracts.

    From ``exchangeInfo`` rows with ``contractType`` CURRENT_QUARTER /
    NEXT_QUARTER. The perp symbol of the same pair (e.g. ``BTCUSDT`` for
    ``BTCUSDT_250926``) is the pair symbol with the ``_YYMMDD`` suffix stripped,
    and the expiry parsed back out of that suffix. Cached one hour
    (near-static metadata). Only TRADING contracts are kept.
    """
    global _delivery_cache, _delivery_ts
    now = time.monotonic()
    if _delivery_cache is not None and now - _delivery_ts < _DELIVERY_TTL:
        return _delivery_cache
    async with _delivery_lock:
        now = time.monotonic()
        if _delivery_cache is not None and now - _delivery_ts < _DELIVERY_TTL:
            return _delivery_cache
        info: dict[str, Any] = await get_json(f"{FAPI_V1}/exchangeInfo")
        out: dict[str, dict[str, dict]] = {}
        for s in info.get("symbols", []):
            ct = s.get("contractType")
            if ct not in ("CURRENT_QUARTER", "NEXT_QUARTER") or s.get("status") != "TRADING":
                continue
            pair = str(s.get("pair") or "")
            sym = str(s.get("symbol") or "")
            if not pair or not sym:
                continue
            entry = {"symbol": sym, "expiry_ms": _expiry_from_symbol(sym)}
            out.setdefault(pair, {})[
                "cq" if ct == "CURRENT_QUARTER" else "nq"
            ] = entry
        _delivery_cache = out
        _delivery_ts = now
        return out


def reset_delivery_cache() -> None:
    """Drop the delivery-map cache (test hook)."""
    global _delivery_cache, _delivery_ts
    _delivery_cache = None
    _delivery_ts = 0.0


async def premium_index_klines(symbol: str, interval: str = "5m", limit: int = 200) -> list[dict]:
    """Trailing premiumIndexKlines ``[{t, premium_pct}]`` for one symbol.

    ``premium_pct`` is Binance's own mark-vs-index premium percent series —
    the z-score baseline. Empty list on failure (caller decides honesty).
    """
    rows: list[dict[str, Any]] = await get_json(
        f"{FAPI_V1}/premiumIndexKlines", {"symbol": symbol, "interval": interval, "limit": limit}
    )
    out: list[dict] = []
    for r in rows:
        try:
            out.append({"t": int(r[0]), "premium_pct": _safe_float(r[1])})
        except (IndexError, TypeError, ValueError):
            continue
    return [r for r in out if r["premium_pct"] is not None]


def basis_zscore(premium_pct_series: list[float], current_pct: float | None) -> float | None:
    """Z-score of the current premium vs the trailing series (population stats)."""
    if current_pct is None or len(premium_pct_series) < 30:
        return None
    hist = premium_pct_series[:-1] or premium_pct_series
    sd = statistics.pstdev(hist)
    if sd <= 0:
        return None
    return round((current_pct - statistics.fmean(hist)) / sd, 3)


def _ann_premium_bps(price: float | None, index: float | None, expiry_ms: int, now_ms: int) -> float | None:
    """Annualized premium of a (delivery) contract vs the index, in bps."""
    if price is None or index is None or index <= 0:
        return None
    years = (expiry_ms - now_ms) / 1000.0 / _YEAR_SECONDS
    if years <= 1.0 / 48:  # sub-half-hour to expiry: annualization explodes
        return None
    spot_basis = (price - index) / index
    return round(spot_basis / years * 10_000.0, 2)


async def fetch_delivery_price(symbol: str) -> float | None:
    """Latest mark price of one delivery contract (premiumIndex, single call)."""
    try:
        d: dict[str, Any] = await get_json(f"{FAPI_V1}/premiumIndex", {"symbol": symbol})
        return _safe_float(d.get("markPrice"))
    except Exception as e:  # a missing delivery leg stays partial, never fatal
        logger.warning("basis: delivery %s fetch failed — %s", symbol, str(e)[:80])
        return None


async def basis_block(
    perp_symbol: str,
    perp_row: dict[str, float],
    deliveries: dict[str, dict] | None = None,
    premium_hist_pct: list[float] | None = None,
    now_ms: int | None = None,
) -> dict:
    """Assemble the per-symbol ``basis`` block.

    ``perp_row`` is a :func:`diveintocrypto_desktop.data.funding.premium_index`
    result for the perpetual. ``deliveries`` is the ``{cq, nq}`` slice from
    :func:`delivery_symbols` (may be empty — perp-only pairs are the normal
    state; the block is then flagged ``partial``). ``premium_hist_pct`` is the
    trailing premium-percent series for the z-score.
    """
    now_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    mark = _safe_float(perp_row.get("mark_price"))
    index = _safe_float(perp_row.get("index_price"))
    if mark is None or index is None or index <= 0:
        return {"unavailable": "premium_index_unavailable"}
    basis_bps = round((mark - index) / index * 10_000.0, 2)

    curve: dict[str, float | None] = {"perp": basis_bps, "cq": None, "nq": None}
    partial = True
    if deliveries:
        for leg in ("cq", "nq"):
            d = deliveries.get(leg)
            if not d:
                continue
            price = await fetch_delivery_price(d["symbol"])
            ann = _ann_premium_bps(price, index, int(d.get("expiry_ms") or 0), now_ms)
            if ann is not None:
                curve[leg] = ann
                partial = False

    last_funding = _safe_float(perp_row.get("last_funding_rate"))
    return {
        "basis_bps": basis_bps,
        "zscore": basis_zscore(premium_hist_pct or [], (mark - index) / index * 100.0),
        "ann_funding": round(last_funding * 3 * 365 * 10_000.0, 2) if last_funding is not None else None,
        "label": basis_label(basis_bps),
        "curve": curve,
        "partial": partial,
    }
