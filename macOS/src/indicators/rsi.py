"""RSI (Relative Strength Index) indicator."""

from typing import Any
import pandas as pd
import numpy as np

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class RSIIndicator(BaseIndicator):
    """RSI with configurable thresholds for signal generation."""

    @property
    def name(self) -> str:
        return "rsi"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)
        strong_buy = self.thresholds.get("strong_buy", 25)
        buy = self.thresholds.get("buy", 35)
        sell = self.thresholds.get("sell", 65)
        strong_sell = self.thresholds.get("strong_sell", 80)

        close = df["close"]
        delta = close.diff()
        gain = delta.where(delta > 0, 0.0)
        loss = (-delta).where(delta < 0, 0.0)

        avg_gain = gain.rolling(window=period, min_periods=period).mean()
        avg_loss = loss.rolling(window=period, min_periods=period).mean()

        rs = avg_gain / avg_loss.replace(0, np.nan)
        rsi = 100.0 - (100.0 / (1.0 + rs))
        current_rsi = rsi.iloc[-1]

        if pd.isna(current_rsi):
            return self._make_result(Signal.NEUTRAL, "RSI data insufficient", {"rsi": None})

        raw = {"rsi": round(current_rsi, 2)}

        if current_rsi <= strong_buy:
            return self._make_result(Signal.STRONG_BUY, f"RSI={current_rsi:.1f} extremely oversold", raw)
        elif current_rsi <= buy:
            return self._make_result(Signal.BUY, f"RSI={current_rsi:.1f} oversold zone", raw)
        elif current_rsi >= strong_sell:
            return self._make_result(Signal.STRONG_SELL, f"RSI={current_rsi:.1f} extremely overbought", raw)
        elif current_rsi >= sell:
            return self._make_result(Signal.SELL, f"RSI={current_rsi:.1f} overbought zone", raw)
        else:
            return self._make_result(Signal.NEUTRAL, f"RSI={current_rsi:.1f} neutral zone", raw)
