"""WaveTrend Oscillator (LazyBear).

A momentum oscillator built by measuring how far the average price (hlc3)
has stretched away from its own EMA, standardizing that stretch by the EMA
of its absolute deviation, then double-smoothing the result. Trading
signals come from the crossover of the smoothed WaveTrend line (WT1 = tci)
against its short signal line (WT2 = 4-bar SMA of WT1), gated by
overbought / oversold zones.

Reference: LazyBear, "WaveTrend Oscillator [WT]", TradingView (2014),
which itself adapts the Sedlmayr / "Market Cipher" WaveTrend construction.

Canonical Pine formula (per bar t):
    ap  = hlc3 = (high + low + close) / 3
    esa = ema(ap, n1)                       # channel EMA of price
    d   = ema(abs(ap - esa), n1)            # EMA of absolute deviation (scale)
    ci  = (ap - esa) / (0.015 * d)          # CCI-style standardized stretch
    tci = ema(ci, n2)                       # smoothed WaveTrend  -> WT1
    wt1 = tci
    wt2 = sma(wt1, signal_len)             # signal line (default 4)
Defaults: n1 (channel) = 10, n2 (average) = 21, signal_len = 4.
OB/OS levels (LazyBear defaults): obLevel1 = 60, obLevel2 = 53,
                                  osLevel1 = -60, osLevel2 = -53.

Signal mapping (crossover-centric, zone-gated):
    Bullish cross (WT1 crosses ABOVE WT2):
        WT1 <= osLevel1 (-60)        -> STRONG_BUY  (cross up from deep oversold)
        WT1 <  obLevel2 (53)         -> BUY         (reversal / momentum cross)
        WT1 >= obLevel2              -> NEUTRAL      (late cross inside overbought)
    Bearish cross (WT1 crosses BELOW WT2):
        WT1 >= obLevel1 (60)         -> STRONG_SELL (cross down from deep overbought)
        WT1 >  osLevel2 (-53)        -> SELL        (reversal / momentum cross)
        WT1 <= osLevel2              -> NEUTRAL      (late cross inside oversold)
    No cross (pre-cross zone context):
        WT1 <= osLevel1 and rising   -> BUY         (deep oversold, turning up)
        WT1 >= obLevel1 and falling  -> SELL        (deep overbought, turning down)
        otherwise                    -> NEUTRAL

Strictly causal / non-repainting: hlc3 uses only bar t's H/L/C; esa, d and
tci are pandas ewm(adjust=False) IIR filters that read current+past state
only; wt2 is a trailing 4-bar SMA. The decision consumes only WT1[-1],
WT1[-2], WT2[-1], WT2[-2] -- no forward shift, no centered window, no future
row. Once the last candle closes those values are final and never repaint.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import (
    BaseIndicator,
    IndicatorResult,
    Signal,
)


class WaveTrendIndicator(BaseIndicator):
    """LazyBear WaveTrend: EMA-CCI double-smoothed oscillator with signal cross."""

    @property
    def name(self) -> str:
        return "wavetrend"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        n1 = int(self.thresholds.get("channel_length", 10))
        n2 = int(self.thresholds.get("average_length", 21))
        signal_len = int(self.thresholds.get("signal_length", 4))
        ob1 = float(self.thresholds.get("ob_level_1", 60.0))
        ob2 = float(self.thresholds.get("ob_level_2", 53.0))
        os1 = float(self.thresholds.get("os_level_1", -60.0))
        os2 = float(self.thresholds.get("os_level_2", -53.0))

        n = len(df)
        # Need both EMAs (n1) + the tci EMA (n2) + the SMA signal (signal_len)
        # to warm up, plus one prior bar for cross detection.
        min_len = n1 + n2 + signal_len + 1
        if n < min_len:
            return self._make_result(
                Signal.NEUTRAL,
                "WaveTrend data insufficient",
                {"wt1": None, "wt2": None},
            )

        high = df["high"].astype(float)
        low = df["low"].astype(float)
        close = df["close"].astype(float)
        ap = (high + low + close) / 3.0  # hlc3, average price

        esa = ap.ewm(span=n1, adjust=False).mean()
        dev = (ap - esa).abs()
        d = dev.ewm(span=n1, adjust=False).mean()

        # ci = (ap - esa) / (0.015 * d); guard flat markets where d == 0.
        denom = 0.015 * d
        ci = (ap - esa) / denom.replace(0.0, np.nan)
        ci = ci.fillna(0.0)

        tci = ci.ewm(span=n2, adjust=False).mean()  # WT1
        wt1 = tci
        wt2 = wt1.rolling(window=signal_len, min_periods=signal_len).mean()  # signal

        wt1_now = wt1.iloc[-1]
        wt2_now = wt2.iloc[-1]
        wt1_prev = wt1.iloc[-2]
        wt2_prev = wt2.iloc[-2]

        if (
            pd.isna(wt1_now)
            or pd.isna(wt2_now)
            or pd.isna(wt1_prev)
            or pd.isna(wt2_prev)
        ):
            return self._make_result(
                Signal.NEUTRAL,
                "WaveTrend data insufficient",
                {"wt1": None, "wt2": None},
            )

        wt1_now = float(wt1_now)
        wt2_now = float(wt2_now)
        wt1_prev = float(wt1_prev)
        wt2_prev = float(wt2_prev)

        raw = {
            "wt1": round(wt1_now, 3),
            "wt2": round(wt2_now, 3),
            "diff": round(wt1_now - wt2_now, 3),
        }

        bullish_cross = wt1_prev <= wt2_prev and wt1_now > wt2_now
        bearish_cross = wt1_prev >= wt2_prev and wt1_now < wt2_now

        # --- Bullish cross ------------------------------------------------
        if bullish_cross:
            if wt1_now <= os1:
                return self._make_result(
                    Signal.STRONG_BUY,
                    f"WaveTrend bullish cross from deep oversold (WT1={wt1_now:.1f})",
                    raw,
                )
            if wt1_now < ob2:
                return self._make_result(
                    Signal.BUY,
                    f"WaveTrend bullish cross (WT1={wt1_now:.1f})",
                    raw,
                )
            return self._make_result(
                Signal.NEUTRAL,
                f"WaveTrend late bullish cross inside overbought (WT1={wt1_now:.1f})",
                raw,
            )

        # --- Bearish cross ------------------------------------------------
        if bearish_cross:
            if wt1_now >= ob1:
                return self._make_result(
                    Signal.STRONG_SELL,
                    f"WaveTrend bearish cross from deep overbought (WT1={wt1_now:.1f})",
                    raw,
                )
            if wt1_now > os2:
                return self._make_result(
                    Signal.SELL,
                    f"WaveTrend bearish cross (WT1={wt1_now:.1f})",
                    raw,
                )
            return self._make_result(
                Signal.NEUTRAL,
                f"WaveTrend late bearish cross inside oversold (WT1={wt1_now:.1f})",
                raw,
            )

        # --- No cross: pre-cross zone context -----------------------------
        rising = wt1_now > wt1_prev
        falling = wt1_now < wt1_prev
        if wt1_now <= os1 and rising:
            return self._make_result(
                Signal.BUY,
                f"WaveTrend deep oversold turning up (WT1={wt1_now:.1f})",
                raw,
            )
        if wt1_now >= ob1 and falling:
            return self._make_result(
                Signal.SELL,
                f"WaveTrend deep overbought turning down (WT1={wt1_now:.1f})",
                raw,
            )

        return self._make_result(
            Signal.NEUTRAL,
            f"WaveTrend neutral (WT1={wt1_now:.1f}, WT2={wt2_now:.1f})",
            raw,
        )
