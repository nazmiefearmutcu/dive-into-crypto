"""Status exporter - produces a dashboard-readable snapshot after each bot cycle.

The bot writes runtime/dashboard_status.json atomically.
The dashboard reads it. No shared memory, no IPC, no sockets.
"""

from pathlib import Path
from typing import Any

from src.persistence.atomic_io import atomic_write_json
from src.utils.logger import get_logger
from src.utils.helpers import iso_now

logger = get_logger("monitoring.status_exporter")

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_DASHBOARD_PATH = str(PROJECT_ROOT / "runtime" / "dashboard_status.json")

# Sentinel for kwargs that distinguish "caller did not pass" from "caller passed None".
_UNSET: Any = object()


def _resolve_dashboard_path(output_path: str) -> Path:
    text = output_path.strip()
    if text in {"", "."}:
        return Path(DEFAULT_DASHBOARD_PATH)

    path = Path(text).expanduser()
    if not path.is_absolute():
        path = PROJECT_ROOT / path

    if path.exists() and path.is_dir():
        return path / "dashboard_status.json"
    if not path.suffix and not path.exists():
        return path / "dashboard_status.json"
    return path


def _coerce_runtime_list(value: Any) -> list[Any]:
    if isinstance(value, list):
        return value
    if isinstance(value, tuple):
        return list(value)
    return []


def _coerce_runtime_dict_list(value: Any) -> list[dict[str, Any]]:
    items = _coerce_runtime_list(value)
    result: list[dict[str, Any]] = []
    for item in items:
        if isinstance(item, dict):
            result.append(dict(item))
    return result


def _coerce_warning_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, (list, tuple)):
        warnings: list[str] = []
        for item in value:
            if item is None:
                continue
            warnings.append(item if isinstance(item, str) else str(item))
        return warnings
    return [str(value)]


def _coerce_optional_str(value: Any) -> str | None:
    return value if isinstance(value, str) else None


def _coerce_number(value: Any) -> int | float:
    if isinstance(value, bool):
        return 0
    if isinstance(value, (int, float)):
        return value
    return 0


