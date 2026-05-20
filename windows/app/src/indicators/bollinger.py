"""Bollinger Bands indicator."""

from typing import Any
import pandas as pd
import numpy as np

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class BollingerBandsIndicator(BaseIndicator):
    """Bollinger Bands with squeeze detection and band position analysis."""

    @property
    def name(self) -> str:
        return "bollinger"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 20)
        std_dev = self.thresholds.get("std_dev", 2.0)
        squeeze_threshold = self.thresholds.get("squeeze_threshold", 0.02)

        close = df["close"]
        sma = close.rolling(window=period).mean()
        rolling_std = close.rolling(window=period).std()
        upper_band = sma + (rolling_std * std_dev)
        lower_band = sma - (rolling_std * std_dev)

        current_close = close.iloc[-1]
        current_upper = upper_band.iloc[-1]
        current_lower = lower_band.iloc[-1]
        current_sma = sma.iloc[-1]

        if pd.isna(current_upper) or pd.isna(current_lower):
            return self._make_result(Signal.NEUTRAL, "Bollinger data insufficient")

        band_width = (current_upper - current_lower) / current_sma if current_sma != 0 else 0
        position = (current_close - current_lower) / (current_upper - current_lower) if (current_upper - current_lower) != 0 else 0.5

        raw = {
            "upper": round(current_upper, 2),
            "lower": round(current_lower, 2),
            "sma": round(current_sma, 2),
            "band_width": round(band_width, 4),
            "position": round(position, 4),
        }

        is_squeeze = band_width < squeeze_threshold

        if current_close < current_lower:
            if is_squeeze:
                return self._make_result(Signal.BUY, "Price below lower band during squeeze - potential breakout", raw)
            return self._make_result(Signal.STRONG_BUY, "Price below lower Bollinger Band", raw)
        elif position < 0.15:
            return self._make_result(Signal.BUY, f"Price near lower Bollinger Band (pos={position:.2f})", raw)
        elif current_close > current_upper:
            if is_squeeze:
                return self._make_result(Signal.SELL, "Price above upper band during squeeze - potential breakout", raw)
            return self._make_result(Signal.STRONG_SELL, "Price above upper Bollinger Band", raw)
        elif position > 0.85:
            return self._make_result(Signal.SELL, f"Price near upper Bollinger Band (pos={position:.2f})", raw)
        else:
            zone = "mid-band neutral"
            if is_squeeze:
                zone = "squeeze detected - awaiting breakout"
            return self._make_result(Signal.NEUTRAL, f"Bollinger {zone} (pos={position:.2f})", raw)
