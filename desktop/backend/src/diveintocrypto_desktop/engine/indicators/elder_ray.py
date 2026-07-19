"""Elder-Ray Bull/Bear Power indicator.

Concept #16 for the Dive Into Crypto engine.

Elder-Ray (Dr. Alexander Elder, "Trading for a Living", 1993) measures the
raw force of buyers and sellers *relative to a trend baseline* rather than the
close alone. A single EMA of the close is treated as the market's consensus of
value. Each candle then yields two "power" readings:

    Bull Power = high - EMA(close)   # how far bulls pushed price above value
    Bear Power = low  - EMA(close)   # how far bears pushed price below value

The classic edge is a *pullback-exhaustion* entry, gated by the EMA trend:

    - Long  : EMA rising  AND Bear Power < 0 but turning up
              (bears failing to push deeper inside an uptrend -> pullback done)
    - Short : EMA falling AND Bull Power > 0 but turning down
              (bulls failing to push higher inside a downtrend -> rally done)

This module keeps that discipline: it fires only on the trend-aligned pullback
(and its stronger, deeper-recovery variant) and otherwise stays NEUTRAL, so it
contributes a *timing* view (mean-reversion within a trend) rather than yet
another trend-continuation vote.

Strictly causal: EMA uses pandas ewm(adjust=False) (past + current only), every
read is on closed candles at index -1 / -2 / -(1+slope_lookback). No future
rows, no repainting.
"""

from typing import Any

import numpy as np  # noqa: F401  (imported per engine convention; parity with siblings)
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class ElderRayIndicator(BaseIndicator):
    """Elder-Ray Bull/Bear Power around an EMA value baseline."""

    @property
    def name(self) -> str:
        return "elder_ray"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        # --- parameters -----------------------------------------------------
        ema_period = int(self.thresholds.get("ema_period", 13))
        slope_lookback = int(self.thresholds.get("slope_lookback", 3))
        # EMA slope smaller than this (fraction of price) counts as "flat".
        flat_slope_pct = float(self.thresholds.get("flat_slope_pct", 0.0005))
        # |power| as a fraction of price that qualifies a move as "strong".
        strong_power_pct = float(self.thresholds.get("strong_power_pct", 0.01))

        # --- guard ----------------------------------------------------------
        min_bars = ema_period + slope_lookback + 2
        if len(df) < min_bars:
            return self._make_result(
                Signal.NEUTRAL, f"insufficient data (<{min_bars} candles)"
            )

        high = df["high"]
        low = df["low"]
        close = df["close"]

        ema = close.ewm(span=ema_period, adjust=False).mean()

        # Bull/Bear power series (causal: elementwise high/low minus same-bar EMA).
        bull_power = high - ema
        bear_power = low - ema

        ema_now = float(ema.iloc[-1])
        ema_ref = float(ema.iloc[-1 - slope_lookback])
        if ema_now == 0 or ema_ref == 0 or pd.isna(ema_now) or pd.isna(ema_ref):
            return self._make_result(Signal.NEUTRAL, "EMA baseline unavailable")

        cur_bull = float(bull_power.iloc[-1])
        cur_bear = float(bear_power.iloc[-1])
        prev_bull = float(bull_power.iloc[-2])
        prev_bear = float(bear_power.iloc[-2])

        # Trend from EMA slope over slope_lookback bars (fraction of price).
        slope_pct = (ema_now - ema_ref) / ema_ref
        up = slope_pct > flat_slope_pct
        down = slope_pct < -flat_slope_pct

        # Power dynamics (direction of change vs prior closed candle).
        bear_rising = cur_bear > prev_bear
        bear_falling = cur_bear < prev_bear
        bull_rising = cur_bull > prev_bull
        bull_falling = cur_bull < prev_bull

        # Normalise powers to fractions of price for scale-free thresholds.
        bull_frac = cur_bull / ema_now
        bear_frac = cur_bear / ema_now

        raw: dict[str, Any] = {
            "ema": round(ema_now, 4),
            "bull_power": round(cur_bull, 4),
            "bear_power": round(cur_bear, 4),
            "bull_frac": round(bull_frac, 5),
            "bear_frac": round(bear_frac, 5),
            "slope_pct": round(slope_pct, 5),
        }

        # --- bullish pullback exhaustion inside an uptrend ------------------
        if up and cur_bear < 0 and bear_rising:
            if bull_rising and bull_frac >= strong_power_pct:
                return self._make_result(
                    Signal.STRONG_BUY,
                    "Uptrend + bears exhausting (bear power rising from below 0) "
                    "with strong bull thrust",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                "Uptrend + bear power turning up from below baseline "
                "(pullback exhausting)",
                raw,
            )

        # --- bearish rally exhaustion inside a downtrend -------------------
        if down and cur_bull > 0 and bull_falling:
            if bear_falling and bear_frac <= -strong_power_pct:
                return self._make_result(
                    Signal.STRONG_SELL,
                    "Downtrend + bulls exhausting (bull power falling from above 0) "
                    "with strong bear thrust",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                "Downtrend + bull power turning down from above baseline "
                "(rally exhausting)",
                raw,
            )

        # --- otherwise: no disciplined Elder-Ray entry ---------------------
        return self._make_result(
            Signal.NEUTRAL, "No trend-aligned power reversal", raw
        )
