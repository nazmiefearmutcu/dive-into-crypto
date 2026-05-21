"""S5 — risk/exit policy + command-queue wiring contracts.

Covers:

1. CommandProcessor: a fresh command with the same stable idempotency_key as
   a processed terminal command MUST dispatch (covered in
   test_command_processor.py); here we verify the END-TO-END path through
   BotService — manual_close enqueue → drain → paper close → state update.
2. paper_reset queue command clears positions, wipes trade history,
   restores paper balance, and resets daily PnL.
3. Paper-mode auto-close on stop_loss / take_profit / trailing_stop /
   liquidation in:
       (a) the active-symbol decision-engine path
       (b) the non-active `_check_other_positions` path
   so positions no longer "accumulate warnings while remaining open".
4. Live-mode never silently closes: positions remain open with a
   `live_exit_unsupported:<reason>` warning instead.
5. Live + futures + SHORT actions are refused with the canonical
   `live_short_unsupported` reason at:
       (a) ExecutionEngine._execute_live
       (b) BotService._assert_live_action_supported (used by manual_close)
   and the dashboard status surfaces a top-level `status_warnings` banner.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import pytest
import yaml

from src.market.live_price_service import FakePriceAdapter, LivePriceService
from src.persistence.schemas import CommandKind, CommandStatus
from src.services.bot_service import BotService
from src.trading.execution_engine import LIVE_SHORT_UNSUPPORTED_REASON
from src.trading.order_models import PositionSide, TradeAction


# ── shared bot fixture ──────────────────────────────────────────────


def _base_cfg(tmp_path: Path, *, mode: str = "paper", market_type: str = "futures") -> dict[str, Any]:
    return {
        "mode": mode,
        "market_type": market_type,
        "timeframe": "4h",
        "polling_interval_seconds": 1,
        "candle_limit": 200,
        "_config_path": str(tmp_path / "config.yaml"),
        "active_symbol_path": str(tmp_path / "active_symbol.txt"),
        "state_path": str(tmp_path / "state.json"),
        "dashboard_status_path": str(tmp_path / "ds.json"),
        "command_queue_path": str(tmp_path / "command_queue.json"),
        "auto_scan_enabled": False,
        "risk": {
            "max_open_positions": 5,
            "confidence_threshold": 55,
            "stop_loss_pct": 0.025,
            "take_profit_pct": 0.05,
            "trailing_stop_pct": 0.02,
            "trailing_stop_activation_pct": 0.03,
            "risk_per_trade": 0.02,
            "daily_loss_limit_pct": 0.05,
            "max_risk_level": "MEDIUM",
            "break_even_trigger_pct": 0.02,
        },
        "paper": {"starting_balance": 10000.0, "fee_pct": 0.001},
        "indicator_weights": {"rsi": 1.5},
        "consensus": {
            "strong_buy_threshold": 1.2, "buy_threshold": 0.4,
            "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
            "min_active_signals": 1, "conflict_ratio_threshold": 0.6,
        },
        "no_trade": {"adx_min": 15, "atr_high_percentile": 95, "min_confidence": 40},
    }


def _make_bot(tmp_path: Path, *, mode: str = "paper", market_type: str = "futures") -> BotService:
    cfg = _base_cfg(tmp_path, mode=mode, market_type=market_type)
    Path(cfg["_config_path"]).write_text(yaml.dump(cfg))
    Path(cfg["active_symbol_path"]).write_text("BTCUSDT\n")
    b = BotService(cfg)

    # Replace external dependencies with mocks (same pattern as cadence test).
    b.binance_client = MagicMock()
    b.binance_client.get_ticker_price = MagicMock(return_value=None)
    b.market_data = MagicMock()
    b.signal_service = MagicMock()
    b.consensus_engine = MagicMock()
    b.position_manager.risk_config = cfg["risk"]

    # Reset paper balance + execution mode after dependency injection.
    b.execution_engine.set_paper_balance(cfg["paper"]["starting_balance"])
    b.execution_engine.mode = mode

    # State store needs an active_symbol so `_export_dashboard_status` works.
    b.state_store.update(
        active_symbol="BTCUSDT", positions={}, trade_history=[],
        daily_pnl=0.0, total_realized_pnl=0.0,
        daily_start_balance=cfg["paper"]["starting_balance"],
    )

    fake_adapter = FakePriceAdapter()
    fake_adapter.set("BTCUSDT", price=50000.0)
    b.live_price_service = LivePriceService(fake_adapter)
    return b


# ── 1. End-to-end manual_close through BotService.command_processor ────


class TestManualCloseQueueWiring:
    def test_manual_close_runs_through_paper_close_path(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        # Seed an open paper LONG at 50000.
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, entry_price=50000.0,
            quantity=0.1, leverage=1,
        )
        starting_balance = b.execution_engine.paper_balance
        # Tick price up so close yields positive PnL.
        b.binance_client.get_ticker_price = MagicMock(return_value=50500.0)

        cmd = b.command_queue.enqueue(
            CommandKind.MANUAL_CLOSE,
            {"symbol": "BTCUSDT"},
            idempotency_key="manual-close-itest-1",
        )
        b._process_pending_commands()

        # Position cleared.
        assert b.position_manager.get_position("BTCUSDT") is None
        # Trade recorded.
        assert len(b.position_manager.trade_history) == 1
        # Balance moved (margin returned + PnL).
        assert b.execution_engine.paper_balance != starting_balance
        # Queue command is terminal.
        after = b.command_queue.get(cmd.id)
        assert after is not None
        assert after.status == CommandStatus.PROCESSED

    def test_manual_close_processes_once_then_dedupes(self, tmp_path):
        """Two ticks with no enqueue in between must NOT re-dispatch the same
        terminal command. Then a fresh enqueue with same stable key MUST
        dispatch (covers the S5 dedupe fix end-to-end)."""
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 50000.0, 0.1, leverage=1,
        )
        b.binance_client.get_ticker_price = MagicMock(return_value=50500.0)

        key = "close::BTCUSDT::pending"
        b.command_queue.enqueue(CommandKind.MANUAL_CLOSE, {"symbol": "BTCUSDT"}, key)
        b._process_pending_commands()
        assert b.position_manager.get_position("BTCUSDT") is None

        # Second tick: queue is empty; nothing happens.
        b._process_pending_commands()
        assert b.position_manager.get_position("BTCUSDT") is None

        # Operator re-clicks close on a NEW position with the SAME stable key.
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 51000.0, 0.05, leverage=1,
        )
        b.binance_client.get_ticker_price = MagicMock(return_value=51500.0)
        second = b.command_queue.enqueue(
            CommandKind.MANUAL_CLOSE, {"symbol": "BTCUSDT"}, key,
        )
        b._process_pending_commands()
        # The fresh command was dispatched — position closed again.
        assert b.position_manager.get_position("BTCUSDT") is None
        after = b.command_queue.get(second.id)
        assert after is not None
        assert after.status == CommandStatus.PROCESSED

    def test_manual_close_for_missing_symbol_is_noop_processed(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        cmd = b.command_queue.enqueue(
            CommandKind.MANUAL_CLOSE,
            {"symbol": "BTCUSDT"},
            idempotency_key="manual-close-noop-1",
        )
        b._process_pending_commands()
        after = b.command_queue.get(cmd.id)
        assert after is not None
        # Handler returned cleanly — command marked PROCESSED, not FAILED.
        assert after.status == CommandStatus.PROCESSED


# ── 2. paper_reset clears positions + balance ───────────────────────


class TestPaperResetQueueWiring:
    def test_paper_reset_clears_positions_and_history(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 50000.0, 0.1, leverage=1,
        )
        b.position_manager.open_position(
            "ETHUSDT", PositionSide.SHORT, 3000.0, 1.0, leverage=2,
        )
        b.binance_client.get_ticker_price = MagicMock(return_value=50000.0)
        b.state_store.update(daily_pnl=-50.0, total_realized_pnl=-12.5)

        cmd = b.command_queue.enqueue(
            CommandKind.PAPER_RESET,
            {"balance": 25000.0},
            idempotency_key="paper-reset-itest-1",
        )
        b._process_pending_commands()

        assert b.position_manager.positions == {}
        assert b.position_manager.trade_history == []
        assert b.position_manager.total_realized_pnl == 0.0
        assert b.execution_engine.paper_balance == pytest.approx(25000.0)
        assert b.state_store.get("daily_pnl") == 0.0

        after = b.command_queue.get(cmd.id)
        assert after is not None
        assert after.status == CommandStatus.PROCESSED

    def test_paper_reset_uses_starting_balance_when_payload_empty(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.execution_engine.set_paper_balance(123.45)
        cmd = b.command_queue.enqueue(
            CommandKind.PAPER_RESET, {}, idempotency_key="paper-reset-default-1",
        )
        b._process_pending_commands()
        assert b.execution_engine.paper_balance == pytest.approx(10000.0)
        after = b.command_queue.get(cmd.id)
        assert after is not None and after.status == CommandStatus.PROCESSED

    def test_paper_reset_rejected_in_live_mode(self, tmp_path):
        b = _make_bot(tmp_path, mode="live", market_type="futures")
        cmd = b.command_queue.enqueue(
            CommandKind.PAPER_RESET,
            {"balance": 5000.0},
            idempotency_key="paper-reset-live-1",
        )
        b._process_pending_commands()
        after = b.command_queue.get(cmd.id)
        assert after is not None
        assert after.status == CommandStatus.FAILED
        assert "live" in (after.error or "").lower()


# ── 3. Paper auto-close on SL / TP / trail / liquidation ────────────


class TestPaperAutoCloseExitTriggers:
    def test_stop_loss_auto_closes_in_paper_active_symbol(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 50000.0, 0.1, leverage=1,
        )
        consensus = {
            "final_signal": "NEUTRAL", "confidence": 30, "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": False, "weighted_score": 0.0,
        }
        # 48000 < SL of 48750 → stop_loss triggers.
        decision = b.decision_engine.decide(
            "BTCUSDT", consensus, current_price=48000.0,
            balance=10000.0, daily_pnl=0.0, daily_start_balance=10000.0,
        )
        assert decision["action"] == TradeAction.CLOSE_LONG.value
        assert "stop_loss" in decision["reason"]

    def test_take_profit_auto_closes_in_paper_active_symbol(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 50000.0, 0.1, leverage=1,
        )
        consensus = {
            "final_signal": "NEUTRAL", "confidence": 30, "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": False, "weighted_score": 0.0,
        }
        # 53000 > TP of 52500 → take_profit triggers.
        decision = b.decision_engine.decide(
            "BTCUSDT", consensus, current_price=53000.0,
            balance=10000.0, daily_pnl=0.0, daily_start_balance=10000.0,
        )
        assert decision["action"] == TradeAction.CLOSE_LONG.value
        assert "take_profit" in decision["reason"]

    def test_trailing_stop_auto_closes_in_paper_active_symbol(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 50000.0, 0.1, leverage=1,
        )
        consensus = {
            "final_signal": "NEUTRAL", "confidence": 30, "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": False, "weighted_score": 0.0,
        }
        # Push price up to activate trailing + break-even.
        # 51500 → +3% activates trailing; new SL = 51500 * 0.98 = 50470.
        b.decision_engine.decide(
            "BTCUSDT", consensus, current_price=51500.0,
            balance=10000.0, daily_pnl=0.0, daily_start_balance=10000.0,
        )
        # Drop back under the trailing SL → trailing_stop triggers.
        decision = b.decision_engine.decide(
            "BTCUSDT", consensus, current_price=50400.0,
            balance=10000.0, daily_pnl=0.0, daily_start_balance=10000.0,
        )
        assert decision["action"] == TradeAction.CLOSE_LONG.value
        # Either trailing_stop or break_even_stop satisfies the contract
        # — both are paper-mode auto-close triggers.
        assert any(
            tag in decision["reason"]
            for tag in ("trailing_stop", "break_even_stop")
        )

    def test_liquidation_auto_closes_in_paper_active_symbol(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        # 10x leverage → tighter liquidation ~10% adverse move from entry.
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 50000.0, 0.1, leverage=10,
        )
        pos = b.position_manager.get_position("BTCUSDT")
        assert pos is not None and pos.liquidation_price is not None
        # Slam price below liquidation.
        consensus = {
            "final_signal": "NEUTRAL", "confidence": 30, "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": False, "weighted_score": 0.0,
        }
        decision = b.decision_engine.decide(
            "BTCUSDT", consensus,
            current_price=pos.liquidation_price - 100.0,
            balance=10000.0, daily_pnl=0.0, daily_start_balance=10000.0,
        )
        assert decision["action"] == TradeAction.CLOSE_LONG.value
        # The auto-close fires on either liquidation OR stop_loss (since the
        # SL is clamped above liquidation with a safety buffer). Either way
        # we MUST not silently hold.
        assert any(
            tag in decision["reason"]
            for tag in ("liquidation", "stop_loss", "break_even_stop")
        )


class TestNonActivePositionsAutoCloseInPaper:
    def test_paper_non_active_stop_loss_auto_closes(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.position_manager.open_position(
            "ETHUSDT", PositionSide.LONG, 3000.0, 1.0, leverage=1,
        )
        # Price below SL of 2925 → stop_loss triggers.
        b.binance_client.get_ticker_price = MagicMock(return_value=2800.0)
        b._check_other_positions(active_symbol="BTCUSDT", balance=10000.0)
        # Auto-closed — position removed, NOT lingering with a warning.
        assert b.position_manager.get_position("ETHUSDT") is None

    def test_paper_non_active_take_profit_auto_closes(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b.position_manager.open_position(
            "ETHUSDT", PositionSide.LONG, 3000.0, 1.0, leverage=1,
        )
        # Price above TP of 3150 → take_profit triggers.
        b.binance_client.get_ticker_price = MagicMock(return_value=3200.0)
        # Avoid the reversal-signal sub-branch by also stubbing market_data.
        b.market_data.get_ohlcv = MagicMock(return_value=None)
        b._check_other_positions(active_symbol="BTCUSDT", balance=10000.0)
        assert b.position_manager.get_position("ETHUSDT") is None


# ── 4. Live mode never silently closes ──────────────────────────────


class TestLiveModeNoSilentClose:
    def test_live_active_position_stop_loss_warns_only(self, tmp_path):
        b = _make_bot(tmp_path, mode="live", market_type="futures")
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.LONG, 50000.0, 0.1, leverage=1,
        )
        consensus = {
            "final_signal": "NEUTRAL", "confidence": 30, "risk_level": "LOW",
            "risk_data": {"position_size_modifier": 1.0, "risk_factors": []},
            "should_trade": False, "weighted_score": 0.0,
        }
        decision = b.decision_engine.decide(
            "BTCUSDT", consensus, current_price=48000.0,
            balance=10000.0, daily_pnl=0.0, daily_start_balance=10000.0,
        )
        # NOT closed — held with warning.
        assert decision["action"] == TradeAction.HOLD.value
        pos = b.position_manager.get_position("BTCUSDT")
        assert pos is not None
        assert pos.warning is not None
        assert pos.warning.startswith("live_exit_unsupported:")
        assert "stop_loss" in pos.warning

    def test_live_non_active_position_warns_only(self, tmp_path):
        b = _make_bot(tmp_path, mode="live", market_type="futures")
        b.position_manager.open_position(
            "ETHUSDT", PositionSide.LONG, 3000.0, 1.0, leverage=1,
        )
        b.binance_client.get_ticker_price = MagicMock(return_value=2800.0)
        b.market_data.get_ohlcv = MagicMock(return_value=None)
        b._check_other_positions(active_symbol="BTCUSDT", balance=10000.0)
        pos = b.position_manager.get_position("ETHUSDT")
        assert pos is not None  # not auto-closed
        assert pos.warning is not None
        assert pos.warning.startswith("live_exit_unsupported:")


# ── 5. Futures live short guard ─────────────────────────────────────


class TestFuturesLiveShortGuard:
    def test_execution_engine_refuses_live_open_short(self, tmp_path):
        b = _make_bot(tmp_path, mode="live", market_type="futures")
        decision = {
            "action": TradeAction.OPEN_SHORT.value,
            "symbol": "BTCUSDT",
            "quantity": 0.01,
            "price": 50000.0,
            "reason": "test",
            "leverage": 5,
        }
        result = b.execution_engine.execute(decision)
        assert result["executed"] is False
        assert LIVE_SHORT_UNSUPPORTED_REASON in result["reason"]

    def test_execution_engine_refuses_live_close_short(self, tmp_path):
        b = _make_bot(tmp_path, mode="live", market_type="futures")
        decision = {
            "action": TradeAction.CLOSE_SHORT.value,
            "symbol": "BTCUSDT",
            "quantity": 0.01,
            "price": 50000.0,
            "reason": "test",
            "leverage": 5,
        }
        result = b.execution_engine.execute(decision)
        assert result["executed"] is False
        assert LIVE_SHORT_UNSUPPORTED_REASON in result["reason"]

    def test_bot_assert_live_action_supported_blocks_short_only_in_live(self, tmp_path):
        live_dir = tmp_path / "live"
        paper_dir = tmp_path / "paper"
        live_dir.mkdir()
        paper_dir.mkdir()
        live_bot = _make_bot(live_dir, mode="live", market_type="futures")
        paper_bot = _make_bot(paper_dir, mode="paper", market_type="futures")

        # Paper mode: no guard ever fires.
        for act in (TradeAction.OPEN_SHORT, TradeAction.CLOSE_SHORT,
                    TradeAction.OPEN_LONG, TradeAction.CLOSE_LONG):
            assert paper_bot._assert_live_action_supported(act) is None

        # Live mode: SHORT blocked, LONG allowed.
        assert live_bot._assert_live_action_supported(
            TradeAction.OPEN_SHORT
        ) == LIVE_SHORT_UNSUPPORTED_REASON
        assert live_bot._assert_live_action_supported(
            TradeAction.CLOSE_SHORT
        ) == LIVE_SHORT_UNSUPPORTED_REASON
        assert live_bot._assert_live_action_supported(TradeAction.OPEN_LONG) is None
        assert live_bot._assert_live_action_supported(TradeAction.CLOSE_LONG) is None

    def test_manual_close_short_in_live_marks_command_failed(self, tmp_path):
        b = _make_bot(tmp_path, mode="live", market_type="futures")
        b.position_manager.open_position(
            "BTCUSDT", PositionSide.SHORT, 50000.0, 0.1, leverage=5,
        )
        b.binance_client.get_ticker_price = MagicMock(return_value=51000.0)
        cmd = b.command_queue.enqueue(
            CommandKind.MANUAL_CLOSE,
            {"symbol": "BTCUSDT"},
            idempotency_key="manual-close-live-short-1",
        )
        b._process_pending_commands()
        after = b.command_queue.get(cmd.id)
        assert after is not None
        assert after.status == CommandStatus.FAILED
        assert LIVE_SHORT_UNSUPPORTED_REASON in (after.error or "")
        # Position kept open — fail closed, not silent close.
        assert b.position_manager.get_position("BTCUSDT") is not None

    def test_status_warnings_surface_in_live_futures(self, tmp_path):
        b = _make_bot(tmp_path, mode="live", market_type="futures")
        b._refresh_status_warnings()
        assert any(LIVE_SHORT_UNSUPPORTED_REASON in w for w in b._status_warnings)

    def test_status_warnings_empty_in_paper(self, tmp_path):
        b = _make_bot(tmp_path, mode="paper", market_type="futures")
        b._refresh_status_warnings()
        assert b._status_warnings == []
