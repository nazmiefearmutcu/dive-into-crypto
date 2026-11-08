"""Regression tests for verified indicator defects (engine/indicators).

Each test pins a corrected behaviour on a deterministic synthetic series:
body-containment engulfing, statistically gated half-life reversion, NaN-safe
percentile / Hurst / squeeze / CMF logic, the ATR filter's single volatility cutoff
set and missing-baseline result, the linreg first-to-last scale, the Mass Index
setback event, VWAP zero volume, degenerate Supertrend / Donchian / Connors
periods, RSI and MFI flat windows against thresholds, and OBV divergence
normalised by window volume.

Series are generated with integer LCG arithmetic (no numpy RNG) so the Kotlin
mirror in android/.../commonTest/.../domain/indicator/IndicatorRegressionTest.kt
can rebuild the identical bars.
"""

import warnings

import pandas as pd
import pytest

from diveintocrypto_desktop.engine.indicators.atr_filter import ATRFilterIndicator
from diveintocrypto_desktop.engine.indicators.awesome_oscillator import AwesomeOscillatorIndicator
from diveintocrypto_desktop.engine.indicators.atr_percentile import ATRPercentileIndicator
from diveintocrypto_desktop.engine.indicators.base import Signal
from diveintocrypto_desktop.engine.indicators.bollinger import BollingerBandsIndicator
from diveintocrypto_desktop.engine.indicators.cmf import CMFIndicator
from diveintocrypto_desktop.engine.indicators.connors_rsi import ConnorsRSIIndicator
from diveintocrypto_desktop.engine.indicators.donchian_breakout import DonchianBreakoutIndicator
from diveintocrypto_desktop.engine.indicators.engulfing import EngulfingIndicator
from diveintocrypto_desktop.engine.indicators.half_life_reversion import HalfLifeReversionIndicator
from diveintocrypto_desktop.engine.indicators.hist_vol_percentile import HistVolPercentileIndicator
from diveintocrypto_desktop.engine.indicators.hurst import HurstRegimeIndicator
from diveintocrypto_desktop.engine.indicators.linreg_slope import LinRegSlopeIndicator
from diveintocrypto_desktop.engine.indicators.mass_index import MassIndexIndicator
from diveintocrypto_desktop.engine.indicators.mfi import MFIIndicator
from diveintocrypto_desktop.engine.indicators.obv import OBVIndicator
from diveintocrypto_desktop.engine.indicators.rsi import RSIIndicator
from diveintocrypto_desktop.engine.indicators.squeeze import TTMSqueezeIndicator
from diveintocrypto_desktop.engine.indicators.supertrend import SupertrendIndicator
from diveintocrypto_desktop.engine.indicators.vwap import VWAPIndicator

NAN = float("nan")


def _ohlcv(rows: list[tuple]) -> pd.DataFrame:
    """rows: (open, high, low, close, volume)."""
    return pd.DataFrame(
        [{"open": o, "high": h, "low": l, "close": c, "volume": v} for o, h, l, c, v in rows]
    )


def _bar(o: float, h: float, l: float, c: float, v: float = 1.0) -> tuple:
    return (o, h, l, c, v)


def _with_thresholds(name: str, **thresholds) -> dict:
    return {"indicator_thresholds": {name: thresholds}}


def _lcg_steps(n: int, seed: int = 12345) -> list[float]:
    """Deterministic pseudo-random steps in [-0.5, 0.5) from a 31-bit LCG."""
    x = seed
    out = []
    for _ in range(n):
        x = (1103515245 * x + 12345) % (2**31)
        out.append(x / 2**31 - 0.5)
    return out


def _lcg_walk(n: int, seed: int = 12345) -> pd.DataFrame:
    close = [100.0]
    for step in _lcg_steps(n - 1, seed):
        close.append(close[-1] + step)
    return _ohlcv([_bar(c, c + 0.2, c - 0.2, c) for c in close])


