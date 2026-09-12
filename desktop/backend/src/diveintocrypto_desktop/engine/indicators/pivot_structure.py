"""Pivot structure (market-structure fractal) indicator.

Labels confirmed fractal swing points with `k` bars on each side (default 2):

    swing high @ j: high[j] strictly greater than the k highs before AND the
                    k highs after j (confirmation needs j + k <= last bar)
    swing low @ j:  mirrored on lows

Structure read from the two most recent confirmed swings of each kind:

    higher-high + higher-low  (HH+HL) -> BUY bias  (uptrend intact)
    lower-high  + lower-low   (LH+LL) -> SELL bias (downtrend intact)
    anything else                     -> NEUTRAL (mixed structure)

STRONG on a structure break: with a BUY bias in place, a close BELOW the last
confirmed swing low breaks the uptrend (choch) -> STRONG_SELL; with a SELL
bias, a close ABOVE the last confirmed swing high -> STRONG_BUY. Causal: only
confirmed (fully-fractaled) swings and the current close are read; the swing
labels themselves never repaint because confirmation requires k later bars.
Uses only numpy/pandas.
"""

from typing import Any

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class PivotStructureIndicator(BaseIndicator):
    """Fractal swing structure bias with break-of-structure strengthening."""

    @property
    def name(self) -> str:
        return "pivot_structure"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        k = int(self.thresholds.get("k", 2))

        n = len(df)
        # A fractal needs k bars on each side.
        if n < 2 * k + 1:
            return self._make_result(Signal.NEUTRAL, "Pivot structure data insufficient")

        high = df["high"].to_numpy(dtype=float)
        low = df["low"].to_numpy(dtype=float)
        c = float(df["close"].iloc[-1])

        swing_highs: list[float] = []
        swing_lows: list[float] = []
        # Last confirmable center index is n-1-k.
        for j in range(k, n - k):
            hi = high[j]
            lo = low[j]
            is_high = True
            is_low = True
            for d in range(1, k + 1):
                if not (hi > high[j - d] and hi > high[j + d]):
                    is_high = False
                if not (lo < low[j - d] and lo < low[j + d]):
                    is_low = False
                if not (is_high or is_low):
                    break
            if is_high:
                swing_highs.append(float(hi))
            if is_low:
                swing_lows.append(float(lo))

        if len(swing_highs) < 2 or len(swing_lows) < 2:
            return self._make_result(
                Signal.NEUTRAL,
                "Pivot structure data insufficient (<2 confirmed swings)",
            )

        last_sh, prev_sh = swing_highs[-1], swing_highs[-2]
        last_sl, prev_sl = swing_lows[-1], swing_lows[-2]

        hh = last_sh > prev_sh
        lh = last_sh < prev_sh
        hl = last_sl > prev_sl
        ll = last_sl < prev_sl

        raw: dict[str, Any] = {
            "last_swing_high": round(last_sh, 6),
            "prev_swing_high": round(prev_sh, 6),
            "last_swing_low": round(last_sl, 6),
            "prev_swing_low": round(prev_sl, 6),
            "structure_break": 0,
        }

        if hh and hl:
            raw["structure"] = "HH+HL"
            if c < last_sl:
                raw["structure_break"] = 1
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Uptrend structure broken: close {c:.4f} below last swing low "
                    f"{last_sl:.4f}",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"Uptrend structure: HH {last_sh:.4f} + HL {last_sl:.4f}",
                raw,
            )

        if lh and ll:
            raw["structure"] = "LH+LL"
            if c > last_sh:
                raw["structure_break"] = 1
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Downtrend structure broken: close {c:.4f} above last swing high "
                    f"{last_sh:.4f}",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"Downtrend structure: LH {last_sh:.4f} + LL {last_sl:.4f}",
                raw,
            )

        raw["structure"] = "MIXED"
        return self._make_result(
            Signal.NEUTRAL,
            "Mixed swing structure (no aligned bias)",
            raw,
        )
