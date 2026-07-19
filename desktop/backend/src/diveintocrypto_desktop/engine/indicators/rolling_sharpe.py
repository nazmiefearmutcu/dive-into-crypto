"""Rolling Sharpe Momentum — risk-adjusted return direction.

Sharpe over the window = mean(logret) / std(logret) · √period. It rewards steady,
low-volatility trends and penalises choppy ones, so a strong up-move on erratic returns
scores lower than a calm grind. Sign gives direction; magnitude gates strength. Distinct
from raw ROC/momentum (which ignores the volatility of the path). Causal.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class RollingSharpeIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "rolling_sharpe"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 20))
        strong = float(self.thresholds.get("strong", 1.0))
        weak = float(self.thresholds.get("weak", 0.3))

        close = df["close"].to_numpy(dtype=float)
        if len(close) < period + 1 or (close <= 0).any():
            return self._make_result(Signal.NEUTRAL, "Sharpe insufficient data")

        logret = np.diff(np.log(close))[-period:]
        mu = float(logret.mean())
        sd = float(logret.std(ddof=1))
        if sd == 0.0:
            return self._make_result(Signal.NEUTRAL, "zero-variance window")
        sharpe = mu / sd * np.sqrt(period)
        raw = {"sharpe": round(float(sharpe), 4)}

        if sharpe >= strong:
            return self._make_result(Signal.STRONG_BUY, f"Sharpe {sharpe:+.2f} strong risk-adj up", raw)
        if sharpe >= weak:
            return self._make_result(Signal.BUY, f"Sharpe {sharpe:+.2f} risk-adj up", raw)
        if sharpe <= -strong:
            return self._make_result(Signal.STRONG_SELL, f"Sharpe {sharpe:+.2f} strong risk-adj down", raw)
        if sharpe <= -weak:
            return self._make_result(Signal.SELL, f"Sharpe {sharpe:+.2f} risk-adj down", raw)
        return self._make_result(Signal.NEUTRAL, f"Sharpe {sharpe:+.2f} neutral", raw)
