"""Chaikin Oscillator indicator.

Momentum of the Accumulation/Distribution Line (ADL):

    CHO = EMA_fast(ADL) - EMA_slow(ADL)

where the ADL is the running total of volume weighted by the intrabar close
location (Money Flow Multiplier). Whereas MACD measures the momentum of
*price* EMAs, the Chaikin Oscillator measures the momentum of the
*money-flow accumulation* curve -- accumulation building or fading, sourced
from where price closes within each candle's range, weighted by volume.

Signal model
------------
The oscillator is unbounded and scales with a symbol's volume, so raw levels
are not comparable across symbols/timeframes. We therefore normalise by the
oscillator's own recent rolling standard deviation to obtain dimensionless
z-scores for both level and one-bar slope, then map with a small,
regime-based decision tree:

  * A zero-line crossover is the classic strongest read (accumulation flips to
    distribution or vice-versa). A decisive crossover impulse (slope z-score
    beyond ``strong_slope``) is STRONG; a shallow one is a plain BUY/SELL.
  * Without a fresh cross, sign + slope give a graded directional bias:
    above zero and rising = accumulation building (BUY); below zero and
    falling = distribution building (SELL). Positive-but-fading or
    negative-but-recovering are NEUTRAL (momentum lost). An extended,
    still-accelerating regime (level z beyond ``strong_level`` AND slope z
    beyond ``strong_slope``) escalates to STRONG.

Strictly causal / look-ahead-free: cumsum, ewm(adjust=False), rolling(...),
and diff() all read only the current and prior candles; the emitted signal is
taken at iloc[-1]. No shift(-n), no centred windows, no repainting.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)

_EPS = 1e-12


class ChaikinOscillatorIndicator(BaseIndicator):
    """Chaikin Oscillator: EMA(3) - EMA(10) of the Accumulation/Distribution Line."""

    @property
    def name(self) -> str:
        return "chaikin_oscillator"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        fast_period = int(self.thresholds.get("fast_period", 3))
        slow_period = int(self.thresholds.get("slow_period", 10))
        norm_period = int(self.thresholds.get("norm_period", 100))
        strong_slope = float(self.thresholds.get("strong_slope", 0.6))
        strong_level = float(self.thresholds.get("strong_level", 1.5))

        # Need enough candles for the slow EMA to be meaningful plus a prior bar.
        if len(df) < slow_period + 2:
            return self._make_result(Signal.NEUTRAL, "Chaikin data insufficient")

        high = df["high"].astype(float)
        low = df["low"].astype(float)
        close = df["close"].astype(float)
        volume = df["volume"].astype(float)

        # --- Accumulation/Distribution Line ---
        hl_range = (high - low)
        # Money Flow Multiplier in [-1, 1]; flat candle (high==low) -> 0 (no bias).
        mfm = ((close - low) - (high - close)) / hl_range.where(hl_range > 0.0, np.nan)
        mfm = mfm.fillna(0.0)
        mfv = mfm * volume
        adl = mfv.cumsum()

        # --- Chaikin Oscillator ---
        ema_fast = adl.ewm(span=fast_period, adjust=False).mean()
        ema_slow = adl.ewm(span=slow_period, adjust=False).mean()
        cho = ema_fast - ema_slow

        current = float(cho.iloc[-1])
        prev = float(cho.iloc[-2])

        if np.isnan(current) or np.isnan(prev):
            return self._make_result(Signal.NEUTRAL, "Chaikin data insufficient")

        # --- Normalisation: z-scores against the oscillator's recent dispersion ---
        min_std_periods = min(len(cho), max(20, fast_period + slow_period))
        std_series = cho.rolling(window=norm_period, min_periods=min_std_periods).std()
        scale = float(std_series.iloc[-1])
        if np.isnan(scale) or scale <= 0.0:
            scale = float(cho.std())
        if np.isnan(scale) or scale <= 0.0:
            scale = _EPS

        slope = current - prev
        level_z = current / (scale + _EPS)
        slope_z = slope / (scale + _EPS)

        bull_cross = prev <= 0.0 < current
        bear_cross = prev >= 0.0 > current

        raw: dict[str, Any] = {
            "cho": round(current, 4),
            "prev_cho": round(prev, 4),
            "adl": round(float(adl.iloc[-1]), 2),
            "level_z": round(level_z, 3),
            "slope_z": round(slope_z, 3),
            "regime": "accumulation" if current > 0 else ("distribution" if current < 0 else "flat"),
        }

        # --- Decision tree ---
        if bull_cross:
            if slope_z >= strong_slope:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Chaikin crossed above zero with decisive impulse (slope_z={slope_z:.2f})",
                    raw,
                )
            return self._make_result(
                Signal.BUY, "Chaikin crossed above zero (accumulation begins)", raw
            )

        if bear_cross:
            if slope_z <= -strong_slope:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Chaikin crossed below zero with decisive impulse (slope_z={slope_z:.2f})",
                    raw,
                )
            return self._make_result(
                Signal.SELL, "Chaikin crossed below zero (distribution begins)", raw
            )

        if current > 0.0:
            rising = current > prev
            if rising and level_z >= strong_level and slope_z >= strong_slope:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Chaikin extended and accelerating up (level_z={level_z:.2f})",
                    raw,
                )
            if rising:
                return self._make_result(
                    Signal.BUY, "Chaikin positive and rising (accumulation building)", raw
                )
            return self._make_result(
                Signal.NEUTRAL, "Chaikin positive but fading (accumulation losing momentum)", raw
            )

        if current < 0.0:
            falling = current < prev
            if falling and level_z <= -strong_level and slope_z <= -strong_slope:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Chaikin extended and accelerating down (level_z={level_z:.2f})",
                    raw,
                )
            if falling:
                return self._make_result(
                    Signal.SELL, "Chaikin negative and falling (distribution building)", raw
                )
            return self._make_result(
                Signal.NEUTRAL, "Chaikin negative but recovering (distribution losing momentum)", raw
            )

        return self._make_result(Signal.NEUTRAL, "Chaikin at zero (no money-flow bias)", raw)
