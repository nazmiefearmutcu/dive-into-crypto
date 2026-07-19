"""Coppock Curve — long-term momentum turn oscillator.

Concept #19 for the Dive Into Crypto engine (BaseIndicator surface).

Edwin Sedgwick Coppock introduced this indicator in Barron's (1962) as a
long-horizon *buy trigger* for equity indices on monthly data. Its shape is a
deliberately slow momentum oscillator centred on zero:

    ROC_long  = 100 * (close / close.shift(roc_long_period)  - 1)   # default 14
    ROC_short = 100 * (close / close.shift(roc_short_period) - 1)   # default 11
    Coppock   = WMA(ROC_long + ROC_short, wma_period)               # default 10

WMA is a *linearly weighted* moving average (weights 1..N, newest = N). Summing
two rates of change of different lookbacks blends two momentum horizons, and the
weighted smoothing removes the jitter of a raw ROC while still reacting to the
most recent candle. The result is a curve that moves slowly enough that a change
in its *slope* is meaningful — the classic signal is not a level crossing but a
TURN.

The canonical Coppock rule: when the curve is below zero and turns UP (a local
trough), long-term downside momentum has exhausted -> a major buy. This module
keeps that as the strongest bullish reading and adds the symmetric bearish case
(above zero, turning DOWN) so the indicator votes in both directions, as the
crypto-futures engine trades long and short.

Signal mapping (two axes: regime = sign of the curve, event = slope turn):
    STRONG_BUY  : curve below zero AND just turned up (local trough)  [classic]
    STRONG_SELL : curve above zero AND just turned down (local peak)  [mirror]
    BUY         : bullish turn while already positive (re-acceleration), OR
                  curve > 0 and still rising (uptrend momentum intact)
    SELL        : bearish turn while already negative (failed recovery), OR
                  curve < 0 and still falling (downtrend momentum intact)
    NEUTRAL     : fading momentum with no confirmed turn, or flat near zero

Non-redundancy: the engine already has `roc` (a single raw ROC), `macd`
(EMA-difference momentum with a signal line) and `awesome_oscillator`
(SMA-difference of median price). Coppock is distinct in construction *and*
regime: it is the sum of TWO long ROCs (14 + 11) smoothed by a linearly
*weighted* MA, giving a much slower "regime-scale" momentum than raw ROC, no
signal-line/price-EMA dependence like MACD, and no median-price/high-low input
like AO. It answers a different question — "has the multi-week momentum regime
turned?" — rather than "is short-term momentum up right now?".

Strictly causal / look-ahead-free: ROC uses close.shift(+n) (past candles only),
the WMA is a trailing rolling window (past + current), and every decision reads
only closed candles at indices -1 / -2 / -3. There is no shift(-n), no forward
fill, no reindex to future timestamps. Once a candle closes, Coppock[-1] is
fixed and never repaints.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class CoppockCurveIndicator(BaseIndicator):
    """Coppock Curve: WMA of (ROC_long + ROC_short) long-term momentum turns."""

    @property
    def name(self) -> str:
        return "coppock_curve"

    @staticmethod
    def _wma(series: pd.Series, period: int) -> pd.Series:
        """Linearly weighted moving average (weights 1..period, newest heaviest).

        Trailing window only -> strictly causal. NaN until `period` valid inputs.
        """
        weights = np.arange(1, period + 1, dtype=float)
        wsum = weights.sum()
        return series.rolling(window=period).apply(
            lambda x: float(np.dot(x, weights) / wsum), raw=True
        )

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        # --- parameters -----------------------------------------------------
        roc_long_period = int(self.thresholds.get("roc_long_period", 14))
        roc_short_period = int(self.thresholds.get("roc_short_period", 11))
        wma_period = int(self.thresholds.get("wma_period", 10))
        # Deadband around zero: |curve| <= zero_band counts as "at the line".
        # Default 0.0 keeps the classic behaviour; widen to damp zero-line noise.
        zero_band = float(self.thresholds.get("zero_band", 0.0))

        # --- guard ----------------------------------------------------------
        # Need the longest ROC shift, then the WMA window, then 3 curve points
        # (-1, -2, -3) for trough / peak detection.
        min_bars = max(roc_long_period, roc_short_period) + wma_period + 3
        if len(df) < min_bars:
            return self._make_result(
                Signal.NEUTRAL, f"insufficient data (<{min_bars} candles)"
            )

        close = df["close"]

        roc_long = (close / close.shift(roc_long_period) - 1.0) * 100.0
        roc_short = (close / close.shift(roc_short_period) - 1.0) * 100.0
        coppock = self._wma(roc_long + roc_short, wma_period)

        cur = coppock.iloc[-1]
        prev = coppock.iloc[-2]
        prev2 = coppock.iloc[-3]

        if pd.isna(cur) or pd.isna(prev) or pd.isna(prev2):
            return self._make_result(Signal.NEUTRAL, "Coppock data insufficient")

        cur = float(cur)
        prev = float(prev)
        prev2 = float(prev2)

        rising = cur > prev
        falling = cur < prev
        # A turn is a local extremum at the *previous* closed bar confirmed by
        # the current bar: trough = down-then-up, peak = up-then-down.
        turn_up = (cur > prev) and (prev <= prev2)
        turn_down = (cur < prev) and (prev >= prev2)

        raw: dict[str, Any] = {
            "coppock": round(cur, 4),
            "coppock_prev": round(prev, 4),
            "coppock_prev2": round(prev2, 4),
            "roc_long": round(float(roc_long.iloc[-1]), 4),
            "roc_short": round(float(roc_short.iloc[-1]), 4),
            "slope": round(cur - prev, 4),
        }

        # --- decision tree (priority order) --------------------------------
        # 1) Classic Coppock buy: upturn while the curve is below zero.
        if turn_up and cur < -zero_band:
            return self._make_result(
                Signal.STRONG_BUY,
                f"Coppock={cur:.3f} turned up from below zero "
                f"(long-term downside momentum exhausted)",
                raw,
            )
        # 2) Mirror sell: downturn while the curve is above zero.
        if turn_down and cur > zero_band:
            return self._make_result(
                Signal.STRONG_SELL,
                f"Coppock={cur:.3f} turned down from above zero "
                f"(long-term upside momentum exhausted)",
                raw,
            )
        # 3) Bullish turn while already positive: momentum re-accelerating.
        if turn_up:
            return self._make_result(
                Signal.BUY,
                f"Coppock={cur:.3f} turned up in positive regime "
                f"(momentum re-accelerating)",
                raw,
            )
        # 4) Bearish turn while already negative: recovery failing.
        if turn_down:
            return self._make_result(
                Signal.SELL,
                f"Coppock={cur:.3f} turned down in negative regime "
                f"(recovery failing)",
                raw,
            )
        # 5) Trend-intact continuation votes (positive & rising / negative & falling).
        if cur > zero_band and rising:
            return self._make_result(
                Signal.BUY,
                f"Coppock={cur:.3f} positive and rising (momentum intact)",
                raw,
            )
        if cur < -zero_band and falling:
            return self._make_result(
                Signal.SELL,
                f"Coppock={cur:.3f} negative and falling (momentum intact)",
                raw,
            )
        # 6) Everything else: fading momentum without a confirmed turn, or flat.
        return self._make_result(
            Signal.NEUTRAL,
            f"Coppock={cur:.3f} no confirmed turn / flat",
            raw,
        )
