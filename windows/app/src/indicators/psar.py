"""Parabolic SAR indicator."""

from typing import Any
import pandas as pd
import numpy as np

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class PSARIndicator(BaseIndicator):
    """Parabolic SAR for trend direction confirmation."""

    @property
    def name(self) -> str:
        return "psar"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        af_start = self.thresholds.get("af_start", 0.02)
        af_increment = self.thresholds.get("af_increment", 0.02)
        af_max = self.thresholds.get("af_max", 0.20)

        high = df["high"].values
        low = df["low"].values
        close = df["close"].values
        n = len(close)

        if n < 3:
            return self._make_result(Signal.NEUTRAL, "PSAR data insufficient")

        psar = np.zeros(n)
        af = np.zeros(n)
        ep = np.zeros(n)
        trend = np.ones(n)  # 1 = up, -1 = down

        # Initialize
        psar[0] = low[0]
        af[0] = af_start
        ep[0] = high[0]
        trend[0] = 1

        for i in range(1, n):
            prev_psar = psar[i - 1]
            prev_af = af[i - 1]
            prev_ep = ep[i - 1]
            prev_trend = trend[i - 1]

            if prev_trend == 1:  # Uptrend
                psar[i] = prev_psar + prev_af * (prev_ep - prev_psar)
                psar[i] = min(psar[i], low[i - 1])
                if i >= 2:
                    psar[i] = min(psar[i], low[i - 2])

                if low[i] < psar[i]:  # Reversal to downtrend
                    trend[i] = -1
                    psar[i] = prev_ep
                    ep[i] = low[i]
                    af[i] = af_start
                else:
                    trend[i] = 1
                    if high[i] > prev_ep:
                        ep[i] = high[i]
                        af[i] = min(prev_af + af_increment, af_max)
                    else:
                        ep[i] = prev_ep
                        af[i] = prev_af
            else:  # Downtrend
                psar[i] = prev_psar + prev_af * (prev_ep - prev_psar)
                psar[i] = max(psar[i], high[i - 1])
                if i >= 2:
                    psar[i] = max(psar[i], high[i - 2])

                if high[i] > psar[i]:  # Reversal to uptrend
                    trend[i] = 1
                    psar[i] = prev_ep
                    ep[i] = high[i]
                    af[i] = af_start
                else:
                    trend[i] = -1
                    if low[i] < prev_ep:
                        ep[i] = low[i]
                        af[i] = min(prev_af + af_increment, af_max)
                    else:
                        ep[i] = prev_ep
                        af[i] = prev_af

        current_trend = trend[-1]
        current_psar = psar[-1]
        current_close = close[-1]
        prev_trend = trend[-2]

        raw = {
            "psar": round(current_psar, 4),
            "trend": "UP" if current_trend == 1 else "DOWN",
            "distance_pct": round(abs(current_close - current_psar) / current_close * 100, 4),
        }

        # Detect trend flip
        trend_flip_bullish = prev_trend == -1 and current_trend == 1
        trend_flip_bearish = prev_trend == 1 and current_trend == -1

        if trend_flip_bullish:
            return self._make_result(Signal.BUY, "PSAR flipped bullish (SAR below price)", raw)
        elif trend_flip_bearish:
            return self._make_result(Signal.SELL, "PSAR flipped bearish (SAR above price)", raw)
        elif current_trend == 1:
            return self._make_result(Signal.BUY, f"PSAR confirms uptrend, SAR={current_psar:.2f}", raw)
        elif current_trend == -1:
            return self._make_result(Signal.SELL, f"PSAR confirms downtrend, SAR={current_psar:.2f}", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "PSAR indeterminate", raw)
