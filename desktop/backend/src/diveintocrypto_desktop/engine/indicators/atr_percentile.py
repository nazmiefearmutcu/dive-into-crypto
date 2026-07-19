"""ATR Percentile — volatility-expansion breakout confirmer.

Ranks the current ATR% against its own history. When volatility is in a high percentile
AND price has made a directional move, that is a breakout worth confirming (strong in the
move's direction). In a low-volatility percentile (compression) it stays NEUTRAL. Distinct
from `atr_filter` (which is a pure 0-weight filter) — this one is directional via price.
Causal (Wilder RMA, trailing percentile).
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class ATRPercentileIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "atr_percentile"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 14))
        lookback = int(self.thresholds.get("lookback", 100))
        high_p = float(self.thresholds.get("high_percentile", 0.80))
        low_p = float(self.thresholds.get("low_percentile", 0.25))
        move_min = float(self.thresholds.get("move_min", 0.005))

        high = df["high"].to_numpy(dtype=float)
        low = df["low"].to_numpy(dtype=float)
        close = df["close"].to_numpy(dtype=float)
        n = len(close)
        if n < period + 2:
            return self._make_result(Signal.NEUTRAL, "ATR%ile insufficient data")

        tr = np.zeros(n)
        for i in range(1, n):
            tr[i] = max(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1]))
        atr = np.full(n, np.nan)
        atr[period] = tr[1:period + 1].mean()
        for i in range(period + 1, n):
            atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period
        atr_pct = atr / close
        valid = atr_pct[~np.isnan(atr_pct)]
        if len(valid) < 5:
            return self._make_result(Signal.NEUTRAL, "ATR%ile warming up")

        window = valid[-lookback:]
        cur = float(window[-1])
        pctile = float((window <= cur).mean())
        move = (close[-1] - close[-1 - period]) / close[-1 - period] if close[-1 - period] else 0.0
        raw = {"atr_pct_percentile": round(pctile, 4), "move": round(float(move), 5)}

        if pctile <= low_p:
            return self._make_result(Signal.NEUTRAL, f"vol compression (p{pctile:.2f})", raw)
        if abs(move) < move_min:
            return self._make_result(Signal.NEUTRAL, f"vol high but flat (p{pctile:.2f})", raw)
        up = move > 0
        strong = pctile >= high_p
        if up:
            return self._make_result(Signal.STRONG_BUY if strong else Signal.BUY, f"vol-expansion up (p{pctile:.2f})", raw)
        return self._make_result(Signal.STRONG_SELL if strong else Signal.SELL, f"vol-expansion down (p{pctile:.2f})", raw)
