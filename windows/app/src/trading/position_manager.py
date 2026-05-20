"""Position management with SL/TP/trailing stop logic."""

from typing import Any, Optional

from src.trading.order_models import (
    Position, PositionSide, TradeRecord, TradeAction,
)
from src.utils.logger import get_logger
from src.utils.helpers import iso_now, pct_change

logger = get_logger("trading.position_manager")


class PositionManager:
    """Manages open positions, stop-loss, take-profit, trailing stops, and break-even."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.risk_config = config.get("risk", {})
        self.positions: dict[str, Position] = {}
        self.trade_history: list[TradeRecord] = []
        self.total_realized_pnl: float = 0.0

    def has_position(self, symbol: str) -> bool:
        return symbol in self.positions

    def get_position(self, symbol: str) -> Optional[Position]:
        return self.positions.get(symbol)

    def open_position(
        self,
        symbol: str,
        side: PositionSide,
        entry_price: float,
        quantity: float,
        atr_value: Optional[float] = None,
        leverage: int = 1,
    ) -> Position:
        """Open a new position with leverage-aware SL/TP/liquidation levels.

        SL/TP/trailing percentages are applied directly to PRICE movement,
        NOT scaled by leverage. Leverage already multiplies PnL via position
        size (quantity). Scaling SL by leverage would make stops impossibly
        tight (e.g. 4% SL with 7x → 0.57% price move = instant stop).

        Minimum TP:
          - Must cover round-trip commission: 2 × fee_pct
          - If TP < min_tp, use min_tp instead

        Liquidation price:
          - LONG:  liq = entry × (1 - 1/leverage) / (1 - maint_rate)
          - SHORT: liq = entry × (1 + 1/leverage) / (1 + maint_rate)
          - SL is clamped to never cross liquidation (with safety buffer)
        """
        sl_pct = self.risk_config.get("stop_loss_pct", 0.025)
        tp_pct = self.risk_config.get("take_profit_pct", 0.05)
        trailing_pct = self.risk_config.get("trailing_stop_pct", 0.02)
        fee_pct = self.config.get("paper", {}).get("fee_pct", 0.001)
        # Binance futures maintenance margin rate (typical tier 1)
        maint_rate = 0.004

        # SL/TP/trailing are PRICE movement percentages — NOT divided by leverage
        effective_sl_pct = sl_pct
        effective_tp_pct = tp_pct
        effective_trailing_pct = trailing_pct

        # Minimum TP must cover round-trip commission
        min_tp_pct = 2 * fee_pct
        if effective_tp_pct < min_tp_pct:
            logger.info(
                f"TP adjusted: {effective_tp_pct:.6f} < min {min_tp_pct:.6f} (2×fee). "
                f"Using min_tp_pct={min_tp_pct:.6f}"
            )
            effective_tp_pct = min_tp_pct

        # Calculate liquidation price
        liquidation_price = None
        if leverage > 1:
            if side == PositionSide.LONG:
                liquidation_price = entry_price * (1 - 1 / leverage) / (1 - maint_rate)
            else:  # SHORT
                liquidation_price = entry_price * (1 + 1 / leverage) / (1 + maint_rate)
            liquidation_price = round(liquidation_price, 8)

        # Calculate raw SL/TP
        if side == PositionSide.LONG:
            stop_loss = entry_price * (1 - effective_sl_pct)
            take_profit = entry_price * (1 + effective_tp_pct)
        else:  # SHORT
            stop_loss = entry_price * (1 + effective_sl_pct)
            take_profit = entry_price * (1 - effective_tp_pct)

        # Clamp SL to never cross liquidation (with 10% safety buffer)
        if liquidation_price is not None and leverage > 1:
            if side == PositionSide.LONG:
                # SL must be ABOVE liquidation price
                # safety_sl = 10% of the gap between entry and liquidation, measured from liq side
                safe_liq = entry_price - (entry_price - liquidation_price) * 0.90
                if stop_loss < safe_liq:
                    logger.warning(
                        f"SL {stop_loss:.2f} would cross liquidation zone "
                        f"(liq={liquidation_price:.2f}). Clamping to {safe_liq:.2f}"
                    )
                    stop_loss = safe_liq
            else:  # SHORT
                safe_liq = entry_price + (liquidation_price - entry_price) * 0.90
                if stop_loss > safe_liq:
                    logger.warning(
                        f"SL {stop_loss:.2f} would cross liquidation zone "
                        f"(liq={liquidation_price:.2f}). Clamping to {safe_liq:.2f}"
                    )
                    stop_loss = safe_liq

        position = Position(
            symbol=symbol,
            side=side,
            entry_price=entry_price,
            quantity=quantity,
            stop_loss=round(stop_loss, 8),
            take_profit=round(take_profit, 8),
            trailing_stop=effective_trailing_pct,
            highest_price=entry_price,
            open_time=iso_now(),
            leverage=leverage,
            liquidation_price=liquidation_price,
        )
        self.positions[symbol] = position

        liq_str = f" | liq={liquidation_price:.2f}" if liquidation_price else ""
        logger.info(
            f"Position OPENED | {symbol} {side.value} | qty={quantity} | "
            f"entry={entry_price} | SL={position.stop_loss:.4f} | TP={position.take_profit:.4f} | "
            f"leverage={leverage}x{liq_str} | "
            f"sl_pct={effective_sl_pct:.4%} tp_pct={effective_tp_pct:.4%}"
        )
        return position

    def update_position(self, symbol: str, current_price: float) -> Optional[str]:
        """Update position with current price. Returns exit reason if triggered, else None.

        All SL updates are guarded by _clamp_sl_to_liquidation to ensure
        the stop-loss never crosses the liquidation price.
        """
        position = self.positions.get(symbol)
        if not position:
            return None

        # Update unrealized PnL
        if position.side == PositionSide.LONG:
            position.unrealized_pnl = (current_price - position.entry_price) * position.quantity
        else:
            position.unrealized_pnl = (position.entry_price - current_price) * position.quantity

        # Check liquidation (emergency exit before exchange liquidates)
        if position.liquidation_price is not None:
            if position.side == PositionSide.LONG and current_price <= position.liquidation_price:
                return "liquidation"
            if position.side == PositionSide.SHORT and current_price >= position.liquidation_price:
                return "liquidation"

        # Break-even logic (price movement %, NOT scaled by leverage)
        be_trigger = self.risk_config.get("break_even_trigger_pct", 0.02)
        if not position.is_break_even:
            if position.side == PositionSide.LONG:
                gain_pct = pct_change(position.entry_price, current_price)
                if gain_pct >= be_trigger:
                    position.stop_loss = position.entry_price
                    position.stop_loss = self._clamp_sl_to_liquidation(position)
                    position.is_break_even = True
                    logger.info(f"Break-even activated for {symbol} at {position.entry_price}")
            elif position.side == PositionSide.SHORT:
                gain_pct = pct_change(current_price, position.entry_price)
                if gain_pct >= be_trigger:
                    position.stop_loss = position.entry_price
                    position.stop_loss = self._clamp_sl_to_liquidation(position)
                    position.is_break_even = True
                    logger.info(f"Break-even activated for {symbol} at {position.entry_price}")

        # Trailing stop logic FIRST (price movement %, NOT scaled by leverage)
        # Must be checked BEFORE generic SL because trailing updates position.stop_loss
        trailing_activation = self.risk_config.get("trailing_stop_activation_pct", 0.03)
        if position.trailing_stop and position.highest_price:
            if position.side == PositionSide.LONG:
                if current_price > position.highest_price:
                    position.highest_price = current_price

                gain_from_entry = pct_change(position.entry_price, position.highest_price)
                if gain_from_entry >= trailing_activation:
                    new_trailing_sl = position.highest_price * (1 - position.trailing_stop)
                    if new_trailing_sl > position.stop_loss:
                        position.stop_loss = round(new_trailing_sl, 8)
                        position.stop_loss = self._clamp_sl_to_liquidation(position)
                        logger.debug(f"Trailing stop updated for {symbol}: SL={position.stop_loss}")

                if current_price <= position.stop_loss and position.is_break_even:
                    return "trailing_stop"

            elif position.side == PositionSide.SHORT:
                if position.highest_price is None or current_price < position.highest_price:
                    position.highest_price = current_price

                gain_from_entry = pct_change(position.highest_price, position.entry_price)
                if gain_from_entry >= trailing_activation:
                    new_trailing_sl = position.highest_price * (1 + position.trailing_stop)
                    if new_trailing_sl < position.stop_loss:
                        position.stop_loss = round(new_trailing_sl, 8)
                        position.stop_loss = self._clamp_sl_to_liquidation(position)

                if current_price >= position.stop_loss and position.is_break_even:
                    return "trailing_stop"

        # Check stop-loss (only original SL, not trailing-modified)
        # If break-even is active and SL was moved to entry, this is a break-even exit
        if position.side == PositionSide.LONG and current_price <= position.stop_loss:
            if position.is_break_even and position.unrealized_pnl >= 0:
                return "break_even_stop"
            return "stop_loss"
        if position.side == PositionSide.SHORT and current_price >= position.stop_loss:
            if position.is_break_even and position.unrealized_pnl >= 0:
                return "break_even_stop"
            return "stop_loss"

        # Check take-profit
        if position.side == PositionSide.LONG and current_price >= position.take_profit:
            return "take_profit"
        if position.side == PositionSide.SHORT and current_price <= position.take_profit:
            return "take_profit"

        return None

    @staticmethod
    def _clamp_sl_to_liquidation(position: Position) -> float:
        """Ensure stop-loss never crosses the liquidation price.

        Returns the (possibly clamped) stop-loss value.
        Uses a 10% safety buffer from liquidation.
        """
        sl = position.stop_loss
        liq = position.liquidation_price
        if liq is None or position.leverage <= 1:
            return sl

        if position.side == PositionSide.LONG:
            # SL must be ABOVE liquidation
            safe_liq = liq * 1.005  # 0.5% above liquidation
            if sl < safe_liq:
                return round(safe_liq, 8)
        else:  # SHORT
            safe_liq = liq * 0.995  # 0.5% below liquidation
            if sl > safe_liq:
                return round(safe_liq, 8)
        return sl

    def close_position(
        self,
        symbol: str,
        exit_price: float,
        reason: str = "",
        fee_pct: float = 0.001,
    ) -> Optional[TradeRecord]:
        """Close a position and record the trade."""
        position = self.positions.get(symbol)
        if not position:
            logger.warning(f"No position to close for {symbol}")
            return None

        if position.side == PositionSide.LONG:
            gross_pnl = (exit_price - position.entry_price) * position.quantity
        else:
            gross_pnl = (position.entry_price - exit_price) * position.quantity

        fee = (position.entry_price * position.quantity + exit_price * position.quantity) * fee_pct
        net_pnl = gross_pnl - fee

        record = TradeRecord(
            symbol=symbol,
            action="CLOSE",
            side=position.side.value,
            entry_price=position.entry_price,
            exit_price=exit_price,
            quantity=position.quantity,
            pnl=round(net_pnl, 4),
            fee=round(fee, 4),
            entry_time=position.open_time,
            exit_time=iso_now(),
            reason=reason,
        )

        self.trade_history.append(record)
        self.total_realized_pnl += net_pnl
        del self.positions[symbol]

        logger.info(
            f"Position CLOSED | {symbol} {position.side.value} | "
            f"entry={position.entry_price} exit={exit_price} | "
            f"PnL={net_pnl:.4f} | reason={reason}"
        )
        return record

    def calculate_quantity(
        self,
        balance: float,
        price: float,
        risk_modifier: float = 1.0,
        leverage: int = 1,
    ) -> float:
        """Calculate order quantity based on risk per trade and leverage.

        With leverage:
          - margin = balance * risk_per_trade (what we put up)
          - position_notional = margin * leverage
          - quantity = position_notional / price
          - Max: balance * 0.95 * leverage / price (don't use more than 95% as margin)
        """
        risk_per_trade = self.risk_config.get("risk_per_trade", 0.02)

        # Margin we're willing to risk
        margin = balance * risk_per_trade * risk_modifier

        # Position size with leverage
        position_notional = margin * leverage
        quantity = position_notional / price

        # Cap at 95% of balance as margin * leverage
        max_quantity = (balance * 0.95 * leverage) / price
        quantity = min(quantity, max_quantity)

        return round(quantity, 8)

    def check_daily_loss_limit(self, daily_pnl: float, daily_start_balance: float) -> bool:
        """Check if daily loss limit has been exceeded. Returns False if disabled."""
        enabled = self.risk_config.get("daily_loss_limit_enabled", True)
        if not enabled:
            return False  # disabled via toggle
        limit_pct = self.risk_config.get("daily_loss_limit_pct", 0.05)
        if limit_pct <= 0:
            return False  # disabled via 0 value
        if daily_start_balance == 0:
            return False
        loss_pct = abs(daily_pnl) / daily_start_balance if daily_pnl < 0 else 0
        return loss_pct >= limit_pct

    def get_max_positions(self) -> int:
        return self.risk_config.get("max_open_positions", 1)

    def get_positions_dict(self) -> dict[str, dict]:
        return {k: v.to_dict() for k, v in self.positions.items()}

    def load_positions(self, positions_data: dict[str, dict]) -> None:
        """Restore positions from saved state."""
        for symbol, pdata in positions_data.items():
            try:
                self.positions[symbol] = Position.from_dict(pdata)
                logger.info(f"Restored position for {symbol}")
            except Exception as e:
                logger.error(f"Failed to restore position for {symbol}: {e}")

    def load_trade_history(self, history_data: list[dict]) -> None:
        """Restore trade history from saved state."""
        for record_data in history_data:
            try:
                self.trade_history.append(TradeRecord.from_dict(record_data))
            except Exception as e:
                logger.error(f"Failed to restore trade record: {e}")
