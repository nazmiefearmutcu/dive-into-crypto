"""Connors RSI (CRSI) indicator.

Connors RSI (Larry Connors) is a composite short-term mean-reversion oscillator
that blends three orthogonal views of price into a single 0..100 line:

    CRSI = ( RSI(close, rsi_period)              # classic price momentum, very fast
           + RSI(streak, streak_period)          # RSI of the up/down run-length (streak)
           + PercentRank(ret1, rank_period) ) / 3 # empirical percentile of the 1-bar return

Component notes
---------------
1. RSI(3) on price      -> a very fast momentum reading (over-reacts, mean-reverts fast).
2. RSI(2) on the streak -> the "streak" is the signed count of consecutive higher/lower
                           closes (+1,+2,+3 for an up run; -1,-2 for a down run; 0 on a flat
                           bar). Applying RSI to it measures how *stretched* the current run
                           is versus recent runs -> run exhaustion, a nonlinear feature that
                           plain price RSI cannot see.
3. PercentRank of the   -> the percentile rank of today's single-bar return inside the trailing
   1-bar return            distribution of prior returns. This is a distribution-relative /
                           empirical-CDF transform (unitless, regime-adaptive): it asks "how
                           unusual is this move vs its own recent history", not "how big".

The three legs are averaged into CRSI, which behaves like a hyper-fast RSI: it spends most of
its time mid-range and only briefly punches into the extremes, where Connors' canonical
mean-reversion triggers live (< 10 oversold / > 90 overbought).

Implementation is intentionally consistent with this repo's ``rsi.py`` (simple rolling-mean
RSI, with the same 50.0 / 100.0 edge handling) so the RSI leg is hand-verifiable and the whole
indicator stays internally cohesive.

Strictly causal / no repainting: every component at bar *i* is a backward-looking function of
closes up to and including bar *i* (rolling means, a cumulative streak, and a percentile over
*prior* returns). The emitted signal reads only ``iloc[-1]``; no future rows are touched.
"""

import numpy as np
import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal


