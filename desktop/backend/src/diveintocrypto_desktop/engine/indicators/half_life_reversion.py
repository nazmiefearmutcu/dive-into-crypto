"""OU Half-Life Mean Reversion — statistically-gated reversion.

Fits an AR(1)/Ornstein-Uhlenbeck model over the window: Δprice_t = a + β·price_{t-1}.
β<0 implies mean reversion with half-life = −ln2/ln(1+β). Only when the series is
statistically mean-reverting AND the half-life is short enough to act on does it fade the
current z-score deviation. This differs from `zscore_reversion` (which always fades): here
the fade is conditioned on a measured reversion tendency.

Gates (all must pass before a fade is emitted):
  1. β < 0 (reversion direction).
  2. Dickey-Fuller style t-stat on β (OLS standard error from the residuals) at or below
     `adf_t_critical` (default -2.86, the 5% asymptotic critical value with constant).
     Without this test, random walks and i.i.d. noise trigger fades about 15-40% of the time.
  3. Implied half-life of at least one bar (β > -0.5) and at most `max_half_life`.
  4. Finite window (non-finite closes are NEUTRAL, never a fit error).

Causal.
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
        adf_crit = float(self.thresholds.get("adf_t_critical", -2.86))

        close = df["close"].to_numpy(dtype=float)
        if len(close) < period or period < 4:
            return self._make_result(Signal.NEUTRAL, "Half-life insufficient data")

        y = close[-period:]
        if not np.isfinite(y).all():
            return self._make_result(Signal.NEUTRAL, "Half-life insufficient data")
        lagged = y[:-1]
        delta = np.diff(y)
        sxx = float(np.sum((lagged - lagged.mean()) ** 2))
        if sxx <= 0.0:
            return self._make_result(Signal.NEUTRAL, "flat series")
        beta, intercept = np.polyfit(lagged, delta, 1)
        beta = float(beta)
        if beta >= 0:
            return self._make_result(Signal.NEUTRAL, "not mean-reverting (β≥0)", {"beta": round(beta, 6)})
        if beta <= -1.0:
            return self._make_result(Signal.NEUTRAL, "AR(1) unstable (β≤-1)", {"beta": round(beta, 6)})

        # OLS standard error of beta from the residual variance (n - 2 degrees of freedom).
        resid = delta - (beta * lagged + float(intercept))
        s2 = float(np.sum(resid ** 2)) / (len(lagged) - 2)
        se = float(np.sqrt(s2 / sxx)) if s2 > 0.0 else 0.0
        t_stat = beta / se if se > 0.0 else float("-inf")
        if t_stat > adf_crit:
            return self._make_result(
                Signal.NEUTRAL,
                f"not statistically mean-reverting (t={t_stat:.2f} > {adf_crit:.2f})",
                {"beta": round(beta, 6)},
            )

        hl = -np.log(2) / np.log1p(beta)
        mean = float(y.mean())
        sd = float(y.std(ddof=1))
        if sd == 0.0:
            return self._make_result(Signal.NEUTRAL, "flat series")
        z = (float(y[-1]) - mean) / sd
        raw = {"beta": round(float(beta), 6), "half_life": round(float(hl), 2), "zscore": round(z, 4)}

        if hl < 1.0:
            return self._make_result(Signal.NEUTRAL, f"half-life {hl:.2f} below 1 bar (implausible)", raw)
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
