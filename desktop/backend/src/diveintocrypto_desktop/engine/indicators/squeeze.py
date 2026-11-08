"""TTM Squeeze indicator."""

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal

class TTMSqueezeIndicator(BaseIndicator):
    """TTM Squeeze (Bollinger Bands inside Keltner Channels)."""

    @property
    def name(self) -> str:
        return "squeeze"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 20)
        bb_mult = self.thresholds.get("bb_mult", 2.0)
        kc_mult = self.thresholds.get("kc_mult", 1.5)

        high = df["high"]
        low = df["low"]
        close = df["close"]
        
        # Bollinger Bands
        sma = close.rolling(window=period).mean()
        std = close.rolling(window=period).std()
        bb_upper = sma + bb_mult * std
        bb_lower = sma - bb_mult * std
        
        # True Range
        tr1 = high - low
        tr2 = (high - close.shift()).abs()
        tr3 = (low - close.shift()).abs()
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        
        # Keltner Channels
        atr = tr.rolling(window=period).mean()
        kc_upper = sma + kc_mult * atr
        kc_lower = sma - kc_mult * atr
        
        # Squeeze On: BB is completely inside KC. Mask warm-up bars to NaN: a NaN
        # comparison evaluates to False, which would otherwise read as "no squeeze".
        bands_valid = sma.notna() & std.notna() & atr.notna()
        squeeze_on = ((bb_upper < kc_upper) & (bb_lower > kc_lower)).where(bands_valid)

        # Momentum proxy
        momentum = close - sma

        current_sq = squeeze_on.iloc[-1]
        prev_raw = squeeze_on.iloc[-2] if len(squeeze_on) >= 2 else current_sq
        prev_sq = bool(prev_raw) if pd.notna(prev_raw) else False
        current_mom = momentum.iloc[-1]

        if pd.isna(current_sq) or pd.isna(current_mom):
             return self._make_result(Signal.NEUTRAL, "Squeeze data insufficient")
        current_sq = bool(current_sq)
             
        raw = {
            "squeeze_on": bool(current_sq),
            "momentum": round(float(current_mom), 4)
        }
        
        # Squeeze release (fires)
        if prev_sq and not current_sq:
            if current_mom > 0:
                return self._make_result(Signal.STRONG_BUY, "Squeeze fired LONG", raw)
            else:
                return self._make_result(Signal.STRONG_SELL, "Squeeze fired SHORT", raw)
                
        # Squeeze is currently on (building energy)
        if current_sq:
            return self._make_result(Signal.NEUTRAL, "Consolidating (Squeeze ON)", raw)
            
        # No squeeze, just trending
        if current_mom > 0:
            return self._make_result(Signal.BUY, "Positive momentum (No Squeeze)", raw)
        else:
            return self._make_result(Signal.SELL, "Negative momentum (No Squeeze)", raw)
