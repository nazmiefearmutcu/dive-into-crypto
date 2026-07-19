"""Relative Vigor Index (RVI) indicator (John Ehlers).

Premise: in a genuine uptrend a market closes *higher than it opens*, and in a
downtrend it closes *lower than it opens*. The Relative Vigor Index measures the
"vigor" (conviction) of a move by comparing the candle BODY (Close - Open) to the
candle RANGE (High - Low). Dividing the body by the range normalizes for
volatility, so RVI reads pure directional conviction rather than raw distance.

Raw (Close - Open) / (High - Low) is far too noisy to trade, so Ehlers smooths
both the numerator and the denominator with a Symmetric Weighted Moving Average
(SWMA) using triangular weights [1, 2, 2, 1] / 6 over 4 consecutive bars, then
sums each smoothed stream over `period` bars before dividing:

    body_t   = Close_t - Open_t
    range_t  = High_t  - Low_t
    swma(x)_t = (x_t + 2*x_{t-1} + 2*x_{t-2} + x_{t-3}) / 6        # triangular SWMA
    num_t    = sum_{k=0..period-1} swma(body)_{t-k}
    den_t    = sum_{k=0..period-1} swma(range)_{t-k}
    RVI_t    = num_t / den_t                                       # bounded in [-1, +1]
    Signal_t = swma(RVI)_t = (RVI_t + 2*RVI_{t-1} + 2*RVI_{t-2} + RVI_{t-3}) / 6

Because |body_t| <= range_t for every bar and range_t >= 0, the triangular-weighted
period sums obey |num_t| <= den_t, so RVI is mathematically bounded to [-1, +1]:
+1 = every bar closed at its high off its open (max bullish vigor), -1 = mirror.
RVI_t > 0 means the average smoothed body is bullish; RVI_t < 0 means bearish.

The tradable event is the RVI / Signal-line crossover (RVI is the fast body-vigor
line, Signal is its SWMA trigger), exactly analogous to a MACD/Stochastic %K-%D
cross but computed on candle-body conviction instead of price momentum or the
close's position in the range.

Reference: John F. Ehlers, "Cybernetic Analysis for Stocks and Futures" (2004),
chapter on the Relative Vigor Index.

Strictly causal: every quantity at bar t uses only bars <= t. SWMA is a trailing
FIR filter over (t-3..t); the rolling sums use the trailing window (t-period+1..t);
there is no centered window, no shift(-k), no future row. Once the last candle
closes, RVI_{-1} and Signal_{-1} are final and never repaint.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class RelativeVigorIndexIndicator(BaseIndicator):
    """Ehlers Relative Vigor Index: candle-body conviction oscillator with a
    SWMA trigger line. Signals on the RVI/Signal crossover, graded by the side
    of the zero line and the strength of the vigor regime."""

    @property
    def name(self) -> str:
        return "relative_vigor_index"

    @staticmethod
    def _swma(s: pd.Series) -> pd.Series:
        """Symmetric (triangular) weighted moving average, weights [1,2,2,1]/6."""
        return (s + 2.0 * s.shift(1) + 2.0 * s.shift(2) + s.shift(3)) / 6.0

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 10))
        strong_level = float(self.thresholds.get("strong_level", 0.5))

        n = len(df)
        # RVI needs the 4-bar SWMA (3 lags) then a `period`-bar sum -> period+3
        # bars; the Signal line adds a further 3-bar SWMA lag -> period+6 bars.
        min_bars = period + 6
        if n < min_bars:
            return self._make_result(
                Signal.NEUTRAL,
                "RVI data insufficient",
                {"rvi": None, "signal": None},
            )

        open_ = df["open"].astype(float)
        high = df["high"].astype(float)
        low = df["low"].astype(float)
        close = df["close"].astype(float)

        body = close - open_          # (Close - Open): candle body / conviction
        rng = high - low              # (High - Low): candle range / volatility

        num = self._swma(body).rolling(window=period, min_periods=period).sum()
        den = self._swma(rng).rolling(window=period, min_periods=period).sum()

        # den is a triangular-weighted sum of (High-Low) >= 0; it is only zero in
        # a fully dead market. Guard against divide-by-zero -> NaN there.
        den_safe = den.where(den > 0.0)
        rvi = num / den_safe
        signal = self._swma(rvi)

        rvi_now = rvi.iloc[-1]
        sig_now = signal.iloc[-1]
        if pd.isna(rvi_now) or pd.isna(sig_now):
            return self._make_result(
                Signal.NEUTRAL,
                "RVI data insufficient",
                {"rvi": None, "signal": None},
            )

        hist = rvi - signal
        cur_hist = hist.iloc[-1]
        prev_hist = hist.iloc[-2] if n >= 2 else np.nan
        if pd.isna(prev_hist):
            prev_hist = cur_hist  # no confirmed cross available yet

        raw = {
            "rvi": round(float(rvi_now), 4),
            "signal": round(float(sig_now), 4),
            "hist": round(float(cur_hist), 4),
            "period": period,
        }

        bullish_cross = prev_hist <= 0.0 and cur_hist > 0.0
        bearish_cross = prev_hist >= 0.0 and cur_hist < 0.0

        # --- Fresh RVI/Signal crossover: the primary RVI event ---------------
        if bullish_cross:
            if rvi_now > 0.0:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"RVI={rvi_now:.3f} crossed above signal in bullish territory",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"RVI={rvi_now:.3f} crossed above signal (below zero, early turn)",
                raw,
            )

        if bearish_cross:
            if rvi_now < 0.0:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"RVI={rvi_now:.3f} crossed below signal in bearish territory",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"RVI={rvi_now:.3f} crossed below signal (above zero, early turn)",
                raw,
            )

        # --- No fresh cross: sustained-vigor continuation -------------------
        if cur_hist > 0.0 and rvi_now >= strong_level:
            return self._make_result(
                Signal.BUY,
                f"RVI={rvi_now:.3f} holding above signal, strong bullish vigor",
                raw,
            )
        if cur_hist < 0.0 and rvi_now <= -strong_level:
            return self._make_result(
                Signal.SELL,
                f"RVI={rvi_now:.3f} holding below signal, strong bearish vigor",
                raw,
            )

        return self._make_result(
            Signal.NEUTRAL,
            f"RVI={rvi_now:.3f} vs signal={sig_now:.3f}: no cross / weak vigor",
            raw,
        )
