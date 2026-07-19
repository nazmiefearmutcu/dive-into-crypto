"""Volume-Weighted Moving Average cross (VWMA short vs long).

VWMA(p) = Σ(close·volume) / Σ(volume) over the last p bars. A short/long VWMA cross
weights price by traded volume, so moves on real participation dominate — distinct
from the plain SMA/EMA crosses and from VWAP (a single anchored average). Causal.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class VWMACrossIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "vwma_cross"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        short = int(self.thresholds.get("short_period", 9))
        long = int(self.thresholds.get("long_period", 21))
        strong = float(self.thresholds.get("strong_divergence_pct", 0.02))

        close = df["close"].to_numpy(dtype=float)
        vol = df["volume"].to_numpy(dtype=float)
        if len(close) < long:
            return self._make_result(Signal.NEUTRAL, "VWMA insufficient data")

        def vwma(p: int) -> float:
            c, v = close[-p:], vol[-p:]
            sv = v.sum()
            return float((c * v).sum() / sv) if sv > 0 else float(c.mean())

        s, l = vwma(short), vwma(long)
        if l == 0.0:
            return self._make_result(Signal.NEUTRAL, "VWMA long is zero")

        div = (s - l) / l
        raw = {"vwma_short": round(s, 4), "vwma_long": round(l, 4), "divergence_pct": round(div, 5)}
        if s > l:
            return self._make_result(
                Signal.STRONG_BUY if div >= strong else Signal.BUY, f"VWMA short>long ({div:+.2%})", raw
            )
        if s < l:
            return self._make_result(
                Signal.STRONG_SELL if div <= -strong else Signal.SELL, f"VWMA short<long ({div:+.2%})", raw
            )
        return self._make_result(Signal.NEUTRAL, "VWMA aligned", raw)
