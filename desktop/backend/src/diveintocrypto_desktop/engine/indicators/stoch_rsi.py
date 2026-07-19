"""Stochastic RSI (StochRSI) indicator.

Concept #24 of the strategy lab. Applies the Stochastic oscillator transform to
the RSI series instead of to price. This turns the *level* of RSI into a
*relative position* of RSI inside its own recent min/max range -- a
"momentum of momentum" oscillator that turns far earlier and more often than
plain RSI, and that is decoupled from raw price highs/lows (unlike the
price-based Stochastic).

Surface: BaseIndicator (price/volume TA). Uses ONLY numpy/pandas.
Strictly causal: every transform is a trailing (left-anchored) rolling window
or a backward diff; the emitted signal reads only .iloc[-1] / .iloc[-2], so it
depends solely on candles up to and including the last one. No shift into the
future, no centered windows, no repainting.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class StochRSIIndicator(BaseIndicator):
    """Stochastic RSI: Stochastic oscillator applied to the RSI series.

    Pipeline (all trailing / causal):
        1. RSI over ``rsi_period`` (same rolling-mean RSI convention as the
           repo's ``rsi`` indicator, so StochRSI operates on the exact series a
           user sees elsewhere).
        2. StochRSI_raw = (RSI - min(RSI, N)) / (max(RSI, N) - min(RSI, N)),
           scaled to 0..100, over ``stoch_period`` (N).
        3. %K = SMA(StochRSI_raw, ``k_smooth``); %D = SMA(%K, ``d_smooth``).

    Signal is derived from the %K zone plus the %K/%D crossover direction, which
    keeps it distinct from both plain RSI (fixed level bands) and price
    Stochastic (close-in-price-range reversals).
    """

    @property
    def name(self) -> str:
        return "stoch_rsi"

    def _rsi(self, close: pd.Series, period: int) -> pd.Series:
        """Rolling-mean RSI, vectorized, matching the repo's rsi.py NaN rules."""
        delta = close.diff()
        gain = delta.where(delta > 0, 0.0)
        loss = (-delta).where(delta < 0, 0.0)

        avg_gain = gain.rolling(window=period, min_periods=period).mean()
        avg_loss = loss.rolling(window=period, min_periods=period).mean()

        is_nan = avg_gain.isna() | avg_loss.isna()
        both_zero = (avg_gain == 0.0) & (avg_loss == 0.0)
        loss_zero = (avg_loss == 0.0) & (avg_gain != 0.0)

        rs = avg_gain / avg_loss.where(avg_loss != 0.0, 1.0)
        standard_rsi = 100.0 - (100.0 / (1.0 + rs))

        rsi_values = np.select(
            condlist=[is_nan, both_zero, loss_zero],
            choicelist=[np.nan, 50.0, 100.0],
            default=standard_rsi,
        )
        return pd.Series(rsi_values, index=close.index)

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        rsi_period = self.thresholds.get("rsi_period", 14)
        stoch_period = self.thresholds.get("stoch_period", 14)
        k_smooth = self.thresholds.get("k_smooth", 3)
        d_smooth = self.thresholds.get("d_smooth", 3)
        oversold = self.thresholds.get("oversold", 20)
        overbought = self.thresholds.get("overbought", 80)

        needed = rsi_period + stoch_period + k_smooth + d_smooth
        if len(df) < needed:
            return self._make_result(
                Signal.NEUTRAL, "StochRSI data insufficient", {"stoch_rsi_k": None}
            )

        close = df["close"]
        rsi = self._rsi(close, rsi_period)

        rsi_min = rsi.rolling(window=stoch_period, min_periods=stoch_period).min()
        rsi_max = rsi.rolling(window=stoch_period, min_periods=stoch_period).max()

        denom = (rsi_max - rsi_min).replace(0.0, np.nan)
        stoch_rsi_raw = 100.0 * (rsi - rsi_min) / denom

        k_line = stoch_rsi_raw.rolling(window=k_smooth, min_periods=k_smooth).mean()
        d_line = k_line.rolling(window=d_smooth, min_periods=d_smooth).mean()

        current_k = k_line.iloc[-1]
        current_d = d_line.iloc[-1]
        prev_k = k_line.iloc[-2] if len(k_line) >= 2 else current_k
        prev_d = d_line.iloc[-2] if len(d_line) >= 2 else current_d

        if (
            pd.isna(current_k)
            or pd.isna(current_d)
            or pd.isna(prev_k)
            or pd.isna(prev_d)
        ):
            return self._make_result(
                Signal.NEUTRAL, "StochRSI data insufficient", {"stoch_rsi_k": None}
            )

        raw = {
            "stoch_rsi_k": round(float(current_k), 2),
            "stoch_rsi_d": round(float(current_d), 2),
        }

        # %K/%D crossovers (momentum-of-momentum turn confirmation).
        cross_up = prev_k <= prev_d and current_k > current_d
        cross_dn = prev_k >= prev_d and current_k < current_d

        # STRONG signals: a fresh %K/%D cross while inside an extreme zone.
        if cross_up and current_k <= oversold:
            return self._make_result(
                Signal.STRONG_BUY,
                f"StochRSI bullish cross in oversold K={current_k:.1f}",
                raw,
            )
        if cross_dn and current_k >= overbought:
            return self._make_result(
                Signal.STRONG_SELL,
                f"StochRSI bearish cross in overbought K={current_k:.1f}",
                raw,
            )

        # BUY: turning up while still oversold, or a bullish cross below midline.
        if (current_k < oversold and current_k > prev_k) or (
            cross_up and current_k < 50.0
        ):
            return self._make_result(
                Signal.BUY,
                f"StochRSI turning up from low K={current_k:.1f}",
                raw,
            )

        # SELL: turning down while still overbought, or a bearish cross above midline.
        if (current_k > overbought and current_k < prev_k) or (
            cross_dn and current_k > 50.0
        ):
            return self._make_result(
                Signal.SELL,
                f"StochRSI turning down from high K={current_k:.1f}",
                raw,
            )

        return self._make_result(
            Signal.NEUTRAL,
            f"StochRSI neutral K={current_k:.1f} D={current_d:.1f}",
            raw,
        )
