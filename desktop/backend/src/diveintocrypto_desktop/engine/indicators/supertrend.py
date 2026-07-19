"""Supertrend indicator."""

import pandas as pd
import numpy as np

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal

class SupertrendIndicator(BaseIndicator):
    """Supertrend indicator."""

    @property
    def name(self) -> str:
        return "supertrend"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 10)
        multiplier = self.thresholds.get("multiplier", 3.0)

        high = df["high"].values
        low = df["low"].values
        close = df["close"].values
        
        n = len(df)
        if n < period:
            return self._make_result(Signal.NEUTRAL, "Supertrend data insufficient")
            
        # Calculate ATR
        tr = np.zeros(n)
        for i in range(1, n):
            tr1 = high[i] - low[i]
            tr2 = abs(high[i] - close[i-1])
            tr3 = abs(low[i] - close[i-1])
            tr[i] = max(tr1, tr2, tr3)
            
        atr = np.zeros(n)
        if n >= period:
            atr[period-1] = np.mean(tr[1:period+1])
        for i in range(period, n):
            # RMA (smma) is usually used in Supertrend for ATR
            atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period
            
        hl2 = (high + low) / 2
        basic_upper = hl2 + (multiplier * atr)
        basic_lower = hl2 - (multiplier * atr)
        
        final_upper = np.zeros(n)
        final_lower = np.zeros(n)
        supertrend = np.zeros(n)
        direction = np.ones(n) # 1 for bull, -1 for bear
        
        final_upper[:] = np.nan
        final_lower[:] = np.nan
        supertrend[:] = np.nan
        
        for i in range(period, n):
            # Final Upper Band
            if np.isnan(final_upper[i-1]) or basic_upper[i] < final_upper[i-1] or close[i-1] > final_upper[i-1]:
                final_upper[i] = basic_upper[i]
            else:
                final_upper[i] = final_upper[i-1]
                
            # Final Lower Band
            if np.isnan(final_lower[i-1]) or basic_lower[i] > final_lower[i-1] or close[i-1] < final_lower[i-1]:
                final_lower[i] = basic_lower[i]
            else:
                final_lower[i] = final_lower[i-1]
                
            # Supertrend direction
            if np.isnan(supertrend[i-1]):
                if close[i] <= final_upper[i]:
                    direction[i] = -1
                    supertrend[i] = final_upper[i]
                else:
                    direction[i] = 1
                    supertrend[i] = final_lower[i]
            else:
                if direction[i-1] == 1 and close[i] < final_lower[i]:
                    direction[i] = -1
                    supertrend[i] = final_upper[i]
                elif direction[i-1] == -1 and close[i] > final_upper[i]:
                    direction[i] = 1
                    supertrend[i] = final_lower[i]
                else:
                    direction[i] = direction[i-1]
                    if direction[i] == 1:
                        supertrend[i] = final_lower[i]
                    else:
                        supertrend[i] = final_upper[i]
                        
        current_direction = direction[-1]
        prev_direction = direction[-2] if n >= 2 else current_direction
        current_st = supertrend[-1]
        
        if pd.isna(current_st):
             return self._make_result(Signal.NEUTRAL, "Supertrend data insufficient")

        raw = {
            "supertrend": round(float(current_st), 4),
            "direction": "BULL" if current_direction == 1 else "BEAR"
        }
        
        if current_direction == 1 and prev_direction == -1:
            return self._make_result(Signal.STRONG_BUY, "Supertrend turned bullish", raw)
        elif current_direction == -1 and prev_direction == 1:
            return self._make_result(Signal.STRONG_SELL, "Supertrend turned bearish", raw)
        elif current_direction == 1:
            return self._make_result(Signal.BUY, "Supertrend is bullish", raw)
        elif current_direction == -1:
            return self._make_result(Signal.SELL, "Supertrend is bearish", raw)
            
        return self._make_result(Signal.NEUTRAL, "Supertrend neutral", raw)
