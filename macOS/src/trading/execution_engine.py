"""Execution engine - handles order placement for paper and live modes."""

from typing import Any, Optional

from src.api.binance_client import BinanceClient
from src.trading.order_models import (
    Order, OrderSide, OrderType, OrderStatus, TradeAction, PositionSide,
)
from src.trading.position_manager import PositionManager
from src.utils.logger import get_logger
from src.utils.helpers import iso_now, retry_with_backoff

logger = get_logger("trading.execution_engine")


class ExecutionEngine:
    """Executes trading decisions in either paper or live mode."""

    def __init__(
        self,
        config: dict[str, Any],
        binance_client: BinanceClient,
        position_manager: PositionManager,
    ) -> None:
        self.config = config
        self.client = binance_client
        self.position_manager = position_manager
        self.mode = config.get("mode", "paper")
        self.paper_config = config.get("paper", {})
        self.paper_balance: float = self.paper_config.get("starting_balance", 10000.0)
        self.paper_fee_pct: float = self.paper_config.get("fee_pct", 0.001)

    def execute(self, decision: dict[str, Any]) -> dict[str, Any]:
        """Execute a trading decision. Returns execution result."""
        action = TradeAction(decision["action"])
        symbol = decision["symbol"]
        quantity = decision["quantity"]
        price = decision["price"]
        leverage = decision.get("leverage", 1)

        if action in (TradeAction.HOLD, TradeAction.NO_ACTION):
            return {
                "executed": False,
                "action": action.value,
                "reason": decision["reason"],
            }

        if self.mode == "paper":
            return self._execute_paper(action, symbol, quantity, price, decision["reason"], leverage)
        else:
            return self._execute_live(action, symbol, quantity, price, decision["reason"], leverage)

    def _execute_paper(
        self,
        action: TradeAction,
        symbol: str,
        quantity: float,
        price: float,
        reason: str,
        leverage: int = 1,
    ) -> dict[str, Any]:
        """Simulate order execution in paper mode.

        With leverage (futures):
          - margin = notional / leverage = (quantity * price) / leverage
          - We deduct margin from balance, not the full notional
          - PnL is based on full notional movement (already embedded in quantity)
        """
        notional = price * quantity
        fee = notional * self.paper_fee_pct
        is_leveraged = leverage > 1

        if action == TradeAction.OPEN_LONG:
            if is_leveraged:
                # Futures: deduct margin + fee
                margin = notional / leverage
                cost = margin + fee
            else:
                # Spot: deduct full amount
                cost = notional + fee

            if cost > self.paper_balance:
                if is_leveraged:
                    max_margin = self.paper_balance * 0.99
                    max_notional = max_margin * leverage
                    max_qty = max_notional / (price * (1 + self.paper_fee_pct))
                else:
                    max_qty = (self.paper_balance * 0.99) / (price * (1 + self.paper_fee_pct))
                if max_qty <= 0:
                    return {"executed": False, "action": action.value, "reason": "Insufficient paper balance"}
                quantity = round(max_qty, 8)
                notional = price * quantity
                fee = notional * self.paper_fee_pct
                cost = (notional / leverage + fee) if is_leveraged else (notional + fee)

            self.paper_balance -= cost
            position = self.position_manager.open_position(
                symbol, PositionSide.LONG, price, quantity, leverage=leverage
            )
            logger.info(
                f"[PAPER] OPEN LONG | {symbol} | qty={quantity} | price={price} | "
                f"leverage={leverage}x | margin={cost:.2f} | fee={fee:.4f} | balance={self.paper_balance:.2f}"
            )
            return {
                "executed": True,
                "action": action.value,
                "mode": "paper",
                "symbol": symbol,
                "side": "BUY",
                "quantity": quantity,
                "price": price,
                "fee": round(fee, 4),
                "leverage": leverage,
                "margin": round(cost, 4),
                "balance_after": round(self.paper_balance, 4),
                "position": position.to_dict(),
            }

        elif action == TradeAction.CLOSE_LONG:
            position = self.position_manager.get_position(symbol)
            pos_leverage = position.leverage if position else 1
            record = self.position_manager.close_position(
                symbol, price, reason, self.paper_fee_pct
            )
            if record:
                if pos_leverage > 1:
                    # Futures: return margin + PnL
                    entry_notional = record.entry_price * record.quantity
                    margin_returned = entry_notional / pos_leverage
                    self.paper_balance += margin_returned + record.pnl
                else:
                    # Spot: return sale proceeds
                    proceeds = price * record.quantity - abs(record.fee)
                    self.paper_balance += proceeds
                logger.info(
                    f"[PAPER] CLOSE LONG | {symbol} | qty={record.quantity} | "
                    f"exit={price} | leverage={pos_leverage}x | PnL={record.pnl:.4f} | balance={self.paper_balance:.2f}"
                )
                return {
                    "executed": True,
                    "action": action.value,
                    "mode": "paper",
                    "symbol": symbol,
                    "side": "SELL",
                    "quantity": record.quantity,
                    "price": price,
                    "pnl": record.pnl,
                    "fee": record.fee,
                    "leverage": pos_leverage,
                    "balance_after": round(self.paper_balance, 4),
                    "reason": reason,
                }
            return {"executed": False, "action": action.value, "reason": "No position to close"}

        elif action == TradeAction.OPEN_SHORT:
            if is_leveraged:
                margin = notional / leverage
                cost = margin + fee
            else:
                cost = fee  # Spot short: only fee for borrow (simplified)

            if cost > self.paper_balance:
                return {"executed": False, "action": action.value, "reason": "Insufficient margin for short"}

            self.paper_balance -= cost
            position = self.position_manager.open_position(
                symbol, PositionSide.SHORT, price, quantity, leverage=leverage
            )
            logger.info(
                f"[PAPER] OPEN SHORT | {symbol} | qty={quantity} | price={price} | "
                f"leverage={leverage}x | margin={cost:.2f} | fee={fee:.4f} | balance={self.paper_balance:.2f}"
            )
            return {
                "executed": True,
                "action": action.value,
                "mode": "paper",
                "symbol": symbol,
                "side": "SELL",
                "quantity": quantity,
                "price": price,
                "fee": round(fee, 4),
                "leverage": leverage,
                "margin": round(cost, 4),
                "balance_after": round(self.paper_balance, 4),
                "position": position.to_dict(),
            }

        elif action == TradeAction.CLOSE_SHORT:
            position = self.position_manager.get_position(symbol)
            pos_leverage = position.leverage if position else 1
            record = self.position_manager.close_position(
                symbol, price, reason, self.paper_fee_pct
            )
            if record:
                if pos_leverage > 1:
                    entry_notional = record.entry_price * record.quantity
                    margin_returned = entry_notional / pos_leverage
                    self.paper_balance += margin_returned + record.pnl
                else:
                    cost = price * record.quantity + abs(record.fee)
                    self.paper_balance -= cost
                logger.info(
                    f"[PAPER] CLOSE SHORT | {symbol} | qty={record.quantity} | "
                    f"exit={price} | leverage={pos_leverage}x | PnL={record.pnl:.4f} | balance={self.paper_balance:.2f}"
                )
                return {
                    "executed": True,
                    "action": action.value,
                    "mode": "paper",
                    "symbol": symbol,
                    "side": "BUY",
                    "quantity": record.quantity,
                    "price": price,
                    "pnl": record.pnl,
                    "fee": record.fee,
                    "leverage": pos_leverage,
                    "balance_after": round(self.paper_balance, 4),
                    "reason": reason,
                }
            return {"executed": False, "action": action.value, "reason": "No short position to close"}

        return {"executed": False, "action": action.value, "reason": "Unhandled action"}

    def _execute_live(
        self,
        action: TradeAction,
        symbol: str,
        quantity: float,
        price: float,
        reason: str,
        leverage: int = 1,
    ) -> dict[str, Any]:
        """Execute real orders on Binance."""
        # Set leverage on Binance Futures before opening position
        if action in (TradeAction.OPEN_LONG, TradeAction.OPEN_SHORT) and leverage > 1:
            try:
                self.client.client.futures_change_leverage(symbol=symbol, leverage=leverage)
                logger.info(f"[LIVE] Leverage set to {leverage}x for {symbol}")
            except Exception as e:
                logger.error(f"Failed to set leverage for {symbol}: {e}")

        if action == TradeAction.OPEN_LONG:
            # Round quantity to symbol precision
            symbol_info = self.client.get_symbol_info(symbol)
            quantity = self._adjust_quantity(quantity, symbol_info)
            if quantity <= 0:
                return {"executed": False, "action": action.value, "reason": "Quantity too small after rounding"}

            order_result = self.client.place_market_buy(symbol, quantity)
            if order_result:
                filled_price = self._get_avg_fill_price(order_result)
                filled_qty = float(order_result.get("executedQty", quantity))
                self.position_manager.open_position(
                    symbol, PositionSide.LONG, filled_price, filled_qty, leverage=leverage
                )
                logger.info(f"[LIVE] OPEN LONG | {symbol} | qty={filled_qty} | price={filled_price} | lev={leverage}x")
                return {
                    "executed": True,
                    "action": action.value,
                    "mode": "live",
                    "symbol": symbol,
                    "side": "BUY",
                    "quantity": filled_qty,
                    "price": filled_price,
                    "order_id": order_result.get("orderId"),
                    "status": order_result.get("status"),
                }
            return {"executed": False, "action": action.value, "reason": "Market buy failed"}

        elif action == TradeAction.CLOSE_LONG:
            position = self.position_manager.get_position(symbol)
            if not position:
                return {"executed": False, "action": action.value, "reason": "No position to close"}

            symbol_info = self.client.get_symbol_info(symbol)
            qty = self._adjust_quantity(position.quantity, symbol_info)

            order_result = self.client.place_market_sell(symbol, qty)
            if order_result:
                filled_price = self._get_avg_fill_price(order_result)
                record = self.position_manager.close_position(symbol, filled_price, reason)
                logger.info(f"[LIVE] CLOSE LONG | {symbol} | price={filled_price}")
                return {
                    "executed": True,
                    "action": action.value,
                    "mode": "live",
                    "symbol": symbol,
                    "side": "SELL",
                    "quantity": qty,
                    "price": filled_price,
                    "pnl": record.pnl if record else 0,
                    "order_id": order_result.get("orderId"),
                    "reason": reason,
                }
            return {"executed": False, "action": action.value, "reason": "Market sell failed"}

        elif action in (TradeAction.OPEN_SHORT, TradeAction.CLOSE_SHORT):
            # Futures short execution - architecture ready
            logger.warning(f"Short execution requires futures mode. Action: {action.value}")
            return {"executed": False, "action": action.value, "reason": "Futures short not yet enabled"}

        return {"executed": False, "action": action.value, "reason": "Unhandled action"}

    def _adjust_quantity(self, quantity: float, symbol_info: Optional[dict]) -> float:
        """Adjust quantity to meet exchange lot size requirements."""
        if not symbol_info:
            return round(quantity, 6)

        filters = {f["filterType"]: f for f in symbol_info.get("filters", [])}
        lot_size = filters.get("LOT_SIZE", {})
        min_qty = float(lot_size.get("minQty", 0.000001))
        step_size = float(lot_size.get("stepSize", 0.000001))

        if quantity < min_qty:
            return 0.0

        # Round down to step size
        precision = len(str(step_size).rstrip("0").split(".")[-1]) if "." in str(step_size) else 0
        adjusted = round(quantity - (quantity % step_size), precision)
        return max(adjusted, 0.0)

    def _get_avg_fill_price(self, order_result: dict) -> float:
        """Extract average fill price from order result."""
        fills = order_result.get("fills", [])
        if fills:
            total_qty = sum(float(f["qty"]) for f in fills)
            total_cost = sum(float(f["price"]) * float(f["qty"]) for f in fills)
            return total_cost / total_qty if total_qty > 0 else float(order_result.get("price", 0))
        # Fallback
        cum_quote = float(order_result.get("cummulativeQuoteQty", 0))
        exec_qty = float(order_result.get("executedQty", 0))
        if exec_qty > 0:
            return cum_quote / exec_qty
        return 0.0

    def get_balance(self) -> float:
        """Get current balance (paper or live)."""
        if self.mode == "paper":
            return self.paper_balance
        return self.client.get_account_balance("USDT")

    def set_paper_balance(self, balance: float) -> None:
        """Set paper balance (for state restore)."""
        self.paper_balance = balance
