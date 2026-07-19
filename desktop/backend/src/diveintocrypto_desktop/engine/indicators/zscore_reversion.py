"""Price Z-Score Mean Reversion — contrarian.

z = (close − rollingMean) / rollingStd over `period`. Stretched-above-mean (high z) is
faded SHORT; stretched-below (low z) is faded LONG. This is intentionally the OPPOSITE
sign to the trend/momentum indicators, so it contributes orthogonal mean-reversion
information to the vote. Bessel (ddof=1) std. Causal.
"""

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class ZScoreReversionIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "zscore_reversion"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 20))
        strong = float(self.thresholds.get("strong", 2.5))
        weak = float(self.thresholds.get("weak", 1.2))

        close = df["close"]
        if len(close) < period:
            return self._make_result(Signal.NEUTRAL, "Z-score insufficient data")

        mean = close.rolling(period).mean().iloc[-1]
        sd = close.rolling(period).std(ddof=1).iloc[-1]
        if sd is None or sd != sd or sd == 0.0:
            return self._make_result(Signal.NEUTRAL, "Z-score undefined")

        z = (float(close.iloc[-1]) - float(mean)) / float(sd)
        raw = {"zscore": round(z, 4)}
        # contrarian: above-mean -> revert down (sell); below-mean -> revert up (buy)
        if z >= strong:
            return self._make_result(Signal.STRONG_SELL, f"z {z:+.2f} over-extended (fade down)", raw)
        if z >= weak:
            return self._make_result(Signal.SELL, f"z {z:+.2f} stretched up (fade)", raw)
        if z <= -strong:
            return self._make_result(Signal.STRONG_BUY, f"z {z:+.2f} over-extended (fade up)", raw)
        if z <= -weak:
            return self._make_result(Signal.BUY, f"z {z:+.2f} stretched down (fade)", raw)
        return self._make_result(Signal.NEUTRAL, f"z {z:+.2f} near mean", raw)
