"""Linear-Regression Slope + R² trend-quality gate.

Fits an OLS line to the last `period` closes. Direction from the slope, but a signal is
only emitted when the fit quality R² clears `r2_min` — a noisy, low-R² move stays
NEUTRAL. Slope is normalised to a % move over the window so the threshold is scale-free.
Causal (regression uses only the closed window).
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class LinRegSlopeIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "linreg_slope"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 20))
        r2_min = float(self.thresholds.get("r2_min", 0.55))
        strong = float(self.thresholds.get("strong_pct", 0.03))
        weak = float(self.thresholds.get("weak_pct", 0.008))

        close = df["close"].to_numpy(dtype=float)
        if len(close) < period:
            return self._make_result(Signal.NEUTRAL, "LinReg insufficient data")

        y = close[-period:]
        x = np.arange(period, dtype=float)
        slope, intercept = np.polyfit(x, y, 1)
        yhat = slope * x + intercept
        ss_res = float(((y - yhat) ** 2).sum())
        ss_tot = float(((y - y.mean()) ** 2).sum())
        r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else 0.0
        mean = y.mean() or 1.0
        slope_pct = float(slope) * period / mean  # total % move implied over the window
        raw = {"slope_pct": round(slope_pct, 5), "r2": round(r2, 4)}

        if r2 < r2_min:
            return self._make_result(Signal.NEUTRAL, f"LinReg low fit R²={r2:.2f}", raw)
        if slope_pct >= strong:
            return self._make_result(Signal.STRONG_BUY, f"LinReg up {slope_pct:+.2%} R²={r2:.2f}", raw)
        if slope_pct >= weak:
            return self._make_result(Signal.BUY, f"LinReg up {slope_pct:+.2%} R²={r2:.2f}", raw)
        if slope_pct <= -strong:
            return self._make_result(Signal.STRONG_SELL, f"LinReg down {slope_pct:+.2%} R²={r2:.2f}", raw)
        if slope_pct <= -weak:
            return self._make_result(Signal.SELL, f"LinReg down {slope_pct:+.2%} R²={r2:.2f}", raw)
        return self._make_result(Signal.NEUTRAL, f"LinReg flat R²={r2:.2f}", raw)
