"""Crocodile integration contract (offline).

The kline and open-interest fetchers hand raw Binance pages to Crocodile's real
public parsers (``crocodile.crypto.exchanges.binance.backfill``). Only the HTTP
call is faked here, so a Crocodile schema change (field renames such as
``exchange_ts`` -> ``source_ts``) or a parser signature change fails this suite
instead of the live app.
"""

from __future__ import annotations

import time
from unittest.mock import patch

import pytest

from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import open_interest as oi_mod
from diveintocrypto_desktop.data.http import FAPI_DATA, FAPI_V1


def _kline_row(open_ms: int, period_ms: int, o: float, c: float, v: float) -> list:
    # Binance /fapi/v1/klines row layout (12 columns, numbers as strings).
    return [
        open_ms, f"{o}", f"{max(o, c) + 1}", f"{min(o, c) - 1}", f"{c}", f"{v}",
        open_ms + period_ms - 1, "0", 10, f"{v / 2}", "0", "0",
    ]


@pytest.mark.asyncio
async def test_fetch_klines_parses_through_crocodile_and_drops_unfinished():
    period_ms = 60 * 60 * 1000
    now_ms = int(time.time() * 1000)
    newest_finished_open = (now_ms // period_ms - 1) * period_ms
    rows = [_kline_row(newest_finished_open - (2 - i) * period_ms, period_ms, 100 + i, 101 + i, 5 + i)
            for i in range(3)]
    rows.append(_kline_row(newest_finished_open + period_ms, period_ms, 200, 201, 9))  # in progress
    seen: dict = {}

    async def fake_get_json(url, params=None, *, rate_limited=False, retry_timeouts=False):
        seen.update(url=url, params=params, rate_limited=rate_limited, retry_timeouts=retry_timeouts)
        return rows

    with patch.object(kl, "get_json", fake_get_json):
        candles = await kl.fetch_klines("BTCUSDT", "1h", limit=3)

    assert seen["url"] == f"{FAPI_V1}/klines"
    assert seen["params"]["symbol"] == "BTCUSDT" and seen["params"]["interval"] == "1h"
    assert seen["params"]["limit"] == 4  # limit + 1 for the anti-repaint trim
    assert seen["retry_timeouts"] is True and seen["rate_limited"] is False
    assert [c["t"] for c in candles] == [r[0] * 1_000_000 for r in rows[:3]]  # ns, finished only
    assert candles[-1] == {"t": rows[2][0] * 1_000_000, "o": 102.0, "h": 104.0, "l": 101.0, "c": 103.0, "v": 7.0}


@pytest.mark.asyncio
async def test_fetch_klines_range_sends_start_and_end():
    seen: dict = {}

    async def fake_get_json(url, params=None, *, rate_limited=False, retry_timeouts=False):
        seen.update(params=params)
        return []

    with patch.object(kl, "get_json", fake_get_json):
        assert await kl.fetch_klines_range("ETHUSDT", "1h", 1_000, 2_000, limit=10) == []
    assert seen["params"] == {"symbol": "ETHUSDT", "interval": "1h", "limit": 10, "startTime": 1_000, "endTime": 2_000}


@pytest.mark.asyncio
async def test_fetch_oi_hist_is_rate_limited_and_parsed_by_crocodile():
    rows = [
        {"symbol": "BTCUSDT", "sumOpenInterest": "100.5", "sumOpenInterestValue": "5000000", "timestamp": 1_700_000_000_000},
        {"symbol": "BTCUSDT", "sumOpenInterest": "101.0", "sumOpenInterestValue": "5100000", "timestamp": 1_700_000_300_000},
    ]
    seen: dict = {}

    async def fake_get_json(url, params=None, *, rate_limited=False, retry_timeouts=False):
        seen.update(url=url, params=params, rate_limited=rate_limited)
        return rows

    with patch.object(oi_mod, "get_json", fake_get_json):
        out = await oi_mod.fetch_oi_hist("BTCUSDT", "bogus-period", limit=2)

    assert seen["url"] == f"{FAPI_DATA}/futures/data/openInterestHist"
    assert seen["params"]["period"] == "5m"  # unknown periods fall back to 5m
    assert seen["rate_limited"] is True  # futures/data shares the 40/60s limiter
    assert out == [
        {"t": 1_700_000_000_000_000_000, "oi": 100.5, "oi_value": 5_000_000.0},
        {"t": 1_700_000_300_000_000_000, "oi": 101.0, "oi_value": 5_100_000.0},
    ]
