"""Fisher Transform indicator (Ehlers).

Gaussianizes a range-normalized median price via the inverse hyperbolic
tangent (arctanh). Price distributions are heavy-tailed and non-Gaussian;
the Fisher Transform reshapes the normalized price into an approximately
Gaussian, unbounded series whose extremes are AMPLIFIED rather than
saturated. This produces crisp, sharply-peaked turning points that fire a
bar or two earlier than a smoothed band oscillator crossing a fixed level.

Reference: John F. Ehlers, "Using the Fisher Transform",
Technical Analysis of Stocks & Commodities, November 2002.

Canonical recursion (per bar t, Price = (High + Low) / 2):
    ratio_t  = (Price_t - MinL_t) / (MaxH_t - MinL_t)          # in [0, 1]
    norm_t   = 2 * (ratio_t - 0.5)                             # in [-1, 1]
    value_t  = 0.33 * norm_t + 0.67 * value_{t-1}              # IIR smoothing
    value_t  = clamp(value_t, -0.999, 0.999)                   # keep log finite
    fisher_t = 0.5 * ln((1 + value_t) / (1 - value_t)) + 0.5 * fisher_{t-1}
where MaxH_t / MinL_t are the rolling max / min of the median price over
`period` bars. The one-bar lag fisher_{t-1} is Ehlers' "trigger" line.

Strictly causal: every quantity at bar t uses only bars <= t. The two
forward recursions (value, fisher) are IIR filters that read past state
only; there is no centered window, no negative shift, no future row. Once
the last candle closes, fisher_{-1} is final and never repaints.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class FisherTransformIndicator(BaseIndicator):
    """Ehlers Fisher Transform: gaussianized turning-point oscillator."""

    @property
    def name(self) -> str:
        return "fisher_transform"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 9))
        extreme = float(self.thresholds.get("extreme", 1.5))
        strong_extreme = float(self.thresholds.get("strong_extreme", 2.5))

        n = len(df)
        if n < period + 1:
            return self._make_result(
                Signal.NEUTRAL, "Fisher Transform data insufficient", {"fisher": None}
            )

        # Median price = (High + Low) / 2  (Ehlers' "Price").
        price = ((df["high"].to_numpy(dtype=float) + df["low"].to_numpy(dtype=float)) / 2.0)

        price_s = pd.Series(price)
        roll_max = price_s.rolling(window=period, min_periods=period).max().to_numpy()
        roll_min = price_s.rolling(window=period, min_periods=period).min().to_numpy()

        fisher_series = np.full(n, np.nan, dtype=float)
        value = 0.0   # smoothed normalized price (state)
        fisher = 0.0  # transformed output (state)

        for i in range(n):
            hi = roll_max[i]
            lo = roll_min[i]
            if np.isnan(hi) or np.isnan(lo):
                # Warm-up window not yet full: hold seeds, emit NaN.
                continue
            rng = hi - lo
            if rng <= 0.0:
                ratio = 0.5  # flat range -> neutral normalization
            else:
                ratio = (price[i] - lo) / rng
            norm = 2.0 * (ratio - 0.5)                 # in [-1, 1]
            value = 0.33 * norm + 0.67 * value          # forward IIR smoothing
            if value > 0.999:
                value = 0.999
            elif value < -0.999:
                value = -0.999
            fisher = 0.5 * np.log((1.0 + value) / (1.0 - value)) + 0.5 * fisher
            fisher_series[i] = fisher

        fisher_now = fisher_series[-1]
        if np.isnan(fisher_now):
            return self._make_result(
                Signal.NEUTRAL, "Fisher Transform data insufficient", {"fisher": None}
            )

        fisher_prev = fisher_series[-2] if n >= 2 else np.nan
        if np.isnan(fisher_prev):
            fisher_prev = fisher_now  # no confirmed turn / cross available

        raw = {
            "fisher": round(float(fisher_now), 4),
            "trigger": round(float(fisher_prev), 4),
            "period": period,
        }

        turning_up = fisher_now > fisher_prev
        turning_down = fisher_now < fisher_prev

        # --- Deep oversold ------------------------------------------------
        if fisher_now <= -strong_extreme:
            if turning_up:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Fisher={fisher_now:.2f} deep oversold reversal (turning up)",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"Fisher={fisher_now:.2f} deeply stretched down, mean-reversion setup",
                raw,
            )

        # --- Oversold -----------------------------------------------------
        if fisher_now <= -extreme:
            if turning_up:
                return self._make_result(
                    Signal.BUY,
                    f"Fisher={fisher_now:.2f} oversold reversal (turning up)",
                    raw,
                )
            return self._make_result(
                Signal.NEUTRAL,
                f"Fisher={fisher_now:.2f} oversold but still falling",
                raw,
            )

        # --- Deep overbought ---------------------------------------------
        if fisher_now >= strong_extreme:
            if turning_down:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Fisher={fisher_now:.2f} deep overbought reversal (turning down)",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"Fisher={fisher_now:.2f} deeply stretched up, mean-reversion setup",
                raw,
            )

        # --- Overbought ---------------------------------------------------
        if fisher_now >= extreme:
            if turning_down:
                return self._make_result(
                    Signal.SELL,
                    f"Fisher={fisher_now:.2f} overbought reversal (turning down)",
                    raw,
                )
            return self._make_result(
                Signal.NEUTRAL,
                f"Fisher={fisher_now:.2f} overbought but still rising",
                raw,
            )

        # --- Mid zone: zero-line cross as fresh momentum ------------------
        if fisher_prev <= 0.0 and fisher_now > 0.0:
            return self._make_result(
                Signal.BUY,
                f"Fisher={fisher_now:.2f} crossed above zero (bullish momentum)",
                raw,
            )
        if fisher_prev >= 0.0 and fisher_now < 0.0:
            return self._make_result(
                Signal.SELL,
                f"Fisher={fisher_now:.2f} crossed below zero (bearish momentum)",
                raw,
            )

        return self._make_result(
            Signal.NEUTRAL, f"Fisher={fisher_now:.2f} neutral zone", raw
        )
