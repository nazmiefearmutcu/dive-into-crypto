"""Historical-Volatility Percentile — exhaustion fade at volatility extremes.

Realised volatility = rolling std of log returns; ranked against its history. In the top
volatility decile the market is typically climaxing, so the latest directional move is
FADED (contrarian). Outside that extreme it stays NEUTRAL. This is the reversion side of
the volatility coin, distinct from `atr_percentile` (which confirms breakouts). Causal.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class HistVolPercentileIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "hist_vol_percentile"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 20))
        lookback = int(self.thresholds.get("lookback", 100))
        extreme = float(self.thresholds.get("extreme_percentile", 0.90))
        very_extreme = float(self.thresholds.get("very_extreme_percentile", 0.97))
        move_min = float(self.thresholds.get("move_min", 0.004))

        close = df["close"].to_numpy(dtype=float)
        n = len(close)
        if n < period + 5:
            return self._make_result(Signal.NEUTRAL, "HV%ile insufficient data")

        logret = np.diff(np.log(np.where(close > 0, close, np.nan)))
        s = pd.Series(logret).rolling(period).std(ddof=1)
        vol = s.to_numpy()
        valid = vol[~np.isnan(vol)]
        if len(valid) < 5:
            return self._make_result(Signal.NEUTRAL, "HV%ile warming up")

        window = valid[-lookback:]
        cur = float(window[-1])
        pctile = float((window <= cur).mean())
        move = (close[-1] - close[-1 - period]) / close[-1 - period] if close[-1 - period] else 0.0
        raw = {"hv_percentile": round(pctile, 4), "move": round(float(move), 5)}

        if pctile < extreme or abs(move) < move_min:
            return self._make_result(Signal.NEUTRAL, f"vol normal (p{pctile:.2f})", raw)
        strong = pctile >= very_extreme
        # fade the move: up-move at vol climax -> SELL, down-move -> BUY
        if move > 0:
            return self._make_result(Signal.STRONG_SELL if strong else Signal.SELL, f"vol-climax fade up (p{pctile:.2f})", raw)
        return self._make_result(Signal.STRONG_BUY if strong else Signal.BUY, f"vol-climax fade down (p{pctile:.2f})", raw)
