"""Symbol builder: offline assemble() on synthetic inputs + live build_symbol()."""

import asyncio
import math

import pytest

from diveintocrypto_desktop.data.http import close_session
from diveintocrypto_desktop.scan import symbol_builder as sb
from diveintocrypto_desktop.scan.constants import ALL_TFS

_SERIES_KEYS = {"oi", "glob", "acc", "pos", "taker", "funding", "price", "bias"}


def _candles(n=300):
    rows, price = [], 100.0
    for i in range(n):
        close = max(price + math.sin(i / 9.0) * 0.7 + 0.04, 1.0)
        rows.append(
            {"t": i * 3_600_000_000_000, "o": price, "h": max(price, close) + 0.3,
             "l": min(price, close) - 0.3, "c": close, "v": 1000 + i}
        )
        price = close
    return rows


def test_assemble_produces_full_data_contract():
    candles_by_tf = {tf: _candles() for tf in ALL_TFS}
    series_data = {k: [1.0 + 0.01 * i for i in range(48)] for k in ("oi", "glob", "acc", "pos", "taker", "price")}
    series_data["funding"] = [0.0001] * 48
    div_inputs = {"1d": ([100 + i for i in range(40)], [1.5 - i * 0.01 for i in range(40)])}

    from diveintocrypto_desktop.engine.loader import load_config
    from diveintocrypto_desktop.engine.signal_service import SignalService
    expected_indicator_count = len(SignalService(load_config()).indicators)

    obj = sb.assemble("BTCUSDT", "Bitcoin", 2.5, 68000.0, candles_by_tf, series_data, div_inputs)

    assert obj["s"] == "BTCUSDT" and obj["name"] == "Bitcoin"
    assert len(obj["multiTf"]) == 12
    assert all(m["signal"] in {"STRONG_BUY", "BUY", "NEUTRAL", "SELL", "STRONG_SELL"} for m in obj["multiTf"])
    assert obj["buy"] + obj["sell"] + obj["neutral"] == 12
    assert len(obj["indicators"]) == expected_indicator_count
    assert {i["name"] for i in obj["indicators"]} >= {"rsi", "macd", "adx_di"}
    assert obj["finalSignal"] in {"STRONG_BUY", "BUY", "NEUTRAL", "SELL", "STRONG_SELL"}
    assert obj["action"] in {"AL", "SAT", "BEKLE"}
    assert obj["risk"] in {"LOW", "MEDIUM", "HIGH"}
    assert set(obj["series"]) == _SERIES_KEYS
    assert len(obj["series"]["bias"]) == 48
    assert obj["whaleRegime"] in {"confirm", "adverse", "neutral"}
    assert "score" in obj["divergence"] and "coverage" in obj["divergence"]


@pytest.mark.live
def test_live_build_symbol_btc():
    from diveintocrypto_desktop.engine.loader import load_config
    from diveintocrypto_desktop.engine.signal_service import SignalService
    expected_indicator_count = len(SignalService(load_config()).indicators)

    async def run():
        try:
            obj = await sb.build_symbol("BTCUSDT")
            assert obj["s"] == "BTCUSDT"
            assert obj["price"] > 0
            assert len(obj["multiTf"]) == 12 and len(obj["indicators"]) == expected_indicator_count
            assert len(obj["candles"]) > 0
            assert any(len(obj["series"][k]) > 0 for k in ("oi", "pos", "taker"))
        finally:
            await close_session()

    asyncio.run(run())
