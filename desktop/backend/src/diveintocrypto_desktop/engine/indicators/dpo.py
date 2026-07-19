"""DPO (Detrended Price Oscillator) indicator.

Isolates the cyclical component of price by subtracting a *time-displaced*
simple moving average from price. The displacement removes the SMA's inherent
lag (~half its period) so the reference baseline is phase-aligned with the price
it smooths, leaving a detrended, (near) zero-mean oscillator that exposes where
price sits inside its dominant cycle.

Causal / look-ahead-free formulation (the non-centered DPO):

    DPO[t] = close[t] - SMA_N(close)[t - displacement]
    displacement = N // 2 + 1

Both terms use only data at or before bar ``t`` (``ma.shift(+displacement)``
pulls *older* SMA values forward -- there is no negative/future shift, so the
value of a closed bar never repaints). Because raw DPO is in price units and is
therefore incomparable across symbols/timeframes, it is standardized by its own
causal rolling mean/std into a z-score, and the 5-level signal is mapped from
that z-score as a mean-reversion *cycle* oscillator (trough -> buy, peak ->
sell), with STRONG levels gated on the oscillator beginning to revert toward
zero (a formed cycle extreme rather than an accelerating one).
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class DPOIndicator(BaseIndicator):
    """Detrended Price Oscillator -- displaced-SMA cycle isolation."""

    @property
    def name(self) -> str:
        return "dpo"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 20))
        # displacement defaults to the classic N/2 + 1; allow explicit override
        disp_cfg = self.thresholds.get("displacement", None)
        displacement = int(disp_cfg) if disp_cfg is not None else (period // 2 + 1)
        lookback = int(self.thresholds.get("zscore_lookback", 100))
        min_obs = int(self.thresholds.get("min_obs", 20))
        strong_z = float(self.thresholds.get("strong_z", 2.0))
        weak_z = float(self.thresholds.get("weak_z", 1.0))

        if period < 2 or displacement < 1:
            return self._make_result(Signal.NEUTRAL, "DPO invalid params")

        # Need at least one DPO value plus a couple to compare direction.
        min_rows = period + displacement + 2
        if len(df) < min_rows:
            return self._make_result(
                Signal.NEUTRAL,
                f"DPO data insufficient ({len(df)}<{min_rows})",
            )

        close = df["close"].astype(float)

        # Causal detrended price oscillator: price minus displaced SMA.
        sma = close.rolling(window=period).mean()
        displaced_sma = sma.shift(displacement)  # positive shift -> past values only
        dpo = close - displaced_sma

        current_dpo = dpo.iloc[-1]
        current_close = close.iloc[-1]
        if pd.isna(current_dpo) or current_close == 0:
            return self._make_result(Signal.NEUTRAL, "DPO data insufficient")

        # Causal rolling standardization (window ends at the current bar).
        roll_mean = dpo.rolling(window=lookback, min_periods=min_obs).mean()
        roll_std = dpo.rolling(window=lookback, min_periods=min_obs).std(ddof=0)

        cur_mean = roll_mean.iloc[-1]
        cur_std = roll_std.iloc[-1]

        dpo_pct = 100.0 * float(current_dpo) / float(current_close)

        # Not enough dispersion / observations to standardize -> no cycle signal.
        if pd.isna(cur_std) or cur_std <= 1e-12:
            raw = {
                "dpo": round(float(current_dpo), 6),
                "dpo_pct": round(dpo_pct, 4),
                "z": 0.0,
                "period": period,
                "displacement": displacement,
            }
            return self._make_result(Signal.NEUTRAL, "DPO flat / no cycle dispersion", raw)

        z = (float(current_dpo) - float(cur_mean)) / float(cur_std)

        # Previous bar z (same causal stats) for reversion confirmation.
        prev_dpo = dpo.iloc[-2]
        prev_mean = roll_mean.iloc[-2]
        prev_std = roll_std.iloc[-2]
        if pd.isna(prev_dpo) or pd.isna(prev_std) or (prev_std is not None and float(prev_std) <= 1e-12):
            z_prev = z
        else:
            z_prev = (float(prev_dpo) - float(prev_mean)) / float(prev_std)

        raw = {
            "dpo": round(float(current_dpo), 6),
            "dpo_pct": round(dpo_pct, 4),
            "z": round(z, 3),
            "z_prev": round(z_prev, 3),
            "period": period,
            "displacement": displacement,
        }

        # Reversion toward the zero line = a cycle extreme that has formed and
        # is starting to unwind (mean-reversion trigger, avoids knife-catching).
        reverting_up = z < 0 and z > z_prev      # deep trough curling back up
        reverting_down = z > 0 and z < z_prev    # extended peak rolling over

        # Mean-reversion cycle mapping (like CCI/RSI extremes, but on a
        # detrended, phase-aligned baseline).
        if z <= -strong_z and reverting_up:
            return self._make_result(
                Signal.STRONG_BUY,
                f"DPO z={z:.2f} deep cycle trough turning up ({dpo_pct:+.2f}% vs detrended baseline)",
                raw,
            )
        elif z >= strong_z and reverting_down:
            return self._make_result(
                Signal.STRONG_SELL,
                f"DPO z={z:.2f} cycle peak rolling over ({dpo_pct:+.2f}% vs detrended baseline)",
                raw,
            )
        elif z <= -weak_z:
            return self._make_result(
                Signal.BUY,
                f"DPO z={z:.2f} below detrended baseline ({dpo_pct:+.2f}%)",
                raw,
            )
        elif z >= weak_z:
            return self._make_result(
                Signal.SELL,
                f"DPO z={z:.2f} above detrended baseline ({dpo_pct:+.2f}%)",
                raw,
            )
        else:
            return self._make_result(
                Signal.NEUTRAL,
                f"DPO z={z:.2f} within cycle band ({dpo_pct:+.2f}%)",
                raw,
            )