# --------------------------------------------------------------------------
# engulfing: the current body must contain the prior body
# --------------------------------------------------------------------------


def test_engulfing_bullish_non_containing_body_is_neutral():
    # Prior red body 100-101; current green body 100.5-101.5 does not cover 100.
    bars = [_bar(100, 100.5, 99.5, 100)] * 3 + [
        _bar(101, 101.2, 99.8, 100),
        _bar(100.5, 101.6, 100.3, 101.5),
    ]
    r = EngulfingIndicator({}).calculate(_ohlcv(bars))
    assert r.signal == Signal.NEUTRAL


def test_engulfing_bearish_non_containing_body_is_neutral():
    # Prior green body 100-101; current red body 99.5-100.5 does not cover 101.
    bars = [_bar(100, 100.5, 99.5, 100)] * 3 + [
        _bar(100, 101.2, 99.8, 101),
        _bar(100.5, 100.6, 99.4, 99.5),
    ]
    r = EngulfingIndicator({}).calculate(_ohlcv(bars))
    assert r.signal == Signal.NEUTRAL


# --------------------------------------------------------------------------
# half_life_reversion: statistical gate, sub-bar floor, finite window
# --------------------------------------------------------------------------


def test_half_life_random_walk_is_neutral_by_significance_gate():
    r = HalfLifeReversionIndicator({}).calculate(_lcg_walk(60))
    assert r.signal == Signal.NEUTRAL
    assert "not statistically mean-reverting" in r.reason


def test_half_life_alternating_series_beta_below_minus_one_is_neutral():
    # y alternates 100/110, so beta = -2 (an unstable AR(1), no finite half-life).
    closes = [100.0 if i % 2 == 0 else 110.0 for i in range(60)]
    df = _ohlcv([_bar(c, c, c, c) for c in closes])
    r = HalfLifeReversionIndicator({}).calculate(df)
    assert r.signal == Signal.NEUTRAL
    assert "unstable" in r.reason


def test_half_life_genuine_ar1_series_votes_a_revert_fade():
    """Positive path: a seeded mean-reverting AR(1) (phi 0.6, beta ~ -0.4) passes the
    t-stat and half-life gates, and a stretched last close fades toward the mean."""
    import random

    rng = random.Random(10)
    y = [0.0]
    for _ in range(59):
        y.append(0.6 * y[-1] + rng.gauss(0.0, 1.0))
    df = _ohlcv([_bar(100.0 + v, 100.0 + v, 100.0 + v, 100.0 + v) for v in y])
    r = HalfLifeReversionIndicator({}).calculate(df)
    assert r.signal == Signal.SELL  # last close is above the window mean: a short fade
    assert "revert" in r.reason and "not statistically" not in r.reason
    assert r.raw_values["beta"] < 0 and 1.0 <= r.raw_values["half_life"] <= 30.0
    assert r.raw_values["zscore"] >= 1.0


def test_half_life_non_finite_window_is_neutral_not_an_error():
    closes = [100.0 + 0.01 * i for i in range(60)]
    closes[-10] = NAN
    df = _ohlcv([_bar(c, c, c, c) for c in closes])
    r = HalfLifeReversionIndicator({}).calculate(df)
    assert r.signal == Signal.NEUTRAL
    assert "insufficient" in r.reason.lower()


# --------------------------------------------------------------------------
# NaN current bar must never reach a directional branch
# --------------------------------------------------------------------------


def test_atr_percentile_nan_last_bar_is_neutral():
    rows = []
    for i in range(120):
        c = 100.0 + 0.01 * i
        rows.append(_bar(c, c + 0.2, c - 0.2, c))
    price = 101.2
    for _ in range(30):
        price += 0.8
        rows.append(_bar(price - 0.4, price + 2.0, price - 2.0, price))
    rows.append(_bar(NAN, NAN, NAN, NAN))
    r = ATRPercentileIndicator({}).calculate(_ohlcv(rows))
    assert r.signal == Signal.NEUTRAL
    assert r.reason == "ATR%ile data invalid"


