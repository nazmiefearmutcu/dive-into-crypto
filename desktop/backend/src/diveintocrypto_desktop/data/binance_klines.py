"""Multi-timeframe OHLCV via Crypcodile's Binance kline parser.

OHLCV is fetched from Binance's official USDT-M ``/fapi/v1/klines`` (official bars,
including the taker-buy volume split) and normalised through Crypcodile's
``parse_klines_page`` so the whole data path stays Crypcodile-centric.
"""

from __future__ import annotations

import asyncio
import time

import pandas as pd
from crypcodile.exchanges.binance.backfill import _live_fetch_klines, parse_klines_page

# The canonical 12 timeframes of the Dive Into Crypto scanner.
TF_LIST = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]

_FAPI = "https://fapi.binance.com/fapi/v1"
_VENUE = "binance-usdm"


async def fetch_klines(symbol: str, interval: str, limit: int = 300) -> list[dict]:
    """Return the most recent ``limit`` candles for ``symbol`` at ``interval``.

    Each candle is ``{t, o, h, l, c, v}`` with ``t`` in nanoseconds UTC.
    """
    now_ms = int(time.time() * 1000)
    raw = await _live_fetch_klines(
        symbol=symbol,
        interval=interval,
        start_time_ms=None,
        end_time_ms=now_ms,
        limit=limit,
        rest_base=_FAPI,
    )
    local_ts = now_ms * 1_000_000
    recs = parse_klines_page(raw, _VENUE, symbol, interval, local_ts)
    return [
        {"t": r.exchange_ts, "o": r.open, "h": r.high, "l": r.low, "c": r.close, "v": r.volume}
        for r in recs
    ]


async def fetch_all_tf(symbol: str, limit: int = 300, intervals: list[str] | None = None) -> dict[str, list[dict]]:
    """Fetch all 12 timeframes for ``symbol`` concurrently."""
    tfs = intervals or TF_LIST
    results = await asyncio.gather(*(fetch_klines(symbol, tf, limit) for tf in tfs))
    return dict(zip(tfs, results))


def to_dataframe(candles: list[dict]) -> pd.DataFrame:
    """Convert ``[{t,o,h,l,c,v}]`` to the OHLCV DataFrame the engine consumes."""
    return pd.DataFrame(
        [{"open": c["o"], "high": c["h"], "low": c["l"], "close": c["c"], "volume": c["v"]} for c in candles]
    )
