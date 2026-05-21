"""Status exporter - produces a dashboard-readable snapshot after each bot cycle.

The bot writes runtime/dashboard_status.json atomically.
The dashboard reads it. No shared memory, no IPC, no sockets.
"""

import json
from pathlib import Path
from typing import Any

from src.utils.logger import get_logger
from src.utils.helpers import iso_now

logger = get_logger("monitoring.status_exporter")

DEFAULT_DASHBOARD_PATH = "runtime/dashboard_status.json"

# Sentinel for kwargs that distinguish "caller did not pass" from "caller passed None".
_UNSET: Any = object()


class StatusExporter:
    """Exports a dashboard-consumable JSON snapshot after every bot cycle."""

    def __init__(self, output_path: str = DEFAULT_DASHBOARD_PATH) -> None:
        self.output_path = Path(output_path)
        self.output_path.parent.mkdir(parents=True, exist_ok=True)

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
        if indicator_results:
            for r in indicator_results:
                vote = r.to_dict()
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
        for sym, pos in positions_raw.items():
            p = dict(pos)
            price = prices.get(sym)
            if price:
                p["current_price"] = price
                if pos.get("side") == "LONG":
                    unr = (price - pos["entry_price"]) * pos["quantity"]
                else:
                    unr = (pos["entry_price"] - price) * pos["quantity"]
                p["unrealized_pnl"] = round(unr, 4)
                total_unrealized += unr
            open_positions.append(p)

        # Performance from trade history
        trade_history = state.get("trade_history", [])
        perf = _compute_performance(trade_history)

        # Score data from consensus
        score_data = {}
        if consensus:
            score_data = consensus.get("score_data", {})

        snapshot: dict[str, Any] = {
            "bot_status": "running" if running else "stopped",
            "mode": config.get("mode", "paper"),
            "market_type": config.get("market_type", "spot"),
            "timeframe": config.get("timeframe", "1h"),
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
                "action": decision.get("action", "N/A") if decision else "N/A",
                "signal": consensus.get("final_signal", "N/A") if consensus else "N/A",
                "confidence": consensus.get("confidence", 0) if consensus else 0,
                "risk_level": consensus.get("risk_level", "N/A") if consensus else "N/A",
                "weighted_score": round(consensus.get("weighted_score", 0), 4) if consensus else 0,
                "reason": decision.get("reason", "") if decision else "",
                "should_trade": consensus.get("should_trade", False) if consensus else False,
                # `price` is decision metadata — pin it to signal_price
                # (the candle close the decision engine acted on), NOT the
                # freshest live tick. See test_status_exporter_canonical.
                "price": signal_price,
                "leverage": decision.get("leverage", 1) if decision else 1,
                "timestamp": decision.get("timestamp", "") if decision else "",
            },
            "indicator_votes": indicator_votes,
            "signal_distribution": {
                "buy": buy_count,
                "sell": sell_count,
                "neutral": neutral_count,
            },
            "score_details": score_data.get("signal_details", []) if score_data else [],
            "trade_history": trade_history[-50:],
            "performance": perf,
            "bot_start_time": state.get("bot_start_time"),
            "last_auto_scan": state.get("last_auto_scan"),
            "last_scan_results": state.get("last_scan_results", []),
            "last_scan_hot_count": state.get("last_scan_hot_count", 0),
            "last_scan_total": state.get("last_scan_total", 0),
            # S5: surface config-time invariants (e.g. futures live short
            # unsupported) so the dashboard can render an honest banner.
            "status_warnings": list(status_warnings or []),
        }

        self._write_atomic(snapshot)
        logger.debug("Dashboard status exported")

    def export_stopped(self, config: dict[str, Any], state: dict[str, Any]) -> None:
        """Export a minimal stopped-state snapshot."""
        snapshot = {
            "bot_status": "stopped",
            "mode": config.get("mode", "paper"),
            "market_type": config.get("market_type", "spot"),
            "timeframe": config.get("timeframe", "1h"),
            "polling_interval": config.get("polling_interval_seconds", 60),
            "active_symbol": state.get("active_symbol", ""),
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
            "balance": round(state.get("paper_balance", 0), 4),
            "daily_pnl": round(state.get("daily_pnl", 0), 4),
            "total_pnl": round(state.get("total_realized_pnl", 0), 4),
            "unrealized_pnl": 0.0,
            "daily_start_balance": round(state.get("daily_start_balance", 0), 4),
            "open_positions_count": len(state.get("positions", {})),
            "open_positions": list(state.get("positions", {}).values()),
            "latest_decision": state.get("last_decision"),
            "indicator_votes": [],
            "signal_distribution": {"buy": 0, "sell": 0, "neutral": 0},
            "score_details": [],
            "trade_history": state.get("trade_history", [])[-50:],
            "performance": _compute_performance(state.get("trade_history", [])),
            "bot_start_time": state.get("bot_start_time"),
        }
        self._write_atomic(snapshot)

    def _write_atomic(self, data: dict[str, Any]) -> None:
        """Atomic write via tmp file + rename."""
        try:
            tmp = self.output_path.with_suffix(".tmp")
            with open(tmp, "w") as f:
                json.dump(data, f, indent=2, default=str)
            tmp.replace(self.output_path)
        except Exception as e:
            logger.error(f"Failed to write dashboard status: {e}")


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

    pnls = [t.get("pnl", 0.0) for t in trade_history]
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
