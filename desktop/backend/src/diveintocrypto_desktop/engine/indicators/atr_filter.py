"""ATR (Average True Range) risk filter indicator."""

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class ATRFilterIndicator(BaseIndicator):
    """ATR as a volatility/risk filter - does not generate directional signals.

    Instead, it provides risk context:
    - High ATR = high volatility = wide ranges (risk elevated)
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

        # Without a full 3x-period baseline there is no volatility regime. Do not default
        # the ratio to 1.0 (that reads as "normal" and fabricates a result).
        if pd.isna(atr_mean) or atr_mean == 0:
            return self._make_result(
                Signal.NEUTRAL,
                f"ATR={current_atr:.2f} ({atr_pct:.2f}%) baseline warming up",
                {
                    "atr": round(current_atr, 4),
                    "atr_pct": round(atr_pct, 4),
                    "atr_ratio": None,
                    "volatility": "UNKNOWN",
                },
            )

        atr_ratio = current_atr / atr_mean

        # One set of cutoffs drives the label, the reason text and the risk factor.
        if atr_ratio > high_vol_multiplier:
            volatility = "HIGH"
        elif atr_ratio < 0.5:
            volatility = "LOW"
        else:
            volatility = "NORMAL"

        raw = {
            "atr": round(current_atr, 4),
            "atr_pct": round(atr_pct, 4),
            "atr_ratio": round(atr_ratio, 4),
            "volatility": volatility,
        }

        # ATR is always NEUTRAL for direction - it's a risk filter. The reason text
        # describes the volatility only: no sizing or trading advice.
        if volatility == "HIGH":
            return self._make_result(
                Signal.NEUTRAL,
                f"High ATR volatility ({atr_ratio:.1f}x average) — wide ranges",
                raw,
            )
        if volatility == "LOW":
            return self._make_result(
                Signal.NEUTRAL,
                f"Low ATR volatility ({atr_ratio:.1f}x average) — narrow ranges",
                raw,
            )
        return self._make_result(
            Signal.NEUTRAL,
            f"ATR={current_atr:.2f} ({atr_pct:.2f}%) normal volatility",
            raw,
        )
