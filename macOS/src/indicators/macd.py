"""MACD (Moving Average Convergence Divergence) indicator."""

from typing import Any
import pandas as pd
import numpy as np

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class MACDIndicator(BaseIndicator):
    """MACD with crossover detection and histogram momentum analysis."""

    @property
    def name(self) -> str:
        return "macd"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        fast = self.thresholds.get("fast_period", 12)
        slow = self.thresholds.get("slow_period", 26)
        signal_period = self.thresholds.get("signal_period", 9)
        strong_hist_threshold = self.thresholds.get("strong_histogram_threshold", 0.5)

        close = df["close"]
        ema_fast = close.ewm(span=fast, adjust=False).mean()
        ema_slow = close.ewm(span=slow, adjust=False).mean()
        macd_line = ema_fast - ema_slow
        signal_line = macd_line.ewm(span=signal_period, adjust=False).mean()
        histogram = macd_line - signal_line

        current_macd = macd_line.iloc[-1]
        current_signal = signal_line.iloc[-1]
        current_hist = histogram.iloc[-1]
        prev_hist = histogram.iloc[-2] if len(histogram) >= 2 else 0

        if pd.isna(current_macd) or pd.isna(current_signal):
            return self._make_result(Signal.NEUTRAL, "MACD data insufficient")

        raw = {
            "macd": round(current_macd, 4),
            "signal": round(current_signal, 4),
            "histogram": round(current_hist, 4),
        }

        # Normalize histogram threshold relative to price
        price = close.iloc[-1]
        norm_threshold = price * strong_hist_threshold / 100.0

        # Detect crossover
        prev_macd = macd_line.iloc[-2] if len(macd_line) >= 2 else current_macd
        prev_signal = signal_line.iloc[-2] if len(signal_line) >= 2 else current_signal
        bullish_cross = prev_macd <= prev_signal and current_macd > current_signal
        bearish_cross = prev_macd >= prev_signal and current_macd < current_signal

        if bullish_cross and abs(current_hist) > norm_threshold:
            return self._make_result(Signal.STRONG_BUY, "MACD bullish crossover with strong momentum", raw)
        elif bullish_cross:
            return self._make_result(Signal.BUY, "MACD bullish crossover", raw)
        elif bearish_cross and abs(current_hist) > norm_threshold:
            return self._make_result(Signal.STRONG_SELL, "MACD bearish crossover with strong momentum", raw)
        elif bearish_cross:
            return self._make_result(Signal.SELL, "MACD bearish crossover", raw)
        elif current_macd > current_signal and current_hist > prev_hist:
            if current_hist > norm_threshold:
                return self._make_result(Signal.STRONG_BUY, "MACD above signal, histogram expanding strongly", raw)
            return self._make_result(Signal.BUY, "MACD above signal, histogram expanding", raw)
        elif current_macd < current_signal and current_hist < prev_hist:
            if abs(current_hist) > norm_threshold:
                return self._make_result(Signal.STRONG_SELL, "MACD below signal, histogram expanding strongly", raw)
            return self._make_result(Signal.SELL, "MACD below signal, histogram expanding", raw)
        else:
            return self._make_result(Signal.NEUTRAL, "MACD indecisive", raw)
