"""Williams %R indicator."""

from typing import Any
import pandas as pd

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class WilliamsRIndicator(BaseIndicator):
    """Williams %R with oversold/overbought reversal detection."""

    @property
    def name(self) -> str:
        return "williams_r"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)
        oversold = self.thresholds.get("oversold", -80)
        overbought = self.thresholds.get("overbought", -20)

        high = df["high"]
        low = df["low"]
        close = df["close"]

        highest_high = high.rolling(window=period).max()
        lowest_low = low.rolling(window=period).min()

        denom = highest_high - lowest_low
        denom = denom.replace(0, float("nan"))
        williams_r = -100.0 * (highest_high - close) / denom

        current_wr = williams_r.iloc[-1]
        prev_wr = williams_r.iloc[-2] if len(williams_r) >= 2 else current_wr

        if pd.isna(current_wr):
            return self._make_result(Signal.NEUTRAL, "Williams %R data insufficient")

        raw = {"williams_r": round(current_wr, 2)}

        # Oversold reversal
        if current_wr < oversold and current_wr > prev_wr:
            if current_wr < -95:
                return self._make_result(Signal.STRONG_BUY, f"Williams %R={current_wr:.1f} extreme oversold reversal", raw)
            return self._make_result(Signal.BUY, f"Williams %R={current_wr:.1f} oversold reversal", raw)

        # Overbought reversal
        if current_wr > overbought and current_wr < prev_wr:
            if current_wr > -5:
                return self._make_result(Signal.STRONG_SELL, f"Williams %R={current_wr:.1f} extreme overbought reversal", raw)
            return self._make_result(Signal.SELL, f"Williams %R={current_wr:.1f} overbought reversal", raw)

        # Still oversold
        if current_wr < oversold:
            return self._make_result(Signal.BUY, f"Williams %R={current_wr:.1f} oversold zone", raw)

        # Still overbought
        if current_wr > overbought:
            return self._make_result(Signal.SELL, f"Williams %R={current_wr:.1f} overbought zone", raw)

        return self._make_result(Signal.NEUTRAL, f"Williams %R={current_wr:.1f} neutral", raw)
