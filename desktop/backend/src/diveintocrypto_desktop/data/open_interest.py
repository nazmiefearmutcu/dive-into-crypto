"""Open-interest history via Crocodile's Binance OI parser.

``/futures/data/openInterestHist`` is a futures/data endpoint, so it goes through
the shared 40/60s limiter in :mod:`diveintocrypto_desktop.data.http` like the
long/short ratio series it is always fetched alongside. Rows are normalised by
Crocodile's public ``parse_open_interest_hist`` into canonical ``OpenInterest``
records.
"""

from __future__ import annotations

import time

from crocodile.crypto.exchanges.binance.backfill import parse_open_interest_hist

from diveintocrypto_desktop.data.http import FAPI_DATA, get_json

_VENUE = "binance-usdm"

# Binance publishes openInterestHist for these periods only.
OI_PERIODS = ["5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d"]


async def fetch_oi_hist(symbol: str, period: str = "5m", limit: int = 48) -> list[dict]:
    """Return recent open-interest points ``[{t, oi, oi_value}]`` (t in ns)."""
    if period not in OI_PERIODS:
        period = "5m"
    now_ms = int(time.time() * 1000)
    raw = await get_json(
        f"{FAPI_DATA}/futures/data/openInterestHist",
        {"symbol": symbol, "period": period, "limit": limit, "endTime": now_ms},
        rate_limited=True,
        retry_timeouts=True,
    )
    local_ts = now_ms * 1_000_000
    recs = parse_open_interest_hist(raw, _VENUE, symbol, local_ts)
    return [
        {"t": r.source_ts, "oi": r.open_interest, "oi_value": r.open_interest_value}
        for r in recs
    ]
