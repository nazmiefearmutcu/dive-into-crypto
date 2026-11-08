"""Multi-timeframe OHLCV via Crocodile's Binance kline parser.

OHLCV is fetched from Binance's official USDT-M ``/fapi/v1/klines`` (official bars,
including the taker-buy volume split) through the shared session in
:mod:`diveintocrypto_desktop.data.http` (pooled connections, 429/5xx +
timeout retries) and normalised through Crocodile's public
``parse_klines_page`` (``crocodile.crypto.exchanges.binance.backfill``) so every
bar is a canonical Crocodile ``OHLCV`` record.

Anti-repaint: Binance returns the current UNFINISHED candle as the last row when
``end_time`` is "now". Every indicator reads ``iloc[-1]``, so a trailing
in-progress candle makes signals flip before the close. We therefore request one
extra row and drop any candle whose close time is still in the future. "Now" is
the exchange's clock (the ``Date`` header of the same response) when available,
because a local clock that runs ahead would count an in-progress candle as closed.
Without that header the local clock is the fallback.
"""

from __future__ import annotations

import asyncio
import time
from typing import Any

import pandas as pd
from crocodile.crypto.exchanges.binance.backfill import parse_klines_page

from diveintocrypto_desktop.data.http import FAPI_V1, capture_response_meta, get_json

# The canonical 12 timeframes of the Dive Into Crypto scanner.
TF_LIST = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]

_VENUE = "binance-usdm"


def period_ns(interval: str) -> int:
    """Candle period in nanoseconds for a Binance interval like ``1m``/``4h``/``1d``."""
    unit_seconds = {"m": 60, "h": 3600, "d": 86400, "w": 604800}
    return int(interval[:-1]) * unit_seconds.get(interval[-1], 60) * 1_000_000_000


async def _get_klines(params: dict[str, Any]) -> Any:
    # Shared pooled session; Binance 429/5xx surface as TransientUpstreamError and
    # are retried with Retry-After-aware backoff, as are client-side timeouts.
    return await get_json(f"{FAPI_V1}/klines", params, retry_timeouts=True)


async def _fetch_raw(symbol: str, interval: str, limit: int, end_time_ms: int) -> Any:
    return await _get_klines({"symbol": symbol, "interval": interval, "limit": limit, "endTime": end_time_ms})


async def _fetch_raw_range(symbol: str, interval: str, start_ms: int, end_ms: int, limit: int) -> Any:
    return await _get_klines(
        {"symbol": symbol, "interval": interval, "limit": limit, "startTime": start_ms, "endTime": end_ms}
    )


def _drop_unfinished(recs: list, interval: str, now_ns: int) -> list:
    """Drop candles whose close time is still in the future (they repaint).

    Returns only finished candles, so an empty list when none has closed yet (for
    example a symbol listed less than one interval ago). The in-progress candle is
    never handed out, not even as a fallback.
    """
    period = period_ns(interval)
    return [r for r in recs if r.source_ts + period <= now_ns]


def _exchange_now_ms(meta: dict, fallback_ms: int) -> int:
    """The "now" the anti-repaint rule compares against: the upstream ``Date``
    header when the response carried one, else the local clock."""
    server = meta.get("server_ms")
    return int(server) if isinstance(server, (int, float)) else fallback_ms


async def fetch_klines(symbol: str, interval: str, limit: int = 300,
                       end_ms: int | None = None) -> list[dict]:
    """Return the most recent ``limit`` FINISHED candles for ``symbol`` at ``interval``.

    Each candle is ``{t, o, h, l, c, v}`` with ``t`` in nanoseconds UTC. The
    current in-progress candle is never returned. ``end_ms`` fetches the window
    ENDING at that instant (historical views); the anti-repaint rule then drops
    candles that were still unfinished *as of ``end_ms``*. For a live window the
    finished test uses the exchange clock (see :func:`_exchange_now_ms`).
    """
    now_ms = int(end_ms if end_ms is not None else time.time() * 1000)
    with capture_response_meta() as meta:
        raw = await _fetch_raw(symbol, interval, limit + 1, now_ms)
    finished_ms = now_ms if end_ms is not None else _exchange_now_ms(meta, now_ms)
    recs = parse_klines_page(raw, _VENUE, symbol, interval, now_ms * 1_000_000)
    recs = _drop_unfinished(list(recs), interval, finished_ms * 1_000_000)
    return [
        {"t": r.source_ts, "o": r.open, "h": r.high, "l": r.low, "c": r.close, "v": r.volume}
        for r in recs[-limit:]
    ]


async def fetch_klines_range(
    symbol: str, interval: str, start_ms: int, end_ms: int, limit: int = 1000
) -> list[dict]:
    """FINISHED candles for ``symbol`` at ``interval`` covering ``[start_ms, end_ms]``.

    Used by the evidence grader to backfill forward returns after an archived
    verdict. Same anti-repaint rule as :func:`fetch_klines` (the trailing
    unfinished candle is dropped, judged on the exchange clock when available).
    """
    now_ms = int(time.time() * 1000)
    with capture_response_meta() as meta:
        raw = await _fetch_raw_range(symbol, interval, start_ms, min(end_ms, now_ms), limit)
    finished_ms = _exchange_now_ms(meta, now_ms)
    recs = parse_klines_page(raw, _VENUE, symbol, interval, now_ms * 1_000_000)
    recs = _drop_unfinished(list(recs), interval, finished_ms * 1_000_000)
    return [
        {"t": r.source_ts, "o": r.open, "h": r.high, "l": r.low, "c": r.close, "v": r.volume}
        for r in recs
    ]


async def fetch_all_tf(symbol: str, limit: int = 300, intervals: list[str] | None = None,
                       end_ms: int | None = None) -> dict[str, list[dict]]:
    """Fetch all 12 timeframes for ``symbol`` concurrently (optionally as-of ``end_ms``)."""
    tfs = intervals or TF_LIST
    tasks = [asyncio.ensure_future(fetch_klines(symbol, tf, limit, end_ms=end_ms)) for tf in tfs]
    try:
        results = await asyncio.gather(*tasks)
    except BaseException:
        # first failure: cancel the sibling requests instead of leaving them running
        # untracked and spending rate-limit budget on results nobody will read
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise
    return dict(zip(tfs, results, strict=True))


def to_dataframe(candles: list[dict]) -> pd.DataFrame:
    """Convert ``[{t,o,h,l,c,v}]`` to the OHLCV DataFrame the engine consumes."""
    return pd.DataFrame(
        [{"open": c["o"], "high": c["h"], "low": c["l"], "close": c["c"], "volume": c["v"]} for c in candles]
    )
