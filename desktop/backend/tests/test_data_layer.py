"""Data-layer tests. Offline tests validate shapes/constants; @live tests hit
the public Binance USDT-M API to prove the real data path end-to-end.
"""

import asyncio

import pytest

from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import funding, open_interest, ratios, universe
from diveintocrypto_desktop.data.http import close_session


# ── offline ────────────────────────────────────────────────────────────────
def test_tf_list_is_the_canonical_twelve():
    assert kl.TF_LIST == ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]


def test_ratio_and_oi_periods_are_the_nine_binance_publishes():
    nine = ["5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d"]
    assert ratios.RATIO_PERIODS == nine
    assert open_interest.OI_PERIODS == nine


def test_to_dataframe_shape():
    df = kl.to_dataframe([{"t": 1, "o": 1.0, "h": 2.0, "l": 0.5, "c": 1.5, "v": 10.0}])
    assert list(df.columns) == ["open", "high", "low", "close", "volume"]
    assert df.iloc[0]["high"] == 2.0


# ── live (network-gated) ─────────────────────────────────────────────────────
@pytest.mark.live
def test_live_klines_btc():
    async def run():
        try:
            candles = await kl.fetch_klines("BTCUSDT", "1h", limit=50)
            assert len(candles) >= 40
            c = candles[-1]
            assert c["h"] >= c["l"] > 0 and c["v"] >= 0 and isinstance(c["t"], int)
        finally:
            await close_session()

    asyncio.run(run())


@pytest.mark.live
def test_live_all_tf_btc():
    async def run():
        try:
            tfs = await kl.fetch_all_tf("BTCUSDT", limit=60)
            assert set(tfs) == set(kl.TF_LIST)
            assert all(len(v) > 0 for v in tfs.values())
        finally:
            await close_session()

    asyncio.run(run())


@pytest.mark.live
def test_live_oi_ratios_funding_btc():
    async def run():
        try:
            oi = await open_interest.fetch_oi_hist("BTCUSDT", "5m", limit=48)
            rs = await ratios.fetch_ratio_series("BTCUSDT", "5m", limit=48)
            pi = await funding.premium_index("BTCUSDT")
            assert len(oi) > 0 and oi[-1]["oi"] > 0
            assert all(k in rs for k in ("glob", "acc", "pos", "taker"))
            assert len(rs["pos"]) > 0 and rs["pos"][-1] > 0
            assert pi["mark_price"] > 0 and pi["index_price"] > 0
        finally:
            await close_session()

    asyncio.run(run())


@pytest.mark.live
def test_live_universe():
    async def run():
        try:
            u = await universe.list_universe(limit=20)
            assert len(u) == 20
            assert u[0]["s"].endswith("USDT")
            assert u[0]["quote_volume"] >= u[-1]["quote_volume"]  # sorted desc
        finally:
            await close_session()

    asyncio.run(run())