def test_hist_vol_percentile_nan_last_bar_is_neutral():
    rows = []
    price = 100.0
    for _ in range(150):
        price *= 1.0005
        rows.append(_bar(price, price + 0.1, price - 0.1, price))
    for i in range(25):
        price *= 1.03 if i % 2 == 0 else 0.98
        rows.append(_bar(price, price + 0.5, price - 0.5, price))
    rows.append(_bar(NAN, NAN, NAN, NAN))
    r = HistVolPercentileIndicator({}).calculate(_ohlcv(rows))
    assert r.signal == Signal.NEUTRAL
    assert r.reason == "HV%ile data invalid"


def test_hurst_nan_inside_window_is_neutral():
    closes = [100.0 * (1.003**i) for i in range(80)]
    closes[-5] = NAN
    df = _ohlcv([_bar(c, c, c, c) for c in closes])
    r = HurstRegimeIndicator({}).calculate(df)
    assert r.signal == Signal.NEUTRAL
    assert r.reason == "Hurst insufficient data"


# --------------------------------------------------------------------------
# atr_filter: one cutoff set, explicit warm-up when the baseline is missing
# --------------------------------------------------------------------------


def test_atr_filter_missing_baseline_is_explicit_not_normal():
    df = _ohlcv([_bar(100 + 0.1 * i, 101 + 0.1 * i, 99 + 0.1 * i, 100.5 + 0.1 * i) for i in range(16)])
    r = ATRFilterIndicator({}).calculate(df)
    assert r.signal == Signal.NEUTRAL
    assert "baseline warming up" in r.reason
    assert r.raw_values["volatility"] == "UNKNOWN"