class ConnorsRSIIndicator(BaseIndicator):
    """Connors RSI: composite RSI(price) + RSI(streak) + PercentRank(return)."""

    @property
    def name(self) -> str:
        return "connors_rsi"

    # --- component helpers (all strictly causal / backward-looking) ---

    @staticmethod
    def _rsi(series: pd.Series, period: int) -> pd.Series:
        """Simple rolling-mean RSI, matching this repo's rsi.py conventions.

        Edge handling identical to rsi.py: warm-up -> NaN, flat window -> 50.0,
        all-gains window -> 100.0.
        """
        delta = series.diff()
        gain = delta.where(delta > 0, 0.0)
        loss = (-delta).where(delta < 0, 0.0)

        avg_gain = gain.rolling(window=period, min_periods=period).mean()
        avg_loss = loss.rolling(window=period, min_periods=period).mean()

        is_nan = avg_gain.isna() | avg_loss.isna()
        both_zero = (avg_gain == 0.0) & (avg_loss == 0.0)
        loss_zero = (avg_loss == 0.0) & (avg_gain != 0.0)

        rs = avg_gain / avg_loss.where(avg_loss != 0.0, 1.0)
        standard_rsi = 100.0 - (100.0 / (1.0 + rs))

        vals = np.select(
            condlist=[is_nan, both_zero, loss_zero],
            choicelist=[np.nan, 50.0, 100.0],
            default=standard_rsi,
        )
        return pd.Series(vals, index=series.index)

    @staticmethod
    def _streak(close: pd.Series) -> pd.Series:
        """Signed run-length of consecutive up/down closes.

        +1,+2,... for an ongoing up run; -1,-2,... for a down run; 0 on a flat bar.
        Purely cumulative over past closes -> streak[i] depends only on close[0..i].
        """
        diff = close.diff().values  # diff[0] is NaN
        out = np.zeros(len(close), dtype=float)
        s = 0.0
        for i in range(len(close)):
            d = diff[i]
            if i == 0 or np.isnan(d):
                s = 0.0
            elif d > 0:
                s = s + 1.0 if s > 0 else 1.0
            elif d < 0:
                s = s - 1.0 if s < 0 else -1.0
            else:
                s = 0.0
            out[i] = s
        return pd.Series(out, index=close.index)

    @staticmethod
    def _percent_rank_last(returns: pd.Series, rank_period: int, min_rank_period: int):
        """PercentRank of the most recent return within the trailing prior-return window.

        Connors' definition: 100 * (count of the previous ``rank_period`` returns that are
        strictly LESS than today's return) / window_size. Uses only returns strictly before
        the current bar plus the current return -> causal.

        Degrades gracefully: if fewer than ``rank_period`` prior returns are available (early
        history), it uses whatever window exists as long as it is at least ``min_rank_period``;
        otherwise returns (None, 0).
        """
        vals = returns.values
        current = vals[-1]
        if current is None or (isinstance(current, float) and np.isnan(current)):
            return None, 0
        prior = vals[:-1]
        prior = prior[~np.isnan(prior)]
        avail = len(prior)
        eff = int(min(rank_period, avail))
        if eff < min_rank_period:
            return None, eff
        window = prior[-eff:]
        pr = 100.0 * float(np.sum(window < current)) / float(eff)
        return pr, eff

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        rsi_period = self.thresholds.get("rsi_period", 3)
        streak_period = self.thresholds.get("streak_period", 2)
        rank_period = self.thresholds.get("rank_period", 100)
        min_rank_period = self.thresholds.get("min_rank_period", 20)

        strong_buy = self.thresholds.get("strong_buy", 10)
        buy = self.thresholds.get("buy", 20)
        sell = self.thresholds.get("sell", 80)
        strong_sell = self.thresholds.get("strong_sell", 90)

        close = df["close"]

        # Need enough bars for the binding percentrank window plus RSI warm-ups.
        min_rows = max(rsi_period + 1, streak_period + 2, min_rank_period + 2)
        if len(df) < min_rows:
            return self._make_result(
                Signal.NEUTRAL,
                f"Connors RSI insufficient data (need >= {min_rows} bars)",
                {"crsi": None},
            )

        # Leg 1: fast RSI of price.
        rsi_price = self._rsi(close, rsi_period)
        rsi_price_last = rsi_price.iloc[-1]

        # Leg 2: RSI of the signed streak.
        streak = self._streak(close)
        rsi_streak = self._rsi(streak, streak_period)
        rsi_streak_last = rsi_streak.iloc[-1]

        # Leg 3: percentile rank of the latest 1-bar return.
        returns = close.pct_change()
        percent_rank, rank_window = self._percent_rank_last(
            returns, rank_period, min_rank_period
        )

        if (
            pd.isna(rsi_price_last)
            or pd.isna(rsi_streak_last)
            or percent_rank is None
        ):
            return self._make_result(
                Signal.NEUTRAL, "Connors RSI component unavailable", {"crsi": None}
            )

        crsi = (float(rsi_price_last) + float(rsi_streak_last) + float(percent_rank)) / 3.0

        raw = {
            "crsi": round(crsi, 2),
            "rsi_price": round(float(rsi_price_last), 2),
            "rsi_streak": round(float(rsi_streak_last), 2),
            "percent_rank": round(float(percent_rank), 2),
            "rank_window": int(rank_window),
        }

        if crsi <= strong_buy:
            return self._make_result(
                Signal.STRONG_BUY, f"Connors RSI={crsi:.1f} extremely oversold", raw
            )
        elif crsi <= buy:
            return self._make_result(
                Signal.BUY, f"Connors RSI={crsi:.1f} oversold", raw
            )
        elif crsi >= strong_sell:
            return self._make_result(
                Signal.STRONG_SELL, f"Connors RSI={crsi:.1f} extremely overbought", raw
            )
        elif crsi >= sell:
            return self._make_result(
                Signal.SELL, f"Connors RSI={crsi:.1f} overbought", raw
            )
        else:
            return self._make_result(
                Signal.NEUTRAL, f"Connors RSI={crsi:.1f} neutral zone", raw
            )
