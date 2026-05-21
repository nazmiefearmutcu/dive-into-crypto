"""Decision engine - translates consensus output into concrete trading actions."""

from typing import Any, Optional

from src.indicators.base import Signal
from src.trading.order_models import TradeAction, PositionSide
from src.trading.position_manager import PositionManager
from src.trading.leverage_manager import LeverageManager
from src.utils.logger import get_logger
from src.utils.helpers import iso_now

logger = get_logger("trading.decision_engine")


class DecisionEngine:
    """Decides what action to take based on consensus and current position state."""

    def __init__(
        self,
        config: dict[str, Any],
        position_manager: PositionManager,
        leverage_manager: Optional[LeverageManager] = None,
    ) -> None:
        self.config = config
        self.position_manager = position_manager
        self.leverage_manager = leverage_manager
        self.risk_config = config.get("risk", {})

    def decide(
        self,
        symbol: str,
        consensus: dict[str, Any],
        current_price: float,
        balance: float,
        daily_pnl: float,
        daily_start_balance: float,
    ) -> dict[str, Any]:
        """Produce a trading decision based on consensus and state."""
        final_signal = Signal(consensus["final_signal"])
        confidence = consensus["confidence"]
        should_trade = consensus["should_trade"]
        risk_level = consensus["risk_level"]
        risk_modifier = consensus["risk_data"]["position_size_modifier"]

        has_position = self.position_manager.has_position(symbol)
        position = self.position_manager.get_position(symbol)

        # Check daily loss limit
        if self.position_manager.check_daily_loss_limit(daily_pnl, daily_start_balance):
            return self._make_decision(
                TradeAction.NO_ACTION, 0, symbol, current_price,
                "Daily loss limit reached - no new trades",
                consensus,
            )

        # If we have a position, update tracking (trailing stop, break-even etc.)
        # S5 exit-semantics policy:
        #   - paper mode: AUTO-CLOSE on stop_loss / take_profit / trailing_stop
        #     / break_even_stop / liquidation. The position is removed by the
        #     close path; no permanent `warning` accumulates cycle-after-cycle.
        #   - live mode: keep the previous warning-only behavior. The live
        #     execution path is not yet verified for exit orders, so we fail
        #     CLOSED — surface a clear warning rather than pretend we closed.
        #   - signal reversal: AUTO-CLOSE in both modes (already routed
        #     through ExecutionEngine.execute, which guards live shorts).
        mode = self.config.get("mode", "paper")
        if has_position and position:
            exit_reason = self.position_manager.update_position(symbol, current_price)
            if exit_reason:
                if mode == "paper":
                    close_action = (
                        TradeAction.CLOSE_LONG if position.side == PositionSide.LONG
                        else TradeAction.CLOSE_SHORT
                    )
                    reason = (
                        f"Auto-close on {exit_reason} (paper) | "
                        f"{position.side.value} entry={position.entry_price} "
                        f"price={current_price}"
                    )
                    logger.info(reason)
                    return self._make_decision(
                        close_action, position.quantity, symbol, current_price,
                        reason,
                        consensus,
                        leverage=position.leverage,
                    )
                # Live mode: warning only, no silent close.
                position.warning = f"live_exit_unsupported:{exit_reason}"
                logger.warning(
                    f"Position {symbol} hit {exit_reason} in live mode — "
                    f"NOT auto-closing (execution path not verified). "
                    f"Manual close required."
                )

            # Track current signal/confidence on the position
            position.current_signal = final_signal.value
            position.current_confidence = confidence

            # ── Signal reversal detection — AUTO-CLOSE ──
            # LONG position + SELL/STRONG_SELL signal → close
            # SHORT position + BUY/STRONG_BUY signal → close
            signal_reversed = False
            if position.side == PositionSide.LONG and final_signal in (Signal.SELL, Signal.STRONG_SELL):
                signal_reversed = True
            elif position.side == PositionSide.SHORT and final_signal in (Signal.BUY, Signal.STRONG_BUY):
                signal_reversed = True

            if signal_reversed and should_trade:
                close_action = (
                    TradeAction.CLOSE_LONG if position.side == PositionSide.LONG
                    else TradeAction.CLOSE_SHORT
                )
                reason = (
                    f"⚡ Sinyal tersine döndü! {position.side.value} pozisyon → "
                    f"sinyal={final_signal.value} güven={confidence}% — otomatik kapatılıyor"
                )
                logger.info(reason)
                return self._make_decision(
                    close_action, position.quantity, symbol, current_price,
                    reason,
                    consensus,
                )

            # Hold current position
            reason = f"Holding {position.side.value} position | signal={final_signal.value} conf={confidence}%"
            if position.warning:
                reason += f" | ⚠ {position.warning}"
            return self._make_decision(
                TradeAction.HOLD, 0, symbol, current_price,
                reason,
                consensus,
            )

        # ── No position — evaluate entry ──

        if not should_trade:
            return self._make_decision(
                TradeAction.NO_ACTION, 0, symbol, current_price,
                f"No trade: signal={final_signal.value} conf={confidence} risk={risk_level}",
                consensus,
            )

        # Calculate dynamic leverage for futures
        leverage = 1
        market_type = self.config.get("market_type", "spot")
        if market_type == "futures" and self.leverage_manager:
            leverage = self.leverage_manager.calculate_leverage(symbol, confidence)

        # Entry decision
        if final_signal in (Signal.STRONG_BUY, Signal.BUY):
            quantity = self.position_manager.calculate_quantity(
                balance, current_price, risk_modifier, leverage
            )
            if quantity <= 0:
                return self._make_decision(
                    TradeAction.NO_ACTION, 0, symbol, current_price,
                    "Calculated quantity too small",
                    consensus, leverage=leverage,
                )
            return self._make_decision(
                TradeAction.OPEN_LONG, quantity, symbol, current_price,
                f"BUY signal | {final_signal.value} conf={confidence} risk={risk_level} lev={leverage}x",
                consensus, leverage=leverage,
            )

        if final_signal in (Signal.STRONG_SELL, Signal.SELL):
            # For spot market, SELL signal without position means no action
            if market_type == "spot":
                return self._make_decision(
                    TradeAction.NO_ACTION, 0, symbol, current_price,
                    f"SELL signal but no position (spot mode, short not available)",
                    consensus,
                )
            else:
                quantity = self.position_manager.calculate_quantity(
                    balance, current_price, risk_modifier, leverage
                )
                if quantity <= 0:
                    return self._make_decision(
                        TradeAction.NO_ACTION, 0, symbol, current_price,
                        "Calculated quantity too small for short",
                        consensus, leverage=leverage,
                    )
                return self._make_decision(
                    TradeAction.OPEN_SHORT, quantity, symbol, current_price,
                    f"SHORT signal | {final_signal.value} conf={confidence} risk={risk_level} lev={leverage}x",
                    consensus, leverage=leverage,
                )

        return self._make_decision(
            TradeAction.NO_ACTION, 0, symbol, current_price,
            f"No actionable signal: {final_signal.value}",
            consensus,
        )

    def _make_decision(
        self,
        action: TradeAction,
        quantity: float,
        symbol: str,
        price: float,
        reason: str,
        consensus: dict[str, Any],
        leverage: int = 1,
    ) -> dict[str, Any]:
        decision = {
            "action": action.value,
            "symbol": symbol,
            "quantity": round(quantity, 8),
            "price": price,
            "reason": reason,
            "timestamp": iso_now(),
            "consensus_signal": consensus["final_signal"],
            "confidence": consensus["confidence"],
            "risk_level": consensus["risk_level"],
            "leverage": leverage,
        }

        logger.info(
            f"Decision: {action.value} | {symbol} | qty={quantity:.8f} | "
            f"price={price} | leverage={leverage}x | {reason}"
        )
        return decision
