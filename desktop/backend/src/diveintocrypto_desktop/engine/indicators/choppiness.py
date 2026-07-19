"""Choppiness Index indicator."""

import pandas as pd
import numpy as np

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal

class ChoppinessIndexIndicator(BaseIndicator):
    """Choppiness Index to determine if market is trending or ranging."""

    @property
    def name(self) -> str:
        return "choppiness"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)
        choppy_threshold = self.thresholds.get("choppy_threshold", 61.8)
        trending_threshold = self.thresholds.get("trending_threshold", 38.2)

        high = df["high"]
        low = df["low"]
        close = df["close"]
        
        tr1 = high - low
        tr2 = (high - close.shift()).abs()
        tr3 = (low - close.shift()).abs()
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        
        atr_sum = tr.rolling(window=period).sum()
        highest_high = high.rolling(window=period).max()
        lowest_low = low.rolling(window=period).min()
        
        is_flat = (atr_sum == 0.0) & atr_sum.notna()
        safe_atr_sum = atr_sum.mask(is_flat, 1.0)
        chop = 100 * np.log10(safe_atr_sum / (highest_high - lowest_low + 1e-10)) / np.log10(period)
        chop = chop.mask(is_flat, 100.0)
        
        current_chop = chop.iloc[-1]
        
        if pd.isna(current_chop):
            return self._make_result(Signal.NEUTRAL, "Choppiness data insufficient")
            
        raw = {
            "chop": round(float(current_chop), 2)
        }
        
        sma = close.rolling(window=period).mean()
        is_bullish = close.iloc[-1] > sma.iloc[-1]
        
        if current_chop > choppy_threshold:
            return self._make_result(Signal.NEUTRAL, "Market is choppy/ranging", raw)
        elif current_chop < trending_threshold:
            if is_bullish:
                return self._make_result(Signal.STRONG_BUY, "Strong bullish trend", raw)
            else:
                return self._make_result(Signal.STRONG_SELL, "Strong bearish trend", raw)
        else:
            if is_bullish:
                return self._make_result(Signal.BUY, "Mild bullish trend", raw)
            else:
                return self._make_result(Signal.SELL, "Mild bearish trend", raw)
