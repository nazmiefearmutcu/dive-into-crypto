"""Chaikin Money Flow (CMF) indicator."""

from typing import Any
import pandas as pd
import numpy as np

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal

class CMFIndicator(BaseIndicator):
    """Chaikin Money Flow indicator."""

    @property
    def name(self) -> str:
        return "cmf"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 20)
        strong_buy = self.thresholds.get("strong_buy", 0.2)
        buy = self.thresholds.get("buy", 0.05)
        strong_sell = self.thresholds.get("strong_sell", -0.2)
        sell = self.thresholds.get("sell", -0.05)

        high = df["high"]
        low = df["low"]
        close = df["close"]
        volume = df["volume"]
        
        # Money Flow Multiplier
        mfm = ((close - low) - (high - close)) / (high - low + 1e-10)
        # Money Flow Volume
        mfv = mfm * volume
        
        # CMF
        cmf = mfv.rolling(window=period).sum() / volume.rolling(window=period).sum()
        
        current_cmf = cmf.iloc[-1]
        
        if pd.isna(current_cmf):
            return self._make_result(Signal.NEUTRAL, "CMF data insufficient")
            
        raw = {
            "cmf": round(float(current_cmf), 4)
        }
        
        if current_cmf >= strong_buy:
            return self._make_result(Signal.STRONG_BUY, f"CMF highly positive (> {strong_buy})", raw)
        elif current_cmf >= buy:
            return self._make_result(Signal.BUY, f"CMF positive (> {buy})", raw)
        elif current_cmf <= strong_sell:
            return self._make_result(Signal.STRONG_SELL, f"CMF highly negative (< {strong_sell})", raw)
        elif current_cmf <= sell:
            return self._make_result(Signal.SELL, f"CMF negative (< {sell})", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "CMF neutral", raw)
