"""OBV (On-Balance Volume) indicator."""

from typing import Any
import pandas as pd
import numpy as np

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class OBVIndicator(BaseIndicator):
    """On-Balance Volume for volume confirmation and divergence detection."""

    @property
    def name(self) -> str:
        return "obv"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        sma_period = self.thresholds.get("sma_period", 20)
        divergence_lookback = self.thresholds.get("divergence_lookback", 10)

        close = df["close"]
        volume = df["volume"]

        # Calculate OBV
        direction = np.where(close.diff() > 0, 1, np.where(close.diff() < 0, -1, 0))
        obv = (volume * direction).cumsum()

        obv_series = pd.Series(obv, index=df.index)
        obv_sma = obv_series.rolling(window=sma_period).mean()

        current_obv = obv_series.iloc[-1]
        current_obv_sma = obv_sma.iloc[-1]

        if pd.isna(current_obv_sma):
            return self._make_result(Signal.NEUTRAL, "OBV data insufficient")

        # Divergence detection
        lookback = min(divergence_lookback, len(close) - 1)
        price_change = (close.iloc[-1] - close.iloc[-lookback - 1]) / close.iloc[-lookback - 1]
        obv_change = obv_series.iloc[-1] - obv_series.iloc[-lookback - 1]
        obv_base = abs(obv_series.iloc[-lookback - 1])
        obv_change_pct = obv_change / obv_base if obv_base != 0 else 0

        raw = {
            "obv": round(current_obv, 2),
            "obv_sma": round(current_obv_sma, 2),
            "price_change_pct": round(price_change * 100, 2),
            "obv_trend": "UP" if current_obv > current_obv_sma else "DOWN",
        }

        # Bearish divergence: price up but OBV down
        if price_change > 0.01 and obv_change_pct < -0.05:
            return self._make_result(
                Signal.SELL,
                f"OBV bearish divergence: price up {price_change*100:.1f}% but OBV declining",
                raw,
            )

        # Bullish divergence: price down but OBV up
        if price_change < -0.01 and obv_change_pct > 0.05:
            return self._make_result(
                Signal.BUY,
                f"OBV bullish divergence: price down {price_change*100:.1f}% but OBV rising",
                raw,
            )

        # OBV trend confirmation
        if current_obv > current_obv_sma:
            obv_strength = (current_obv - current_obv_sma) / abs(current_obv_sma) if current_obv_sma != 0 else 0
            if obv_strength > 0.1:
                return self._make_result(Signal.BUY, "OBV strongly above SMA, volume confirms uptrend", raw)
            return self._make_result(Signal.BUY, "OBV above SMA, volume supports upward move", raw)
        elif current_obv < current_obv_sma:
            obv_strength = (current_obv_sma - current_obv) / abs(current_obv_sma) if current_obv_sma != 0 else 0
            if obv_strength > 0.1:
                return self._make_result(Signal.SELL, "OBV strongly below SMA, volume confirms downtrend", raw)
            return self._make_result(Signal.SELL, "OBV below SMA, volume supports downward move", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "OBV flat / no volume confirmation", raw)
