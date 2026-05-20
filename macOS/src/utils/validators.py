"""Validation utilities for the trading bot."""

import re
from pathlib import Path
from typing import Any

from src.utils.logger import get_logger

logger = get_logger("validators")

VALID_SYMBOL_PATTERN = re.compile(r"^[A-Z0-9]{2,20}$")
VALID_TIMEFRAMES = {"1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M"}
VALID_MODES = {"paper", "live"}
VALID_MARKET_TYPES = {"spot", "futures"}


def validate_symbol(symbol: str) -> bool:
    """Validate a trading symbol string."""
    if not symbol or not isinstance(symbol, str):
        return False
    symbol = symbol.strip().upper()
    return bool(VALID_SYMBOL_PATTERN.match(symbol))


def validate_timeframe(timeframe: str) -> bool:
    """Validate a candlestick timeframe string."""
    return timeframe in VALID_TIMEFRAMES


def validate_mode(mode: str) -> bool:
    """Validate trading mode."""
    return mode in VALID_MODES


def validate_config(config: dict[str, Any]) -> list[str]:
    """Validate config dictionary and return list of errors."""
    errors: list[str] = []

    mode = config.get("mode", "paper")
    if not validate_mode(mode):
        errors.append(f"Invalid mode: {mode}. Must be one of {VALID_MODES}")

    market_type = config.get("market_type", "spot")
    if market_type not in VALID_MARKET_TYPES:
        errors.append(f"Invalid market_type: {market_type}. Must be one of {VALID_MARKET_TYPES}")

    timeframe = config.get("timeframe", "1h")
    if not validate_timeframe(timeframe):
        errors.append(f"Invalid timeframe: {timeframe}. Must be one of {VALID_TIMEFRAMES}")

    risk = config.get("risk", {})
    if risk.get("risk_per_trade", 0) <= 0 or risk.get("risk_per_trade", 0) > 1:
        errors.append("risk_per_trade must be between 0 and 1")
    if risk.get("stop_loss_pct", 0) <= 0:
        errors.append("stop_loss_pct must be positive")
    if risk.get("take_profit_pct", 0) <= 0:
        errors.append("take_profit_pct must be positive")
    if risk.get("confidence_threshold", 0) < 0 or risk.get("confidence_threshold", 0) > 100:
        errors.append("confidence_threshold must be between 0 and 100")

    symbol_path = config.get("active_symbol_path", "")
    if not symbol_path:
        errors.append("active_symbol_path must be specified")

    return errors


def validate_state(state: dict[str, Any]) -> bool:
    """Validate state dictionary has required keys."""
    required_keys = {"active_symbol", "positions", "paper_balance"}
    return required_keys.issubset(state.keys())
