"""Vortex Indicator (VI)."""

from typing import Any
import pandas as pd
import numpy as np

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal

class VortexIndicator(BaseIndicator):
    """Vortex Indicator."""

    @property
    def name(self) -> str:
        return "vortex"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)

        high = df["high"]
        low = df["low"]
        close = df["close"]
        
        # True Range
        tr1 = high - low
        tr2 = (high - close.shift()).abs()
        tr3 = (low - close.shift()).abs()
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        
        vm_plus = (high - low.shift()).abs()
        vm_minus = (low - high.shift()).abs()
        
        sum_tr = tr.rolling(window=period).sum()
        sum_vm_plus = vm_plus.rolling(window=period).sum()
        sum_vm_minus = vm_minus.rolling(window=period).sum()
        
        vi_plus = sum_vm_plus / (sum_tr + 1e-10)
        vi_minus = sum_vm_minus / (sum_tr + 1e-10)
        
        curr_plus = vi_plus.iloc[-1]
        curr_minus = vi_minus.iloc[-1]
        prev_plus = vi_plus.iloc[-2] if len(vi_plus) >= 2 else curr_plus
        prev_minus = vi_minus.iloc[-2] if len(vi_minus) >= 2 else curr_minus
        
        if pd.isna(curr_plus):
            return self._make_result(Signal.NEUTRAL, "Vortex data insufficient")
            
        raw = {
            "vi_plus": round(float(curr_plus), 4),
            "vi_minus": round(float(curr_minus), 4)
        }
        
        bullish_cross = prev_plus <= prev_minus and curr_plus > curr_minus
        bearish_cross = prev_plus >= prev_minus and curr_plus < curr_minus
        
        if bullish_cross:
            return self._make_result(Signal.STRONG_BUY, "Vortex bullish crossover", raw)
        elif bearish_cross:
            return self._make_result(Signal.STRONG_SELL, "Vortex bearish crossover", raw)
        elif curr_plus > curr_minus:
            return self._make_result(Signal.BUY, "Vortex is bullish", raw)
        elif curr_plus < curr_minus:
            return self._make_result(Signal.SELL, "Vortex is bearish", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "Vortex neutral", raw)
