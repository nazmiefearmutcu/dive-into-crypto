"""Funding rate + mark/index price from Binance USDT-M public endpoints.

The funding lens (:func:`funding_lens`) turns the raw funding surface into the
planner-facing block: the *predicted* (current-interval, pre-settlement) rate
from ``premiumIndex.lastFundingRate``, the last *settled* rates from the
``fundingRate`` tail, the annualized APR those imply, seconds to the next
settlement, and a crowding ``regime`` label. Every failed/absent input is an
explicit ``None`` — never a zero dressed as data.
"""

from __future__ import annotations

from typing import Any

from diveintocrypto_desktop.data.http import FAPI_V1, get_json

# Funding settles three times a day (00:00 / 08:00 / 16:00 UTC) for most pairs.
_SETTLE_SECONDS = 8 * 3600


async def premium_index(symbol: str) -> dict[str, float]:
    """Current mark price, index price and last funding rate for one symbol."""
    d: dict[str, Any] = await get_json(f"{FAPI_V1}/premiumIndex", {"symbol": symbol})
    return {
        "mark_price": float(d["markPrice"]),
        "index_price": float(d["indexPrice"]),
        "last_funding_rate": float(d["lastFundingRate"]),
        "next_funding_time": int(d.get("nextFundingTime", 0)),
    }


async def premium_index_all() -> dict[str, dict[str, float]]:
    """premiumIndex for EVERY symbol in one call (weight 10) → ``{symbol: row}``.

    Cheaper than per-symbol loops for scan-wide funding/basis annotations.
    """
    rows: list[dict[str, Any]] = await get_json(f"{FAPI_V1}/premiumIndex")
    out: dict[str, dict[str, float]] = {}
    for d in rows:
        try:
            out[str(d["symbol"])] = {
                "mark_price": float(d["markPrice"]),
                "index_price": float(d["indexPrice"]),
                "last_funding_rate": float(d["lastFundingRate"]),
                "next_funding_time": int(d.get("nextFundingTime", 0)),
            }
        except (KeyError, TypeError, ValueError):
            continue
    return out


async def funding_hist(symbol: str, limit: int = 48) -> list[dict]:
    """Recent funding events ``[{t, funding_rate}]`` (t in ms)."""
    rows: list[dict[str, Any]] = await get_json(
        f"{FAPI_V1}/fundingRate", {"symbol": symbol, "limit": limit}
    )
    return [{"t": int(r["fundingTime"]), "funding_rate": float(r["fundingRate"])} for r in rows]


# ── funding lens (pure math over fetched inputs) ──────────────────────────────
def funding_regime(predicted: float | None) -> str:
    """Crowding label from the predicted (pre-settlement) rate.

    Annualized bands: |rate|·3·365 — ±5% APR is quiet, ±25% crowded, beyond
    that extreme. Sign says which side is paying.
    """
    if predicted is None:
        return "unavailable"
    apr = predicted * 3 * 365
    if apr >= 0.25:
        return "extreme_long_crowding"
    if apr >= 0.05:
        return "long_crowding"
    if apr <= -0.25:
        return "extreme_short_crowding"
    if apr <= -0.05:
        return "short_crowding"
    return "balanced"


def funding_lens(
    predicted_funding: float | None,
    settled_tail: list[float],
    next_funding_time_ms: int | None,
    now_ms: int | None = None,
) -> dict:
    """``funding_lens`` block — pure over already-fetched inputs.

    ``predicted_funding`` comes from premiumIndex ``lastFundingRate`` (the
    current, not-yet-settled interval); ``settled_tail`` is the ``fundingRate``
    history tail (oldest→newest); ``next_funding_time_ms`` is the exchange's
    ``nextFundingTime`` (``None`` when 0/absent — never a fabricated countdown).
    """
    import time as _time

    last_settled = settled_tail[-1] if settled_tail else None
    apr = predicted_funding * 3 * 365 if predicted_funding is not None else None
    seconds_to_funding = None
    if next_funding_time_ms:
        now = int(now_ms if now_ms is not None else _time.time() * 1000)
        delta = (int(next_funding_time_ms) - now) / 1000.0
        if delta > 0:
            seconds_to_funding = round(delta, 1)
    return {
        "predicted_funding": predicted_funding,
        "last_settled": last_settled,
        "apr": round(apr, 6) if apr is not None else None,
        "seconds_to_funding": seconds_to_funding,
        "regime": funding_regime(predicted_funding),
    }
