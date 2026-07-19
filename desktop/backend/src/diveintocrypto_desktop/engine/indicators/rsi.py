"""RSI (Relative Strength Index) indicator."""

import pandas as pd
import numpy as np

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class RSIIndicator(BaseIndicator):
    """RSI with configurable thresholds for signal generation."""

    @property
    def name(self) -> str:
        return "rsi"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)
        strong_buy = self.thresholds.get("strong_buy", 25)
        buy = self.thresholds.get("buy", 35)
        sell = self.thresholds.get("sell", 65)
        strong_sell = self.thresholds.get("strong_sell", 80)

        close = df["close"]
        delta = close.diff()
        gain = delta.where(delta > 0, 0.0)
        loss = (-delta).where(delta < 0, 0.0)

        avg_gain = gain.rolling(window=period, min_periods=period).mean()
        avg_loss = loss.rolling(window=period, min_periods=period).mean()

        is_nan = avg_gain.isna() | avg_loss.isna()
        both_zero = (avg_gain == 0.0) & (avg_loss == 0.0)
        loss_zero = (avg_loss == 0.0) & (avg_gain != 0.0)

        rs = avg_gain / avg_loss.where(avg_loss != 0.0, 1.0)
        standard_rsi = 100.0 - (100.0 / (1.0 + rs))

        rsi_values = np.select(
            condlist=[is_nan, both_zero, loss_zero],
            choicelist=[np.nan, 50.0, 100.0],
            default=standard_rsi
        )
        rsi = pd.Series(rsi_values, index=df.index)
        current_rsi = rsi.iloc[-1]

        if pd.isna(current_rsi):
            return self._make_result(Signal.NEUTRAL, "RSI data insufficient", {"rsi": None})

        raw = {"rsi": round(current_rsi, 2)}

        if current_rsi <= strong_buy:
            return self._make_result(Signal.STRONG_BUY, f"RSI={current_rsi:.1f} extremely oversold", raw)
        elif current_rsi <= buy:
            return self._make_result(Signal.BUY, f"RSI={current_rsi:.1f} oversold zone", raw)
        elif current_rsi >= strong_sell:
            return self._make_result(Signal.STRONG_SELL, f"RSI={current_rsi:.1f} extremely overbought", raw)
        elif current_rsi >= sell:
            return self._make_result(Signal.SELL, f"RSI={current_rsi:.1f} overbought zone", raw)
        else:
            return self._make_result(Signal.NEUTRAL, f"RSI={current_rsi:.1f} neutral zone", raw)
