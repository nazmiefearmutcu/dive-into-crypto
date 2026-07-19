"""TRIX indicator.

TRIX = the 1-bar rate-of-change (in percent) of a *triple* exponentially
smoothed EMA of closing price. Because the price is smoothed three times
before the derivative is taken, TRIX is a low-noise momentum oscillator that
filters out price cycles shorter than the smoothing period and centres on
zero. A signal line (EMA of TRIX) is added for MACD-style crossover reads.

Design summary
--------------
1.  ema1 = EMA(close, period)
2.  ema2 = EMA(ema1,  period)
3.  ema3 = EMA(ema2,  period)
4.  trix = 100 * (ema3_t - ema3_{t-1}) / ema3_{t-1}          # percent, per bar
5.  signal = EMA(trix, signal_period)                        # trigger line
6.  hist = trix - signal

Signal mapping (5 levels)
-------------------------
Event-first (scale-free), then regime + magnitude:

  * bullish signal-line cross (trix crosses up through signal):
        STRONG_BUY if trix > 0 (cross confirmed in positive territory)
        BUY        if trix <= 0 (early reversal, momentum still negative)
  * bearish signal-line cross (mirror):
        STRONG_SELL if trix < 0 else SELL
  * no fresh cross -> trend follow on TRIX position + slope:
        trix > +zero_band and rising:
            STRONG_BUY if trix >  strong_threshold else BUY
        trix < -zero_band and falling:
            STRONG_SELL if trix < -strong_threshold else SELL
        positive-but-fading / negative-but-recovering / |trix|<=zero_band:
            NEUTRAL

Causality / no look-ahead
-------------------------
Every EMA (adjust=False) is a purely recursive filter over past and current
bars only. TRIX at bar t uses ema3_t and ema3_{t-1}; the signal EMA of TRIX
uses only TRIX values up to t. The emitted decision reads .iloc[-1] and
.iloc[-2] exclusively - no future rows, no negative shift, no repainting.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class TRIXIndicator(BaseIndicator):
    """Triple-smoothed EMA rate-of-change momentum oscillator."""

    @property
    def name(self) -> str:
        return "trix"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 15))
        signal_period = int(self.thresholds.get("signal_period", 9))
        strong_threshold = float(self.thresholds.get("strong_threshold", 0.10))
        zero_band = float(self.thresholds.get("zero_band", 0.02))

        # Need enough bars to warm up the triple EMA meaningfully.
        if len(df) < period * 3:
            return self._make_result(Signal.NEUTRAL, "TRIX data insufficient")

        close = df["close"].astype(float)

        ema1 = close.ewm(span=period, adjust=False).mean()
        ema2 = ema1.ewm(span=period, adjust=False).mean()
        ema3 = ema2.ewm(span=period, adjust=False).mean()

        # 1-bar percent ROC of the triple-smoothed line.
        prev_ema3 = ema3.shift(1)
        trix = 100.0 * (ema3 - prev_ema3) / prev_ema3.replace(0.0, np.nan)

        signal = trix.ewm(span=signal_period, adjust=False).mean()

        current_trix = trix.iloc[-1]
        prev_trix = trix.iloc[-2] if len(trix) >= 2 else current_trix
        current_signal = signal.iloc[-1]
        prev_signal = signal.iloc[-2] if len(signal) >= 2 else current_signal

        if pd.isna(current_trix) or pd.isna(current_signal):
            return self._make_result(Signal.NEUTRAL, "TRIX data insufficient")

        hist = current_trix - current_signal
        raw = {
            "trix": round(float(current_trix), 4),
            "trix_prev": round(float(prev_trix), 4),
            "signal": round(float(current_signal), 4),
            "histogram": round(float(hist), 4),
        }

        rising = current_trix > prev_trix
        falling = current_trix < prev_trix

        bull_cross = prev_trix <= prev_signal and current_trix > current_signal
        bear_cross = prev_trix >= prev_signal and current_trix < current_signal

        # --- Dead-zone: within +/-zero_band of the zero line, TRIX oscillations
        #     (including signal-line crossovers) are noise -> stay flat. This
        #     gates every crossover below to |trix| > zero_band. ---
        if abs(current_trix) <= zero_band:
            return self._make_result(
                Signal.NEUTRAL, f"TRIX={current_trix:.4f} flat / near zero", raw
            )

        # --- Event-first: signal-line crossovers (now |trix| > zero_band) ---
        if bull_cross:
            if current_trix > 0:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"TRIX={current_trix:.4f} bullish signal cross above zero",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"TRIX={current_trix:.4f} bullish signal cross (below zero, early)",
                raw,
            )
        if bear_cross:
            if current_trix < 0:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"TRIX={current_trix:.4f} bearish signal cross below zero",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"TRIX={current_trix:.4f} bearish signal cross (above zero, early)",
                raw,
            )

        # --- Regime + slope + magnitude (no fresh cross) ---
        if current_trix > 0:  # positive regime (already > zero_band)
            if rising:
                if current_trix > strong_threshold:
                    return self._make_result(
                        Signal.STRONG_BUY,
                        f"TRIX={current_trix:.4f} strong positive momentum rising",
                        raw,
                    )
                return self._make_result(
                    Signal.BUY,
                    f"TRIX={current_trix:.4f} positive momentum rising",
                    raw,
                )
            return self._make_result(
                Signal.NEUTRAL,
                f"TRIX={current_trix:.4f} positive but momentum fading",
                raw,
            )

        # negative regime (already < -zero_band)
        if falling:
            if current_trix < -strong_threshold:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"TRIX={current_trix:.4f} strong negative momentum falling",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"TRIX={current_trix:.4f} negative momentum falling",
                raw,
            )
        return self._make_result(
            Signal.NEUTRAL,
            f"TRIX={current_trix:.4f} negative but momentum recovering",
            raw,
        )
