"""Qstick — SMA of candle bodies (close − open).

Measures persistent buying/selling bias in the candle bodies over `period`, normalised
by price so the threshold is scale-free. Positive = bodies close above their opens
(accumulation). Causal (uses only the closed window).
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class QstickIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "qstick"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 14))
        strong = float(self.thresholds.get("strong_pct", 0.003))

        open_ = df["open"].to_numpy(dtype=float)
        close = df["close"].to_numpy(dtype=float)
        if len(close) < period:
            return self._make_result(Signal.NEUTRAL, "Qstick insufficient data")

        q = float((close - open_)[-period:].mean())
        ref = abs(float(close[-1])) or 1.0
        qn = q / ref
        raw = {"qstick": round(q, 6), "qstick_pct": round(qn, 6)}
        if qn >= strong:
            return self._make_result(Signal.STRONG_BUY, f"Qstick {qn:+.3%} strong body-buying", raw)
        if q > 0:
            return self._make_result(Signal.BUY, f"Qstick {qn:+.3%} body-buying", raw)
        if qn <= -strong:
            return self._make_result(Signal.STRONG_SELL, f"Qstick {qn:+.3%} strong body-selling", raw)
        if q < 0:
            return self._make_result(Signal.SELL, f"Qstick {qn:+.3%} body-selling", raw)
        return self._make_result(Signal.NEUTRAL, "Qstick flat", raw)
