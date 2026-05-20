"""MFI (Money Flow Index) indicator."""

from typing import Any
import pandas as pd
import numpy as np

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class MFIIndicator(BaseIndicator):
    """Money Flow Index - volume-weighted RSI variant."""

    @property
    def name(self) -> str:
        return "mfi"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)
        strong_buy_level = self.thresholds.get("strong_buy", 20)
        buy_level = self.thresholds.get("buy", 30)
        sell_level = self.thresholds.get("sell", 70)
        strong_sell_level = self.thresholds.get("strong_sell", 80)

        typical_price = (df["high"] + df["low"] + df["close"]) / 3.0
        money_flow = typical_price * df["volume"]

        tp_diff = typical_price.diff()
        positive_flow = money_flow.where(tp_diff > 0, 0.0)
        negative_flow = money_flow.where(tp_diff < 0, 0.0)

        positive_sum = positive_flow.rolling(window=period).sum()
        negative_sum = negative_flow.rolling(window=period).sum()
        negative_sum = negative_sum.replace(0, np.nan)

        money_ratio = positive_sum / negative_sum
        mfi = 100.0 - (100.0 / (1.0 + money_ratio))

        current_mfi = mfi.iloc[-1]

        if pd.isna(current_mfi):
            return self._make_result(Signal.NEUTRAL, "MFI data insufficient")

        raw = {"mfi": round(current_mfi, 2)}

        if current_mfi <= strong_buy_level:
            return self._make_result(Signal.STRONG_BUY, f"MFI={current_mfi:.1f} strong money flow oversold", raw)
        elif current_mfi <= buy_level:
            return self._make_result(Signal.BUY, f"MFI={current_mfi:.1f} money flow oversold", raw)
        elif current_mfi >= strong_sell_level:
            return self._make_result(Signal.STRONG_SELL, f"MFI={current_mfi:.1f} strong money flow overbought", raw)
        elif current_mfi >= sell_level:
            return self._make_result(Signal.SELL, f"MFI={current_mfi:.1f} money flow overbought", raw)
        else:
            return self._make_result(Signal.NEUTRAL, f"MFI={current_mfi:.1f} neutral money flow", raw)
