"""Rolling Volume Weighted Average Price (VWAP) indicator."""

from typing import Any
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal

class VWAPIndicator(BaseIndicator):
    """Rolling VWAP indicator."""

    @property
    def name(self) -> str:
        return "vwap"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 20)

        high = df["high"]
        low = df["low"]
        close = df["close"]
        volume = df["volume"]
        
        typical_price = (high + low + close) / 3
        tp_v = typical_price * volume
        
        rolling_tp_v = tp_v.rolling(window=period).sum()
        rolling_v = volume.rolling(window=period).sum()
        
        vwap = rolling_tp_v / (rolling_v + 1e-10)
        
        current_vwap = vwap.iloc[-1]
        current_close = close.iloc[-1]
        prev_close = close.iloc[-2] if len(close) >= 2 else current_close
        prev_vwap = vwap.iloc[-2] if len(vwap) >= 2 else current_vwap
        
        if pd.isna(current_vwap):
             return self._make_result(Signal.NEUTRAL, "VWAP data insufficient")
             
        raw = {
            "vwap": round(float(current_vwap), 4),
        }
        
        bullish_cross = prev_close <= prev_vwap and current_close > current_vwap
        bearish_cross = prev_close >= prev_vwap and current_close < current_vwap
        
        distance_pct = (current_close - current_vwap) / current_vwap
        
        if bullish_cross:
            return self._make_result(Signal.STRONG_BUY, "Price crossed above VWAP", raw)
        elif bearish_cross:
            return self._make_result(Signal.STRONG_SELL, "Price crossed below VWAP", raw)
        elif distance_pct > 0.05:
            return self._make_result(Signal.NEUTRAL, "Price too far above VWAP (overextended)", raw)
        elif distance_pct < -0.05:
            return self._make_result(Signal.NEUTRAL, "Price too far below VWAP (overextended)", raw)
        elif current_close > current_vwap:
            return self._make_result(Signal.BUY, "Price above VWAP", raw)
        elif current_close < current_vwap:
            return self._make_result(Signal.SELL, "Price below VWAP", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "Price at VWAP", raw)
