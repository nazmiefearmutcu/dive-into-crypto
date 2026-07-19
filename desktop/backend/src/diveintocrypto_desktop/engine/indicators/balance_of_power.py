"""Balance of Power (BOP) indicator.

Concept #30 for the Dive Into Crypto engine.

Balance of Power (Igor Livshin, "Stocks & Commodities", Aug 2001) measures who
*won each candle* by locating the close relative to the open, scaled by the
candle's full trading range:

    BOP_raw[i] = (close[i] - open[i]) / (high[i] - low[i])

Interpretation of a single bar:
    +1  close printed at the high after opening at the low  -> total bull control
    -1  close printed at the low  after opening at the high  -> total bear control
     0  close == open (or a zero-range bar)                  -> a standoff

The raw series is intrinsically bounded to [-1, +1] and is very noisy, so — per
Livshin and the hint — it is smoothed with a simple moving average (default 14):

    BOP[i] = SMA(BOP_raw, period)[i]

A positive smoothed BOP means that, averaged over the window, buyers repeatedly
dragged the close toward the top of each bar's range; negative means sellers
repeatedly slammed it toward the bottom. Unlike close-only momentum, BOP reads
the *intrabar conviction* (open->close travel as a share of the bar's range),
so it can reveal buyers quietly winning bars even while price grinds sideways.

Signal mapping (level bands + zero-line cross for an early read):
    STRONG_BUY   smoothed BOP >= +strong_buy      (deep, sustained buying pressure)
    BUY          smoothed BOP >= +buy             (net buying pressure)
                 or a fresh cross up through zero  (buyers taking over, early)
    SELL         smoothed BOP <= -sell            (net selling pressure)
                 or a fresh cross down through zero
    STRONG_SELL  smoothed BOP <= -strong_sell     (deep, sustained selling pressure)
    NEUTRAL      otherwise (pressure balanced near zero, no fresh cross)

Strictly causal / look-ahead-free: BOP_raw[i] depends only on OHLC of bar i; the
SMA is a trailing window [i-period+1 .. i] (past + current only); the emitted
signal reads solely iloc[-1] and iloc[-2] (closed candles). No future rows are
consulted and the value at any bar never changes when later bars arrive, so
there is no repainting.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class BalanceOfPowerIndicator(BaseIndicator):
    """Smoothed Balance of Power: intrabar (close-open)/(high-low) conviction."""

    @property
    def name(self) -> str:
        return "balance_of_power"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        # --- parameters -----------------------------------------------------
        period = int(self.thresholds.get("period", 14))
        strong_buy = float(self.thresholds.get("strong_buy", 0.20))
        buy = float(self.thresholds.get("buy", 0.05))
        sell = float(self.thresholds.get("sell", -0.05))
        strong_sell = float(self.thresholds.get("strong_sell", -0.20))

        # --- guard ----------------------------------------------------------
        # Need a full window at index -1 (period bars) plus one more bar so that
        # index -2 also has a defined smoothed value for the cross/slope read.
        min_bars = period + 1
        if len(df) < min_bars:
            return self._make_result(
                Signal.NEUTRAL, f"insufficient data (<{min_bars} candles)"
            )

        o = df["open"].to_numpy(dtype=float)
        h = df["high"].to_numpy(dtype=float)
        l = df["low"].to_numpy(dtype=float)
        c = df["close"].to_numpy(dtype=float)

        # Per-bar Balance of Power, bounded to [-1, +1].
        # Zero-range bars (high == low) carry no directional info -> 0.
        rng = h - l
        safe_rng = np.where(rng > 0, rng, 1.0)
        raw = np.where(rng > 0, (c - o) / safe_rng, 0.0)

        # Smoothed BOP (trailing SMA, causal).
        bop = pd.Series(raw, index=df.index).rolling(window=period).mean()

        cur = bop.iloc[-1]
        prev = bop.iloc[-2]
        if pd.isna(cur) or pd.isna(prev):
            return self._make_result(Signal.NEUTRAL, "BOP data insufficient")

        cur = float(cur)
        prev = float(prev)

        cross_up = prev <= 0.0 < cur
        cross_down = prev >= 0.0 > cur

        raw_values: dict[str, Any] = {
            "bop": round(cur, 4),
            "bop_prev": round(prev, 4),
            "bop_raw_last": round(float(raw[-1]), 4),
            "period": period,
        }

        # --- deep, sustained pressure --------------------------------------
        if cur >= strong_buy:
            return self._make_result(
                Signal.STRONG_BUY,
                f"BOP={cur:.3f} deep buying pressure (>= {strong_buy})",
                raw_values,
            )
        if cur <= strong_sell:
            return self._make_result(
                Signal.STRONG_SELL,
                f"BOP={cur:.3f} deep selling pressure (<= {strong_sell})",
                raw_values,
            )

        # --- moderate net pressure -----------------------------------------
        if cur >= buy:
            return self._make_result(
                Signal.BUY, f"BOP={cur:.3f} net buying pressure (>= {buy})", raw_values
            )
        if cur <= sell:
            return self._make_result(
                Signal.SELL, f"BOP={cur:.3f} net selling pressure (<= {sell})", raw_values
            )

        # --- near-zero band: fresh zero-line cross = early shift -----------
        if cross_up:
            return self._make_result(
                Signal.BUY, f"BOP crossed above zero ({prev:.3f}->{cur:.3f})", raw_values
            )
        if cross_down:
            return self._make_result(
                Signal.SELL, f"BOP crossed below zero ({prev:.3f}->{cur:.3f})", raw_values
            )

        return self._make_result(
            Signal.NEUTRAL, f"BOP={cur:.3f} balanced (no directional edge)", raw_values
        )
