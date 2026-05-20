"""Tests for the decision engine."""

import pytest

from src.trading.decision_engine import DecisionEngine
from src.trading.position_manager import PositionManager
from src.trading.order_models import TradeAction, PositionSide


@pytest.fixture
def decision_setup(default_config):
    pm = PositionManager(default_config)
    de = DecisionEngine(default_config, pm)
    return de, pm


class TestDecisionEngine:
    def test_buy_signal_opens_long(self, decision_setup, default_config):
        de, pm = decision_setup
        consensus = {
            "final_signal": "BUY",
            "confidence": 70,
            "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": True,
            "weighted_score": 0.8,
        }
        decision = de.decide("BTCUSDT", consensus, 50000.0, 10000.0, 0.0, 10000.0)
        assert decision["action"] == TradeAction.OPEN_LONG.value
        assert decision["quantity"] > 0

    def test_no_trade_when_confidence_low(self, decision_setup, default_config):
        de, pm = decision_setup
        consensus = {
            "final_signal": "BUY",
            "confidence": 20,
            "risk_level": "HIGH",
            "risk_data": {"position_size_modifier": 0.25, "risk_factors": []},
            "should_trade": False,
            "weighted_score": 0.3,
        }
        decision = de.decide("BTCUSDT", consensus, 50000.0, 10000.0, 0.0, 10000.0)
        assert decision["action"] == TradeAction.NO_ACTION.value

    def test_neutral_signal_no_action(self, decision_setup):
        de, pm = decision_setup
        consensus = {
            "final_signal": "NEUTRAL",
            "confidence": 50,
            "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": False,
            "weighted_score": 0.1,
        }
        decision = de.decide("BTCUSDT", consensus, 50000.0, 10000.0, 0.0, 10000.0)
        assert decision["action"] in (TradeAction.NO_ACTION.value, TradeAction.HOLD.value)

    def test_close_on_opposing_signal(self, decision_setup):
        de, pm = decision_setup
        # Open a long position first
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)

        consensus = {
            "final_signal": "STRONG_SELL",
            "confidence": 70,
            "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": True,
            "weighted_score": -1.5,
        }
        decision = de.decide("BTCUSDT", consensus, 49000.0, 10000.0, 0.0, 10000.0)
        assert decision["action"] == TradeAction.CLOSE_LONG.value

    def test_hold_when_aligned_signal(self, decision_setup):
        de, pm = decision_setup
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)

        consensus = {
            "final_signal": "BUY",
            "confidence": 60,
            "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": True,
            "weighted_score": 0.8,
        }
        decision = de.decide("BTCUSDT", consensus, 51000.0, 10000.0, 0.0, 10000.0)
        assert decision["action"] == TradeAction.HOLD.value

    def test_daily_loss_limit(self, default_config):
        # Force a 5% daily loss limit for this test
        cfg = {**default_config, "risk": {**default_config.get("risk", {}), "daily_loss_limit_enabled": True, "daily_loss_limit_pct": 0.05}}
        pm = PositionManager(cfg)
        de = DecisionEngine(cfg, pm)
        consensus = {
            "final_signal": "BUY",
            "confidence": 80,
            "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": True,
            "weighted_score": 1.0,
        }
        # Set daily loss beyond limit (5% of 10000 = 500)
        decision = de.decide("BTCUSDT", consensus, 50000.0, 9000.0, -600.0, 10000.0)
        assert decision["action"] == TradeAction.NO_ACTION.value

    def test_sell_signal_spot_no_action(self, default_config):
        # Force spot mode for this test
        cfg = {**default_config, "market_type": "spot"}
        pm = PositionManager(cfg)
        de = DecisionEngine(cfg, pm)
        consensus = {
            "final_signal": "SELL",
            "confidence": 70,
            "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": True,
            "weighted_score": -0.8,
        }
        # No position, spot mode - SELL should result in no action
        decision = de.decide("BTCUSDT", consensus, 50000.0, 10000.0, 0.0, 10000.0)
        assert decision["action"] == TradeAction.NO_ACTION.value
