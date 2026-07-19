"""KST (Know Sure Thing) indicator.

Martin Pring's Know Sure Thing is a momentum oscillator built from four
Rate-of-Change (ROC) series of increasing length, each smoothed with a simple
moving average and then combined in a weighted sum that deliberately favours the
longer-term horizons. A signal line (SMA of the KST) provides the trigger.

    RCMA_i = SMA( ROC(close, roc_i), sma_i )
    KST    = 1*RCMA1 + 2*RCMA2 + 3*RCMA3 + 4*RCMA4
    Signal = SMA(KST, signal_period)

Defaults are Pring's canonical set: ROC 10/15/20/30, SMA 10/10/10/15,
weights 1/2/3/4, signal 9.

Signal mapping (Pring methodology):
    * A KST/Signal crossover is the primary trigger.
    * A bullish crossover that occurs while KST is still below zero catches the
      momentum turn early (reversal from a washed-out state) -> STRONG_BUY.
      A bullish crossover already above zero is trend continuation -> BUY.
      Symmetric logic for the short side.
    * With no fresh crossover, the sustained regime (KST vs Signal) combined with
      the zero line and KST slope grades BUY / SELL / NEUTRAL.

Strictly causal: every term (ROC via close.shift, SMA via trailing rolling mean,
weighted sum, signal SMA) uses only candles up to and including the current bar.
The decision reads only the last two fully-formed values (iloc[-1], iloc[-2]).
No future rows, no centred windows, no full-series extrema -> no repainting.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class KSTIndicator(BaseIndicator):
    """Know Sure Thing: weighted sum of four smoothed ROCs plus a signal line."""

    @property
    def name(self) -> str:
        return "kst"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        # --- Parameters (Pring canonical defaults) ---
        roc1 = int(self.thresholds.get("roc1_period", 10))
        roc2 = int(self.thresholds.get("roc2_period", 15))
        roc3 = int(self.thresholds.get("roc3_period", 20))
        roc4 = int(self.thresholds.get("roc4_period", 30))
        sma1 = int(self.thresholds.get("sma1_period", 10))
        sma2 = int(self.thresholds.get("sma2_period", 10))
        sma3 = int(self.thresholds.get("sma3_period", 10))
        sma4 = int(self.thresholds.get("sma4_period", 15))
        w1 = float(self.thresholds.get("weight1", 1.0))
        w2 = float(self.thresholds.get("weight2", 2.0))
        w3 = float(self.thresholds.get("weight3", 3.0))
        w4 = float(self.thresholds.get("weight4", 4.0))
        signal_period = int(self.thresholds.get("signal_period", 9))

        close = df["close"]

        # Minimum bars for the last KST *and* its signal line to be fully formed,
        # plus one extra bar so iloc[-2] is also valid (crossover detection).
        min_required = max(
            roc1 + sma1,
            roc2 + sma2,
            roc3 + sma3,
            roc4 + sma4,
        ) + signal_period + 1
        if len(close) < min_required:
            return self._make_result(Signal.NEUTRAL, "KST data insufficient")

        def _roc(period: int) -> pd.Series:
            prev = close.shift(period)
            return (close - prev) / prev * 100.0

        # Smoothed rate-of-change moving averages (trailing SMAs -> causal).
        rcma1 = _roc(roc1).rolling(window=sma1).mean()
        rcma2 = _roc(roc2).rolling(window=sma2).mean()
        rcma3 = _roc(roc3).rolling(window=sma3).mean()
        rcma4 = _roc(roc4).rolling(window=sma4).mean()

        kst = w1 * rcma1 + w2 * rcma2 + w3 * rcma3 + w4 * rcma4
        signal_line = kst.rolling(window=signal_period).mean()

        kst_now = kst.iloc[-1]
        kst_prev = kst.iloc[-2]
        sig_now = signal_line.iloc[-1]
        sig_prev = signal_line.iloc[-2]

        if (
            pd.isna(kst_now)
            or pd.isna(kst_prev)
            or pd.isna(sig_now)
            or pd.isna(sig_prev)
        ):
            return self._make_result(Signal.NEUTRAL, "KST data insufficient")

        raw = {
            "kst": round(float(kst_now), 4),
            "signal": round(float(sig_now), 4),
            "hist": round(float(kst_now - sig_now), 4),
        }

        bullish_cross = kst_prev <= sig_prev and kst_now > sig_now
        bearish_cross = kst_prev >= sig_prev and kst_now < sig_now
        rising = kst_now > kst_prev

        if bullish_cross:
            if kst_now < 0:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"KST bullish crossover below zero (KST={kst_now:.2f})",
                    raw,
                )
            return self._make_result(
                Signal.BUY,
                f"KST bullish crossover above zero (KST={kst_now:.2f})",
                raw,
            )
        if bearish_cross:
            if kst_now > 0:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"KST bearish crossover above zero (KST={kst_now:.2f})",
                    raw,
                )
            return self._make_result(
                Signal.SELL,
                f"KST bearish crossover below zero (KST={kst_now:.2f})",
                raw,
            )

        # No fresh crossover: grade the sustained regime.
        if kst_now > sig_now:
            if kst_now > 0:
                return self._make_result(
                    Signal.BUY, f"KST above signal and above zero (KST={kst_now:.2f})", raw
                )
            if rising:
                return self._make_result(
                    Signal.BUY, f"KST above signal, recovering below zero (KST={kst_now:.2f})", raw
                )
            return self._make_result(
                Signal.NEUTRAL, f"KST above signal but weak below zero (KST={kst_now:.2f})", raw
            )
        if kst_now < sig_now:
            if kst_now < 0:
                return self._make_result(
                    Signal.SELL, f"KST below signal and below zero (KST={kst_now:.2f})", raw
                )
            if not rising:
                return self._make_result(
                    Signal.SELL, f"KST below signal, fading above zero (KST={kst_now:.2f})", raw
                )
            return self._make_result(
                Signal.NEUTRAL, f"KST below signal but firm above zero (KST={kst_now:.2f})", raw
            )

        return self._make_result(Signal.NEUTRAL, f"KST flat at signal (KST={kst_now:.2f})", raw)
