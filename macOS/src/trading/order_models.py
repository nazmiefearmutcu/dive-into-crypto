"""Order and position data models."""

from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Any, Optional


class OrderSide(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


class OrderType(str, Enum):
    MARKET = "MARKET"
    LIMIT = "LIMIT"


class PositionSide(str, Enum):
    LONG = "LONG"
    SHORT = "SHORT"
    FLAT = "FLAT"


class OrderStatus(str, Enum):
    PENDING = "PENDING"
    FILLED = "FILLED"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"


class TradeAction(str, Enum):
    OPEN_LONG = "OPEN_LONG"
    CLOSE_LONG = "CLOSE_LONG"
    OPEN_SHORT = "OPEN_SHORT"
    CLOSE_SHORT = "CLOSE_SHORT"
    HOLD = "HOLD"
    NO_ACTION = "NO_ACTION"


@dataclass
class Order:
    symbol: str
    side: OrderSide
    order_type: OrderType
    quantity: float
    price: Optional[float] = None
    status: OrderStatus = OrderStatus.PENDING
    order_id: Optional[str] = None
    filled_price: Optional[float] = None
    filled_quantity: Optional[float] = None
    fee: float = 0.0
    timestamp: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["side"] = self.side.value
        d["order_type"] = self.order_type.value
        d["status"] = self.status.value
        return d

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Order":
        data["side"] = OrderSide(data["side"])
        data["order_type"] = OrderType(data["order_type"])
        data["status"] = OrderStatus(data["status"])
        return cls(**data)


@dataclass
class Position:
    symbol: str
    side: PositionSide
    entry_price: float
    quantity: float
    stop_loss: float
    take_profit: float
    trailing_stop: Optional[float] = None
    trailing_stop_price: Optional[float] = None
    highest_price: Optional[float] = None
    open_time: Optional[str] = None
    unrealized_pnl: float = 0.0
    realized_pnl: float = 0.0
    is_break_even: bool = False
    leverage: int = 1
    liquidation_price: Optional[float] = None
    warning: Optional[str] = None
    current_signal: Optional[str] = None
    current_confidence: Optional[int] = None

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["side"] = self.side.value
        return d

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Position":
        data["side"] = PositionSide(data["side"])
        return cls(**data)


@dataclass
class TradeRecord:
    symbol: str
    action: str
    side: str
    entry_price: float
    exit_price: Optional[float] = None
    quantity: float = 0.0
    pnl: float = 0.0
    fee: float = 0.0
    entry_time: Optional[str] = None
    exit_time: Optional[str] = None
    reason: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "TradeRecord":
        return cls(**data)
