"""ATR (Average True Range) risk filter indicator."""

from typing import Any
import pandas as pd
import numpy as np

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class ATRFilterIndicator(BaseIndicator):
    """ATR as a volatility/risk filter - does not generate directional signals.

    Instead, it provides risk context:
    - High ATR = high volatility = reduce position size or avoid trading
    - Normal ATR = standard conditions
    - Low ATR = low volatility = potential breakout ahead
    """

    @property
    def name(self) -> str:
        return "atr_filter"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)
        high_vol_multiplier = self.thresholds.get("high_volatility_multiplier", 2.0)

        high = df["high"]
        low = df["low"]
        close = df["close"]

        tr1 = high - low
        tr2 = (high - close.shift(1)).abs()
        tr3 = (low - close.shift(1)).abs()
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)

        atr = tr.rolling(window=period).mean()
        current_atr = atr.iloc[-1]
        current_price = close.iloc[-1]

        if pd.isna(current_atr) or current_price == 0:
            return self._make_result(Signal.NEUTRAL, "ATR data insufficient")

        atr_pct = (current_atr / current_price) * 100.0
        atr_mean = atr.rolling(window=period * 3).mean().iloc[-1]
        atr_ratio = current_atr / atr_mean if not pd.isna(atr_mean) and atr_mean != 0 else 1.0

        raw = {
            "atr": round(current_atr, 4),
            "atr_pct": round(atr_pct, 4),
            "atr_ratio": round(atr_ratio, 4),
            "volatility": "HIGH" if atr_ratio > high_vol_multiplier else "NORMAL" if atr_ratio > 0.8 else "LOW",
        }

        # ATR is always NEUTRAL for direction - it's a risk filter
        if atr_ratio > high_vol_multiplier:
            return self._make_result(
                Signal.NEUTRAL,
                f"ATR={current_atr:.2f} ({atr_pct:.2f}%) HIGH volatility - risk elevated, reduce position",
                raw,
            )
        elif atr_ratio < 0.5:
            return self._make_result(
                Signal.NEUTRAL,
                f"ATR={current_atr:.2f} ({atr_pct:.2f}%) LOW volatility - potential breakout watch",
                raw,
            )
        else:
            return self._make_result(
                Signal.NEUTRAL,
                f"ATR={current_atr:.2f} ({atr_pct:.2f}%) normal volatility",
                raw,
            )
