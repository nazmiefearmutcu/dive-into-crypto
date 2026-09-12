"""Unit tests for the price-action pattern library (synthetic candles):
engulfing (bull/bear/strong/marginal/none), liquidity_sweep (sweep-low/high,
ATR-scaled strength, breakout-not-sweep), pivot_structure (HH+HL, LH+LL,
mixed, structure break, insufficient data).
"""

import pandas as pd
import pytest

from diveintocrypto_desktop.engine.indicators.engulfing import EngulfingIndicator
from diveintocrypto_desktop.engine.indicators.liquidity_sweep import LiquiditySweepIndicator
from diveintocrypto_desktop.engine.indicators.pivot_structure import PivotStructureIndicator
from diveintocrypto_desktop.engine.indicators.base import Signal

CFG: dict = {}  # in-code defaults == default.yaml thresholds


def _df(bars: list[dict]) -> pd.DataFrame:
    return pd.DataFrame(
        [{"open": b["o"], "high": b["h"], "low": b["l"], "close": b["c"], "volume": 1.0} for b in bars]
    )


def _bar(o: float, h: float, l: float, c: float) -> dict:
    return {"o": o, "h": h, "l": l, "c": c}


# --------------------------------------------------------------------------
# engulfing
# --------------------------------------------------------------------------


class TestEngulfing:
    def test_bullish_strong(self):
        bars = [_bar(100, 100.5, 99.5, 100)] * 3 + [
            _bar(101, 101.2, 99.8, 100),  # prior: red body 100->101
            _bar(99.7, 101.4, 99.6, 101.3),  # green body engulfs, ratio 1.6/1.8
        ]
        r = EngulfingIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.STRONG_BUY
        assert r.score == 2

    def test_bullish_marginal_long_wick(self):
        bars = [_bar(100, 100.5, 99.5, 100)] * 3 + [
            _bar(101, 101.2, 99.8, 100),
            _bar(99.7, 101.4, 98.0, 101.0),  # engulf holds, ratio 1.3/3.4 < 0.7
        ]
        r = EngulfingIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.BUY
        assert r.score == 1

    def test_bearish_strong(self):
        bars = [_bar(100, 100.5, 99.5, 100)] * 3 + [
            _bar(100, 101.2, 99.8, 101),  # prior: green body
            _bar(101.3, 101.4, 99.6, 99.7),  # red body engulfs, ratio >= 0.7
        ]
        r = EngulfingIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.STRONG_SELL
        assert r.score == -2

    def test_bearish_marginal_long_wick(self):
        bars = [_bar(100, 100.5, 99.5, 100)] * 3 + [
            _bar(100, 101.2, 99.8, 101),
            _bar(101.3, 101.4, 100.0, 100.0),  # body 1.3, range 1.4... use wide wick below
        ]
        # Replace last bar: engulfing red body with a long lower wick.
        bars[-1] = _bar(101.0, 101.4, 99.0, 99.7)  # body 1.3, range 2.4, ratio 0.54
        r = EngulfingIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.SELL
        assert r.score == -1

    def test_no_pattern_same_colour(self):
        bars = [_bar(100, 100.5, 99.5, 100)] * 3 + [
            _bar(100, 100.6, 99.6, 100.4),  # green
            _bar(100.4, 101.0, 100.0, 100.8),  # green again — no engulf setup
        ]
        r = EngulfingIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.NEUTRAL

    def test_insufficient_data(self):
        r = EngulfingIndicator(CFG).calculate(_df([_bar(100, 101, 99, 100.5)]))
        assert r.signal == Signal.NEUTRAL
        assert "insufficient" in r.reason.lower()


# --------------------------------------------------------------------------
# liquidity_sweep
# --------------------------------------------------------------------------


def _sweep_base() -> list[dict]:
    """24 bars: flat 100, a bar setting the swing low, then the test bar."""
    bars = [_bar(100, 100.1, 99.9, 100)] * 24
    bars[22] = _bar(100, 100.1, 99.0, 99.5)  # prior bar: swing low 99.0
    return bars


class TestLiquiditySweep:
    def test_sweep_low_strong_buy(self):
        bars = _sweep_base()
        bars[23] = _bar(99.2, 99.6, 98.5, 99.5)  # pierces 99.0 by 0.5, closes above
        r = LiquiditySweepIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.STRONG_BUY
        assert r.raw_values["low_penetration"] == pytest.approx(0.5, abs=1e-6)

    def test_sweep_low_weak_buy(self):
        bars = _sweep_base()
        bars[23] = _bar(99.2, 99.6, 98.95, 99.5)  # penetration 0.05 < 0.5xATR
        r = LiquiditySweepIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.BUY
        assert r.score == 1

    def test_sweep_high_strong_sell(self):
        bars = _sweep_base()
        bars[22] = _bar(100, 101.0, 99.9, 100.5)  # prior bar: swing high 101.0
        bars[23] = _bar(100.8, 101.5, 100.4, 100.5)  # pierces 101.0, closes below
        r = LiquiditySweepIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.STRONG_SELL
        assert r.score == -2

    def test_sweep_high_weak_sell(self):
        bars = _sweep_base()
        bars[22] = _bar(100, 101.0, 99.9, 100.5)
        bars[23] = _bar(100.8, 101.05, 100.4, 100.5)  # penetration 0.05, small
        r = LiquiditySweepIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.SELL
        assert r.score == -1

    def test_breakout_close_outside_is_not_a_sweep(self):
        bars = _sweep_base()
        bars[23] = _bar(99.2, 99.6, 98.5, 98.8)  # pierces low AND closes below it
        r = LiquiditySweepIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.NEUTRAL

    def test_no_sweep_inside_range(self):
        bars = _sweep_base()
        bars[23] = _bar(100, 100.05, 99.5, 100.0)
        r = LiquiditySweepIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.NEUTRAL

    def test_insufficient_data(self):
        bars = [_bar(100, 100.1, 99.9, 100)] * 5
        r = LiquiditySweepIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.NEUTRAL
        assert "insufficient" in r.reason.lower()