def test_atr_filter_label_matches_reason_text_on_every_prefix():
    steps = _lcg_steps(260, seed=7)
    close = [100.0]
    for k, s in enumerate(steps):
        scale = 0.02 if (k // 30) % 2 == 0 else 10.0  # alternating calm and volatile phases
        close.append(close[-1] + s * scale)
    rows = [_bar(c, c + abs(s) + 0.1, c - abs(s) - 0.1, c) for c, s in zip(close, [0.0] + steps, strict=True)]
    df = _ohlcv(rows)
    seen = set()
    for n in range(60, len(df) + 1, 7):
        r = ATRFilterIndicator({}).calculate(df.iloc[:n].reset_index(drop=True))
        label = r.raw_values["volatility"]
        seen.add(label)
        assert ("Low ATR volatility" in r.reason) == (label == "LOW"), (n, label, r.reason)
        assert ("High ATR volatility" in r.reason) == (label == "HIGH"), (n, label, r.reason)
    assert seen == {"LOW", "NORMAL", "HIGH"}, f"series did not exercise every label: {seen}"


# --------------------------------------------------------------------------
# squeeze: warm-up is insufficient data, not a momentum SELL
# --------------------------------------------------------------------------


@pytest.mark.parametrize("n", [10, 19])
def test_squeeze_warm_up_is_insufficient_not_sell(n):
    df = _ohlcv([_bar(100 + 0.1 * i, 101, 99, 100 + 0.1 * i) for i in range(n)])
    r = TTMSqueezeIndicator({}).calculate(df)
    assert r.signal == Signal.NEUTRAL
    assert r.reason == "Squeeze data insufficient"


# --------------------------------------------------------------------------
# linreg_slope: first-to-last move over period-1 steps
# --------------------------------------------------------------------------


def test_linreg_slope_pct_is_first_to_last_move():
    closes = [100.0 + 0.5 * i for i in range(20)]
    df = _ohlcv([_bar(c, c, c, c) for c in closes])
    r = LinRegSlopeIndicator({}).calculate(df)
    mean = sum(closes) / len(closes)
    expected = 0.5 * 19 / mean
    assert r.raw_values["r2"] == pytest.approx(1.0)
    assert r.raw_values["slope_pct"] == pytest.approx(expected, abs=1e-5)


# --------------------------------------------------------------------------
# mass_index: the setback is an event on the current bar
# --------------------------------------------------------------------------


def test_mass_index_reversal_vote_fires_once_per_setback():
    rows = []
    for i in range(130):
        c = 100.0 + 0.2 * i
        half = 1.5 if 60 <= i <= 75 else 0.5  # range bulge at bars 60-75
        rows.append(_bar(c, c + half, c - half, c))
    df = _ohlcv(rows)
    directional = []
    for n in range(60, len(df) + 1):
        r = MassIndexIndicator({}).calculate(df.iloc[:n].reset_index(drop=True))
        if r.signal in (Signal.SELL, Signal.STRONG_SELL):
            directional.append(n)
    # Level semantics voted on ~25 consecutive bars; event semantics votes on one bar.
    # Exactly one vote, at the setback bar (n=86, the bar after the bulge peaks): an
    # indicator that never fires must fail here, not pass vacuously.
    assert directional == [86]


# --------------------------------------------------------------------------
# vwap: zero volume has no VWAP
# --------------------------------------------------------------------------


def test_vwap_zero_volume_window_is_insufficient_without_warnings():
    df = _ohlcv([_bar(100 + i, 101 + i, 99 + i, 100.5 + i, 0.0) for i in range(25)])
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        r = VWAPIndicator({}).calculate(df)
    assert r.signal == Signal.NEUTRAL
    assert r.reason == "VWAP data insufficient"


# --------------------------------------------------------------------------
# supertrend / donchian / connors: degenerate periods do not raise
# --------------------------------------------------------------------------


def test_supertrend_period_zero_and_exact_length_are_insufficient():
    df = _ohlcv([_bar(100 + i, 101 + i, 99 + i, 100.5 + i) for i in range(12)])
    r0 = SupertrendIndicator(_with_thresholds("supertrend", period=0)).calculate(df)
    assert r0.signal == Signal.NEUTRAL
    r_exact = SupertrendIndicator(_with_thresholds("supertrend", period=12)).calculate(df)
    assert r_exact.signal == Signal.NEUTRAL


def test_donchian_zero_exit_period_is_insufficient_not_error():
    df = _ohlcv([_bar(100 + i, 101 + i, 99 + i, 100.5 + i) for i in range(40)])
    r = DonchianBreakoutIndicator(_with_thresholds("donchian_breakout", exit_period=0)).calculate(df)
    assert r.signal == Signal.NEUTRAL


def test_connors_rsi_zero_rsi_period_is_insufficient_not_error():
    df = _ohlcv([_bar(100 + (i % 3), 101, 99, 100 + (i % 3)) for i in range(150)])
    r = ConnorsRSIIndicator(_with_thresholds("connors_rsi", rsi_period=0)).calculate(df)
    assert r.signal == Signal.NEUTRAL


# --------------------------------------------------------------------------
# bollinger: squeeze breakouts follow the breakout direction
# --------------------------------------------------------------------------


def test_bollinger_squeeze_breakout_directions():
    closes = [100.0 + 0.1 * (i % 2) for i in range(20)]
    cfg = _with_thresholds("bollinger", period=20, squeeze_threshold=0.5)
    down = _ohlcv([_bar(c, c, c, c) for c in closes + [90.0]])
    up = _ohlcv([_bar(c, c, c, c) for c in closes + [110.0]])
    assert BollingerBandsIndicator(cfg).calculate(down).signal == Signal.SELL
    assert BollingerBandsIndicator(cfg).calculate(up).signal == Signal.BUY


# --------------------------------------------------------------------------
# obv: divergence normalised by window volume, not by |base OBV|
# --------------------------------------------------------------------------


def test_obv_divergence_uses_window_volume_scale():
    rows = []
    price = 100.0
    for _ in range(30):  # long rising prefix: cumulative OBV builds to ~+30,000
        price += 0.1
        rows.append(_bar(price, price, price, price, 1000.0))
    for i in range(10):  # last 10 bars: up +0.5 on volume 100, down -0.2 on volume 300
        price += 0.5 if i % 2 == 0 else -0.2
        rows.append(_bar(price, price, price, price, 100.0 if i % 2 == 0 else 300.0))
    r = OBVIndicator({}).calculate(_ohlcv(rows))
    # obv change -1000 over window volume 2000 = -0.5 (Python reference), not -1000 / 30,000.
    assert r.signal == Signal.SELL
    assert "bearish divergence" in r.reason


# --------------------------------------------------------------------------
# rsi / mfi: a flat window is 50 and goes through the threshold ladder
# --------------------------------------------------------------------------


def test_rsi_flat_window_maps_to_50_through_thresholds():
    df = _ohlcv([_bar(100, 100, 100, 100)] * 30)
    r = RSIIndicator(_with_thresholds("rsi", sell=40)).calculate(df)
    assert r.raw_values["rsi"] == 50.0
    assert r.signal == Signal.SELL
    assert "overbought zone" in r.reason


def test_mfi_flat_window_maps_to_50_through_thresholds():
    df = _ohlcv([_bar(100, 100, 100, 100, 1000.0)] * 30)
    r = MFIIndicator(_with_thresholds("mfi", sell=40)).calculate(df)
    assert r.raw_values["mfi"] == 50.0
    assert r.signal == Signal.SELL


# --------------------------------------------------------------------------
# cmf: zero volume is insufficient data, not a 0.0 reading
# --------------------------------------------------------------------------


def test_cmf_zero_volume_window_is_insufficient():
    df = _ohlcv([_bar(100, 101, 99, 100.5, 0.0)] * 25)
    r = CMFIndicator({}).calculate(df)
    assert r.signal == Signal.NEUTRAL
    assert r.reason == "CMF data insufficient"


# --------------------------------------------------------------------------
# awesome_oscillator: a single valid AO bar is warm-up, not a trend label
# --------------------------------------------------------------------------


def test_awesome_oscillator_single_valid_bar_is_warm_up():
    rows = [_bar(100 + 0.1 * i, 101 + 0.1 * i, 99 + 0.1 * i, 100.5 + 0.1 * i) for i in range(34)]
    r = AwesomeOscillatorIndicator({}).calculate(_ohlcv(rows))
    assert r.signal == Signal.NEUTRAL
    assert r.reason == "AO warming up"


# --------------------------------------------------------------------------
# period 1 has no sample std: insufficient data, not an exception
# --------------------------------------------------------------------------


def test_squeeze_and_bollinger_period_one_are_insufficient():
    df = _ohlcv([_bar(100 + 0.1 * i, 101, 99, 100 + 0.1 * i) for i in range(30)])
    cfg = _with_thresholds("squeeze", period=1)
    assert TTMSqueezeIndicator(cfg).calculate(df).signal == Signal.NEUTRAL
    cfg_bb = _with_thresholds("bollinger", period=1)
    assert BollingerBandsIndicator(cfg_bb).calculate(df).signal == Signal.NEUTRAL


# --------------------------------------------------------------------------
# atr_filter: reason text describes the volatility only (no sizing advice)
# --------------------------------------------------------------------------


def test_atr_filter_reason_text_has_no_sizing_or_trading_advice():
    rows = [_bar(100.0, 100.5, 99.5, 100.0) for _ in range(60)]
    rows.append(_bar(100.0, 120.0, 80.0, 100.0))  # one violent final bar: HIGH volatility
    r = ATRFilterIndicator({}).calculate(_ohlcv(rows))
    assert r.raw_values["volatility"] == "HIGH"
    assert r.reason.startswith("High ATR volatility (") and "wide ranges" in r.reason
    for banned in ("reduce", "position", "breakout", "avoid"):
        assert banned not in r.reason.lower()
