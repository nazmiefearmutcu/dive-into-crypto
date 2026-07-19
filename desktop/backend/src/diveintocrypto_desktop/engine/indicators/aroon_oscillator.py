"""Aroon Oscillator indicator.

Concept #26 for the Dive Into Crypto engine (SURFACE 1: BaseIndicator / price TA).

The Aroon system (Tushar Chande, 1995) measures a trend by the *recency* of the
extreme, not by the *magnitude* of price moves. It answers two questions:

    Aroon Up   = 100 * (period - bars_since_highest_high) / period
    Aroon Down = 100 * (period - bars_since_lowest_low)  / period

where ``bars_since_*`` is counted over the trailing window of ``period + 1``
candles (the current candle plus ``period`` prior candles). A value of 100 means
the extreme is the current candle; a value of 0 means it is the oldest candle in
the window.

The Aroon **Oscillator** collapses the two lines into a single signed series:

    osc = Aroon Up - Aroon Down            (range: -100 .. +100)

Interpretation:
    * osc  -> +100 : consecutive fresh highs, stale lows  -> strong uptrend
    * osc  -> -100 : consecutive fresh lows, stale highs   -> strong downtrend
    * osc near   0 : neither a fresh high nor a fresh low  -> consolidation
    * osc crossing zero upward   -> an uptrend is *emerging* (Up crosses above Down)
    * osc crossing zero downward -> a downtrend is *emerging*

This module maps the oscillator to the engine's 5-level Signal using a
magnitude-plus-crossover scheme: magnitude gives conviction (STRONG at the
extremes), a fresh zero-line crossover gives the *emergence* trigger that lifts a
low-magnitude reading out of NEUTRAL, and a small oscillator with no fresh
crossover is treated as no-trend (NEUTRAL).

Strictly causal: for candle ``i`` only highs/lows in ``[i - period, i]`` are read
(argmax / argmin position within a trailing window). Stateless: the result is a
pure function of the passed DataFrame. No repainting: the signal at the last
closed candle never changes when recomputed over the same candles.
"""

from typing import Any

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class AroonOscillatorIndicator(BaseIndicator):
    """Aroon Oscillator: bars-since-high vs bars-since-low; trend emergence."""

    @property
    def name(self) -> str:
        return "aroon_oscillator"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        period = int(self.thresholds.get("period", 25))
        strong_level = float(self.thresholds.get("strong_level", 70.0))
        weak_level = float(self.thresholds.get("weak_level", 30.0))

        # Need `period + 1` candles for the current oscillator value and one more
        # candle to also compute the previous value for crossover detection.
        if period < 1 or len(df) < period + 2:
            return self._make_result(
                Signal.NEUTRAL,
                f"Aroon insufficient data (need >= {max(period + 2, 3)} bars)",
            )

        highs = df["high"].to_numpy(dtype=float)
        lows = df["low"].to_numpy(dtype=float)
        n = len(highs)

        if not (np.isfinite(highs[-(period + 2):]).all()
                and np.isfinite(lows[-(period + 2):]).all()):
            return self._make_result(Signal.NEUTRAL, "Aroon data contains NaN/inf")

        up_c, down_c, osc_c = self._aroon_at(highs, lows, n - 1, period)
        up_p, down_p, osc_p = self._aroon_at(highs, lows, n - 2, period)

        bull_cross = osc_p <= 0.0 < osc_c
        bear_cross = osc_p >= 0.0 > osc_c

        raw: dict[str, Any] = {
            "aroon_up": round(up_c, 2),
            "aroon_down": round(down_c, 2),
            "oscillator": round(osc_c, 2),
            "prev_oscillator": round(osc_p, 2),
            "period": period,
        }

        # --- Bullish side --------------------------------------------------
        if osc_c > 0.0:
            if osc_c >= strong_level:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"Aroon osc={osc_c:.0f} (up={up_c:.0f}/down={down_c:.0f}) "
                    "dominant uptrend",
                    raw,
                )
            if bull_cross:
                return self._make_result(
                    Signal.BUY,
                    f"Aroon osc={osc_c:.0f} bullish zero-cross: uptrend emerging",
                    raw,
                )
            if osc_c >= weak_level:
                return self._make_result(
                    Signal.BUY,
                    f"Aroon osc={osc_c:.0f} uptrend intact",
                    raw,
                )
            return self._make_result(
                Signal.NEUTRAL,
                f"Aroon osc={osc_c:.0f} weak/undefined (no fresh cross)",
                raw,
            )

        # --- Bearish side --------------------------------------------------
        if osc_c < 0.0:
            if osc_c <= -strong_level:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"Aroon osc={osc_c:.0f} (up={up_c:.0f}/down={down_c:.0f}) "
                    "dominant downtrend",
                    raw,
                )
            if bear_cross:
                return self._make_result(
                    Signal.SELL,
                    f"Aroon osc={osc_c:.0f} bearish zero-cross: downtrend emerging",
                    raw,
                )
            if osc_c <= -weak_level:
                return self._make_result(
                    Signal.SELL,
                    f"Aroon osc={osc_c:.0f} downtrend intact",
                    raw,
                )
            return self._make_result(
                Signal.NEUTRAL,
                f"Aroon osc={osc_c:.0f} weak/undefined (no fresh cross)",
                raw,
            )

        # osc_c == 0 : Aroon Up == Aroon Down exactly -> balanced / no trend.
        return self._make_result(Signal.NEUTRAL, "Aroon osc=0 balanced", raw)

    @staticmethod
    def _aroon_at(
        highs: np.ndarray, lows: np.ndarray, end: int, period: int
    ) -> tuple[float, float, float]:
        """Aroon Up, Aroon Down and Oscillator for the candle at index ``end``.

        Uses only the trailing window ``highs/lows[end - period : end + 1]`` — the
        current candle plus ``period`` prior candles. On ties the *most recent*
        occurrence of the extreme is used (largest window index), i.e. the
        smallest "bars since", matching the standard Aroon convention.
        """
        start = end - period
        wh = highs[start:end + 1]
        wl = lows[start:end + 1]
        last = len(wh) - 1  # == period: local index of the current candle

        # Most-recent occurrence of the extreme via reversed argmax/argmin.
        h_pos = last - int(np.argmax(wh[::-1]))
        l_pos = last - int(np.argmin(wl[::-1]))

        aroon_up = 100.0 * h_pos / period
        aroon_down = 100.0 * l_pos / period
        return aroon_up, aroon_down, aroon_up - aroon_down
