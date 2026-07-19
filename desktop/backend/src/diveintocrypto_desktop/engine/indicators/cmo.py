"""Chande Momentum Oscillator (CMO) — momentum-trend framing.

CMO = 100 * (ΣUp − ΣDown) / (ΣUp + ΣDown) over `period`. Unlike RSI (overbought/
oversold reversal), this reads CMO as directional momentum: positive = up-momentum.
Strictly causal (uses only the closed window ending at the last candle).
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class CMOIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "cmo"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 14))
        strong = float(self.thresholds.get("strong", 50.0))
        weak = float(self.thresholds.get("weak", 15.0))

        close = df["close"].to_numpy(dtype=float)
        if len(close) < period + 1:
            return self._make_result(Signal.NEUTRAL, "CMO insufficient data")

        d = np.diff(close)[-period:]
        up = float(np.where(d > 0, d, 0.0).sum())
        dn = float(np.where(d < 0, -d, 0.0).sum())
        denom = up + dn
        if denom == 0.0:
            return self._make_result(Signal.NEUTRAL, "CMO flat window")

        cmo = 100.0 * (up - dn) / denom
        raw = {"cmo": round(cmo, 4)}
        if cmo >= strong:
            return self._make_result(Signal.STRONG_BUY, f"CMO {cmo:.1f} strong up-momentum", raw)
        if cmo >= weak:
            return self._make_result(Signal.BUY, f"CMO {cmo:.1f} up-momentum", raw)
        if cmo <= -strong:
            return self._make_result(Signal.STRONG_SELL, f"CMO {cmo:.1f} strong down-momentum", raw)
        if cmo <= -weak:
            return self._make_result(Signal.SELL, f"CMO {cmo:.1f} down-momentum", raw)
        return self._make_result(Signal.NEUTRAL, f"CMO {cmo:.1f} flat", raw)