class StatusExporter:
    """Exports a dashboard-consumable JSON snapshot after every bot cycle."""

    def __init__(self, output_path: str = DEFAULT_DASHBOARD_PATH) -> None:
        if isinstance(output_path, (str, Path)):
            self.output_path = _resolve_dashboard_path(str(output_path))
        else:
            self.output_path = Path(DEFAULT_DASHBOARD_PATH)
        self.output_path.parent.mkdir(parents=True, exist_ok=True)
        self._last_write_error: str | None = None

    def export(
        self,
        config: dict[str, Any],
        state: dict[str, Any],
        consensus: dict[str, Any] | None,
        indicator_results: list[Any] | None,
        decision: dict[str, Any] | None,
        execution_result: dict[str, Any] | None,
        balance: float,
        current_price: float | None,
        cycle_count: int,
        running: bool,
        all_prices: dict[str, float] | None = None,
        *,
        signal_price: float | None = _UNSET,
        display_price: float | None = _UNSET,
        display_price_source: str | None = None,
        price_age_ms: int | None = None,
        mark_price: float | None = None,
        best_bid: float | None = None,
        best_ask: float | None = None,
        status_warnings: list[str] | None = None,
    ) -> None:
        """Build and atomically write the dashboard snapshot.

        S3 adds the canonical price fields:

        - ``signal_price`` is the candle close the decision engine used. It
          mirrors ``latest_decision.price`` and changes only when a new
          candle closes.
        - ``display_price`` is the freshest ticker the bot pulled from the
          LivePriceService cache (REST polling rescue today). The dashboard
          puts this on screen. May be more recent than ``signal_price``.
        - ``display_price_source`` labels the transport ("rest:binance" /
          "fake" / "cycle_close" / "unavailable").
        - ``price_age_ms`` is how old the display price is, in milliseconds.

        ``current_price`` is preserved as a mirror of ``display_price`` so
        S2 dashboard truth tests keep passing verbatim.
        """
        # Default signal_price to the cycle's current_price when the caller
        # doesn't yet pass it (pre-S3 call sites).
        if signal_price is _UNSET:
            signal_price = current_price
        if display_price is _UNSET:
            display_price = current_price

        config = config if isinstance(config, dict) else {}
        state = state if isinstance(state, dict) else {}
        consensus_data = consensus if isinstance(consensus, dict) else {}
        decision_data = decision if isinstance(decision, dict) else {}
        indicator_source = indicator_results if isinstance(indicator_results, (list, tuple)) else []

        # Resolve display_price_source honestly.
        if display_price is None:
            resolved_source = "unavailable"
        elif display_price_source:
            resolved_source = display_price_source
        elif display_price == signal_price:
            resolved_source = "cycle_close"
        else:
            resolved_source = "rest:binance"

        # current_price is the legacy mirror of display_price for S2 compat.
        current_display = display_price
        # Indicator votes
        indicator_votes: list[dict[str, Any]] = []
        buy_count = 0
        sell_count = 0
        neutral_count = 0
        if indicator_source:
            for r in indicator_source:
                try:
                    if hasattr(r, "to_dict"):
                        vote = r.to_dict()
                    elif isinstance(r, dict):
                        vote = dict(r)
                    else:
                        continue
                except Exception:
                    continue
                indicator_votes.append(vote)
                sig = vote.get("signal", "NEUTRAL")
                if sig in ("BUY", "STRONG_BUY"):
                    buy_count += 1
                elif sig in ("SELL", "STRONG_SELL"):
                    sell_count += 1
                else:
                    neutral_count += 1

        # Open positions list — calculate unrealized PnL for ALL positions
        positions_raw = state.get("positions", {})
        open_positions: list[dict[str, Any]] = []
        total_unrealized = 0.0
        prices = dict(all_prices) if all_prices else {}
        # Active symbol price is always available from the cycle.
        # Prefer display_price (the live tick) for unrealized PnL math
        # because the dashboard shows that one; fall back to current_price.
        _active_pos_price = current_display if current_display is not None else current_price
        if _active_pos_price and state.get("active_symbol"):
            prices.setdefault(state["active_symbol"], _active_pos_price)
        if isinstance(positions_raw, dict):
            for sym, pos in positions_raw.items():
                if not isinstance(pos, dict):
                    continue
                p = dict(pos)
                try:
                    entry_price = float(pos.get("entry_price", 0) or 0)
                    quantity = float(pos.get("quantity", 0) or 0)
                except Exception:
                    continue
                price = prices.get(sym)
                if price is not None:
                    try:
                        price_val = float(price)
                    except Exception:
                        price_val = None
                    if price_val is not None:
                        p["current_price"] = price_val
                        if pos.get("side") == "LONG":
                            unr = (price_val - entry_price) * quantity
                        else:
                            unr = (entry_price - price_val) * quantity
                        p["unrealized_pnl"] = round(unr, 4)
                        total_unrealized += unr
                open_positions.append(p)

        # Performance from trade history
        trade_history = _coerce_runtime_list(state.get("trade_history", []))
        perf = _compute_performance(trade_history)

        # Score data from consensus
        score_data = consensus_data.get("score_data", {}) if consensus_data else {}
        if not isinstance(score_data, dict):
            score_data = {}

        mode_value = config.get("mode", "paper")
        mode = mode_value.strip().lower() if isinstance(mode_value, str) else "paper"
        market_type_value = config.get("market_type", "spot")
        market_type = market_type_value.strip().lower() if isinstance(market_type_value, str) else "spot"
        timeframe_value = config.get("timeframe", "1h")
        if isinstance(timeframe_value, str):
            tf = timeframe_value.strip()
            timeframe = "1M" if tf.upper() == "1M" else tf.lower()
        else:
            timeframe = "1h"

        snapshot: dict[str, Any] = {
            "bot_status": "running" if running else "stopped",
            "mode": mode,
            "market_type": market_type,
            "timeframe": timeframe,
            "polling_interval": config.get("polling_interval_seconds", 60),
            "active_symbol": state.get("active_symbol", ""),
            # S2 backward-compat: current_price = display_price.
            "current_price": current_display,
            # S3 canonical price contract:
            "display_price": display_price,
            "display_price_source": resolved_source,
            "price_age_ms": price_age_ms,
            "signal_price": signal_price,
            "mark_price": mark_price,
            "best_bid": best_bid,
            "best_ask": best_ask,
            "last_update": iso_now(),
            "cycle_count": cycle_count,
            "balance": round(balance, 4),
            "daily_pnl": round(state.get("daily_pnl", 0.0), 4),
            "total_pnl": round(state.get("total_realized_pnl", 0.0), 4),
            "unrealized_pnl": round(total_unrealized, 4),
            "daily_start_balance": round(state.get("daily_start_balance", balance), 4),
            "open_positions_count": len(open_positions),
            "open_positions": open_positions,
            "latest_decision": {
                "action": decision_data.get("action", "N/A"),
                "signal": consensus_data.get("final_signal", "N/A"),
                "confidence": consensus_data.get("confidence", 0),
                "risk_level": consensus_data.get("risk_level", "N/A"),
                "weighted_score": round(consensus_data.get("weighted_score", 0), 4),
                "reason": decision_data.get("reason", ""),
                "should_trade": consensus_data.get("should_trade", False),
                # `price` is decision metadata — pin it to signal_price
                # (the candle close the decision engine acted on), NOT the
                # freshest live tick. See test_status_exporter_canonical.
                "price": signal_price,
                "leverage": decision_data.get("leverage", 1),
                "timestamp": decision_data.get("timestamp", ""),
            },
            "indicator_votes": indicator_votes,
            "signal_distribution": {
                "buy": buy_count,
                "sell": sell_count,
                "neutral": neutral_count,
            },
            "score_details": _coerce_runtime_dict_list(score_data.get("signal_details", [])) if score_data else [],
            "trade_history": _coerce_runtime_dict_list(trade_history)[-50:],
            "performance": perf,
            "bot_start_time": state.get("bot_start_time"),
            "last_auto_scan": _coerce_optional_str(state.get("last_auto_scan")),
            "last_scan_results": _coerce_runtime_dict_list(state.get("last_scan_results", [])),
            "last_scan_hot_count": _coerce_number(state.get("last_scan_hot_count", 0)),
            "last_scan_total": _coerce_number(state.get("last_scan_total", 0)),
            # S5: surface config-time invariants (e.g. futures live short
            # unsupported) so the dashboard can render an honest banner.
            "status_warnings": _coerce_warning_list(status_warnings),
        }

        self._write_atomic(snapshot)
        logger.debug("Dashboard status exported")

    def export_stopped(self, config: dict[str, Any], state: dict[str, Any]) -> None:
        """Export a minimal stopped-state snapshot."""
        config = config if isinstance(config, dict) else {}
        state = state if isinstance(state, dict) else {}
        mode_value = config.get("mode", "paper")
        mode = mode_value.strip().lower() if isinstance(mode_value, str) else "paper"
        market_type_value = config.get("market_type", "spot")
        market_type = market_type_value.strip().lower() if isinstance(market_type_value, str) else "spot"
        timeframe_value = config.get("timeframe", "1h")
        if isinstance(timeframe_value, str):
            tf = timeframe_value.strip()
            timeframe = "1M" if tf.upper() == "1M" else tf.lower()
        else:
            timeframe = "1h"

        positions_raw = state.get("positions", {})
        if not isinstance(positions_raw, dict):
            positions_raw = {}
        open_positions = [dict(pos) for pos in positions_raw.values() if isinstance(pos, dict)]
        trade_history = _coerce_runtime_list(state.get("trade_history", []))
        latest_decision_raw = state.get("last_decision", {})
        latest_decision = latest_decision_raw if isinstance(latest_decision_raw, dict) else {}

        snapshot = {
            "bot_status": "stopped",
            "mode": mode,
            "market_type": market_type,
            "timeframe": timeframe,
            "polling_interval": config.get("polling_interval_seconds", 60),
            "active_symbol": _coerce_optional_str(state.get("active_symbol")) or "",
            "current_price": None,
            # S3 canonical fields — honest 'unavailable' for stopped state.
            "display_price": None,
            "display_price_source": "unavailable",
            "price_age_ms": None,
            "signal_price": None,
            "mark_price": None,
            "best_bid": None,
            "best_ask": None,
            "last_update": iso_now(),
            "cycle_count": 0,
            "balance": round(_coerce_number(state.get("paper_balance", 0)), 4),
            "daily_pnl": round(_coerce_number(state.get("daily_pnl", 0)), 4),
            "total_pnl": round(_coerce_number(state.get("total_realized_pnl", 0)), 4),
            "unrealized_pnl": 0.0,
            "daily_start_balance": round(_coerce_number(state.get("daily_start_balance", 0)), 4),
            "open_positions_count": len(open_positions),
            "open_positions": open_positions,
            "latest_decision": {
                "action": latest_decision.get("action", "N/A"),
                "signal": latest_decision.get("signal", "N/A"),
                "confidence": _coerce_number(latest_decision.get("confidence", 0)),
                "risk_level": _coerce_optional_str(latest_decision.get("risk_level")) or "N/A",
                "weighted_score": _coerce_number(latest_decision.get("weighted_score", 0)),
                "reason": _coerce_optional_str(latest_decision.get("reason")) or "",
                "should_trade": bool(latest_decision.get("should_trade", False)),
                "price": latest_decision.get("price"),
                "leverage": _coerce_number(latest_decision.get("leverage", 1)),
                "timestamp": _coerce_optional_str(latest_decision.get("timestamp")) or "",
            },
            "indicator_votes": [],
            "signal_distribution": {"buy": 0, "sell": 0, "neutral": 0},
            "score_details": [],
            "trade_history": _coerce_runtime_dict_list(trade_history)[-50:],
            "performance": _compute_performance(trade_history),
            "bot_start_time": state.get("bot_start_time"),
            "last_auto_scan": _coerce_optional_str(state.get("last_auto_scan")),
            "last_scan_results": _coerce_runtime_dict_list(state.get("last_scan_results", [])),
            "last_scan_hot_count": _coerce_number(state.get("last_scan_hot_count", 0)),
            "last_scan_total": _coerce_number(state.get("last_scan_total", 0)),
            "status_warnings": _coerce_warning_list(state.get("status_warnings", [])),
        }
        self._write_atomic(snapshot)

    def _write_atomic(self, data: dict[str, Any]) -> None:
        """Atomic write via tmp file + rename."""
        try:
            self._last_write_error = None
            atomic_write_json(self.output_path, data, indent=2)
        except Exception as e:
            self._last_write_error = str(e)
            logger.error(f"Failed to write dashboard status: {e}")
            try:
                if self.output_path.exists():
                    self.output_path.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale dashboard status after write error: {cleanup_exc}"
                )


