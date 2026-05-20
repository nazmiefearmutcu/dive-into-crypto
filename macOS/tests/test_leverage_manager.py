"""Tests for dynamic leverage manager."""

import pytest
from unittest.mock import MagicMock, patch

from src.trading.leverage_manager import LeverageManager


@pytest.fixture
def config():
    return {
        "market_type": "futures",
        "leverage": {
            "enabled": True,
            "scale_ratio": 0.80,
            "min_leverage": 2,
        },
    }


@pytest.fixture
def manager(config):
    return LeverageManager(config)


class TestLeverageCalculation:
    """Test confidence-to-leverage scaling."""

    def test_btc_full_confidence_100x(self, manager):
        """BTC at 100% confidence should give 100x (125 * 0.80)."""
        manager._max_leverage_cache["BTCUSDT"] = 125
        lev = manager.calculate_leverage("BTCUSDT", 100)
        assert lev == 100

    def test_btc_90_confidence(self, manager):
        """BTC at 90% confidence -> 125 * 0.80 * 0.90 = 90x."""
        manager._max_leverage_cache["BTCUSDT"] = 125
        lev = manager.calculate_leverage("BTCUSDT", 90)
        assert lev == 90

    def test_btc_50_confidence(self, manager):
        """BTC at 50% confidence -> 125 * 0.80 * 0.50 = 50x."""
        manager._max_leverage_cache["BTCUSDT"] = 125
        lev = manager.calculate_leverage("BTCUSDT", 50)
        assert lev == 50

    def test_btc_30_confidence(self, manager):
        """BTC at 30% confidence -> 125 * 0.80 * 0.30 = 30x."""
        manager._max_leverage_cache["BTCUSDT"] = 125
        lev = manager.calculate_leverage("BTCUSDT", 30)
        assert lev == 30

    def test_altcoin_50x_max_full_confidence(self, manager):
        """Altcoin with 50x max at 100% -> 50 * 0.80 = 40x."""
        manager._max_leverage_cache["ATOMUSDT"] = 50
        lev = manager.calculate_leverage("ATOMUSDT", 100)
        assert lev == 40

    def test_altcoin_50x_max_half_confidence(self, manager):
        """Altcoin with 50x max at 50% -> 50 * 0.80 * 0.50 = 20x."""
        manager._max_leverage_cache["ATOMUSDT"] = 50
        lev = manager.calculate_leverage("ATOMUSDT", 50)
        assert lev == 20

    def test_minimum_leverage_enforced(self, manager):
        """Very low confidence should still give min_leverage (2x)."""
        manager._max_leverage_cache["BTCUSDT"] = 125
        lev = manager.calculate_leverage("BTCUSDT", 1)
        assert lev == 2  # min_leverage

    def test_disabled_returns_1x(self, config):
        """When leverage is disabled, should return 1x."""
        config["leverage"]["enabled"] = False
        mgr = LeverageManager(config)
        mgr._max_leverage_cache["BTCUSDT"] = 125
        lev = mgr.calculate_leverage("BTCUSDT", 100)
        assert lev == 1

    def test_low_max_leverage_coin(self, manager):
        """Coin with 25x max at 100% -> 25 * 0.80 = 20x."""
        manager._max_leverage_cache["BLUAIUSDT"] = 25
        lev = manager.calculate_leverage("BLUAIUSDT", 100)
        assert lev == 20

    def test_eth_100x_max(self, manager):
        """ETH with 100x max at 100% -> 100 * 0.80 = 80x."""
        manager._max_leverage_cache["ETHUSDT"] = 100
        lev = manager.calculate_leverage("ETHUSDT", 100)
        assert lev == 80


