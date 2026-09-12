"""Engulfing candlestick pattern indicator.

Classic two-bar reversal pattern. The current bar's REAL BODY (|close - open|)
fully engulfs the prior bar's body, and the two bars have opposite colours:

    bullish: current close > open, prior close < open,
             open_cur <= open_prior AND close_cur >= close_prior
    bearish: mirrored (current red engulfs prior green body)

Strength grading: the pattern is STRONG when the engulfing candle's body
carves out most of its total range (body / (high - low) >= `body_ratio`,
default 0.7) — a decisive, low-wick reversal candle rather than a marginal
engulf. Causal: reads only the last two closed bars. Uses only numpy/pandas.
"""

from typing import Any

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class EngulfingIndicator(BaseIndicator):
    """Bullish/bearish engulfing pattern with body-dominance strength filter."""

    @property
    def name(self) -> str:
        return "engulfing"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        # Minimum body/range ratio on the engulfing candle for a STRONG read.
        body_ratio_min = float(self.thresholds.get("body_ratio", 0.7))

        if len(df) < 2:
            return self._make_result(Signal.NEUTRAL, "Engulfing data insufficient (<2 candles)")

        o_prev = float(df["open"].iloc[-2])
        c_prev = float(df["close"].iloc[-2])
        o_cur = float(df["open"].iloc[-1])
        c_cur = float(df["close"].iloc[-1])
        h_cur = float(df["high"].iloc[-1])
        l_cur = float(df["low"].iloc[-1])

        body_cur = abs(c_cur - o_cur)
        body_prev = abs(c_prev - o_prev)
        range_cur = h_cur - l_cur
        # 0-range current bar cannot produce a meaningful engulf.
        ratio = body_cur / range_cur if range_cur > 0 else 0.0

        raw: dict[str, Any] = {
            "body_cur": round(body_cur, 6),
            "body_prev": round(body_prev, 6),
            "body_ratio": round(ratio, 4),
            "strong_ratio": body_ratio_min,
        }

        bullish = c_cur > o_cur and c_prev < o_prev and o_cur <= o_prev and c_cur >= c_prev
        bearish = c_cur < o_cur and c_prev > o_prev and o_cur >= o_prev and c_cur <= c_prev
        strong = body_cur > body_prev and ratio >= body_ratio_min

        if bullish:
            raw["direction"] = 1
            if strong:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Bullish engulfing, body/range={ratio:.2f} decisive",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"Bullish engulfing, body/range={ratio:.2f} marginal",
                raw,
            )
        if bearish:
            raw["direction"] = -1
            if strong:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Bearish engulfing, body/range={ratio:.2f} decisive",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"Bearish engulfing, body/range={ratio:.2f} marginal",
                raw,
            )

        raw["direction"] = 0
        return self._make_result(Signal.NEUTRAL, "No engulfing pattern", raw)