def _compute_performance(trade_history: list[dict[str, Any]]) -> dict[str, Any]:
    """Compute performance metrics from trade history."""
    if not trade_history:
        return {
            "total_trades": 0,
            "wins": 0,
            "losses": 0,
            "win_rate": 0.0,
            "avg_pnl": 0.0,
            "total_pnl": 0.0,
            "best_trade": 0.0,
            "worst_trade": 0.0,
            "max_drawdown": 0.0,
            "avg_win": 0.0,
            "avg_loss": 0.0,
            "profit_factor": 0.0,
        }

    pnls: list[float] = []
    for t in trade_history:
        try:
            pnls.append(float(t.get("pnl", 0.0) or 0.0))
        except Exception:
            pnls.append(0.0)
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]
    total_pnl = sum(pnls)

    # Max drawdown
    cumulative = 0.0
    peak = 0.0
    max_dd = 0.0
    for p in pnls:
        cumulative += p
        if cumulative > peak:
            peak = cumulative
        dd = peak - cumulative
        if dd > max_dd:
            max_dd = dd

    gross_profit = sum(wins) if wins else 0.0
    gross_loss = abs(sum(losses)) if losses else 0.0

    return {
        "total_trades": len(pnls),
        "wins": len(wins),
        "losses": len(losses),
        "win_rate": round(len(wins) / len(pnls) * 100, 1) if pnls else 0.0,
        "avg_pnl": round(total_pnl / len(pnls), 4) if pnls else 0.0,
        "total_pnl": round(total_pnl, 4),
        "best_trade": round(max(pnls), 4) if pnls else 0.0,
        "worst_trade": round(min(pnls), 4) if pnls else 0.0,
        "max_drawdown": round(max_dd, 4),
        "avg_win": round(sum(wins) / len(wins), 4) if wins else 0.0,
        "avg_loss": round(sum(losses) / len(losses), 4) if losses else 0.0,
        "profit_factor": round(gross_profit / gross_loss, 2) if gross_loss > 0 else 0.0,
    }