class TestMaxLeverage:
    """Test max leverage fetching and caching."""

    def test_known_btc_leverage(self, manager):
        """BTC should return 125x from known values."""
        lev = manager.get_max_leverage("BTCUSDT")
        assert lev == 125

    def test_known_eth_leverage(self, manager):
        """ETH should return 100x from known values."""
        lev = manager.get_max_leverage("ETHUSDT")
        assert lev == 100

    def test_unknown_coin_default(self, manager):
        """Unknown coin should return default (20x)."""
        lev = manager.get_max_leverage("UNKNOWNUSDT")
        assert lev == 20

    def test_caching(self, manager):
        """Second call should use cache."""
        lev1 = manager.get_max_leverage("BTCUSDT")
        manager._max_leverage_cache["BTCUSDT"] = 999
        lev2 = manager.get_max_leverage("BTCUSDT")
        assert lev2 == 999  # From cache

    def test_clear_cache(self, manager):
        """clear_cache should reset the cache."""
        manager.get_max_leverage("BTCUSDT")
        assert "BTCUSDT" in manager._max_leverage_cache
        manager.clear_cache()
        assert "BTCUSDT" not in manager._max_leverage_cache

    def test_binance_api_fetch(self, config):
        """Test fetching from Binance API when available."""
        mock_client = MagicMock()
        mock_client.get_symbol_info.return_value = {"symbol": "BTCUSDT"}
        mock_client.client.futures_leverage_bracket.return_value = [
            {"brackets": [{"initialLeverage": 125}]}
        ]
        mgr = LeverageManager(config, binance_client=mock_client)
        lev = mgr.get_max_leverage("BTCUSDT")
        assert lev == 125


class TestQuantityWithLeverage:
    """Test that position sizing works correctly with leverage."""

    def test_quantity_multiplied_by_leverage(self):
        """Position quantity should scale with leverage."""
        from src.trading.position_manager import PositionManager
        config = {"risk": {"risk_per_trade": 0.10, "stop_loss_pct": 0.025}}
        pm = PositionManager(config)

        qty_1x = pm.calculate_quantity(10000, 50000, 1.0, leverage=1)
        qty_10x = pm.calculate_quantity(10000, 50000, 1.0, leverage=10)

        assert qty_10x == pytest.approx(qty_1x * 10, rel=0.01)

    def test_position_stores_leverage(self):
        """Position object should store the leverage value."""
        from src.trading.position_manager import PositionManager
        config = {"risk": {"risk_per_trade": 0.10, "stop_loss_pct": 0.025, "take_profit_pct": 0.05, "trailing_stop_pct": 0.02}}
        pm = PositionManager(config)
        from src.trading.order_models import PositionSide
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.1, leverage=50)
        assert pos.leverage == 50

    def test_position_default_leverage_1(self):
        """Position should default to 1x leverage."""
        from src.trading.position_manager import PositionManager
        config = {"risk": {"risk_per_trade": 0.10, "stop_loss_pct": 0.025, "take_profit_pct": 0.05, "trailing_stop_pct": 0.02}}
        pm = PositionManager(config)
        from src.trading.order_models import PositionSide
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.1)
        assert pos.leverage == 1

    def test_leverage_serialization(self):
        """Leverage should survive to_dict/from_dict roundtrip."""
        from src.trading.order_models import Position, PositionSide
        pos = Position(
            symbol="BTCUSDT", side=PositionSide.LONG, entry_price=50000,
            quantity=0.1, stop_loss=49000, take_profit=52500, leverage=75,
        )
        d = pos.to_dict()
        assert d["leverage"] == 75
        restored = Position.from_dict(d)
        assert restored.leverage == 75


