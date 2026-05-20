#!/usr/bin/env python3
"""CLI tool: Show current bot status from dashboard_status.json and state.json.

Usage:
    python scripts/show_status.py
    python scripts/show_status.py --full
    python scripts/show_status.py --json
"""

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))


def load_json(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return None


def format_time_ago(iso_str: str) -> str:
    """Convert ISO timestamp to human-readable time ago."""
    try:
        dt = datetime.fromisoformat(iso_str)
        now = datetime.now(timezone.utc)
        delta = now - dt
        secs = int(delta.total_seconds())
        if secs < 60:
            return f"{secs}s ago"
        elif secs < 3600:
            return f"{secs // 60}m {secs % 60}s ago"
        elif secs < 86400:
            return f"{secs // 3600}h {(secs % 3600) // 60}m ago"
        else:
            return f"{secs // 86400}d ago"
    except Exception:
        return iso_str or "N/A"


def print_compact(status: dict, state: dict | None):
    """Print compact status summary."""
    bot = status.get("bot_status", "unknown")
    last = status.get("last_update", "")
    stale = ""
    try:
        dt = datetime.fromisoformat(last)
        delta = (datetime.now(timezone.utc) - dt).total_seconds()
        if delta > 300:
            stale = " (STALE DATA)"
    except Exception:
        pass

    print("=" * 55)
    print(f"  TRADING BOT STATUS{stale}")
    print("=" * 55)
    print(f"  Status:       {bot.upper()}")
    print(f"  Mode:         {status.get('mode', 'N/A')}")
    print(f"  Market:       {status.get('market_type', 'N/A')}")
    print(f"  Timeframe:    {status.get('timeframe', 'N/A')}")
    print(f"  Symbol:       {status.get('active_symbol', 'N/A')}")
    print(f"  Price:        {status.get('current_price', 'N/A')}")
    print(f"  Last Update:  {format_time_ago(last)}")
    print(f"  Cycle:        #{status.get('cycle_count', 0)}")
    print("-" * 55)
    print(f"  Balance:      ${status.get('balance', 0):,.4f}")
    print(f"  Daily PnL:    ${status.get('daily_pnl', 0):,.4f}")
    print(f"  Total PnL:    ${status.get('total_pnl', 0):,.4f}")
    print(f"  Unrealized:   ${status.get('unrealized_pnl', 0):,.4f}")
    print(f"  Positions:    {status.get('open_positions_count', 0)}")
    print("-" * 55)

    decision = status.get("latest_decision") or {}
    print(f"  Signal:       {decision.get('signal', 'N/A')}")
    print(f"  Confidence:   {decision.get('confidence', 'N/A')}%")
    print(f"  Risk:         {decision.get('risk_level', 'N/A')}")
    print(f"  Action:       {decision.get('action', 'N/A')}")
    print("-" * 55)

    dist = status.get("signal_distribution", {})
    print(f"  Indicators:   BUY={dist.get('buy', 0)} | SELL={dist.get('sell', 0)} | NEUTRAL={dist.get('neutral', 0)}")

    perf = status.get("performance", {})
    if perf and perf.get("total_trades", 0) > 0:
        print("-" * 55)
        print(f"  Trades:       {perf['total_trades']}  (W:{perf['wins']} / L:{perf['losses']})")
        print(f"  Win Rate:     {perf['win_rate']}%")
        print(f"  Avg PnL:      ${perf['avg_pnl']:,.4f}")
    print("=" * 55)


def print_full(status: dict, state: dict | None):
    """Print full status with positions and indicator details."""
    print_compact(status, state)

    positions = status.get("open_positions", [])
    if positions:
        print("\n  OPEN POSITIONS:")
        print("  " + "-" * 53)
        for p in positions:
            print(f"    {p.get('symbol', 'N/A')} | {p.get('side', 'N/A')} | "
                  f"entry={p.get('entry_price', 0)} | qty={p.get('quantity', 0)} | "
                  f"SL={p.get('stop_loss', 0)} | TP={p.get('take_profit', 0)} | "
                  f"PnL={p.get('unrealized_pnl', 0):.4f}")

    votes = status.get("indicator_votes", [])
    if votes:
        print(f"\n  INDICATOR SIGNALS ({len(votes)}):")
        print("  " + "-" * 53)
        for v in votes:
            score_str = f"+{v['score']}" if v["score"] > 0 else str(v["score"])
            print(f"    {v['name']:15s} | {v['signal']:12s} ({score_str:>3s}) | {v['reason'][:50]}")

    history = status.get("trade_history", [])
    if history:
        print(f"\n  RECENT TRADES (last {min(len(history), 10)}):")
        print("  " + "-" * 53)
        for t in history[-10:]:
            print(f"    {t.get('side', 'N/A'):5s} | entry={t.get('entry_price', 0)} -> "
                  f"exit={t.get('exit_price', 'open')} | PnL={t.get('pnl', 0):.4f} | "
                  f"{t.get('reason', '')[:30]}")


def main():
    parser = argparse.ArgumentParser(description="Show current bot status (read-only)")
    parser.add_argument("--full", action="store_true", help="Show full details including indicators and trades")
    parser.add_argument("--json", action="store_true", help="Output raw JSON")
    parser.add_argument("--status-file", default="runtime/dashboard_status.json", help="Path to dashboard status file")
    parser.add_argument("--state-file", default="runtime/state.json", help="Path to state file")
    args = parser.parse_args()

    status_path = Path(args.status_file)
    state_path = Path(args.state_file)

    status = load_json(status_path)
    state = load_json(state_path)

    if status is None and state is None:
        print("ERROR: No status data found. Is the bot running?", file=sys.stderr)
        print(f"  Checked: {status_path}", file=sys.stderr)
        print(f"  Checked: {state_path}", file=sys.stderr)
        sys.exit(1)

    # Fallback: if dashboard_status.json missing, build minimal from state.json
    if status is None and state:
        status = {
            "bot_status": "unknown (no dashboard snapshot)",
            "mode": "N/A",
            "active_symbol": state.get("active_symbol", "N/A"),
            "current_price": None,
            "last_update": state.get("last_save_time", ""),
            "cycle_count": 0,
            "balance": state.get("paper_balance", 0),
            "daily_pnl": state.get("daily_pnl", 0),
            "total_pnl": state.get("total_realized_pnl", 0),
            "unrealized_pnl": 0,
            "open_positions_count": len(state.get("positions", {})),
            "open_positions": list(state.get("positions", {}).values()),
            "latest_decision": state.get("last_decision"),
            "signal_distribution": {"buy": 0, "sell": 0, "neutral": 0},
            "indicator_votes": [],
            "trade_history": state.get("trade_history", []),
            "performance": {},
        }

    if args.json:
        print(json.dumps(status, indent=2, default=str))
    elif args.full:
        print_full(status, state)
    else:
        print_compact(status, state)


if __name__ == "__main__":
    main()
