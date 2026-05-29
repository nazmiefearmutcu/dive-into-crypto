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


# ─── S7: Rescue-safety validation seam ────────────────────────────
#
# These thresholds are deliberately conservative for the rescue build.
# Operators who knowingly need higher risk can leave the warnings in
# place — nothing here raises or mutates config. The list is closed
# (one place to grow it) so S8 can promote individual warnings to
# errors with explicit migration notes.

RESCUE_RISK_PER_TRADE_MAX = 0.05          # >5 % per-trade risk warned
RESCUE_MAX_OPEN_POSITIONS_MAX = 10        # >10 simultaneous positions warned


def validate_rescue_safety(config: dict[str, Any]) -> tuple[list[str], list[str]]:
    """Return ``(errors, warnings)`` for rescue-build risk surfaces.

    The function MUST NOT raise and MUST NOT mutate ``config``. Callers
    decide whether to surface ``warnings`` (log, dashboard banner) and
    whether ``errors`` should refuse load — currently ``errors`` is
    always ``[]`` and reserved for S8 promotions.

    Surfaces audited:
        * ``mode`` (paper/live)
        * ``market_type`` (spot/futures) paired with live mode
        * ``risk.risk_per_trade`` upper bound
        * ``risk.max_open_positions`` upper bound
        * ``risk.daily_loss_limit_enabled``
        * ``dashboard_fallback_enabled`` (S6 gate)
    """
    errors: list[str] = []
    warnings: list[str] = []

    mode = config.get("mode", "paper")
    market_type = config.get("market_type", "spot")

    if mode == "live":
        warnings.append(
            "mode=live — verify API credentials and that risk parameters are intentional"
        )
        if market_type == "futures":
            warnings.append(
                "mode=live + market_type=futures — S5 fail-closed paths active for futures-short"
            )

    risk = config.get("risk") or {}

    rpt = risk.get("risk_per_trade", 0) or 0
    if rpt > RESCUE_RISK_PER_TRADE_MAX:
        warnings.append(
            f"risk.risk_per_trade={rpt} exceeds rescue cap "
            f"{RESCUE_RISK_PER_TRADE_MAX} — capital at heightened risk per trade"
        )

    mop = risk.get("max_open_positions", 0) or 0
    if mop > RESCUE_MAX_OPEN_POSITIONS_MAX:
        warnings.append(
            f"risk.max_open_positions={mop} exceeds rescue cap "
            f"{RESCUE_MAX_OPEN_POSITIONS_MAX} — concurrent exposure may stack"
        )

    if not risk.get("daily_loss_limit_enabled", True):
        warnings.append(
            "risk.daily_loss_limit_enabled=false — no daily-loss circuit breaker"
        )

    # The S6 dashboard fallback is opt-out (`default True`). In rescue mode
    # we want it explicitly turned off so the UI surfaces true staleness
    # instead of replaying stale snapshots.
    if config.get("dashboard_fallback_enabled", True):
        warnings.append(
            "dashboard_fallback_enabled=true — dashboard may serve stale fallback data; "
            "set to false to require live status"
        )

    return errors, warnings
