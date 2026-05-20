"""CCI (Commodity Channel Index) indicator."""

from typing import Any
import pandas as pd
import numpy as np

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class CCIIndicator(BaseIndicator):
    """CCI with standard threshold-based signal generation."""

    @property
    def name(self) -> str:
        return "cci"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 20)
        buy_level = self.thresholds.get("buy", -100)
        strong_buy_level = self.thresholds.get("strong_buy", -200)
        sell_level = self.thresholds.get("sell", 100)
        strong_sell_level = self.thresholds.get("strong_sell", 200)

        typical_price = (df["high"] + df["low"] + df["close"]) / 3.0
        sma_tp = typical_price.rolling(window=period).mean()
        mean_deviation = typical_price.rolling(window=period).apply(
            lambda x: np.mean(np.abs(x - np.mean(x))), raw=True
        )
        mean_deviation = mean_deviation.replace(0, np.nan)

        cci = (typical_price - sma_tp) / (0.015 * mean_deviation)
        current_cci = cci.iloc[-1]

        if pd.isna(current_cci):
            return self._make_result(Signal.NEUTRAL, "CCI data insufficient")

        raw = {"cci": round(current_cci, 2)}

        if current_cci <= strong_buy_level:
            return self._make_result(Signal.STRONG_BUY, f"CCI={current_cci:.1f} deeply oversold", raw)
        elif current_cci <= buy_level:
            return self._make_result(Signal.BUY, f"CCI={current_cci:.1f} oversold", raw)
        elif current_cci >= strong_sell_level:
            return self._make_result(Signal.STRONG_SELL, f"CCI={current_cci:.1f} deeply overbought", raw)
        elif current_cci >= sell_level:
            return self._make_result(Signal.SELL, f"CCI={current_cci:.1f} overbought", raw)
        else:
            return self._make_result(Signal.NEUTRAL, f"CCI={current_cci:.1f} neutral range", raw)
