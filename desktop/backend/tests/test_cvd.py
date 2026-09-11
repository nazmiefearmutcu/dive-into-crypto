"""CVD (cumulative volume delta) — parser + rolling aggregation over fake
aggTrades payloads, snapshot cache, honest unavailability. Fully offline.
"""

import asyncio

import pytest

from diveintocrypto_desktop.data import cvd


def _trade(i: int, t: int, q: str = "1.0", p: str = "100.0", m: bool = False) -> dict:
    return {"a": i, "p": p, "q": q, "nq": q, "f": i, "l": i, "T": t, "m": m}


# ── parser ────────────────────────────────────────────────────────────────────
def test_parse_trades_object_payload_sides():
    t0 = 1_700_000_000_000
    payload = [
        _trade(1, t0, m=False),  # buy-taker
        _trade(2, t0 + 1000, m=True),  # sell-taker
    ]
    trades = cvd.parse_trades(payload)
    assert [t["side"] for t in trades] == [1, -1]
    assert trades[0]["q"] == 1.0 and trades[0]["t"] == t0


def test_parse_trades_legacy_positional_payload():
    trades = cvd.parse_trades([["100.0", "2.0", 7, 1_700_000_000_000, True]])
    assert len(trades) == 1
    assert trades[0] == {"p": 100.0, "q": 2.0, "t": 1_700_000_000_000, "side": -1}


def test_parse_trades_skips_corrupt_rows():
    payload = [
        _trade(1, 1_700_000_000_000),
        {"p": "oops", "q": "1", "T": "x", "m": False},  # bad numbers
        ["1.0", "1.0"],  # too short
        None,
        _trade(2, 1_700_000_001_000),
    ]
    trades = cvd.parse_trades(payload)
    assert [t["t"] for t in trades] == [1_700_000_000_000, 1_700_000_001_000]


# ── rolling aggregation ───────────────────────────────────────────────────────
def test_rolling_cvd_balanced_book_is_zero():
    t0 = 1_700_000_000_000
    trades = cvd.parse_trades([
        _trade(1, t0, q="2.5", m=False),
        _trade(2, t0 + 1000, q="2.5", m=True),
        _trade(3, t0 + 2000, q="1.0", m=False),
        _trade(4, t0 + 3000, q="1.0", m=True),
    ])
    snap = cvd.rolling_cvd(trades)
    assert snap["window_trades"] == 4
    assert snap["cvd"] == 0.0
    assert snap["buy_vol"] == 3.5 and snap["sell_vol"] == 3.5
    assert snap["delta_series"][-1] == 0.0
    assert snap["window_seconds"] == 3.0


def test_rolling_cvd_buy_pressure_positive():
    t0 = 1_700_000_000_000
    trades = cvd.parse_trades([
        _trade(1, t0, q="1.0", m=False),
        _trade(2, t0 + 1000, q="0.5", m=True),
        _trade(3, t0 + 2000, q="3.0", m=False),
    ])
    snap = cvd.rolling_cvd(trades)
    assert snap["cvd"] == 3.5
    assert snap["buy_vol"] == 4.0 and snap["sell_vol"] == 0.5
    assert snap["delta_series"] == [1.0, 0.5, 3.5]


def test_rolling_cvd_window_cuts_old_trades():
    t0 = 1_700_000_000_000
    trades = cvd.parse_trades([
        _trade(1, t0 - cvd.WINDOW_SECONDS * 1000 - 5000, q="99.0", m=False),  # outside
        _trade(2, t0, q="1.0", m=False),
    ])
    snap = cvd.rolling_cvd(trades)
    assert snap["window_trades"] == 1
    assert snap["cvd"] == 1.0 and snap["buy_vol"] == 1.0 and snap["sell_vol"] == 0.0


def test_rolling_cvd_series_downsampled_to_cap():
    t0 = 1_700_000_000_000
    payload = [_trade(i, t0 + i * 1000, q="0.1", m=i % 3 == 0) for i in range(600)]
    snap = cvd.rolling_cvd(cvd.parse_trades(payload))
    assert len(snap["delta_series"]) <= cvd.SERIES_POINTS + 1
    assert snap["window_trades"] == 600


def test_rolling_cvd_empty_is_empty():
    assert cvd.rolling_cvd([]) == {}


# ── snapshot cache + honest failure ───────────────────────────────────────────
@pytest.fixture(autouse=True)
def _clean_cache():
    cvd.reset_cache()
    yield
    cvd.reset_cache()


@pytest.mark.asyncio
async def test_snapshot_caches_per_symbol():
    calls = []

    async def fake_fetch(symbol, limit=cvd.AGG_TRADES_LIMIT):
        calls.append(symbol)
        return [_trade(1, 1_700_000_000_000, q="2.0", m=False)]

    with patch_fetch(fake_fetch):
        s1 = await cvd.snapshot("BTCUSDT")
        s2 = await cvd.snapshot("BTCUSDT")
        assert s1 == s2
        assert s1["cvd"] == 2.0
        assert calls == ["BTCUSDT"], "second read must be served from the 10s cache"


@pytest.mark.asyncio
async def test_snapshot_unavailable_on_failure():
    async def boom(symbol, limit=cvd.AGG_TRADES_LIMIT):
        raise RuntimeError("binance 451 unreachable")

    with patch_fetch(boom):
        snap = await cvd.snapshot("BTCUSDT")
    assert set(snap) == {"unavailable"}
    assert "451" in snap["unavailable"]


@pytest.mark.asyncio
async def test_snapshot_empty_payload_is_honest():
    async def empty(symbol, limit=cvd.AGG_TRADES_LIMIT):
        return []

    with patch_fetch(empty):
        snap = await cvd.snapshot("XYZUSDT")
    assert snap == {"unavailable": "no_trades_in_window"}


class patch_fetch:
    """Small context helper: swap the module-level fetch used by snapshot()."""

    def __init__(self, fn) -> None:
        self.fn = fn
        self._orig = cvd.fetch_agg_trades

    def __enter__(self):
        cvd.fetch_agg_trades = self.fn
        return self

    def __exit__(self, *exc):
        cvd.fetch_agg_trades = self._orig
        return False
