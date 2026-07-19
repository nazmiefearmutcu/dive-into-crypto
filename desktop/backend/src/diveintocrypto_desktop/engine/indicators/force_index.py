"""Force Index (Elder) — EMA-smoothed price-change × volume.

FI = (close − prev_close) · volume, smoothed by EMA(period). Combines direction,
magnitude and volume into one momentum-of-money measure. Strength is judged relative
to recent |FI| so it is scale-free across symbols. Causal.
"""

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class ForceIndexIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "force_index"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 13))
        strong = float(self.thresholds.get("strong_ratio", 1.5))

        close = df["close"]
        vol = df["volume"]
        if len(close) < period + 1:
            return self._make_result(Signal.NEUTRAL, "Force Index insufficient data")

        fi = (close.diff() * vol).fillna(0.0)
        fi_ema = fi.ewm(span=period, adjust=False).mean()
        val = float(fi_ema.iloc[-1])
        recent = float(fi_ema.abs().tail(period).mean()) or 1.0
        ratio = val / recent
        raw = {"force_index": round(val, 4), "ratio": round(ratio, 4)}
        if val > 0:
            return self._make_result(
                Signal.STRONG_BUY if ratio >= strong else Signal.BUY, f"Force Index +{ratio:.2f} (buying)", raw
            )
        if val < 0:
            return self._make_result(
                Signal.STRONG_SELL if ratio <= -strong else Signal.SELL, f"Force Index {ratio:.2f} (selling)", raw
            )
        return self._make_result(Signal.NEUTRAL, "Force Index flat", raw)
