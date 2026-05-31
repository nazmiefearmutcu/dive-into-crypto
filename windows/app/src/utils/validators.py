"""Validation utilities for the trading bot."""

import re
from math import isfinite
from pathlib import Path
from typing import Any

from src.utils.logger import get_logger

logger = get_logger("validators")

VALID_SYMBOL_PATTERN = re.compile(r"^[A-Z0-9]{2,20}$")
VALID_TIMEFRAMES = {"1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M"}
VALID_MODES = {"paper", "live"}
VALID_MARKET_TYPES = {"spot", "futures"}
VALID_RISK_LEVELS = {"LOW", "MEDIUM", "HIGH"}


def validate_symbol(symbol: str) -> bool:
    """Validate a trading symbol string."""
    if not symbol or not isinstance(symbol, str):
        return False
    symbol = symbol.strip().upper()
    return bool(VALID_SYMBOL_PATTERN.match(symbol))


def validate_timeframe(timeframe: str) -> bool:
    """Validate a candlestick timeframe string."""
    if not timeframe or not isinstance(timeframe, str):
        return False
    text = timeframe.strip()
    if not text:
        return False
    normalized = "1M" if text.upper() == "1M" else text.lower()
    return normalized in VALID_TIMEFRAMES


def validate_mode(mode: str) -> bool:
    """Validate trading mode."""
    if not mode or not isinstance(mode, str):
        return False
    return mode.strip().lower() in VALID_MODES


def validate_market_type(market_type: str) -> bool:
    """Validate market type."""
    if not market_type or not isinstance(market_type, str):
        return False
    return market_type.strip().lower() in VALID_MARKET_TYPES


def validate_risk_level(risk_level: str) -> bool:
    """Validate risk level."""
    if not risk_level or not isinstance(risk_level, str):
        return False
    return risk_level.strip().upper() in VALID_RISK_LEVELS


def _validate_path_field(
    errors: list[str],
    name: str,
    value: Any,
    *,
    required: bool = False,
) -> None:
    if value is None:
        if required:
            errors.append(f"{name} must be specified")
        return
    if not isinstance(value, (str, Path)):
        errors.append(f"{name} must be a string path")
        return
    if not str(value).strip():
        errors.append(f"{name} must be a non-empty path")


def _mapping_section(errors: list[str], config: dict[str, Any], name: str) -> dict[str, Any]:
    value = config.get(name)
    if value is None:
        return {}
    if not isinstance(value, dict):
        errors.append(f"{name} must be a mapping")
        return {}
    return value


