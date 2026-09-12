"""Multi-timeframe OHLCV via Crypcodile's Binance kline parser.

OHLCV is fetched from Binance's official USDT-M ``/fapi/v1/klines`` (official bars,
including the taker-buy volume split) and normalised through Crypcodile's
``parse_klines_page`` so the whole data path stays Crypcodile-centric.

Anti-repaint: Binance returns the current UNFINISHED candle as the last row when
``end_time`` is "now". Every indicator reads ``iloc[-1]``, so a trailing
in-progress candle makes signals flip before the close. We therefore request one
extra row and drop any candle whose close time is still in the future.
"""

from __future__ import annotations

import asyncio
import time
from typing import Any

import pandas as pd
from crypcodile.exchanges.binance.backfill import _live_fetch_klines, parse_klines_page

from diveintocrypto_desktop.data.http import FAPI_V1, TransientUpstreamError, run_with_retries

# The canonical 12 timeframes of the Dive Into Crypto scanner.
TF_LIST = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]

_VENUE = "binance-usdm"


def period_ns(interval: str) -> int:
    """Candle period in nanoseconds for a Binance interval like ``1m``/``4h``/``1d``."""
    unit_seconds = {"m": 60, "h": 3600, "d": 86400, "w": 604800}
    return int(interval[:-1]) * unit_seconds.get(interval[-1], 60) * 1_000_000_000


async def _fetch_raw(symbol: str, interval: str, limit: int, end_time_ms: int) -> Any:
    async def send() -> Any:
        return await _live_fetch_klines(
            symbol=symbol,
            interval=interval,
            start_time_ms=None,
            end_time_ms=end_time_ms,
            limit=limit,
            rest_base=FAPI_V1,
        )

    return await run_with_retries(
        send,
        should_retry=lambda e: isinstance(e, TransientUpstreamError) or isinstance(e, asyncio.TimeoutError),
    )


async def _fetch_raw_range(symbol: str, interval: str, start_ms: int, end_ms: int, limit: int) -> Any:
    async def send() -> Any:
        return await _live_fetch_klines(
            symbol=symbol,
            interval=interval,
            start_time_ms=start_ms,
            end_time_ms=end_ms,
            limit=limit,
            rest_base=FAPI_V1,
        )

    return await run_with_retries(
        send,
        should_retry=lambda e: isinstance(e, TransientUpstreamError) or isinstance(e, asyncio.TimeoutError),
    )


def _drop_unfinished(recs: list, interval: str, now_ns: int) -> list:
    """Drop trailing candles whose close time is in the future (they still repaint).

    If a skewed clock marks every candle unfinished, degrade to the newest candle
    rather than returning nothing downstream.
    """
    period = period_ns(interval)
    out = [r for r in recs if r.exchange_ts + period <= now_ns]
    return out if out else recs[-1:]


async def fetch_klines(symbol: str, interval: str, limit: int = 300,
                       end_ms: int | None = None) -> list[dict]:
    """Return the most recent ``limit`` FINISHED candles for ``symbol`` at ``interval``.

    Each candle is ``{t, o, h, l, c, v}`` with ``t`` in nanoseconds UTC. The
    current in-progress candle is never returned. ``end_ms`` fetches the window
    ENDING at that instant (historical views); the anti-repaint rule then drops
    candles that were still unfinished *as of ``end_ms``*.
    """
    now_ms = int(end_ms if end_ms is not None else time.time() * 1000)
    raw = await _fetch_raw(symbol, interval, limit + 1, now_ms)
    local_ts = now_ms * 1_000_000
    recs = parse_klines_page(raw, _VENUE, symbol, interval, local_ts)
    recs = _drop_unfinished(list(recs), interval, local_ts)
    return [
        {"t": r.exchange_ts, "o": r.open, "h": r.high, "l": r.low, "c": r.close, "v": r.volume}
        for r in recs[-limit:]
    ]


async def fetch_klines_range(
    symbol: str, interval: str, start_ms: int, end_ms: int, limit: int = 1000
) -> list[dict]:
    """FINISHED candles for ``symbol`` at ``interval`` covering ``[start_ms, end_ms]``.

    Used by the evidence grader to backfill forward returns after an archived
    verdict. Same anti-repaint rule as :func:`fetch_klines` (the trailing
    unfinished candle is dropped).
    """
    now_ms = int(time.time() * 1000)
    raw = await _fetch_raw_range(symbol, interval, start_ms, min(end_ms, now_ms), limit)
    local_ts = now_ms * 1_000_000
    recs = parse_klines_page(raw, _VENUE, symbol, interval, local_ts)
    recs = _drop_unfinished(list(recs), interval, local_ts)
    return [
        {"t": r.exchange_ts, "o": r.open, "h": r.high, "l": r.low, "c": r.close, "v": r.volume}
        for r in recs
    ]


async def fetch_all_tf(symbol: str, limit: int = 300, intervals: list[str] | None = None,
                       end_ms: int | None = None) -> dict[str, list[dict]]:
    """Fetch all 12 timeframes for ``symbol`` concurrently (optionally as-of ``end_ms``)."""
    tfs = intervals or TF_LIST
    results = await asyncio.gather(*(fetch_klines(symbol, tf, limit, end_ms=end_ms) for tf in tfs))
    return dict(zip(tfs, results))


def to_dataframe(candles: list[dict]) -> pd.DataFrame:
    """Convert ``[{t,o,h,l,c,v}]`` to the OHLCV DataFrame the engine consumes."""
    return pd.DataFrame(
        [{"open": c["o"], "high": c["h"], "low": c["l"], "close": c["c"], "volume": c["v"]} for c in candles]
    )
