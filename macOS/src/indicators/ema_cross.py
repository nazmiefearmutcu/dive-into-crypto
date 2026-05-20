"""EMA (Exponential Moving Average) Crossover indicator."""

from typing import Any
import pandas as pd

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class EMACrossIndicator(BaseIndicator):
    """EMA crossover with slope and divergence analysis."""

    @property
    def name(self) -> str:
        return "ema_cross"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        short_period = self.thresholds.get("short_period", 9)
        long_period = self.thresholds.get("long_period", 21)
        strong_divergence_pct = self.thresholds.get("strong_divergence_pct", 0.02)

        close = df["close"]
        ema_short = close.ewm(span=short_period, adjust=False).mean()
        ema_long = close.ewm(span=long_period, adjust=False).mean()

        current_short = ema_short.iloc[-1]
        current_long = ema_long.iloc[-1]
        prev_short = ema_short.iloc[-2] if len(ema_short) >= 2 else current_short
        prev_long = ema_long.iloc[-2] if len(ema_long) >= 2 else current_long

        if pd.isna(current_short) or pd.isna(current_long):
            return self._make_result(Signal.NEUTRAL, "EMA data insufficient")

        divergence = (current_short - current_long) / current_long if current_long != 0 else 0
        short_slope = (current_short - prev_short) / prev_short if prev_short != 0 else 0

        raw = {
            "ema_short": round(current_short, 2),
            "ema_long": round(current_long, 2),
            "divergence_pct": round(divergence, 4),
            "short_slope": round(short_slope, 6),
        }

        bullish_cross = prev_short <= prev_long and current_short > current_long
        bearish_cross = prev_short >= prev_long and current_short < current_long

        if bullish_cross:
            if abs(divergence) > strong_divergence_pct:
                return self._make_result(Signal.STRONG_BUY, "EMA bullish crossover with strong divergence", raw)
            return self._make_result(Signal.BUY, "EMA bullish crossover", raw)
        elif bearish_cross:
            if abs(divergence) > strong_divergence_pct:
                return self._make_result(Signal.STRONG_SELL, "EMA bearish crossover with strong divergence", raw)
            return self._make_result(Signal.SELL, "EMA bearish crossover", raw)
        elif current_short > current_long and short_slope > 0:
            if divergence > strong_divergence_pct:
                return self._make_result(Signal.STRONG_BUY, f"EMA bullish with positive slope, div={divergence:.3f}", raw)
            return self._make_result(Signal.BUY, f"EMA bullish alignment, slope positive", raw)
        elif current_short < current_long and short_slope < 0:
            if abs(divergence) > strong_divergence_pct:
                return self._make_result(Signal.STRONG_SELL, f"EMA bearish with negative slope, div={divergence:.3f}", raw)
            return self._make_result(Signal.SELL, f"EMA bearish alignment, slope negative", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "EMA neutral / mixed signals", raw)
