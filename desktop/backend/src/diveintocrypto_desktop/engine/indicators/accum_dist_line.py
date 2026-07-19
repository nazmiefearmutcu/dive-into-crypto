"""Accumulation/Distribution Line (ADL) indicator.

The ADL is the running total of volume weighted by the intrabar Close Location
Value (CLV), a.k.a. the Money Flow Multiplier:

    CLV_i = ((close_i - low_i) - (high_i - close_i)) / (high_i - low_i)   in [-1, 1]
    ADL_t = sum_{i<=t} CLV_i * volume_i

A candle that closes near its high contributes +volume (accumulation); one that
closes near its low contributes -volume (distribution). Unlike a close-to-close
scheme, the ADL reads *where inside each candle's range* price settled -- so a
down-closing candle that nonetheless finished near its high still registers as
accumulation.

Two independent reads are combined here:

1. SLOPE (trend of accumulation). The raw ADL is unbounded and scales with a
   symbol's volume, so its absolute level is meaningless across symbols. We
   instead measure the *net accumulation rate* over a trailing window:

        net_clv = (ADL[-1] - ADL[-1-n]) / sum(volume over last n bars)   in [-1, 1]

   This is dimensionless and bounded: the numerator is sum(CLV_i * vol_i) and
   |CLV_i| <= 1, so |net_clv| <= 1. It is the volume-weighted average CLV over
   the window -- a directly comparable "how hard are they accumulating" number.

2. DIVERGENCE (leading reversal). Over a trailing lookback we split the window
   into an older half and a recent half and compare the price swing extreme in
   each half against the ADL value AT those same bars:
     * Bearish: price prints a higher high but the ADL prints a lower high
       (rally on fading accumulation -> distribution into strength).
     * Bullish: price prints a lower low but the ADL prints a higher low
       (sell-off on fading distribution -> accumulation into weakness).
   Divergence is the leading signal and takes priority over the slope read;
   when slope confirms the divergence direction the call escalates to STRONG.

Signal mapping (5-level):
  * Bullish divergence + net_clv > 0            -> STRONG_BUY   (leading + confirmed)
  * Bullish divergence (net_clv <= 0)           -> BUY          (leading only)
  * Bearish divergence + net_clv < 0            -> STRONG_SELL
  * Bearish divergence (net_clv >= 0)           -> SELL
  * No divergence: graded by net_clv thresholds:
        net_clv >= strong_buy   -> STRONG_BUY
        net_clv >= buy          -> BUY
        net_clv <= strong_sell  -> STRONG_SELL
        net_clv <= sell         -> SELL
        otherwise               -> NEUTRAL

Strictly causal / look-ahead-free: cumsum(), a fixed trailing window, and
argmax/argmin over already-observed bars read only the current and prior
candles. The divergence uses fixed sub-window halves (never N confirmation
bars *after* a pivot), so no pivot repaints. The emitted signal is taken at
iloc[-1]; no shift(-n), no centred windows, no future rows.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)

_EPS = 1e-12


class AccumDistLineIndicator(BaseIndicator):
    """Accumulation/Distribution Line: net-CLV slope + price/ADL swing divergence."""

    @property
    def name(self) -> str:
        return "accum_dist_line"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        slope_period = int(self.thresholds.get("slope_period", 20))
        divergence_lookback = int(self.thresholds.get("divergence_lookback", 30))
        min_pivot_pct = float(self.thresholds.get("min_pivot_pct", 0.003))
        strong_buy = float(self.thresholds.get("strong_buy", 0.25))
        buy = float(self.thresholds.get("buy", 0.08))
        sell = float(self.thresholds.get("sell", -0.08))
        strong_sell = float(self.thresholds.get("strong_sell", -0.25))

        need = max(slope_period, divergence_lookback) + 2
        if len(df) < need:
            return self._make_result(Signal.NEUTRAL, "ADL data insufficient")

        high = df["high"].astype(float)
        low = df["low"].astype(float)
        close = df["close"].astype(float)
        volume = df["volume"].astype(float)

        # --- Accumulation/Distribution Line ---
        hl_range = high - low
        # Close Location Value in [-1, 1]; flat candle (high == low) -> 0 (no bias).
        clv = ((close - low) - (high - close)) / hl_range.where(hl_range > 0.0, np.nan)
        clv = clv.fillna(0.0)
        mfv = clv * volume
        adl = mfv.cumsum()

        adl_arr = adl.to_numpy()
        close_arr = close.to_numpy()
        vol_arr = volume.to_numpy()
        n = len(adl_arr)

        current_adl = float(adl_arr[-1])
        if np.isnan(current_adl):
            return self._make_result(Signal.NEUTRAL, "ADL data insufficient")

        # --- Slope: net accumulation rate over the trailing window, bounded [-1, 1] ---
        adl_delta = current_adl - float(adl_arr[-1 - slope_period])
        vol_sum = float(vol_arr[-slope_period:].sum())
        net_clv = adl_delta / vol_sum if vol_sum > _EPS else 0.0
        net_clv = float(np.clip(net_clv, -1.0, 1.0))

        # --- Divergence: split a trailing window into older/recent halves ---
        window = min(divergence_lookback, n - 1)
        half = window // 2
        bearish_div = False
        bullish_div = False
        recent_bear_pos = -1
        recent_bull_pos = -1

        if half >= 2:
            recent_lo = n - half          # start index of recent half
            older_lo = n - 2 * half       # start index of older half

            recent_close = close_arr[recent_lo:n]
            older_close = close_arr[older_lo:recent_lo]

            # Bearish: higher price high but lower ADL high.
            r_hi = recent_lo + int(np.argmax(recent_close))
            o_hi = older_lo + int(np.argmax(older_close))
            price_hh = close_arr[r_hi] > close_arr[o_hi] * (1.0 + min_pivot_pct)
            adl_lh = adl_arr[r_hi] < adl_arr[o_hi]
            if price_hh and adl_lh:
                bearish_div = True
                recent_bear_pos = r_hi

            # Bullish: lower price low but higher ADL low.
            r_lo = recent_lo + int(np.argmin(recent_close))
            o_lo = older_lo + int(np.argmin(older_close))
            price_ll = close_arr[r_lo] < close_arr[o_lo] * (1.0 - min_pivot_pct)
            adl_hl = adl_arr[r_lo] > adl_arr[o_lo]
            if price_ll and adl_hl:
                bullish_div = True
                recent_bull_pos = r_lo

        raw: dict[str, Any] = {
            "adl": round(current_adl, 2),
            "net_clv": round(net_clv, 4),
            "slope_period": slope_period,
            "divergence": (
                "bearish" if (bearish_div and not bullish_div)
                else "bullish" if (bullish_div and not bearish_div)
                else "both" if (bearish_div and bullish_div)
                else "none"
            ),
        }

        # If both fire, defer to the divergence whose recent pivot is more recent.
        if bearish_div and bullish_div:
            if recent_bull_pos >= recent_bear_pos:
                bearish_div = False
            else:
                bullish_div = False

        # --- Divergence takes priority (leading reversal) ---
        if bullish_div:
            if net_clv > 0.0:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Bullish ADL divergence (price LL, ADL HL) confirmed by accumulation net_clv={net_clv:.2f}",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                "Bullish ADL divergence: price lower-low but ADL higher-low (hidden accumulation)",
                raw,
            )

        if bearish_div:
            if net_clv < 0.0:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Bearish ADL divergence (price HH, ADL LH) confirmed by distribution net_clv={net_clv:.2f}",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                "Bearish ADL divergence: price higher-high but ADL lower-high (hidden distribution)",
                raw,
            )

        # --- No divergence: graded slope of accumulation ---
        if net_clv >= strong_buy:
            return self._make_result(
                Signal.STRONG_BUY, f"ADL rising hard, net_clv={net_clv:.2f} (strong accumulation)", raw
            )
        if net_clv >= buy:
            return self._make_result(
                Signal.BUY, f"ADL rising, net_clv={net_clv:.2f} (accumulation)", raw
            )
        if net_clv <= strong_sell:
            return self._make_result(
                Signal.STRONG_SELL, f"ADL falling hard, net_clv={net_clv:.2f} (strong distribution)", raw
            )
        if net_clv <= sell:
            return self._make_result(
                Signal.SELL, f"ADL falling, net_clv={net_clv:.2f} (distribution)", raw
            )
        return self._make_result(
            Signal.NEUTRAL, f"ADL flat, net_clv={net_clv:.2f} (no accumulation bias)", raw
        )
