"""Parity gate: the ported engine must reproduce the Android/Python reference
fixture (BTCUSDT 1h x300) exactly on signal+score and within tolerance on raw
values. Fixtures extracted verbatim from android/.../testutil/FixtureData.kt.
"""

import json
import pathlib

import pandas as pd
import pytest

from diveintocrypto_desktop.engine.loader import load_config
from diveintocrypto_desktop.engine.signal_service import SignalService

FX = pathlib.Path(__file__).parent / "fixtures"


def _dataframe() -> pd.DataFrame:
    candles = json.loads((FX / "btcusdt_1h_300.json").read_text())
    return pd.DataFrame(
        [{"open": c["o"], "high": c["h"], "low": c["l"], "close": c["c"], "volume": c["v"]} for c in candles]
    )


def _results() -> dict:
    return {r.name: r for r in SignalService(load_config()).calculate_all(_dataframe())}


def _expected() -> dict:
    return json.loads((FX / "btcusdt_1h_300_expected.json").read_text())


@pytest.mark.parametrize("name", sorted(_expected().keys()))
def test_indicator_signal_and_score_parity(name):
    exp = _expected()[name]
    got = _results()[name]
    assert got.signal.value == exp["signal"], f"{name}: signal {got.signal.value} != {exp['signal']}"
    assert got.score == exp["score"], f"{name}: score {got.score} != {exp['score']}"


@pytest.mark.parametrize("name", sorted(_expected().keys()))
def test_indicator_raw_values_parity(name):
    exp = _expected()[name]
    got = _results()[name]
    raw = got.raw_values or {}
    for k, v in (exp.get("raw_values") or {}).items():
        if not isinstance(v, (int, float)):
            continue  # skip string fields like volatility="NORMAL"
        assert k in raw, f"{name}: missing raw value {k}"
        tol = max(0.1, abs(float(v)) * 0.005)
        assert abs(float(raw[k]) - float(v)) <= tol, f"{name}.{k}: {raw[k]} != {v} (tol {tol})"
