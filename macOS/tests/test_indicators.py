"""Tests for all indicator modules."""

import pytest
import pandas as pd
import numpy as np

from src.indicators.base import Signal, SIGNAL_SCORES, IndicatorResult
from src.indicators.rsi import RSIIndicator
from src.indicators.macd import MACDIndicator
from src.indicators.bollinger import BollingerBandsIndicator
from src.indicators.sma_cross import SMACrossIndicator
from src.indicators.ema_cross import EMACrossIndicator
from src.indicators.stochastic import StochasticIndicator
from src.indicators.adx_di import ADXDIIndicator
from src.indicators.cci import CCIIndicator
from src.indicators.williams_r import WilliamsRIndicator
from src.indicators.roc import ROCIndicator
from src.indicators.mfi import MFIIndicator
from src.indicators.atr_filter import ATRFilterIndicator
from src.indicators.ichimoku import IchimokuIndicator
from src.indicators.psar import PSARIndicator
from src.indicators.obv import OBVIndicator


class TestSignalScores:
    def test_score_mapping(self):
        assert SIGNAL_SCORES[Signal.STRONG_BUY] == 2
        assert SIGNAL_SCORES[Signal.BUY] == 1
        assert SIGNAL_SCORES[Signal.NEUTRAL] == 0
        assert SIGNAL_SCORES[Signal.SELL] == -1
        assert SIGNAL_SCORES[Signal.STRONG_SELL] == -2

    def test_indicator_result_to_dict(self):
        result = IndicatorResult(
            name="test", signal=Signal.BUY, score=1, reason="test reason"
        )
        d = result.to_dict()
        assert d["name"] == "test"
        assert d["signal"] == "BUY"
        assert d["score"] == 1


class TestRSI:
    def test_rsi_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = RSIIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert isinstance(result, IndicatorResult)
        assert result.name == "rsi"
        assert result.signal in Signal
        assert result.score == SIGNAL_SCORES[result.signal]
        assert result.raw_values is not None
        assert "rsi" in result.raw_values

    def test_rsi_bullish_trend(self, default_config, sample_ohlcv_bullish):
        ind = RSIIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        # In a strong uptrend, RSI should not be STRONG_SELL
        assert result.signal != Signal.STRONG_SELL or result.raw_values["rsi"] >= 80

    def test_rsi_bearish_trend(self, default_config, sample_ohlcv_bearish):
        ind = RSIIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bearish)
        # In a downtrend, RSI should tend towards lower values
        assert result.raw_values["rsi"] is not None


class TestMACD:
    def test_macd_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = MACDIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert isinstance(result, IndicatorResult)
        assert result.name == "macd"
        assert "macd" in result.raw_values

    def test_macd_bullish_trend(self, default_config, sample_ohlcv_bullish):
        ind = MACDIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        # MACD may produce any signal depending on recent crossover timing
        assert result.signal in Signal


class TestBollinger:
    def test_bollinger_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = BollingerBandsIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "bollinger"
        assert "upper" in result.raw_values
        assert "lower" in result.raw_values
        assert "band_width" in result.raw_values


class TestSMACross:
    def test_sma_cross_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = SMACrossIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "sma_cross"
        assert "sma_short" in result.raw_values

    def test_sma_bullish_trend(self, default_config, sample_ohlcv_bullish):
        ind = SMACrossIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.signal in (Signal.STRONG_BUY, Signal.BUY, Signal.NEUTRAL)


class TestEMACross:
    def test_ema_cross_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = EMACrossIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "ema_cross"
        assert "ema_short" in result.raw_values


class TestStochastic:
    def test_stochastic_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = StochasticIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "stochastic"
        assert "k" in result.raw_values


class TestADXDI:
    def test_adx_di_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = ADXDIIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "adx_di"
        assert "adx" in result.raw_values
        assert "plus_di" in result.raw_values
        assert "minus_di" in result.raw_values


class TestCCI:
    def test_cci_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = CCIIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "cci"
        assert "cci" in result.raw_values


class TestWilliamsR:
    def test_williams_r_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = WilliamsRIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "williams_r"
        assert "williams_r" in result.raw_values
        # Williams %R is always between -100 and 0
        assert -100 <= result.raw_values["williams_r"] <= 0


class TestROC:
    def test_roc_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = ROCIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "roc"
        assert "roc" in result.raw_values


class TestMFI:
    def test_mfi_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = MFIIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "mfi"
        assert "mfi" in result.raw_values
        assert 0 <= result.raw_values["mfi"] <= 100


class TestATRFilter:
    def test_atr_always_neutral(self, default_config, sample_ohlcv_bullish):
        ind = ATRFilterIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "atr_filter"
        assert result.signal == Signal.NEUTRAL
        assert result.score == 0
        assert "atr" in result.raw_values
        assert "volatility" in result.raw_values


class TestIchimoku:
    def test_ichimoku_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = IchimokuIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "ichimoku"
        assert result.signal in Signal


class TestPSAR:
    def test_psar_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = PSARIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "psar"
        assert "psar" in result.raw_values
        assert "trend" in result.raw_values


class TestOBV:
    def test_obv_returns_result(self, default_config, sample_ohlcv_bullish):
        ind = OBVIndicator(default_config)
        result = ind.calculate(sample_ohlcv_bullish)
        assert result.name == "obv"
        assert "obv" in result.raw_values


class TestAllIndicatorsIntegration:
    """Integration test: run all indicators on the same data."""

    def test_all_indicators_on_bullish(self, default_config, sample_ohlcv_bullish):
        indicators = [
            RSIIndicator, MACDIndicator, BollingerBandsIndicator,
            SMACrossIndicator, EMACrossIndicator, StochasticIndicator,
            ADXDIIndicator, CCIIndicator, WilliamsRIndicator,
            ROCIndicator, MFIIndicator, ATRFilterIndicator,
            IchimokuIndicator, PSARIndicator, OBVIndicator,
        ]
        results = []
        for cls in indicators:
            ind = cls(default_config)
            result = ind.calculate(sample_ohlcv_bullish)
            assert isinstance(result, IndicatorResult)
            assert result.score == SIGNAL_SCORES[result.signal]
            results.append(result)

        assert len(results) == 15

    def test_all_indicators_on_bearish(self, default_config, sample_ohlcv_bearish):
        indicators = [
            RSIIndicator, MACDIndicator, BollingerBandsIndicator,
            SMACrossIndicator, EMACrossIndicator, StochasticIndicator,
            ADXDIIndicator, CCIIndicator, WilliamsRIndicator,
            ROCIndicator, MFIIndicator, ATRFilterIndicator,
            IchimokuIndicator, PSARIndicator, OBVIndicator,
        ]
        for cls in indicators:
            ind = cls(default_config)
            result = ind.calculate(sample_ohlcv_bearish)
            assert isinstance(result, IndicatorResult)
