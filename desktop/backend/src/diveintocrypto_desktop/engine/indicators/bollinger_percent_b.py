"""Bollinger %B with a bandwidth (squeeze/expansion) gate.

%B = (close − lower) / (upper − lower); bandwidth = (upper − lower) / mid. Distinct
from the plain Bollinger signal: it is gated by bandwidth so band touches during a
squeeze (low bandwidth) are treated as NEUTRAL (coiling), while breakouts beyond the
bands during expansion emit strong signals. Uses Bessel (ddof=1) std per repo convention.
"""

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class BollingerPercentBIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "bollinger_percent_b"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 20))
        std_dev = float(self.thresholds.get("std_dev", 2.0))
        squeeze = float(self.thresholds.get("squeeze_bandwidth", 0.04))

        close = df["close"]
        if len(close) < period:
            return self._make_result(Signal.NEUTRAL, "%B insufficient data")

        mid = close.rolling(period).mean().iloc[-1]
        sd = close.rolling(period).std(ddof=1).iloc[-1]
        if sd is None or sd != sd or sd == 0.0 or mid == 0.0:
            return self._make_result(Signal.NEUTRAL, "%B undefined")

        upper = mid + std_dev * sd
        lower = mid - std_dev * sd
        c = float(close.iloc[-1])
        pctb = (c - lower) / (upper - lower)
        bandwidth = (upper - lower) / mid
        raw = {"percent_b": round(float(pctb), 4), "bandwidth": round(float(bandwidth), 5)}

        if bandwidth < squeeze:  # coiling: no directional call yet
            return self._make_result(Signal.NEUTRAL, f"%B squeeze (bw {bandwidth:.3f})", raw)
        if pctb >= 1.0:
            return self._make_result(Signal.STRONG_BUY, f"%B {pctb:.2f} breakout up", raw)
        if pctb >= 0.8:
            return self._make_result(Signal.BUY, f"%B {pctb:.2f} upper band", raw)
        if pctb <= 0.0:
            return self._make_result(Signal.STRONG_SELL, f"%B {pctb:.2f} breakout down", raw)
        if pctb <= 0.2:
            return self._make_result(Signal.SELL, f"%B {pctb:.2f} lower band", raw)
        return self._make_result(Signal.NEUTRAL, f"%B {pctb:.2f} mid-band", raw)
