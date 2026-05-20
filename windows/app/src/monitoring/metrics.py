"""Metrics computation for the dashboard performance view."""

from typing import Any
from datetime import datetime, timezone


def compute_daily_summary(trade_history: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Group trade history by date and produce daily summaries."""
    daily: dict[str, list[float]] = {}
    for t in trade_history:
        exit_time = t.get("exit_time", "")
        if not exit_time:
            continue
        try:
            if isinstance(exit_time, str):
                date_str = exit_time[:10]
            else:
                date_str = str(exit_time)[:10]
        except Exception:
            continue
        daily.setdefault(date_str, []).append(t.get("pnl", 0.0))

    summaries: list[dict[str, Any]] = []
    for date_str in sorted(daily.keys()):
        pnls = daily[date_str]
        wins = [p for p in pnls if p > 0]
        losses = [p for p in pnls if p < 0]
        summaries.append({
            "date": date_str,
            "trades": len(pnls),
            "wins": len(wins),
            "losses": len(losses),
            "pnl": round(sum(pnls), 4),
            "win_rate": round(len(wins) / len(pnls) * 100, 1) if pnls else 0.0,
        })
    return summaries


def compute_equity_curve(
    starting_balance: float,
    trade_history: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Compute equity curve from trade history."""
    curve = [{"trade_num": 0, "equity": starting_balance, "pnl": 0.0}]
    equity = starting_balance
    for i, t in enumerate(trade_history):
        pnl = t.get("pnl", 0.0)
        equity += pnl
        curve.append({
            "trade_num": i + 1,
            "equity": round(equity, 4),
            "pnl": round(pnl, 4),
        })
    return curve


def compute_streak(trade_history: list[dict[str, Any]]) -> dict[str, int]:
    """Compute current and max win/loss streaks."""
    if not trade_history:
        return {"current_streak": 0, "max_win_streak": 0, "max_loss_streak": 0}

    max_win = 0
    max_loss = 0
    current = 0
    current_type = None

    for t in trade_history:
        pnl = t.get("pnl", 0.0)
        if pnl > 0:
            if current_type == "win":
                current += 1
            else:
                current = 1
                current_type = "win"
            max_win = max(max_win, current)
        elif pnl < 0:
            if current_type == "loss":
                current += 1
            else:
                current = 1
                current_type = "loss"
            max_loss = max(max_loss, current)
        else:
            current = 0
            current_type = None

    return {
        "current_streak": current if current_type == "win" else -current if current_type == "loss" else 0,
        "max_win_streak": max_win,
        "max_loss_streak": max_loss,
    }
