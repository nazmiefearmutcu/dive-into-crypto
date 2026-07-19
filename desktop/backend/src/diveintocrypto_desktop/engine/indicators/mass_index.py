"""Mass Index indicator (Donald Dorsey, 1992).

The Mass Index detects trend *reversals* by tracking the expansion and
contraction of the daily high-low range. It does NOT measure the magnitude of
volatility (that is ATR's job) and it does NOT measure trend/range regime (that
is Choppiness's job). Instead it watches the ratio of a fast EMA of the range to
a slow (double-smoothed) EMA of the range: when ranges bulge, the ratio rises;
summed over a window this produces the "Mass Index". A "reversal bulge" is the
canonical Dorsey setup: the Mass Index rises above 27, then falls back below
26.5 -- a reversal is imminent.

The Mass Index itself is direction-agnostic (it only says "a reversal is coming").
Direction is supplied by the slope of a short EMA of close, exactly as Dorsey
prescribed: if price has been *rising* into the bulge, the reversal is a top
(bearish); if *falling*, the reversal is a bottom (bullish).

Formula
-------
    range  = high - low
    ema1   = EMA(range, ema_period)                # fast single-smoothed range
    ema2   = EMA(ema1,  ema_period2)               # slow double-smoothed range
    ratio  = ema1 / ema2                            # dimensionless bulge ratio
    MI     = rolling_sum(ratio, sum_period)         # the Mass Index

Trigger (per Dorsey):
    a "reversal bulge" fired when MI climbed to >= bulge_threshold somewhere in
    the recent lookback window and has since fallen back below setback_threshold
    on the current (closed) bar.

Direction filter:
    trend = sign( EMA(close, dir_ema_period)[-1] - EMA(close, dir_ema_period)[-1-dir_lookback] )
    trend UP   -> reversal is a top    -> SELL side
    trend DOWN -> reversal is a bottom -> BUY  side
    trend FLAT -> no trend to reverse  -> NEUTRAL

Strictly causal: every quantity at bar i uses only bars <= i (EMAs are recursive
with adjust=False, the rolling sum is trailing, the slope looks strictly
backward). No future rows, no centered windows, no repainting.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class MassIndexIndicator(BaseIndicator):
    """Mass Index reversal-bulge detector (EMA-ratio of high-low range)."""

    @property
    def name(self) -> str:
        return "mass_index"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        ema_period = int(self.thresholds.get("ema_period", 9))
        ema_period2 = int(self.thresholds.get("ema_period2", 9))
        sum_period = int(self.thresholds.get("sum_period", 25))
        bulge_threshold = float(self.thresholds.get("bulge_threshold", 27.0))
        setback_threshold = float(self.thresholds.get("setback_threshold", 26.5))
        strong_bulge_threshold = float(
            self.thresholds.get("strong_bulge_threshold", 27.5)
        )
        reversal_lookback = int(self.thresholds.get("reversal_lookback", 25))
        dir_ema_period = int(self.thresholds.get("dir_ema_period", 9))
        dir_lookback = int(self.thresholds.get("dir_lookback", 9))

        n = len(df)
        # Need enough bars for the rolling sum plus a little bulge history.
        if n < sum_period + 5:
            return self._make_result(Signal.NEUTRAL, "Mass Index data insufficient")

        high = df["high"].astype(float)
        low = df["low"].astype(float)
        close = df["close"].astype(float)

        rng = (high - low).clip(lower=0.0)

        # Fast single-smoothed range and slow double-smoothed range (recursive
        # EMA, adjust=False -> strictly causal).
        ema1 = rng.ewm(span=ema_period, adjust=False).mean()
        ema2 = ema1.ewm(span=ema_period2, adjust=False).mean()

        # Dimensionless bulge ratio. A perfectly flat market gives ema1==ema2==0;
        # define that degenerate ratio as 1.0 (no bulge).
        ratio = ema1 / ema2.replace(0.0, np.nan)
        ratio = ratio.replace([np.inf, -np.inf], np.nan).fillna(1.0)

        mi = ratio.rolling(window=sum_period).sum()
        mi_valid = mi.dropna()
        if mi_valid.empty:
            return self._make_result(Signal.NEUTRAL, "Mass Index data insufficient")

        mi_last = float(mi_valid.iloc[-1])

        # Look for a bulge in the recent (trailing) window of Mass Index values.
        lookback = min(reversal_lookback, len(mi_valid))
        recent = mi_valid.iloc[-lookback:]
        bulge_peak = float(recent.max())

        # Reversal trigger: bulge occurred (peak >= threshold) AND the index has
        # since retreated below the setback level on the current closed bar.
        triggered = (bulge_peak >= bulge_threshold) and (mi_last < setback_threshold)
        strong_bulge = bulge_peak >= strong_bulge_threshold

        # Direction filter: slope of a short EMA of close, looking strictly back.
        ema_close = close.ewm(span=dir_ema_period, adjust=False).mean()
        dl = min(dir_lookback, n - 1)
        slope = float(ema_close.iloc[-1] - ema_close.iloc[-1 - dl])
        if slope > 0:
            trend = "UP"
        elif slope < 0:
            trend = "DOWN"
        else:
            trend = "FLAT"

        raw: dict[str, Any] = {
            "mass_index": round(mi_last, 3),
            "bulge_peak": round(bulge_peak, 3),
            "bulge_threshold": bulge_threshold,
            "setback_threshold": setback_threshold,
            "triggered": bool(triggered),
            "trend": trend,
            "slope": round(slope, 6),
        }

        if triggered and trend == "UP":
            # Rising price into a bulge -> reversal top -> bearish.
            if strong_bulge:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Reversal bulge (peak {bulge_peak:.2f}) after uptrend -> top",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"Reversal bulge (peak {bulge_peak:.2f}) after uptrend -> top",
                raw,
            )

        if triggered and trend == "DOWN":
            # Falling price into a bulge -> reversal bottom -> bullish.
            if strong_bulge:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Reversal bulge (peak {bulge_peak:.2f}) after downtrend -> bottom",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"Reversal bulge (peak {bulge_peak:.2f}) after downtrend -> bottom",
                raw,
            )

        if triggered:  # trend FLAT
            return self._make_result(
                Signal.NEUTRAL,
                f"Reversal bulge (peak {bulge_peak:.2f}) but no trend to reverse",
                raw,
            )

        if mi_last >= bulge_threshold:
            return self._make_result(
                Signal.NEUTRAL,
                f"Mass Index {mi_last:.2f} bulging - awaiting setback below "
                f"{setback_threshold:.1f}",
                raw,
            )

        return self._make_result(
            Signal.NEUTRAL,
            f"Mass Index {mi_last:.2f} - no reversal bulge",
            raw,
        )
