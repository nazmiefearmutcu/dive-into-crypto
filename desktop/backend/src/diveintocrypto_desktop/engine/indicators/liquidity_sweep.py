"""Liquidity sweep (stop-hunt) indicator.

Detects a wick that PIERCES the prior N-bar swing high/low while the bar
CLOSES back inside that range — the classic stop-hunt / liquidity-grab
reversal:

    sweep of lows:  low_cur < min(low[i-N .. i-1])  AND close_cur > that low
                    -> liquidity taken below, price rejected back up = BUY
    sweep of highs: high_cur > max(high[i-N .. i-1]) AND close_cur < that high
                    -> liquidity taken above, price rejected back down = SELL

Strength grading: the sweep is STRONG when the wick penetration beyond the
swing level is at least `strong_penetration_atr` (default 0.5) x ATR
(`atr_period`, default 14, simple rolling mean of True Range — same ATR
definition as the atr_filter indicator). Causal: the swing window and the ATR
window both end at (or before) the current bar. Uses only numpy/pandas.
"""

from typing import Any

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class LiquiditySweepIndicator(BaseIndicator):
    """N-bar swing sweep with ATR-scaled penetration strength filter."""

    @property
    def name(self) -> str:
        return "liquidity_sweep"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        lookback = int(self.thresholds.get("lookback", 10))
        atr_period = int(self.thresholds.get("atr_period", 14))
        strong_pen = float(self.thresholds.get("strong_penetration_atr", 0.5))

        n = len(df)
        # Need `lookback` prior bars for the swing window plus `atr_period`
        # bars for a defined ATR on the current bar.
        if n < max(lookback, atr_period) + 1:
            return self._make_result(Signal.NEUTRAL, "Liquidity sweep data insufficient")

        high = df["high"]
        low = df["low"]
        close = df["close"]

        # Swing extremes over the `lookback` bars STRICTLY BEFORE the current
        # one (rolling(w) at i covers [i-w+1..i]; shift(1) -> [i-w..i-1]).
        swing_low = float(low.rolling(window=lookback).min().shift(1).iloc[-1])
        swing_high = float(high.rolling(window=lookback).max().shift(1).iloc[-1])

        # ATR (simple rolling mean of True Range, window ends at current bar).
        tr1 = high - low
        tr2 = (high - close.shift(1)).abs()
        tr3 = (low - close.shift(1)).abs()
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        atr = float(tr.rolling(window=atr_period).mean().iloc[-1])

        c = float(close.iloc[-1])
        l = float(low.iloc[-1])
        h = float(high.iloc[-1])

        if pd.isna(swing_low) or pd.isna(swing_high):
            return self._make_result(Signal.NEUTRAL, "Liquidity sweep data insufficient")

        # Penetration beyond the swept level (>= 0 when the wick pierces).
        low_pen = swing_low - l
        high_pen = h - swing_high

        swept_low = l < swing_low and c > swing_low
        swept_high = h > swing_high and c < swing_high

        raw: dict[str, Any] = {
            "swing_low": round(swing_low, 6),
            "swing_high": round(swing_high, 6),
            "low_penetration": round(low_pen, 6),
            "high_penetration": round(high_pen, 6),
            "atr": round(atr, 6) if not pd.isna(atr) else None,
            "strong_penetration_atr": strong_pen,
        }

        if swept_low and swept_high:
            # Both sides swept in one bar — conflicting stop-hunts, no edge.
            raw["direction"] = 0
            return self._make_result(
                Signal.NEUTRAL,
                "Both swing sides swept and rejected — conflicting liquidity grab",
                raw,
            )

        if swept_low:
            strong = (not pd.isna(atr)) and low_pen >= strong_pen * atr
            raw["direction"] = 1
            if strong:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Swept {lookback}-bar low {swing_low:.4f} by {low_pen:.4f} "
                    f"(>= {strong_pen}x ATR) and closed back above",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"Swept {lookback}-bar low {swing_low:.4f} and closed back above "
                f"(stop-hunt reversal)",
                raw,
            )

        if swept_high:
            strong = (not pd.isna(atr)) and high_pen >= strong_pen * atr
            raw["direction"] = -1
            if strong:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Swept {lookback}-bar high {swing_high:.4f} by {high_pen:.4f} "
                    f"(>= {strong_pen}x ATR) and closed back below",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"Swept {lookback}-bar high {swing_high:.4f} and closed back below "
                f"(stop-hunt reversal)",
                raw,
            )

        raw["direction"] = 0
        return self._make_result(
            Signal.NEUTRAL,
            "No sweep: price stayed inside the prior range",
            raw,
        )
