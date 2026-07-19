"""Kalman-Filter Trend — 1-D random-walk-plus-noise level estimate.

Runs a scalar Kalman filter over the close series to get a smooth, low-lag `level`. The
signal combines price-vs-level position with the level's own slope: price above a rising
level = uptrend. The filter is inherently causal (each estimate uses only past+current
observations). Gains are set by the process/measurement variance ratio.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class KalmanTrendIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "kalman_trend"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        q = float(self.thresholds.get("process_var", 1e-4))
        r = float(self.thresholds.get("measure_var", 1e-2))
        strong = float(self.thresholds.get("strong_pct", 0.01))

        close = df["close"].to_numpy(dtype=float)
        n = len(close)
        if n < 10:
            return self._make_result(Signal.NEUTRAL, "Kalman insufficient data")

        x = close[0]
        p = 1.0
        prev = x
        for i in range(1, n):
            p += q                      # predict
            k = p / (p + r)             # gain
            prev = x
            x = x + k * (close[i] - x)  # update
            p *= (1.0 - k)

        level = x
        slope = x - prev
        price = close[-1]
        if level == 0.0:
            return self._make_result(Signal.NEUTRAL, "Kalman undefined")
        dev = (price - level) / level
        raw = {"level": round(float(level), 4), "dev_pct": round(float(dev), 5), "slope": round(float(slope), 6)}

        if price > level and slope > 0:
            return self._make_result(Signal.STRONG_BUY if dev >= strong else Signal.BUY, f"price>level rising ({dev:+.2%})", raw)
        if price < level and slope < 0:
            return self._make_result(Signal.STRONG_SELL if dev <= -strong else Signal.SELL, f"price<level falling ({dev:+.2%})", raw)
        return self._make_result(Signal.NEUTRAL, f"price/level unresolved ({dev:+.2%})", raw)
