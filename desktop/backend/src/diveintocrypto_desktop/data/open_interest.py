"""Open-interest history via Crypcodile's Binance OI parser."""

from __future__ import annotations

import time

from crypcodile.exchanges.binance.backfill import _live_fetch_open_interest_hist, parse_open_interest_hist

_FAPI_DATA = "https://fapi.binance.com"
_VENUE = "binance-usdm"

# Binance publishes openInterestHist for these periods only.
OI_PERIODS = ["5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d"]


async def fetch_oi_hist(symbol: str, period: str = "5m", limit: int = 48) -> list[dict]:
    """Return recent open-interest points ``[{t, oi, oi_value}]`` (t in ns)."""
    if period not in OI_PERIODS:
        period = "5m"
    now_ms = int(time.time() * 1000)
    raw = await _live_fetch_open_interest_hist(
        symbol=symbol,
        period=period,
        start_time_ms=None,
        end_time_ms=now_ms,
        limit=limit,
        rest_base=_FAPI_DATA,
    )
    local_ts = now_ms * 1_000_000
    recs = parse_open_interest_hist(raw, _VENUE, symbol, local_ts)
    return [
        {"t": r.exchange_ts, "oi": r.open_interest, "oi_value": r.open_interest_value}
        for r in recs
    ]
