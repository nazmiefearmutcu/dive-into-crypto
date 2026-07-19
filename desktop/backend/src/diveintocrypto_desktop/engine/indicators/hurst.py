"""Hurst-Exponent Regime — trend/mean-revert router in a single indicator.

Estimates the generalised Hurst exponent H from the log-price window (slope of
log dispersion of lagged differences vs log lag). H>0.5 = persistent/trending, H<0.5 =
anti-persistent/mean-reverting. The indicator then reads the window's net move THROUGH
that regime: in a trending regime it follows the move; in a mean-reverting regime it
fades it; near-random (H≈0.5) it abstains. Causal.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


def _hurst(logp: np.ndarray) -> float:
    n = len(logp)
    max_lag = min(20, n // 2)
    if max_lag < 4:
        return 0.5
    lags = np.arange(2, max_lag)
    tau = []
    for lag in lags:
        diff = logp[lag:] - logp[:-lag]
        sd = np.std(diff)
        tau.append(sd if sd > 0 else 1e-10)
    slope = np.polyfit(np.log(lags), np.log(tau), 1)[0]
    return float(slope)


class HurstRegimeIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "hurst"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 64))
        trend_h = float(self.thresholds.get("trend_h", 0.55))
        revert_h = float(self.thresholds.get("revert_h", 0.45))
        move_min = float(self.thresholds.get("move_min", 0.004))

        close = df["close"].to_numpy(dtype=float)
        if len(close) < period or (close <= 0).any():
            return self._make_result(Signal.NEUTRAL, "Hurst insufficient data")

        w = close[-period:]
        h = _hurst(np.log(w))
        move = (w[-1] - w[0]) / w[0] if w[0] else 0.0
        raw = {"hurst": round(h, 4), "move": round(float(move), 5)}

        if abs(move) < move_min or (revert_h <= h <= trend_h):
            return self._make_result(Signal.NEUTRAL, f"H={h:.2f} random/flat", raw)
        up = move > 0
        if h > trend_h:  # trending: follow
            strong = h > trend_h + 0.10
            if up:
                return self._make_result(Signal.STRONG_BUY if strong else Signal.BUY, f"H={h:.2f} trend up", raw)
            return self._make_result(Signal.STRONG_SELL if strong else Signal.SELL, f"H={h:.2f} trend down", raw)
        # mean-reverting: fade
        strong = h < revert_h - 0.10
        if up:
            return self._make_result(Signal.STRONG_SELL if strong else Signal.SELL, f"H={h:.2f} fade up", raw)
        return self._make_result(Signal.STRONG_BUY if strong else Signal.BUY, f"H={h:.2f} fade down", raw)
