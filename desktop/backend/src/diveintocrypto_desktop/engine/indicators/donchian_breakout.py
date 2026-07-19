"""Donchian Channel Breakout indicator (turtle N-bar high/low break).

Momentum / trend-following breakout system in the spirit of the original
Turtle Traders. It builds two Donchian channels from *prior-bar* price
extremes -- a wide entry channel (N bars) and a narrower confirmation
channel (M bars) -- and grades where the most recent close sits relative
to those channels:

    upper_N = max(high[i-N .. i-1])      lower_N = min(low[i-N .. i-1])
    upper_M = max(high[i-M .. i-1])      lower_M = min(low[i-M .. i-1])

A close that pushes *beyond* a channel is a breakout. Breaking the wide
N-channel is the strongest event (a genuine N-bar new high/low); breaking
only the narrower M-channel is a weaker, developing breakout.

Crucially the channels are computed on bars strictly BEFORE the current
one (rolling().shift(1)), so the barrier the close must clear does not
contain the close itself. This makes the breakout test meaningful and
strictly causal / non-repainting.

Uses only numpy / pandas.
"""

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class DonchianBreakoutIndicator(BaseIndicator):
    """Turtle-style Donchian channel breakout (dual entry/exit channel)."""

    @property
    def name(self) -> str:
        return "donchian_breakout"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        # Entry channel length (classic Turtle System-1 uses 20).
        period = int(self.thresholds.get("period", 20))
        # Confirmation / exit channel length (Turtle uses 10). Must be < period.
        exit_period = int(self.thresholds.get("exit_period", 10))
        # Optional fractional buffer a close must exceed a channel by to count
        # as a break (0.0 = pure turtle break). e.g. 0.001 = 0.1%.
        buffer_pct = float(self.thresholds.get("breakout_buffer_pct", 0.0))

        if exit_period >= period:
            exit_period = max(1, period // 2)

        high = df["high"]
        low = df["low"]
        close = df["close"]

        n = len(df)
        # Need `period` prior bars for the wide channel plus the current bar.
        if n < period + 1:
            return self._make_result(Signal.NEUTRAL, "Donchian data insufficient")

        # Channels built ONLY from bars strictly before the current one:
        # rolling(w) at i aggregates bars [i-w+1 .. i]; .shift(1) moves it to
        # [i-w .. i-1], i.e. the w bars immediately preceding bar i.
        upper_n = high.rolling(window=period).max().shift(1)
        lower_n = low.rolling(window=period).min().shift(1)
        upper_m = high.rolling(window=exit_period).max().shift(1)
        lower_m = low.rolling(window=exit_period).min().shift(1)

        c = float(close.iloc[-1])
        u_n = upper_n.iloc[-1]
        l_n = lower_n.iloc[-1]
        u_m = upper_m.iloc[-1]
        l_m = lower_m.iloc[-1]

        if pd.isna(u_n) or pd.isna(l_n) or pd.isna(u_m) or pd.isna(l_m):
            return self._make_result(Signal.NEUTRAL, "Donchian data insufficient")

        u_n = float(u_n)
        l_n = float(l_n)
        u_m = float(u_m)
        l_m = float(l_m)
        mid_n = (u_n + l_n) / 2.0

        # Buffered breakout barriers.
        up_barrier_n = u_n * (1.0 + buffer_pct)
        lo_barrier_n = l_n * (1.0 - buffer_pct)
        up_barrier_m = u_m * (1.0 + buffer_pct)
        lo_barrier_m = l_m * (1.0 - buffer_pct)

        width_n = u_n - l_n
        position = (c - l_n) / width_n if width_n > 0 else 0.5

        raw = {
            "period": period,
            "exit_period": exit_period,
            "upper": round(u_n, 6),
            "lower": round(l_n, 6),
            "mid": round(mid_n, 6),
            "exit_upper": round(u_m, 6),
            "exit_lower": round(l_m, 6),
            "close": round(c, 6),
            "position": round(position, 4),
        }

        # Ordered, monotonic 5-level map. Because the N-window contains the
        # M-window, u_n >= u_m and l_n <= l_m, so breaking the N barrier is
        # strictly rarer/stronger than breaking only the M barrier.
        if c > up_barrier_n:
            return self._make_result(
                Signal.STRONG_BUY,
                f"Breakout above {period}-bar high {u_n:.4f} (pos={position:.2f})",
                raw,
            )
        if c > up_barrier_m:
            return self._make_result(
                Signal.BUY,
                f"Breakout above {exit_period}-bar high {u_m:.4f}, approaching {period}-bar high",
                raw,
            )
        if c < lo_barrier_n:
            return self._make_result(
                Signal.STRONG_SELL,
                f"Breakdown below {period}-bar low {l_n:.4f} (pos={position:.2f})",
                raw,
            )
        if c < lo_barrier_m:
            return self._make_result(
                Signal.SELL,
                f"Breakdown below {exit_period}-bar low {l_m:.4f}, approaching {period}-bar low",
                raw,
            )

        side = "upper" if c >= mid_n else "lower"
        return self._make_result(
            Signal.NEUTRAL,
            f"Inside Donchian channel ({side} half, pos={position:.2f})",
            raw,
        )
