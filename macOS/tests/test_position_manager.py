"""Tests for the position manager."""

import pytest

from src.trading.position_manager import PositionManager
from src.trading.order_models import PositionSide


@pytest.fixture
def pm(default_config):
    return PositionManager(default_config)


class TestPositionManager:
    def test_open_position(self, pm):
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)
        assert pos.symbol == "BTCUSDT"
        assert pos.side == PositionSide.LONG
        assert pos.entry_price == 50000.0
        assert pos.quantity == 0.1
        assert pos.stop_loss < 50000.0  # SL below entry for long
        assert pos.take_profit > 50000.0  # TP above entry for long
        assert pm.has_position("BTCUSDT")

    def test_close_position(self, pm):
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)
        record = pm.close_position("BTCUSDT", 51000.0, "test_close")
        assert record is not None
        assert record.pnl > 0  # Profitable trade
        assert not pm.has_position("BTCUSDT")
        assert len(pm.trade_history) == 1

    def test_stop_loss_trigger(self, pm):
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)
        pos = pm.get_position("BTCUSDT")
        # Price below stop loss
        exit_reason = pm.update_position("BTCUSDT", pos.stop_loss - 100)
        assert exit_reason == "stop_loss"

    def test_take_profit_trigger(self, pm):
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)
        pos = pm.get_position("BTCUSDT")
        # Price above take profit
        exit_reason = pm.update_position("BTCUSDT", pos.take_profit + 100)
        assert exit_reason == "take_profit"

    def test_break_even_activation(self, pm):
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)
        # Move price up by more than break_even_trigger_pct (2%)
        pm.update_position("BTCUSDT", 51500.0)
        pos = pm.get_position("BTCUSDT")
        assert pos.is_break_even
        # SL should be at or above entry (trailing stop may push it higher)
        assert pos.stop_loss >= 50000.0

    def test_calculate_quantity(self, pm):
        qty = pm.calculate_quantity(10000.0, 50000.0, 1.0)
        assert qty > 0
        # Should not exceed 95% of balance / price
        assert qty <= 10000.0 * 0.95 / 50000.0

    def test_daily_loss_limit(self, default_config):
        # Force a 5% daily loss limit for this test
        cfg = {**default_config, "risk": {**default_config.get("risk", {}), "daily_loss_limit_enabled": True, "daily_loss_limit_pct": 0.05}}
        pm2 = PositionManager(cfg)
        assert not pm2.check_daily_loss_limit(-100, 10000.0)
        assert pm2.check_daily_loss_limit(-600, 10000.0)  # 6% > 5% limit

    def test_daily_loss_limit_disabled(self, default_config):
        cfg = {**default_config, "risk": {**default_config.get("risk", {}), "daily_loss_limit_pct": 0}}
        pm2 = PositionManager(cfg)
        assert not pm2.check_daily_loss_limit(-9999, 10000.0)  # never triggers when disabled

    def test_short_position(self, pm):
        pos = pm.open_position("BTCUSDT", PositionSide.SHORT, 50000.0, 0.1)
        assert pos.stop_loss > 50000.0  # SL above entry for short
        assert pos.take_profit < 50000.0  # TP below entry for short

        record = pm.close_position("BTCUSDT", 49000.0, "short_profit")
        assert record.pnl > 0  # Profitable short

    def test_unrealized_pnl_tracking(self, pm):
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)
        pm.update_position("BTCUSDT", 51000.0)
        pos = pm.get_position("BTCUSDT")
        assert pos.unrealized_pnl == pytest.approx(100.0, abs=1)

    def test_save_and_restore_positions(self, pm):
        pm.open_position("BTCUSDT", PositionSide.LONG, 50000.0, 0.1)
        positions_dict = pm.get_positions_dict()

        new_pm = PositionManager(pm.config)
        new_pm.load_positions(positions_dict)
        assert new_pm.has_position("BTCUSDT")
        pos = new_pm.get_position("BTCUSDT")
        assert pos.entry_price == 50000.0

    def test_no_position_returns_none(self, pm):
        assert pm.get_position("BTCUSDT") is None
        assert pm.update_position("BTCUSDT", 50000.0) is None
        assert pm.close_position("BTCUSDT", 50000.0) is None