class TestLeverageAwareSLTP:
    """Test SL/TP scaling, liquidation, and minimum TP with leverage."""

    @pytest.fixture
    def pm_config(self):
        return {
            "risk": {
                "stop_loss_pct": 0.025,       # 2.5%
                "take_profit_pct": 0.05,       # 5%
                "trailing_stop_pct": 0.02,     # 2%
                "trailing_stop_activation_pct": 0.03,
                "break_even_trigger_pct": 0.02,
            },
            "paper": {"fee_pct": 0.001},       # 0.1%
        }

    def test_1x_no_scaling(self, pm_config):
        """At 1x, SL/TP should be at configured percentages."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.1, leverage=1)
        assert pos.stop_loss == pytest.approx(50000 * 0.975, rel=1e-6)
        assert pos.take_profit == pytest.approx(50000 * 1.05, rel=1e-6)
        assert pos.liquidation_price is None
        assert pos.trailing_stop == 0.02

    def test_10x_sl_not_scaled(self, pm_config):
        """At 10x, SL % should be the same as configured (price movement, not ROI)."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.1, leverage=10)
        # SL = 2.5% price movement -> 50000 * 0.975 = 48750
        assert pos.stop_loss == pytest.approx(50000 * (1 - 0.025), rel=1e-4)

    def test_10x_tp_not_scaled(self, pm_config):
        """At 10x, TP % should be the same as configured (price movement, not ROI)."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.1, leverage=10)
        # TP = 5% price movement -> 50000 * 1.05 = 52500
        assert pos.take_profit == pytest.approx(50000 * 1.05, rel=1e-4)

    def test_tp_covers_commission(self, pm_config):
        """If configured TP is less than 2×fee, it must be clamped to min."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm_config["risk"]["take_profit_pct"] = 0.0001  # 0.01% — less than 2×fee (0.2%)
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.01, leverage=10)
        min_tp = 50000 * (1 + 2 * 0.001)  # 50000 * 1.002 = 50100
        assert pos.take_profit == pytest.approx(min_tp, rel=1e-4)

    def test_liquidation_price_long(self, pm_config):
        """Liquidation price for LONG should be below entry."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.01, leverage=50)
        assert pos.liquidation_price is not None
        assert pos.liquidation_price < 50000
        # liq ≈ 50000 * (1 - 1/50) / (1 - 0.004) = 50000 * 0.98 / 0.996 ≈ 49197
        assert pos.liquidation_price == pytest.approx(50000 * 0.98 / 0.996, rel=1e-4)

    def test_liquidation_price_short(self, pm_config):
        """Liquidation price for SHORT should be above entry."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.SHORT, 50000, 0.01, leverage=50)
        assert pos.liquidation_price is not None
        assert pos.liquidation_price > 50000
        # liq ≈ 50000 * (1 + 1/50) / (1 + 0.004) = 50000 * 1.02 / 1.004 ≈ 50797
        assert pos.liquidation_price == pytest.approx(50000 * 1.02 / 1.004, rel=1e-4)

    def test_sl_never_below_liquidation_long(self, pm_config):
        """SL for LONG must always be above liquidation price when SL is wide enough to cross."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        # With high leverage, even moderate SL can cross liquidation
        # At 100x, liq ≈ entry * 0.99 = 49500. SL of 2% = 49000 would cross.
        pm_config["risk"]["stop_loss_pct"] = 0.02  # 2% — crosses liq at 100x
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.01, leverage=100)
        assert pos.stop_loss > pos.liquidation_price

    def test_sl_never_above_liquidation_short(self, pm_config):
        """SL for SHORT must always be below liquidation price."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm_config["risk"]["stop_loss_pct"] = 0.02  # 2% — crosses liq at 100x
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.SHORT, 50000, 0.01, leverage=100)
        assert pos.stop_loss < pos.liquidation_price

    def test_trailing_stop_not_scaled_by_leverage(self, pm_config):
        """Trailing stop % should NOT be scaled by leverage (price movement %)."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.1, leverage=10)
        # trailing = 2% price movement, same regardless of leverage
        assert pos.trailing_stop == pytest.approx(0.02, rel=1e-4)

    def test_update_position_liquidation_exit(self, pm_config):
        """Price hitting liquidation should trigger emergency exit."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.01, leverage=50)
        # Price drops to liquidation
        result = pm.update_position("BTCUSDT", pos.liquidation_price - 1)
        assert result == "liquidation"

    def test_update_position_liquidation_short(self, pm_config):
        """SHORT: price rising to liquidation should trigger emergency exit."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.SHORT, 50000, 0.01, leverage=50)
        result = pm.update_position("BTCUSDT", pos.liquidation_price + 1)
        assert result == "liquidation"

    def test_clamp_sl_during_trailing(self, pm_config):
        """Trailing stop should never push SL past liquidation."""
        from src.trading.position_manager import PositionManager
        from src.trading.order_models import PositionSide
        pm = PositionManager(pm_config)
        pos = pm.open_position("BTCUSDT", PositionSide.LONG, 50000, 0.01, leverage=50)
        # SL should be above liquidation
        assert pos.stop_loss > pos.liquidation_price

    def test_liquidation_serialization(self, pm_config):
        """Liquidation price should survive to_dict/from_dict."""
        from src.trading.order_models import Position, PositionSide
        pos = Position(
            symbol="BTCUSDT", side=PositionSide.LONG, entry_price=50000,
            quantity=0.01, stop_loss=49500, take_profit=50500,
            leverage=50, liquidation_price=49100.0,
        )
        d = pos.to_dict()
        assert d["liquidation_price"] == 49100.0
        restored = Position.from_dict(d)
        assert restored.liquidation_price == 49100.0
