"""Klinger Volume Oscillator (KVO) indicator.

Concept #17 for the Dive Into Crypto engine.

Stephen Klinger's Volume Oscillator fuses *price direction* and *volume* into a
"volume force" (VF) series, then applies a MACD-style dual-EMA (34/55) with a
signal line (13). Unlike MACD/AO (price only) or OBV/CMF (raw or intrabar volume),
the VF is range-weighted and normalised by a *cumulative measurement* (cm) that
carries trend-persistence memory and resets on trend flips. That cm-normalisation
is unique among the engine's 22 indicators.

Algorithm (strictly causal, one bar = one row):

  tp[i]    = (high[i] + low[i] + close[i]) / 3            # typical price
  dm[i]    = high[i] - low[i]                             # daily range
  trend[i] = +1 if tp[i] > tp[i-1]
             -1 if tp[i] < tp[i-1]
             trend[i-1] if tp[i] == tp[i-1]  (persist; seed +1)
  cm[i]    = cm[i-1] + dm[i]     if trend[i] == trend[i-1]
             dm[i-1] + dm[i]     if trend[i] != trend[i-1]   (reset on flip)
  vf[i]    = volume[i] * |2 * (dm[i]/cm[i] - 1)| * trend[i] * 100
             (vf[i] = 0 when dm[i] == 0, i.e. a zero-range bar exerts no force)

  kvo    = EMA(vf, fast=34) - EMA(vf, slow=55)
  signal = EMA(kvo, 13)

Signal mapping (5-level) — the KVO zero line marks the money-flow regime
(kvo>0 = net accumulation force, kvo<0 = net distribution). A signal-line cross
is upgraded to STRONG only when the zero-line regime agrees:

  bullish cross (kvo crosses above signal)  & kvo > 0  -> STRONG_BUY
  bullish cross                             & kvo <= 0 -> BUY
  bearish cross (kvo crosses below signal)  & kvo < 0  -> STRONG_SELL
  bearish cross                             & kvo >= 0 -> SELL
  no cross, kvo>signal, histogram rising, kvo>0        -> BUY   (sustained accumulation)
  no cross, kvo<signal, histogram falling, kvo<0       -> SELL  (sustained distribution)
  otherwise                                            -> NEUTRAL
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class KlingerOscillatorIndicator(BaseIndicator):
    """Klinger Volume Oscillator: EMA(34)-EMA(55) of volume force + signal line."""

    @property
    def name(self) -> str:
        return "klinger_oscillator"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        fast_period = int(self.thresholds.get("fast_period", 34))
        slow_period = int(self.thresholds.get("slow_period", 55))
        signal_period = int(self.thresholds.get("signal_period", 13))

        n = len(df)
        # Need enough bars to warm the slow EMA and inspect one prior bar.
        if n < slow_period + 1:
            return self._make_result(
                Signal.NEUTRAL, "Klinger data insufficient (need > slow_period bars)"
            )

        high = df["high"].to_numpy(dtype=float)
        low = df["low"].to_numpy(dtype=float)
        close = df["close"].to_numpy(dtype=float)
        volume = df["volume"].to_numpy(dtype=float)

        tp = (high + low + close) / 3.0
        dm = high - low  # daily measurement (range), always >= 0

        # trend: signed direction of typical price, persisting on ties.
        trend = np.empty(n, dtype=float)
        trend[0] = 1.0
        for i in range(1, n):
            if tp[i] > tp[i - 1]:
                trend[i] = 1.0
            elif tp[i] < tp[i - 1]:
                trend[i] = -1.0
            else:
                trend[i] = trend[i - 1]

        # cm: cumulative measurement with reset when trend flips.
        cm = np.empty(n, dtype=float)
        cm[0] = dm[0]
        for i in range(1, n):
            if trend[i] == trend[i - 1]:
                cm[i] = cm[i - 1] + dm[i]
            else:
                cm[i] = dm[i - 1] + dm[i]

        # Volume force. Zero-range bars (dm == 0) exert no force. Guard cm == 0.
        eps = 1e-12
        ratio = np.divide(dm, cm, out=np.zeros_like(dm), where=cm > eps)
        vf = volume * np.abs(2.0 * (ratio - 1.0)) * trend * 100.0
        vf = np.where(dm <= 0.0, 0.0, vf)

        vf_series = pd.Series(vf, index=df.index)
        ema_fast = vf_series.ewm(span=fast_period, adjust=False).mean()
        ema_slow = vf_series.ewm(span=slow_period, adjust=False).mean()
        kvo = ema_fast - ema_slow
        signal_line = kvo.ewm(span=signal_period, adjust=False).mean()
        histogram = kvo - signal_line

        cur_kvo = float(kvo.iloc[-1])
        cur_sig = float(signal_line.iloc[-1])
        cur_hist = float(histogram.iloc[-1])
        prev_kvo = float(kvo.iloc[-2])
        prev_sig = float(signal_line.iloc[-2])
        prev_hist = float(histogram.iloc[-2])

        if not np.isfinite(cur_kvo) or not np.isfinite(cur_sig):
            return self._make_result(Signal.NEUTRAL, "Klinger non-finite output")

        raw = {
            "kvo": round(cur_kvo, 4),
            "signal": round(cur_sig, 4),
            "histogram": round(cur_hist, 4),
            "regime": "POS" if cur_kvo > 0 else ("NEG" if cur_kvo < 0 else "ZERO"),
        }

        bullish_cross = prev_kvo <= prev_sig and cur_kvo > cur_sig
        bearish_cross = prev_kvo >= prev_sig and cur_kvo < cur_sig

        if bullish_cross and cur_kvo > 0:
            return self._make_result(
                Signal.STRONG_BUY,
                "KVO bullish signal-line cross confirmed above zero (accumulation)",
                raw,
            )
        if bullish_cross:
            return self._make_result(
                Signal.BUY, "KVO bullish signal-line cross (below zero, unconfirmed)", raw
            )
        if bearish_cross and cur_kvo < 0:
            return self._make_result(
                Signal.STRONG_SELL,
                "KVO bearish signal-line cross confirmed below zero (distribution)",
                raw,
            )
        if bearish_cross:
            return self._make_result(
                Signal.SELL, "KVO bearish signal-line cross (above zero, unconfirmed)", raw
            )
        if cur_kvo > cur_sig and cur_hist > prev_hist and cur_kvo > 0:
            return self._make_result(
                Signal.BUY, "KVO above signal, histogram expanding, positive regime", raw
            )
        if cur_kvo < cur_sig and cur_hist < prev_hist and cur_kvo < 0:
            return self._make_result(
                Signal.SELL, "KVO below signal, histogram expanding, negative regime", raw
            )
        return self._make_result(Signal.NEUTRAL, "KVO indecisive", raw)
