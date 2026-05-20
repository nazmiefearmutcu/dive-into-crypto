"""Ichimoku Cloud indicator."""

from typing import Any
import pandas as pd

from src.indicators.base import BaseIndicator, IndicatorResult, Signal


class IchimokuIndicator(BaseIndicator):
    """Ichimoku Cloud with Tenkan/Kijun cross and cloud position analysis."""

    @property
    def name(self) -> str:
        return "ichimoku"

    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        tenkan_period = self.thresholds.get("tenkan_period", 9)
        kijun_period = self.thresholds.get("kijun_period", 26)
        senkou_b_period = self.thresholds.get("senkou_b_period", 52)

        high = df["high"]
        low = df["low"]
        close = df["close"]

        # Tenkan-sen (Conversion Line)
        tenkan = (high.rolling(window=tenkan_period).max() + low.rolling(window=tenkan_period).min()) / 2.0
        # Kijun-sen (Base Line)
        kijun = (high.rolling(window=kijun_period).max() + low.rolling(window=kijun_period).min()) / 2.0
        # Senkou Span A (Leading Span A)
        senkou_a = ((tenkan + kijun) / 2.0).shift(kijun_period)
        # Senkou Span B (Leading Span B)
        senkou_b = ((high.rolling(window=senkou_b_period).max() + low.rolling(window=senkou_b_period).min()) / 2.0).shift(kijun_period)

        current_close = close.iloc[-1]
        current_tenkan = tenkan.iloc[-1]
        current_kijun = kijun.iloc[-1]
        current_senkou_a = senkou_a.iloc[-1]
        current_senkou_b = senkou_b.iloc[-1]

        if any(pd.isna(v) for v in [current_tenkan, current_kijun, current_senkou_a, current_senkou_b]):
            return self._make_result(Signal.NEUTRAL, "Ichimoku data insufficient (need more candles)")

        prev_tenkan = tenkan.iloc[-2] if len(tenkan) >= 2 else current_tenkan
        prev_kijun = kijun.iloc[-2] if len(kijun) >= 2 else current_kijun

        cloud_top = max(current_senkou_a, current_senkou_b)
        cloud_bottom = min(current_senkou_a, current_senkou_b)

        raw = {
            "tenkan": round(current_tenkan, 2),
            "kijun": round(current_kijun, 2),
            "senkou_a": round(current_senkou_a, 2),
            "senkou_b": round(current_senkou_b, 2),
            "cloud_top": round(cloud_top, 2),
            "cloud_bottom": round(cloud_bottom, 2),
        }

        # Determine cloud position
        above_cloud = current_close > cloud_top
        below_cloud = current_close < cloud_bottom
        in_cloud = not above_cloud and not below_cloud

        # Tenkan/Kijun cross
        tk_bullish_cross = prev_tenkan <= prev_kijun and current_tenkan > current_kijun
        tk_bearish_cross = prev_tenkan >= prev_kijun and current_tenkan < current_kijun
        tenkan_above_kijun = current_tenkan > current_kijun

        signals: list[str] = []
        score = 0

        if above_cloud:
            signals.append("price above cloud (bullish)")
            score += 1
        elif below_cloud:
            signals.append("price below cloud (bearish)")
            score -= 1
        else:
            signals.append("price in cloud (neutral)")

        if tk_bullish_cross:
            signals.append("TK bullish cross")
            score += 1
        elif tk_bearish_cross:
            signals.append("TK bearish cross")
            score -= 1
        elif tenkan_above_kijun:
            score += 0.5
        else:
            score -= 0.5

        # Cloud color (future cloud direction)
        if current_senkou_a > current_senkou_b:
            signals.append("bullish cloud")
            score += 0.5
        else:
            signals.append("bearish cloud")
            score -= 0.5

        reason = "Ichimoku: " + ", ".join(signals)

        if score >= 2:
            return self._make_result(Signal.STRONG_BUY, reason, raw)
        elif score >= 0.5:
            return self._make_result(Signal.BUY, reason, raw)
        elif score <= -2:
            return self._make_result(Signal.STRONG_SELL, reason, raw)
        elif score <= -0.5:
            return self._make_result(Signal.SELL, reason, raw)
        else:
            return self._make_result(Signal.NEUTRAL, reason, raw)
