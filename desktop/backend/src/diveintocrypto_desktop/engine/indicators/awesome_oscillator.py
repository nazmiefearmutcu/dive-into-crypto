"""Awesome Oscillator (AO) indicator."""

from typing import Any
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal

class AwesomeOscillatorIndicator(BaseIndicator):
    """Awesome Oscillator indicator."""

    @property
    def name(self) -> str:
        return "awesome_oscillator"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        fast_period = self.thresholds.get("fast_period", 5)
        slow_period = self.thresholds.get("slow_period", 34)

        median_price = (df["high"] + df["low"]) / 2
        sma_fast = median_price.rolling(window=fast_period).mean()
        sma_slow = median_price.rolling(window=slow_period).mean()
        
        ao = sma_fast - sma_slow
        
        current_ao = ao.iloc[-1]
        prev_ao = ao.iloc[-2] if len(ao) >= 2 else current_ao
        prev2_ao = ao.iloc[-3] if len(ao) >= 3 else prev_ao
        
        if pd.isna(current_ao):
            return self._make_result(Signal.NEUTRAL, "AO data insufficient")
            
        raw = {
            "ao": round(float(current_ao), 4),
            "prev_ao": round(float(prev_ao), 4)
        }
        
        # Zero line crossover
        bullish_cross = prev_ao <= 0 and current_ao > 0
        bearish_cross = prev_ao >= 0 and current_ao < 0
        
        # Saucer (change in momentum direction)
        bullish_saucer = current_ao > 0 and prev_ao > 0 and prev2_ao > prev_ao and current_ao > prev_ao
        bearish_saucer = current_ao < 0 and prev_ao < 0 and prev2_ao < prev_ao and current_ao < prev_ao
        
        if bullish_cross:
            return self._make_result(Signal.STRONG_BUY, "AO crossed above zero line", raw)
        elif bearish_cross:
            return self._make_result(Signal.STRONG_SELL, "AO crossed below zero line", raw)
        elif bullish_saucer:
            return self._make_result(Signal.BUY, "AO bullish saucer pattern", raw)
        elif bearish_saucer:
            return self._make_result(Signal.SELL, "AO bearish saucer pattern", raw)
        elif current_ao > 0 and current_ao > prev_ao:
            return self._make_result(Signal.NEUTRAL, "AO positive and rising", raw)
        elif current_ao > 0 and current_ao < prev_ao:
            return self._make_result(Signal.NEUTRAL, "AO positive and falling", raw)
        elif current_ao < 0 and current_ao < prev_ao:
            return self._make_result(Signal.NEUTRAL, "AO negative and falling", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "AO negative and rising", raw)