def validate_config(config: dict[str, Any]) -> list[str]:
    """Validate config dictionary and return list of errors."""
    errors: list[str] = []

    mode = config.get("mode", "paper")
    if not validate_mode(mode):
        errors.append(f"Invalid mode: {mode}. Must be one of {VALID_MODES}")

    market_type = config.get("market_type", "spot")
    if not validate_market_type(market_type):
        errors.append(f"Invalid market_type: {market_type}. Must be one of {VALID_MARKET_TYPES}")

    timeframe = config.get("timeframe", "1h")
    if not validate_timeframe(timeframe):
        errors.append(f"Invalid timeframe: {timeframe}. Must be one of {VALID_TIMEFRAMES}")

    polling_interval_seconds = config.get("polling_interval_seconds", 60)
    if not isinstance(polling_interval_seconds, int) or isinstance(polling_interval_seconds, bool) or polling_interval_seconds <= 0:
        errors.append("polling_interval_seconds must be a positive integer")

    candle_limit = config.get("candle_limit", 200)
    if not isinstance(candle_limit, int) or isinstance(candle_limit, bool) or candle_limit <= 0:
        errors.append("candle_limit must be a positive integer")

    risk = _mapping_section(errors, config, "risk")
    risk_per_trade = risk.get("risk_per_trade", 0)
    if not isinstance(risk_per_trade, (int, float)) or isinstance(risk_per_trade, bool) or not isfinite(risk_per_trade) or risk_per_trade <= 0 or risk_per_trade > 1:
        errors.append("risk_per_trade must be between 0 and 1")
    stop_loss_pct = risk.get("stop_loss_pct", 0)
    if not isinstance(stop_loss_pct, (int, float)) or isinstance(stop_loss_pct, bool) or not isfinite(stop_loss_pct) or stop_loss_pct <= 0:
        errors.append("stop_loss_pct must be positive")
    take_profit_pct = risk.get("take_profit_pct", 0)
    if not isinstance(take_profit_pct, (int, float)) or isinstance(take_profit_pct, bool) or not isfinite(take_profit_pct) or take_profit_pct <= 0:
        errors.append("take_profit_pct must be positive")
    confidence_threshold = risk.get("confidence_threshold", 0)
    if not isinstance(confidence_threshold, (int, float)) or isinstance(confidence_threshold, bool) or not isfinite(confidence_threshold) or confidence_threshold < 0 or confidence_threshold > 100:
        errors.append("confidence_threshold must be between 0 and 100")
    trailing_stop_pct = risk.get("trailing_stop_pct", 0)
    if not isinstance(trailing_stop_pct, (int, float)) or isinstance(trailing_stop_pct, bool) or not isfinite(trailing_stop_pct) or trailing_stop_pct < 0:
        errors.append("risk.trailing_stop_pct must be non-negative")
    trailing_stop_activation_pct = risk.get("trailing_stop_activation_pct", 0)
    if not isinstance(trailing_stop_activation_pct, (int, float)) or isinstance(trailing_stop_activation_pct, bool) or not isfinite(trailing_stop_activation_pct) or trailing_stop_activation_pct < 0:
        errors.append("risk.trailing_stop_activation_pct must be non-negative")
    break_even_trigger_pct = risk.get("break_even_trigger_pct", 0)
    if not isinstance(break_even_trigger_pct, (int, float)) or isinstance(break_even_trigger_pct, bool) or not isfinite(break_even_trigger_pct) or break_even_trigger_pct < 0:
        errors.append("risk.break_even_trigger_pct must be non-negative")
    if "daily_loss_limit_enabled" in risk and not isinstance(risk.get("daily_loss_limit_enabled"), bool):
        errors.append("risk.daily_loss_limit_enabled must be a boolean")
    daily_loss_limit_pct = risk.get("daily_loss_limit_pct", 0)
    if daily_loss_limit_pct is not None:
        if not isinstance(daily_loss_limit_pct, (int, float)) or isinstance(daily_loss_limit_pct, bool) or not isfinite(daily_loss_limit_pct) or daily_loss_limit_pct < 0 or daily_loss_limit_pct > 1:
            errors.append("risk.daily_loss_limit_pct must be between 0 and 1")
    max_open_positions = risk.get("max_open_positions", 0)
    if not isinstance(max_open_positions, int) or isinstance(max_open_positions, bool) or max_open_positions < 0:
        errors.append("risk.max_open_positions must be a non-negative integer")
    if "max_risk_level" in risk and not validate_risk_level(risk.get("max_risk_level")):
        errors.append("risk.max_risk_level must be one of LOW/MEDIUM/HIGH")

    leverage = _mapping_section(errors, config, "leverage")
    leverage_min = leverage.get("min_leverage", None)
    if leverage_min is not None and (not isinstance(leverage_min, int) or isinstance(leverage_min, bool) or leverage_min < 1):
        errors.append("leverage.min_leverage must be a positive integer")
    leverage_scale_ratio = leverage.get("scale_ratio", None)
    if leverage_scale_ratio is not None and (
        not isinstance(leverage_scale_ratio, (int, float))
        or isinstance(leverage_scale_ratio, bool)
        or leverage_scale_ratio <= 0
        or leverage_scale_ratio > 1
    ):
        errors.append("leverage.scale_ratio must be between 0 and 1")
    if "enabled" in leverage and not isinstance(leverage.get("enabled"), bool):
        errors.append("leverage.enabled must be a boolean")

    no_trade = _mapping_section(errors, config, "no_trade")
    no_trade_min_confidence = no_trade.get("min_confidence", None)
    if no_trade_min_confidence is not None and (
        not isinstance(no_trade_min_confidence, (int, float))
        or isinstance(no_trade_min_confidence, bool)
        or no_trade_min_confidence < 0
        or no_trade_min_confidence > 100
    ):
        errors.append("no_trade.min_confidence must be between 0 and 100")
    no_trade_adx_min = no_trade.get("adx_min", None)
    if no_trade_adx_min is not None and (
        not isinstance(no_trade_adx_min, (int, float))
        or isinstance(no_trade_adx_min, bool)
        or no_trade_adx_min < 0
    ):
        errors.append("no_trade.adx_min must be non-negative")
    no_trade_atr_percentile = no_trade.get("atr_high_percentile", None)
    if no_trade_atr_percentile is not None and (
        not isinstance(no_trade_atr_percentile, (int, float))
        or isinstance(no_trade_atr_percentile, bool)
        or no_trade_atr_percentile < 0
        or no_trade_atr_percentile > 100
    ):
        errors.append("no_trade.atr_high_percentile must be between 0 and 100")

    consensus = _mapping_section(errors, config, "consensus")
    strong_buy_threshold = consensus.get("strong_buy_threshold", None)
    buy_threshold = consensus.get("buy_threshold", None)
    sell_threshold = consensus.get("sell_threshold", None)
    strong_sell_threshold = consensus.get("strong_sell_threshold", None)

    for name, value in [
        ("consensus.strong_buy_threshold", strong_buy_threshold),
        ("consensus.buy_threshold", buy_threshold),
        ("consensus.sell_threshold", sell_threshold),
        ("consensus.strong_sell_threshold", strong_sell_threshold),
    ]:
        if value is not None and (
            not isinstance(value, (int, float)) or isinstance(value, bool)
        ):
            errors.append(f"{name} must be a number")

    conflict_ratio_threshold = consensus.get("conflict_ratio_threshold", None)
    if conflict_ratio_threshold is not None and (
        not isinstance(conflict_ratio_threshold, (int, float))
        or isinstance(conflict_ratio_threshold, bool)
        or conflict_ratio_threshold < 0
        or conflict_ratio_threshold > 1
    ):
        errors.append("consensus.conflict_ratio_threshold must be between 0 and 1")

    min_active_signals = consensus.get("min_active_signals", None)
    if min_active_signals is not None and (
        not isinstance(min_active_signals, int)
        or isinstance(min_active_signals, bool)
        or min_active_signals < 1
    ):
        errors.append("consensus.min_active_signals must be a positive integer")

    if (
        isinstance(strong_buy_threshold, (int, float))
        and isinstance(buy_threshold, (int, float))
        and strong_buy_threshold < buy_threshold
    ):
        errors.append("consensus.strong_buy_threshold must be >= consensus.buy_threshold")
    if (
        isinstance(strong_sell_threshold, (int, float))
        and isinstance(sell_threshold, (int, float))
        and strong_sell_threshold > sell_threshold
    ):
        errors.append("consensus.strong_sell_threshold must be <= consensus.sell_threshold")

    paper = _mapping_section(errors, config, "paper")
    paper_starting_balance = paper.get("starting_balance", None)
    if paper_starting_balance is not None and (
        not isinstance(paper_starting_balance, (int, float))
        or isinstance(paper_starting_balance, bool)
        or paper_starting_balance < 0
    ):
        errors.append("paper.starting_balance must be a non-negative number")
    paper_fee_pct = paper.get("fee_pct", None)
    if paper_fee_pct is not None and (
        not isinstance(paper_fee_pct, (int, float))
        or isinstance(paper_fee_pct, bool)
        or paper_fee_pct < 0
    ):
        errors.append("paper.fee_pct must be non-negative")

    _validate_path_field(errors, "active_symbol_path", config.get("active_symbol_path"), required=True)
    _validate_path_field(errors, "log_path", config.get("log_path"))
    _validate_path_field(errors, "state_path", config.get("state_path"))
    _validate_path_field(errors, "dashboard_status_path", config.get("dashboard_status_path"))
    _validate_path_field(errors, "command_queue_path", config.get("command_queue_path"))
    _validate_path_field(errors, "pid_path", config.get("pid_path"))

    return errors


