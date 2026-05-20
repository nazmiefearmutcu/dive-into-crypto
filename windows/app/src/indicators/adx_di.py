"""ADX + Directional Index (DI) indicator."""

from typing import Any
import pandas as pd
import numpy as np

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class ADXDIIndicator(BaseIndicator):
    """ADX with +DI/-DI for trend strength and direction confirmation."""

    @property
    def name(self) -> str:
        return "adx_di"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = self.thresholds.get("period", 14)
        strong_trend = self.thresholds.get("strong_trend", 25)
        weak_trend = self.thresholds.get("weak_trend", 15)

        high = df["high"]
        low = df["low"]
        close = df["close"]

        plus_dm = high.diff()
        minus_dm = -low.diff()

        plus_dm = plus_dm.where((plus_dm > minus_dm) & (plus_dm > 0), 0.0)
        minus_dm = minus_dm.where((minus_dm > plus_dm) & (minus_dm > 0), 0.0)

        tr1 = high - low
        tr2 = (high - close.shift(1)).abs()
        tr3 = (low - close.shift(1)).abs()
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)

        atr = tr.rolling(window=period).mean()
        atr = atr.replace(0, np.nan)

        plus_di = 100.0 * (plus_dm.rolling(window=period).mean() / atr)
        minus_di = 100.0 * (minus_dm.rolling(window=period).mean() / atr)

        di_sum = plus_di + minus_di
        di_sum = di_sum.replace(0, np.nan)
        dx = 100.0 * ((plus_di - minus_di).abs() / di_sum)
        adx = dx.rolling(window=period).mean()

        current_adx = adx.iloc[-1]
        current_plus_di = plus_di.iloc[-1]
        current_minus_di = minus_di.iloc[-1]

        if pd.isna(current_adx) or pd.isna(current_plus_di) or pd.isna(current_minus_di):
            return self._make_result(Signal.NEUTRAL, "ADX/DI data insufficient")

        raw = {
            "adx": round(current_adx, 2),
            "plus_di": round(current_plus_di, 2),
            "minus_di": round(current_minus_di, 2),
        }

        if current_adx < weak_trend:
            return self._make_result(
                Signal.NEUTRAL,
                f"ADX={current_adx:.1f} weak trend - no directional conviction",
                raw,
            )

        is_strong = current_adx >= strong_trend

        if current_plus_di > current_minus_di:
            if is_strong:
                return self._make_result(Signal.STRONG_BUY, f"ADX={current_adx:.1f} strong bullish trend (+DI>{'-'}DI)", raw)
            return self._make_result(Signal.BUY, f"ADX={current_adx:.1f} bullish trend (+DI>{'-'}DI)", raw)
        elif current_minus_di > current_plus_di:
            if is_strong:
                return self._make_result(Signal.STRONG_SELL, f"ADX={current_adx:.1f} strong bearish trend ({'-'}DI>+DI)", raw)
            return self._make_result(Signal.SELL, f"ADX={current_adx:.1f} bearish trend ({'-'}DI>+DI)", raw)
        else:
            return self._make_result(Signal.NEUTRAL, f"ADX={current_adx:.1f} DI lines converging", raw)
