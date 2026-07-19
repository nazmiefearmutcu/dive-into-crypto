"""Ultimate Oscillator (UO) indicator.

Larry Williams' Ultimate Oscillator (1976) blends *buying pressure* normalised
by *true range* across three lookback horizons (7 / 14 / 28) with a fixed
4 : 2 : 1 weighting. The multi-horizon blend is the whole point of the design:
a single-period momentum oscillator (e.g. RSI) produces false divergences when
its lookback happens to be mis-tuned to the current swing; combining a fast, a
medium and a slow window with the fast window weighted most heavily keeps the
oscillator responsive while damping single-window whipsaw.

Per candle:
    prior_close = close[t-1]
    true_low    = min(low, prior_close)
    true_high   = max(high, prior_close)
    BP (buying pressure) = close - true_low
    TR (true range)      = true_high - true_low

Averages over each window are ratios of summed BP to summed TR:
    Avg_n = sum(BP, n) / sum(TR, n)

    UO = 100 * (4*Avg7 + 2*Avg14 + 1*Avg28) / (4 + 2 + 1)

UO is bounded in [0, 100]. It is strictly causal: BP/TR use only the current
and the immediately-prior candle, rolling sums are trailing-only, and the
emitted verdict reads exclusively the last (most recent, closed) candle. Once a
candle closes its BP/TR are frozen, so the historical series never repaints.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class UltimateOscillatorIndicator(BaseIndicator):
    """Ultimate Oscillator: weighted 7/14/28 buying-pressure vs true-range blend."""

    @property
    def name(self) -> str:
        return "ultimate_oscillator"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        short_period = int(self.thresholds.get("short_period", 7))
        mid_period = int(self.thresholds.get("mid_period", 14))
        long_period = int(self.thresholds.get("long_period", 28))
        w_short = float(self.thresholds.get("weight_short", 4.0))
        w_mid = float(self.thresholds.get("weight_mid", 2.0))
        w_long = float(self.thresholds.get("weight_long", 1.0))
        strong_buy_level = float(self.thresholds.get("strong_buy", 20.0))
        buy_level = float(self.thresholds.get("buy", 30.0))
        sell_level = float(self.thresholds.get("sell", 70.0))
        strong_sell_level = float(self.thresholds.get("strong_sell", 80.0))

        max_period = max(short_period, mid_period, long_period)
        # Longest rolling window plus one row for the prior-close shift.
        if len(df) < max_period + 1:
            return self._make_result(Signal.NEUTRAL, "Ultimate Oscillator data insufficient")

        high = df["high"].astype(float)
        low = df["low"].astype(float)
        close = df["close"].astype(float)
        prior_close = close.shift(1)

        true_low = np.minimum(low, prior_close)
        true_high = np.maximum(high, prior_close)
        bp = close - true_low          # buying pressure
        tr = true_high - true_low      # true range

        def _avg(period: int) -> pd.Series:
            bp_sum = bp.rolling(window=period).sum()
            tr_sum = tr.rolling(window=period).sum()
            # Guard flat markets (tr_sum == 0): yield NaN -> NEUTRAL fallback.
            safe_tr = tr_sum.where(tr_sum != 0.0, np.nan)
            return bp_sum / safe_tr

        avg_short = _avg(short_period)
        avg_mid = _avg(mid_period)
        avg_long = _avg(long_period)

        weight_total = w_short + w_mid + w_long
        if weight_total == 0.0:
            return self._make_result(Signal.NEUTRAL, "Ultimate Oscillator weights invalid")

        uo = 100.0 * (w_short * avg_short + w_mid * avg_mid + w_long * avg_long) / weight_total

        current_uo = uo.iloc[-1]
        prev_uo = uo.iloc[-2] if len(uo) >= 2 else current_uo

        if pd.isna(current_uo):
            return self._make_result(Signal.NEUTRAL, "Ultimate Oscillator data insufficient")

        current_uo = float(current_uo)
        raw: dict[str, Any] = {
            "uo": round(current_uo, 2),
            "prev_uo": round(float(prev_uo), 2) if not pd.isna(prev_uo) else None,
        }

        if current_uo <= strong_buy_level:
            return self._make_result(
                Signal.STRONG_BUY, f"UO={current_uo:.1f} deeply oversold across 7/14/28", raw
            )
        elif current_uo <= buy_level:
            return self._make_result(
                Signal.BUY, f"UO={current_uo:.1f} oversold buying-pressure zone", raw
            )
        elif current_uo >= strong_sell_level:
            return self._make_result(
                Signal.STRONG_SELL, f"UO={current_uo:.1f} deeply overbought across 7/14/28", raw
            )
        elif current_uo >= sell_level:
            return self._make_result(
                Signal.SELL, f"UO={current_uo:.1f} overbought buying-pressure zone", raw
            )
        else:
            return self._make_result(
                Signal.NEUTRAL, f"UO={current_uo:.1f} neutral buying-pressure balance", raw
            )