def validate_state(state: dict[str, Any]) -> bool:
    """Validate state dictionary has required keys."""
    if not isinstance(state, dict):
        return False
    required_keys = {"active_symbol", "positions", "paper_balance"}
    if not required_keys.issubset(state.keys()):
        return False
    active_symbol = state.get("active_symbol")
    if not isinstance(active_symbol, str) or not active_symbol.strip():
        return False
    if not isinstance(state.get("positions"), dict):
        return False
    for pos in state.get("positions", {}).values():
        if not isinstance(pos, dict):
            return False
        required_position_keys = {"symbol", "side", "entry_price", "quantity", "stop_loss", "take_profit"}
        if not required_position_keys.issubset(pos.keys()):
            return False
        if not isinstance(pos.get("symbol"), str) or not pos.get("symbol", "").strip():
            return False
        if not isinstance(pos.get("side"), str) or not pos.get("side", "").strip():
            return False
        for field in ("entry_price", "quantity", "stop_loss", "take_profit"):
            value = pos.get(field)
            if (
                not isinstance(value, (int, float))
                or isinstance(value, bool)
                or not isfinite(value)
            ):
                return False
    paper_balance = state.get("paper_balance")
    if (
        not isinstance(paper_balance, (int, float))
        or isinstance(paper_balance, bool)
        or not isfinite(paper_balance)
    ):
        return False
    trade_history = state.get("trade_history")
    if trade_history is not None:
        if not isinstance(trade_history, list):
            return False
        for item in trade_history:
            if not isinstance(item, dict):
                return False
            required_trade_keys = {"symbol", "action", "side", "entry_price"}
            if not required_trade_keys.issubset(item.keys()):
                return False
            if not isinstance(item.get("symbol"), str) or not item.get("symbol", "").strip():
                return False
            if not isinstance(item.get("action"), str) or not item.get("action", "").strip():
                return False
            if not isinstance(item.get("side"), str) or not item.get("side", "").strip():
                return False
            entry_price = item.get("entry_price")
            if (
                not isinstance(entry_price, (int, float))
                or isinstance(entry_price, bool)
                or not isfinite(entry_price)
            ):
                return False
            pnl = item.get("pnl", 0)
            if (
                not isinstance(pnl, (int, float))
                or isinstance(pnl, bool)
                or not isfinite(pnl)
            ):
                return False

    last_auto_scan = state.get("last_auto_scan")
    if last_auto_scan is not None and not isinstance(last_auto_scan, str):
        return False

    last_scan_results = state.get("last_scan_results")
    if last_scan_results is not None and (
        not isinstance(last_scan_results, list)
        or not all(isinstance(item, dict) for item in last_scan_results)
    ):
        return False

    last_scan_hot_count = state.get("last_scan_hot_count")
    if last_scan_hot_count is not None and (
        not isinstance(last_scan_hot_count, (int, float))
        or isinstance(last_scan_hot_count, bool)
        or not isfinite(last_scan_hot_count)
        or last_scan_hot_count < 0
    ):
        return False

    last_scan_total = state.get("last_scan_total")
    if last_scan_total is not None and (
        not isinstance(last_scan_total, (int, float))
        or isinstance(last_scan_total, bool)
        or not isfinite(last_scan_total)
        or last_scan_total < 0
    ):
        return False

    status_warnings = state.get("status_warnings")
    if status_warnings is not None and (
        not isinstance(status_warnings, list)
        or not all(isinstance(item, str) for item in status_warnings)
    ):
        return False

    last_decision = state.get("last_decision")
    if last_decision is not None:
        if not isinstance(last_decision, dict):
            return False
        required_decision_keys = {"action", "signal", "confidence", "risk_level", "price", "timestamp"}
        if not required_decision_keys.issubset(last_decision.keys()):
            return False
        if not isinstance(last_decision.get("action"), str) or not last_decision.get("action", "").strip():
            return False
        if not isinstance(last_decision.get("signal"), str) or not last_decision.get("signal", "").strip():
            return False
        if not isinstance(last_decision.get("risk_level"), str) or not last_decision.get("risk_level", "").strip():
            return False
        if not isinstance(last_decision.get("timestamp"), str) or not last_decision.get("timestamp", "").strip():
            return False
        confidence = last_decision.get("confidence")
        if (
            not isinstance(confidence, (int, float))
            or isinstance(confidence, bool)
            or not isfinite(confidence)
        ):
            return False
        price = last_decision.get("price")
        if (
            not isinstance(price, (int, float))
            or isinstance(price, bool)
            or not isfinite(price)
        ):
            return False
    return True


