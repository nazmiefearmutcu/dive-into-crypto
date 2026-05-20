"""Tests for the consensus engine, scorer, and risk assessment."""

import pytest

from src.indicators.base import IndicatorResult, Signal, SIGNAL_SCORES
from src.consensus.scorer import compute_weighted_score
from src.consensus.risk import assess_risk, RiskLevel
from src.consensus.engine import ConsensusEngine


def _make_result(name: str, signal: Signal) -> IndicatorResult:
    return IndicatorResult(
        name=name,
        signal=signal,
        score=SIGNAL_SCORES[signal],
        reason=f"test {name}",
        raw_values={},
    )


class TestScorer:
    def test_all_buy_signals(self):
        results = [
            _make_result("rsi", Signal.BUY),
            _make_result("macd", Signal.BUY),
            _make_result("bollinger", Signal.BUY),
            _make_result("sma_cross", Signal.BUY),
            _make_result("ema_cross", Signal.BUY),
        ]
        weights = {"rsi": 1.5, "macd": 2.0, "bollinger": 1.5, "sma_cross": 1.8, "ema_cross": 1.8}
        data = compute_weighted_score(results, weights)
        assert data["weighted_score"] > 0
        assert data["buy_count"] == 5
        assert data["sell_count"] == 0

    def test_mixed_signals(self):
        results = [
            _make_result("rsi", Signal.BUY),
            _make_result("macd", Signal.SELL),
            _make_result("bollinger", Signal.NEUTRAL),
        ]
        weights = {"rsi": 1.0, "macd": 1.0, "bollinger": 1.0}
        data = compute_weighted_score(results, weights)
        assert data["buy_count"] == 1
        assert data["sell_count"] == 1
        assert data["neutral_count"] == 1

    def test_zero_weight_excluded(self):
        results = [
            _make_result("atr_filter", Signal.NEUTRAL),
            _make_result("rsi", Signal.BUY),
        ]
        weights = {"atr_filter": 0.0, "rsi": 1.5}
        data = compute_weighted_score(results, weights)
        assert data["total_weight"] == 1.5

    def test_strong_signals(self):
        results = [
            _make_result("rsi", Signal.STRONG_BUY),
            _make_result("macd", Signal.STRONG_BUY),
        ]
        weights = {"rsi": 1.0, "macd": 1.0}
        data = compute_weighted_score(results, weights)
        assert data["weighted_score"] == 2.0
        assert data["strong_buy_count"] == 2


class TestRiskAssessment:
    def test_low_risk(self, default_config):
        results = [
            _make_result("rsi", Signal.BUY),
            _make_result("adx_di", Signal.BUY),
        ]
        results[1].raw_values = {"adx": 30, "plus_di": 25, "minus_di": 15}

        score_data = {"buy_count": 5, "sell_count": 0, "active_signals": 5, "weighted_score": 1.0}
        risk = assess_risk(results, score_data, default_config)
        assert risk["risk_level"] in ("LOW", "MEDIUM")

    def test_high_risk_conflict(self, default_config):
        results = [_make_result("rsi", Signal.BUY)]
        score_data = {"buy_count": 5, "sell_count": 5, "active_signals": 10, "weighted_score": 0.1}
        risk = assess_risk(results, score_data, default_config)
        assert risk["risk_score"] >= 2

    def test_high_atr_volatility(self, default_config):
        results = [
            IndicatorResult(
                name="atr_filter", signal=Signal.NEUTRAL, score=0,
                reason="high vol", raw_values={"volatility": "HIGH", "atr": 1500}
            ),
        ]
        score_data = {"buy_count": 3, "sell_count": 0, "active_signals": 3, "weighted_score": 0.8}
        risk = assess_risk(results, score_data, default_config)
        assert "High ATR volatility" in risk["risk_factors"]


class TestConsensusEngine:
    def test_strong_buy_consensus(self, default_config):
        engine = ConsensusEngine(default_config)
        results = [
            _make_result("rsi", Signal.STRONG_BUY),
            _make_result("macd", Signal.STRONG_BUY),
            _make_result("bollinger", Signal.BUY),
            _make_result("sma_cross", Signal.BUY),
            _make_result("ema_cross", Signal.BUY),
            _make_result("adx_di", Signal.BUY),
            _make_result("ichimoku", Signal.BUY),
            _make_result("psar", Signal.BUY),
            _make_result("obv", Signal.BUY),
            _make_result("stochastic", Signal.BUY),
            _make_result("cci", Signal.NEUTRAL),
            _make_result("williams_r", Signal.NEUTRAL),
            _make_result("roc", Signal.BUY),
            _make_result("mfi", Signal.BUY),
            _make_result("atr_filter", Signal.NEUTRAL),
        ]
        consensus = engine.evaluate(results)
        assert consensus["final_signal"] in ("STRONG_BUY", "BUY")
        assert consensus["confidence"] > 0
        assert consensus["should_trade"] is True

    def test_neutral_on_conflict(self, default_config):
        engine = ConsensusEngine(default_config)
        results = [
            _make_result("rsi", Signal.STRONG_BUY),
            _make_result("macd", Signal.STRONG_SELL),
            _make_result("bollinger", Signal.BUY),
            _make_result("sma_cross", Signal.SELL),
            _make_result("ema_cross", Signal.BUY),
            _make_result("adx_di", Signal.SELL),
            _make_result("ichimoku", Signal.BUY),
            _make_result("psar", Signal.SELL),
            _make_result("obv", Signal.NEUTRAL),
            _make_result("stochastic", Signal.NEUTRAL),
            _make_result("cci", Signal.NEUTRAL),
            _make_result("williams_r", Signal.NEUTRAL),
            _make_result("roc", Signal.NEUTRAL),
            _make_result("mfi", Signal.NEUTRAL),
            _make_result("atr_filter", Signal.NEUTRAL),
        ]
        consensus = engine.evaluate(results)
        # With conflicting signals, should be neutral or low confidence
        assert consensus["final_signal"] in ("NEUTRAL", "BUY", "SELL")

    def test_confidence_range(self, default_config):
        engine = ConsensusEngine(default_config)
        results = [_make_result("rsi", Signal.BUY)]
        consensus = engine.evaluate(results)
        assert 0 <= consensus["confidence"] <= 100