# --------------------------------------------------------------------------
# pivot_structure
# --------------------------------------------------------------------------


def _uptrend_zigzag() -> list[dict]:
    """HH+HL zigzag: swing high 104.5 (j=2) -> swing low 99.8 (j=4) ->
    higher high 105.5 (j=7) -> higher low 100.4 (j=9), ends mid-range."""
    return [
        _bar(99, 100.5, 98.5, 100),
        _bar(100, 102.5, 99.5, 102),
        _bar(102, 104.5, 101.5, 104),
        _bar(104, 104.2, 101.8, 102),
        _bar(102, 102.2, 99.8, 100),
        _bar(100, 101.2, 99.9, 101),
        _bar(101, 103.5, 100.8, 103),
        _bar(103, 105.5, 102.8, 105),
        _bar(105, 105.3, 102.6, 103),
        _bar(103, 103.2, 100.4, 101),
        _bar(101, 102.2, 100.6, 102),
        _bar(102, 103.4, 101.5, 103),
    ]


def _downtrend_zigzag() -> list[dict]:
    """LH+LL zigzag (mirror of the uptrend around 101): swing low 97.5 ->
    swing high 104.2 -> lower low 96.5 -> lower high 102.5."""
    return [
        _bar(103, 104.5, 102.5, 103),
        _bar(103, 103.5, 101.5, 102),
        _bar(102, 102.4, 97.5, 98),  # swing low j=2
        _bar(98, 100.5, 97.6, 100),
        _bar(100, 104.2, 99.8, 104),  # swing high j=4
        _bar(104, 103.8, 102.6, 103),
        _bar(103, 103.0, 101.4, 102),
        _bar(102, 102.3, 96.5, 97),  # lower low j=7
        _bar(97, 99.5, 96.6, 99),
        _bar(99, 102.5, 98.6, 102),  # lower high j=9
        _bar(102, 102.4, 100.8, 101),
        _bar(101, 101.6, 100.2, 100.8),
    ]


class TestPivotStructure:
    def test_uptrend_hh_hl_is_buy(self):
        r = PivotStructureIndicator(CFG).calculate(_df(_uptrend_zigzag()))
        assert r.signal == Signal.BUY
        assert r.raw_values["structure"] == "HH+HL"

    def test_downtrend_lh_ll_is_sell(self):
        r = PivotStructureIndicator(CFG).calculate(_df(_downtrend_zigzag()))
        assert r.signal == Signal.SELL
        assert r.raw_values["structure"] == "LH+LL"

    def test_bearish_structure_break_is_strong_sell(self):
        bars = _uptrend_zigzag()
        # One extra bar closing below the last confirmed swing low (100.4).
        # The break bar is APPENDED: with k=2 the final bars participate in
        # confirming the j=9 swing, so replacing them would unconfirm it.
        bars.append(_bar(101.5, 101.7, 99.0, 99.5))
        r = PivotStructureIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.STRONG_SELL
        assert r.raw_values["structure_break"] == 1

    def test_bullish_structure_break_is_strong_buy(self):
        bars = _downtrend_zigzag()
        # One extra bar closing above the last confirmed swing high (102.5).
        bars.append(_bar(101.0, 103.0, 100.5, 102.8))
        r = PivotStructureIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.STRONG_BUY
        assert r.raw_values["structure_break"] == 1

    def test_mixed_structure_is_neutral(self):
        bars = _uptrend_zigzag()
        # Turn the second low into a LOWER low while highs stay HH -> mixed.
        bars[9] = _bar(103, 103.2, 98.0, 101)
        bars[10] = _bar(101, 102.2, 98.2, 102)
        r = PivotStructureIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.NEUTRAL
        assert r.raw_values["structure"] == "MIXED"

    def test_flat_series_has_no_swings(self):
        bars = [_bar(100, 100.5, 99.5, 100)] * 30
        r = PivotStructureIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.NEUTRAL
        assert "insufficient" in r.reason.lower()

    def test_insufficient_data(self):
        bars = [_bar(100, 101, 99, 100.5)] * 4
        r = PivotStructureIndicator(CFG).calculate(_df(bars))
        assert r.signal == Signal.NEUTRAL