# ─── S7: Rescue-safety validation seam ────────────────────────────
#
# These thresholds are deliberately conservative for the rescue build.
# Operators who knowingly need higher risk can leave the warnings in
# place — nothing here raises or mutates config. The list is closed
# (one place to grow it) so S8 can promote individual warnings to
# errors with explicit migration notes.

RESCUE_RISK_PER_TRADE_MAX = 0.05          # >5 % per-trade risk warned
RESCUE_MAX_OPEN_POSITIONS_MAX = 10        # >10 simultaneous positions warned


def _rescue_safety_messages(config: dict[str, Any]) -> tuple[list[str], list[str]]:
    """Return ``(errors, warnings)`` for rescue-build risk surfaces."""
    errors: list[str] = []
    warnings: list[str] = []

    mode_value = config.get("mode", "paper")
    mode = mode_value.strip().lower() if isinstance(mode_value, str) else "paper"
    market_type_value = config.get("market_type", "spot")
    market_type = market_type_value.strip().lower() if isinstance(market_type_value, str) else "spot"

    if mode == "live":
        warnings.append(
            "mode=live — verify API credentials and that risk parameters are intentional"
        )
        if market_type == "futures":
            warnings.append(
                "mode=live + market_type=futures — S5 fail-closed paths active for futures-short"
            )

    risk = config.get("risk")
    if risk is None:
        risk = {}
    elif not isinstance(risk, dict):
        return errors, warnings

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
    return _rescue_safety_messages(config)
