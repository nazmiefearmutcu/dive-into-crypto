"""OU Half-Life Mean Reversion — statistically-gated reversion.

Fits an AR(1)/Ornstein-Uhlenbeck model over the window: Δprice_t = a + β·price_{t-1}.
β<0 implies mean reversion with half-life = −ln2/ln(1+β). Only when the series is
statistically mean-reverting AND the half-life is short enough to act on does it fade the
current z-score deviation. This differs from `zscore_reversion` (which always fades): here
the fade is conditioned on a measured reversion tendency. Causal.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class HalfLifeReversionIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "half_life_reversion"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 50))
        max_hl = float(self.thresholds.get("max_half_life", 30.0))
        strong = float(self.thresholds.get("strong_z", 2.0))
        weak = float(self.thresholds.get("weak_z", 1.0))

        close = df["close"].to_numpy(dtype=float)
        if len(close) < period:
            return self._make_result(Signal.NEUTRAL, "Half-life insufficient data")

        y = close[-period:]
        lagged = y[:-1]
        delta = np.diff(y)
        beta = np.polyfit(lagged, delta, 1)[0]
        if beta >= 0:
            return self._make_result(Signal.NEUTRAL, "not mean-reverting (β≥0)", {"beta": round(float(beta), 6)})
        hl = -np.log(2) / np.log1p(beta) if -1.0 < beta < 0.0 else float("inf")
        mean = float(y.mean())
        sd = float(y.std(ddof=1))
        if sd == 0.0:
            return self._make_result(Signal.NEUTRAL, "flat series")
        z = (float(y[-1]) - mean) / sd
        raw = {"beta": round(float(beta), 6), "half_life": round(float(hl), 2), "zscore": round(z, 4)}

        if hl > max_hl or hl != hl:
            return self._make_result(Signal.NEUTRAL, f"half-life {hl:.1f} too slow", raw)
        # mean-reverting & actionable: fade the deviation
        if z >= strong:
            return self._make_result(Signal.STRONG_SELL, f"revert (hl {hl:.1f}, z {z:+.2f})", raw)
        if z >= weak:
            return self._make_result(Signal.SELL, f"revert (hl {hl:.1f}, z {z:+.2f})", raw)
        if z <= -strong:
            return self._make_result(Signal.STRONG_BUY, f"revert (hl {hl:.1f}, z {z:+.2f})", raw)
        if z <= -weak:
            return self._make_result(Signal.BUY, f"revert (hl {hl:.1f}, z {z:+.2f})", raw)
        return self._make_result(Signal.NEUTRAL, f"near mean (hl {hl:.1f}, z {z:+.2f})", raw)
