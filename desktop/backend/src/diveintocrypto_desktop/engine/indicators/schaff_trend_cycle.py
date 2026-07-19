"""Schaff Trend Cycle (STC) indicator.

Doug Schaff's Trend Cycle: take a MACD line (fast EMA - slow EMA) and pass it
through a *double* stochastic transform with recursive smoothing. The result is
a fast, front-running trend-cycle oscillator bounded to 0..100 that turns much
earlier than raw MACD and whipsaws far less than a raw stochastic in trends.

Strictly causal / look-ahead-free:
  * EMA via ewm(adjust=False): value at bar i uses only bars <= i.
  * rolling(cycle).min/max: trailing window, past bars only.
  * The PF / STC smoothing loops run forward; bar i depends only on i and i-1.
  * The emitted signal reads only stc[-1], stc[-2], stc[-3] (last candles).
  * Appending a new bar never mutates earlier values -> no repainting.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class SchaffTrendCycleIndicator(BaseIndicator):
    """MACD passed through a double stochastic -> fast bounded trend cycle."""

    @property
    def name(self) -> str:
        return "schaff_trend_cycle"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        macd_fast = int(self.thresholds.get("macd_fast", 23))
        macd_slow = int(self.thresholds.get("macd_slow", 50))
        cycle = int(self.thresholds.get("cycle", 10))
        smooth = float(self.thresholds.get("smooth_factor", 0.5))
        lower = float(self.thresholds.get("lower_band", 25.0))
        upper = float(self.thresholds.get("upper_band", 75.0))

        min_bars = macd_slow + cycle
        if len(df) < min_bars:
            return self._make_result(
                Signal.NEUTRAL, f"STC insufficient data (<{min_bars} bars)"
            )

        close = df["close"].astype(float)

        # --- 1) MACD line (unbounded momentum series) ---
        ema_fast = close.ewm(span=macd_fast, adjust=False).mean()
        ema_slow = close.ewm(span=macd_slow, adjust=False).mean()
        macd = (ema_fast - ema_slow).to_numpy()
        n = macd.shape[0]

        # --- 2) First stochastic of the MACD line, then recursive smoothing (PF) ---
        macd_ser = pd.Series(macd)
        ll1 = macd_ser.rolling(window=cycle).min().to_numpy()
        hh1 = macd_ser.rolling(window=cycle).max().to_numpy()
        pf = self._double_smooth_stoch(macd, ll1, hh1, smooth)

        # --- 3) Second stochastic of PF, then recursive smoothing -> STC ---
        pf_ser = pd.Series(pf)
        ll2 = pf_ser.rolling(window=cycle).min().to_numpy()
        hh2 = pf_ser.rolling(window=cycle).max().to_numpy()
        stc = self._double_smooth_stoch(pf, ll2, hh2, smooth)

        stc_now = stc[-1]
        stc_prev = stc[-2] if n >= 2 else np.nan
        stc_prev2 = stc[-3] if n >= 3 else np.nan

        if np.isnan(stc_now) or np.isnan(stc_prev):
            return self._make_result(Signal.NEUTRAL, "STC not yet warmed up")

        # Clamp for clean reporting (numerically already within [0, 100]).
        stc_now = float(min(100.0, max(0.0, stc_now)))
        stc_prev = float(min(100.0, max(0.0, stc_prev)))

        rising = stc_now > stc_prev
        falling = stc_now < stc_prev

        raw = {
            "stc": round(stc_now, 2),
            "prev": round(stc_prev, 2),
            "macd": round(float(macd[-1]), 6),
            "lower": lower,
            "upper": upper,
        }

        # Primary signals: a band cross = a fresh trend-cycle turn (strongest).
        cross_up_lower = stc_prev <= lower < stc_now
        cross_down_upper = stc_prev >= upper > stc_now

        if cross_up_lower:
            return self._make_result(
                Signal.STRONG_BUY,
                f"STC turned up through {lower:.0f} (new bullish cycle) STC={stc_now:.1f}",
                raw,
            )
        if cross_down_upper:
            return self._make_result(
                Signal.STRONG_SELL,
                f"STC turned down through {upper:.0f} (new bearish cycle) STC={stc_now:.1f}",
                raw,
            )

        # Oversold zone: only a rising STC is actionable (early accumulation).
        if stc_now < lower:
            if rising:
                return self._make_result(
                    Signal.BUY, f"STC rising inside oversold zone STC={stc_now:.1f}", raw
                )
            return self._make_result(
                Signal.NEUTRAL, f"STC deeply oversold, no turn yet STC={stc_now:.1f}", raw
            )

        # Overbought zone: only a falling STC is actionable (early distribution).
        if stc_now > upper:
            if falling:
                return self._make_result(
                    Signal.SELL, f"STC falling inside overbought zone STC={stc_now:.1f}", raw
                )
            return self._make_result(
                Signal.NEUTRAL, f"STC deeply overbought, no turn yet STC={stc_now:.1f}", raw
            )

        # Mid zone (lower..upper): slope carries the cycle direction.
        if rising:
            return self._make_result(
                Signal.BUY, f"STC rising through mid-zone STC={stc_now:.1f}", raw
            )
        if falling:
            return self._make_result(
                Signal.SELL, f"STC falling through mid-zone STC={stc_now:.1f}", raw
            )

        return self._make_result(Signal.NEUTRAL, f"STC flat STC={stc_now:.1f}", raw)

    @staticmethod
    def _double_smooth_stoch(
        src: np.ndarray, ll: np.ndarray, hh: np.ndarray, smooth: float
    ) -> np.ndarray:
        """Stochastic %K of `src` over a trailing window, then recursive smoothing.

        %K(i) = 100 * (src - ll) / (hh - ll); if the window range is zero the prior
        %K is carried forward (Schaff's convention). The smoothed leg is an EMA-like
        recursion: out(i) = out(i-1) + smooth * (%K(i) - out(i-1)), seeded on the
        first valid bar. Purely forward -> causal, no repainting.
        """
        n = src.shape[0]
        out = np.full(n, np.nan)
        frac_prev = np.nan
        out_prev = np.nan
        for i in range(n):
            if np.isnan(ll[i]) or np.isnan(hh[i]) or np.isnan(src[i]):
                continue
            rng = hh[i] - ll[i]
            if rng > 0:
                frac = (src[i] - ll[i]) / rng * 100.0
            else:
                frac = frac_prev if not np.isnan(frac_prev) else 50.0
            frac_prev = frac
            if np.isnan(out_prev):
                out_i = frac
            else:
                out_i = out_prev + smooth * (frac - out_prev)
            out[i] = out_i
            out_prev = out_i
        return out
