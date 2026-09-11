"""Anti-repaint regression: the current UNFINISHED candle must never reach the
engine (every indicator reads iloc[-1]; a trailing in-progress candle makes
signals flip before the close). Offline: the Binance page + parser are mocked.
"""

import time
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from diveintocrypto_desktop.data import binance_klines as kl


def _records(count: int, interval: str, *, trailing_unfinished: bool) -> list[SimpleNamespace]:
    """``count`` parsed kline records (t in NANOSECONDS, like parse_klines_page),
    oldest→newest, ending "now".
    """
    period_ns = kl.period_ns(interval)
    now_ns = time.time_ns()
    # Finished candles close 5ms before the anchor: like the real path, the last
    # FINISHED candle must close strictly before fetch_klines' own "now" clock.
    newest_open = now_ns - period_ns - 5_000_000
    if trailing_unfinished:
        newest_open = now_ns  # opened THIS instant → closes in the future
    out = []
    for i in range(count):
        open_ts = newest_open - (count - 1 - i) * period_ns
        out.append(SimpleNamespace(exchange_ts=open_ts, open=1.0, high=2.0, low=0.5, close=1.5, volume=10.0 + i))
    return out


def test_period_ns_converts_binance_intervals():
    assert kl.period_ns("1m") == 60 * 1_000_000_000
    assert kl.period_ns("4h") == 4 * 3600 * 1_000_000_000
    assert kl.period_ns("1d") == 86_400_000_000_000


def test_drop_unfinished_removes_only_the_future_closing_candle():
    now_ns = time.time_ns()
    period = kl.period_ns("1h")
    finished = SimpleNamespace(exchange_ts=now_ns - period, open=1, high=1, low=1, close=1, volume=1)
    unfinished = SimpleNamespace(exchange_ts=now_ns, open=1, high=1, low=1, close=1, volume=1)
    out = kl._drop_unfinished([finished, unfinished], "1h", now_ns)
    assert out == [finished]


def test_drop_unfinished_degrades_to_newest_when_all_look_unfinished():
    now_ns = time.time_ns()
    period = kl.period_ns("1h")
    older_unfinished = SimpleNamespace(exchange_ts=now_ns + 1, open=1, high=1, low=1, close=1, volume=1)
    newest_unfinished = SimpleNamespace(exchange_ts=now_ns + period, open=1, high=1, low=1, close=1, volume=1)
    assert kl._drop_unfinished([older_unfinished, newest_unfinished], "1h", now_ns) == [newest_unfinished]
    assert kl._drop_unfinished([], "1h", now_ns) == []


@pytest.mark.asyncio
async def test_fetch_klines_requests_one_extra_and_drops_unfinished_candle():
    """limit=300 must yield 300 FINISHED candles: fetch limit+1, trim the tail."""
    raw_page = object()  # opaque upstream payload; the parser mock is ours
    finished = _records(300, "1h", trailing_unfinished=False)
    with_unfinished = finished + _records(1, "1h", trailing_unfinished=True)
    calls: dict = {}

    async def fake_fetch_raw(symbol, interval, limit, end_time_ms):
        calls.update(symbol=symbol, interval=interval, limit=limit, end_time_ms=end_time_ms)
        return raw_page

    def fake_parse(raw, venue, symbol, interval, local_ts):
        assert raw is raw_page and venue == "binance-usdm" and symbol == "BTCUSDT" and interval == "1h"
        return with_unfinished

    with patch.object(kl, "_fetch_raw", fake_fetch_raw), patch.object(kl, "parse_klines_page", fake_parse):
        candles = await kl.fetch_klines("BTCUSDT", "1h", limit=300)

    assert calls["limit"] == 301  # one extra row so the trim keeps `limit` bars
    assert len(candles) == 300
    assert candles[-1]["t"] == finished[-1].exchange_ts  # newest FINISHED candle
    assert all(c["t"] + kl.period_ns("1h") <= calls["end_time_ms"] * 1_000_000 for c in candles)
