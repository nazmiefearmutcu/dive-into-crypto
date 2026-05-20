"""SMA (Simple Moving Average) Crossover indicator."""

from typing import Any
import pandas as pd

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class SMACrossIndicator(BaseIndicator):
    """SMA crossover with divergence strength measurement."""

    @property
    def name(self) -> str:
        return "sma_cross"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        short_period = self.thresholds.get("short_period", 10)
        long_period = self.thresholds.get("long_period", 50)
        strong_divergence_pct = self.thresholds.get("strong_divergence_pct", 0.02)

        close = df["close"]
        sma_short = close.rolling(window=short_period).mean()
        sma_long = close.rolling(window=long_period).mean()

        current_short = sma_short.iloc[-1]
        current_long = sma_long.iloc[-1]
        prev_short = sma_short.iloc[-2] if len(sma_short) >= 2 else current_short
        prev_long = sma_long.iloc[-2] if len(sma_long) >= 2 else current_long

        if pd.isna(current_short) or pd.isna(current_long):
            return self._make_result(Signal.NEUTRAL, "SMA data insufficient")

        divergence = (current_short - current_long) / current_long if current_long != 0 else 0

        raw = {
            "sma_short": round(current_short, 2),
            "sma_long": round(current_long, 2),
            "divergence_pct": round(divergence, 4),
        }

        bullish_cross = prev_short <= prev_long and current_short > current_long
        bearish_cross = prev_short >= prev_long and current_short < current_long

        if bullish_cross:
            if abs(divergence) > strong_divergence_pct:
                return self._make_result(Signal.STRONG_BUY, "SMA golden cross with strong divergence", raw)
            return self._make_result(Signal.BUY, "SMA golden cross (short above long)", raw)
        elif bearish_cross:
            if abs(divergence) > strong_divergence_pct:
                return self._make_result(Signal.STRONG_SELL, "SMA death cross with strong divergence", raw)
            return self._make_result(Signal.SELL, "SMA death cross (short below long)", raw)
        elif current_short > current_long:
            if divergence > strong_divergence_pct:
                return self._make_result(Signal.STRONG_BUY, f"SMA bullish, strong divergence={divergence:.3f}", raw)
            return self._make_result(Signal.BUY, f"SMA bullish alignment, divergence={divergence:.3f}", raw)
        elif current_short < current_long:
            if abs(divergence) > strong_divergence_pct:
                return self._make_result(Signal.STRONG_SELL, f"SMA bearish, strong divergence={divergence:.3f}", raw)
            return self._make_result(Signal.SELL, f"SMA bearish alignment, divergence={divergence:.3f}", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "SMA flat / converging", raw)
