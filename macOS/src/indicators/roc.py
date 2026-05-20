"""ROC (Rate of Change) indicator."""

from typing import Any
import pandas as pd

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class ROCIndicator(BaseIndicator):
    """Rate of Change momentum indicator."""

    @property
    def name(self) -> str:
        return "roc"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 12)
        strong_threshold = self.thresholds.get("strong_threshold", 5.0)
        weak_threshold = self.thresholds.get("weak_threshold", 1.0)

        close = df["close"]
        prev_close = close.shift(period)
        roc = ((close - prev_close) / prev_close) * 100.0

        current_roc = roc.iloc[-1]
        prev_roc = roc.iloc[-2] if len(roc) >= 2 else current_roc

        if pd.isna(current_roc):
            return self._make_result(Signal.NEUTRAL, "ROC data insufficient")

        raw = {
            "roc": round(current_roc, 2),
            "roc_prev": round(prev_roc, 2),
        }

        momentum_increasing = current_roc > prev_roc
        momentum_decreasing = current_roc < prev_roc

        if current_roc > strong_threshold and momentum_increasing:
            return self._make_result(Signal.STRONG_BUY, f"ROC={current_roc:.2f}% strong positive momentum rising", raw)
        elif current_roc > weak_threshold and momentum_increasing:
            return self._make_result(Signal.BUY, f"ROC={current_roc:.2f}% positive momentum rising", raw)
        elif current_roc > weak_threshold:
            return self._make_result(Signal.BUY, f"ROC={current_roc:.2f}% positive momentum", raw)
        elif current_roc < -strong_threshold and momentum_decreasing:
            return self._make_result(Signal.STRONG_SELL, f"ROC={current_roc:.2f}% strong negative momentum falling", raw)
        elif current_roc < -weak_threshold and momentum_decreasing:
            return self._make_result(Signal.SELL, f"ROC={current_roc:.2f}% negative momentum falling", raw)
        elif current_roc < -weak_threshold:
            return self._make_result(Signal.SELL, f"ROC={current_roc:.2f}% negative momentum", raw)
        else:
            return self._make_result(Signal.NEUTRAL, f"ROC={current_roc:.2f}% weak/flat momentum", raw)
