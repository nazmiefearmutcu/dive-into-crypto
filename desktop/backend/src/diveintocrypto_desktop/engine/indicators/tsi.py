"""True Strength Index (TSI) — double-smoothed momentum with signal-line cross.

TSI = 100 * DS(mom) / DS(|mom|) where mom = close.diff() and DS = EMA(long) then
EMA(short). A signal line = EMA(TSI, signal). Direction from TSI sign + signal cross.
Strictly causal (EMAs use adjust=False, only past data).
"""

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class TSIIndicator(BaseIndicator):
    @property
    def name(self) -> str:
        return "tsi"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        long = int(self.thresholds.get("long", 25))
        short = int(self.thresholds.get("short", 13))
        sig = int(self.thresholds.get("signal", 13))
        strong = float(self.thresholds.get("strong", 25.0))

        close = df["close"]
        if len(close) < long + short + 2:
            return self._make_result(Signal.NEUTRAL, "TSI insufficient data")

        mom = close.diff().fillna(0.0)

        def ds(x: pd.Series) -> pd.Series:
            return x.ewm(span=long, adjust=False).mean().ewm(span=short, adjust=False).mean()

        denom = ds(mom.abs())
        tsi = 100.0 * ds(mom) / denom.replace(0.0, float("nan"))
        tsi_line = tsi.ewm(span=sig, adjust=False).mean()

        t = float(tsi.iloc[-1])
        sg = float(tsi_line.iloc[-1])
        if t != t or sg != sg:  # NaN guard
            return self._make_result(Signal.NEUTRAL, "TSI undefined")

        raw = {"tsi": round(t, 4), "signal": round(sg, 4)}
        if t > 0 and t > sg:
            return self._make_result(
                Signal.STRONG_BUY if t >= strong else Signal.BUY, f"TSI {t:.1f} > signal (bull)", raw
            )
        if t < 0 and t < sg:
            return self._make_result(
                Signal.STRONG_SELL if t <= -strong else Signal.SELL, f"TSI {t:.1f} < signal (bear)", raw
            )
        return self._make_result(Signal.NEUTRAL, f"TSI {t:.1f} vs signal {sg:.1f} unresolved", raw)
