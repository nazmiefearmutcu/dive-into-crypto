"""Keltner Channel Breakout indicator.

Concept #13 for the Dive Into Crypto engine.

A Keltner Channel is an EMA midline surrounded by bands set at a multiple of
the Average True Range (ATR):

    midline = EMA(close, ema_period)
    half    = multiplier * ATR(atr_period)          # ATR via Wilder's RMA
    upper   = midline + half
    lower   = midline - half

Unlike Bollinger Bands (which use the standard deviation of close-to-close
returns), the Keltner band width is driven by the *true range* (high/low/gap
aware). This makes the channel smoother and less reactive to single close-to-
close spikes.

Interpretation is deliberately the CANONICAL Keltner one -- a *momentum /
trend-following breakout* model (Linda Raschke style): a close pushing beyond
a band is read as a breakout in that direction, NOT as an over-extension to
fade. This is the opposite regime to the repo's Bollinger indicator, which
fades the bands (mean reversion). The two therefore capture different market
regimes rather than duplicating each other.

Signal mapping (channel position p = (close - EMA) / half):
    p >= +1.0                         -> STRONG_BUY  (bullish breakout above upper)
    +inner_band <= p < +1.0, slope>=0 -> BUY         (upper channel, uptrend)
    p <= -1.0                         -> STRONG_SELL (bearish breakdown below lower)
    -1.0 < p <= -inner_band, slope<=0 -> SELL        (lower channel, downtrend)
    otherwise                         -> NEUTRAL

`slope` is the EMA slope over the last `slope_lookback` candles and gates the
inner-channel BUY/SELL so a mid-channel bias must agree with the midline
direction (a trend-following filter, not present in Bollinger).

Strictly causal: EMA (ewm, adjust=False) and Wilder ATR are recursive over past
rows only; every value read is at iloc[-1] and depends solely on candles up to
and including the last one. No forward references, no repainting.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class KeltnerBreakoutIndicator(BaseIndicator):
    """EMA +/- ATR channel breakout (ATR-based, distinct from stdev Bollinger)."""

    @property
    def name(self) -> str:
        return "keltner_breakout"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        ema_period = int(self.thresholds.get("ema_period", 20))
        atr_period = int(self.thresholds.get("atr_period", 10))
        multiplier = float(self.thresholds.get("multiplier", 2.0))
        inner_band = float(self.thresholds.get("inner_band", 0.5))
        slope_lookback = int(self.thresholds.get("slope_lookback", 3))

        needed = max(ema_period, atr_period) + slope_lookback + 1
        if len(df) < needed:
            return self._make_result(Signal.NEUTRAL, "Keltner data insufficient")

        high = df["high"].astype(float)
        low = df["low"].astype(float)
        close = df["close"].astype(float)

        # --- EMA midline (recursive, causal) ---
        ema = close.ewm(span=ema_period, adjust=False).mean()

        # --- ATR via Wilder's RMA of True Range (causal) ---
        prev_close = close.shift(1)
        tr1 = high - low
        tr2 = (high - prev_close).abs()
        tr3 = (low - prev_close).abs()
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)  # first row -> high-low (NaN skipped)
        atr = tr.ewm(alpha=1.0 / atr_period, adjust=False).mean()

        cur_ema = float(ema.iloc[-1])
        cur_atr = float(atr.iloc[-1])
        cur_close = float(close.iloc[-1])

        if not np.isfinite(cur_ema) or not np.isfinite(cur_atr) or cur_atr <= 0:
            return self._make_result(Signal.NEUTRAL, "Keltner data insufficient")

        half = multiplier * cur_atr
        upper = cur_ema + half
        lower = cur_ema - half

        # Channel position: +1 == at upper band, -1 == at lower band, 0 == midline.
        position = (cur_close - cur_ema) / half if half != 0 else 0.0

        # Midline slope over the lookback window (past data only).
        past_ema = float(ema.iloc[-1 - slope_lookback])
        slope = cur_ema - past_ema

        # Was price inside the channel on the previous candle? (breakout freshness)
        prev_close_v = float(close.iloc[-2])
        prev_ema = float(ema.iloc[-2])
        prev_atr = float(atr.iloc[-2])
        prev_upper = prev_ema + multiplier * prev_atr
        prev_lower = prev_ema - multiplier * prev_atr

        raw = {
            "ema": round(cur_ema, 4),
            "upper": round(upper, 4),
            "lower": round(lower, 4),
            "atr": round(cur_atr, 4),
            "position": round(position, 4),
            "slope": round(slope, 6),
            "multiplier": multiplier,
        }

        # --- 5-level breakout mapping ---
        if position >= 1.0:
            fresh = prev_close_v <= prev_upper
            tag = "fresh breakout" if fresh else "sustained breakout"
            raw["state"] = "BREAKOUT_UP"
            return self._make_result(
                Signal.STRONG_BUY,
                f"Close above upper Keltner band ({tag}, p={position:.2f})",
                raw,
            )

        if position <= -1.0:
            fresh = prev_close_v >= prev_lower
            tag = "fresh breakdown" if fresh else "sustained breakdown"
            raw["state"] = "BREAKDOWN"
            return self._make_result(
                Signal.STRONG_SELL,
                f"Close below lower Keltner band ({tag}, p={position:.2f})",
                raw,
            )

        if position >= inner_band and slope >= 0:
            raw["state"] = "UPPER_CHANNEL_UPTREND"
            return self._make_result(
                Signal.BUY,
                f"Upper Keltner channel with rising midline (p={position:.2f})",
                raw,
            )

        if position <= -inner_band and slope <= 0:
            raw["state"] = "LOWER_CHANNEL_DOWNTREND"
            return self._make_result(
                Signal.SELL,
                f"Lower Keltner channel with falling midline (p={position:.2f})",
                raw,
            )

        raw["state"] = "INSIDE_CHANNEL"
        return self._make_result(
            Signal.NEUTRAL,
            f"Price inside Keltner channel (p={position:.2f})",
            raw,
        )
