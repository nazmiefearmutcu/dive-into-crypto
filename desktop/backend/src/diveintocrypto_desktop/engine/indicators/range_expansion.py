"""Range Expansion (NR7 / WR7) — compression/expansion breakout setup.

Compares the current bar's high-low range to the last `lookback` bars. A widest-range bar
(WR7) is an expansion breakout → strong signal in that bar's direction. A narrowest-range
bar (NR7) is compression → NEUTRAL (coiled, awaiting release). Otherwise a weak signal only
when the last bar agrees with the short trend. Causal (no future bars).
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class RangeExpansionIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "range_expansion"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        lookback = int(self.thresholds.get("lookback", 7))

        high = df["high"].to_numpy(dtype=float)
        low = df["low"].to_numpy(dtype=float)
        close = df["close"].to_numpy(dtype=float)
        open_ = df["open"].to_numpy(dtype=float)
        if len(close) < lookback + 1:
            return self._make_result(Signal.NEUTRAL, "Range insufficient data")

        rng = (high - low)[-lookback:]
        cur = float(rng[-1])
        is_wr = cur >= rng.max()
        is_nr = cur <= rng.min()
        last_dir = 1 if close[-1] > open_[-1] else -1 if close[-1] < open_[-1] else 0
        trend = 1 if close[-1] > close[-lookback] else -1 if close[-1] < close[-lookback] else 0
        raw = {"cur_range": round(cur, 6), "is_wr7": float(is_wr), "is_nr7": float(is_nr)}

        if is_nr:
            return self._make_result(Signal.NEUTRAL, "NR7 compression (coiled)", raw)
        if is_wr and last_dir != 0:
            return self._make_result(
                Signal.STRONG_BUY if last_dir > 0 else Signal.STRONG_SELL, "WR7 expansion breakout", raw
            )
        if last_dir != 0 and last_dir == trend:
            return self._make_result(Signal.BUY if last_dir > 0 else Signal.SELL, "range/trend agree", raw)
        return self._make_result(Signal.NEUTRAL, "no expansion setup", raw)
