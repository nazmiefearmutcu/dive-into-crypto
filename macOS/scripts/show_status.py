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

from src.persistence.schemas import DashboardStatusSchema, StateSchema
from src.utils.validators import validate_state


def load_json(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
        return None
    except Exception:
        return None


def _resolve_runtime_file(value: object, default_filename: str) -> Path:
    if value is None:
        return Path("runtime") / default_filename
    text = str(value).strip()
    if text in {"", "."}:
        return Path("runtime") / default_filename

    path = Path(text).expanduser()
    if path.exists():
        if path.is_dir():
            return path / default_filename
        return path
    if not path.suffix:
        return path / default_filename
    return path


def _ensure_dict(value: object) -> dict:
    return value if isinstance(value, dict) else {}


def _ensure_list(value: object) -> list:
    return value if isinstance(value, list) else []


def _ensure_warning_list(value: object) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        warnings: list[str] = []
        for item in value:
            if item is None:
                continue
            warnings.append(item if isinstance(item, str) else str(item))
        return warnings
    return [str(value)]


def _has_valid_status_snapshot(value: object) -> bool:
    if not isinstance(value, dict):
        return False
    required = {"bot_status", "last_update", "active_symbol"}
    if not required.issubset(value.keys()):
        return False
    try:
        DashboardStatusSchema.from_legacy(value)
    except Exception:
        return False
    return True


def _has_valid_state_snapshot(value: object) -> bool:
    if not isinstance(value, dict):
        return False
    if not validate_state(value):
        return False
    try:
        StateSchema.from_legacy(value)
    except Exception:
        return False
    return True


def _has_useful_multi_scan_snapshot(value: object) -> bool:
    if not isinstance(value, dict):
        return False
    return any(key in value for key in ("cross_ranking", "timeframes", "scan_time", "status_warnings", "warnings"))


def _has_useful_auto_scan_snapshot(value: object) -> bool:
    if not isinstance(value, dict):
        return False
    useful_keys = {
        "scanning",
        "state",
        "pct",
        "done",
        "total",
        "last_auto_scan",
        "last_scan_results",
        "last_scan_hot_count",
        "last_scan_total",
        "warnings",
        "status_warnings",
    }
    return any(key in value for key in useful_keys)


def _merge_warning_lists(*values: object) -> list[str]:
    merged: list[str] = []
    seen: set[str] = set()
    for value in values:
        for item in _ensure_warning_list(value):
            if item in seen:
                continue
            seen.add(item)
            merged.append(item)
    return merged


def _is_newer_snapshot(candidate_ts: object, existing_ts: object) -> bool:
    candidate_dt = _parse_iso_datetime(candidate_ts) if isinstance(candidate_ts, str) else None
    if candidate_dt is None:
        return False
    existing_dt = _parse_iso_datetime(existing_ts) if isinstance(existing_ts, str) else None
    if existing_dt is None:
        return True
    return candidate_dt > existing_dt


def _merge_multi_scan_snapshot(status: dict, multi_scan: object) -> dict:
    status = _ensure_dict(status)
    multi = _ensure_dict(multi_scan)
    if not multi:
        return status

    warnings = _merge_warning_lists(status.get("status_warnings"), multi.get("status_warnings"), multi.get("warnings"))
    if warnings:
        status["status_warnings"] = warnings

    scan_time = multi.get("scan_time")
    if isinstance(scan_time, str) and scan_time and _is_newer_snapshot(scan_time, status.get("last_auto_scan") or status.get("last_update")):
        status["last_auto_scan"] = scan_time
        status["last_update"] = scan_time
        cross_ranking = multi.get("cross_ranking")
        if isinstance(cross_ranking, list):
            status["last_scan_results"] = cross_ranking
            timeframes = multi.get("timeframes") if isinstance(multi.get("timeframes"), dict) else {}
            totals = [int(t.get("total_scanned", 0)) for t in timeframes.values() if isinstance(t, dict) and isinstance(t.get("total_scanned"), (int, float))]
            status["last_scan_total"] = max(totals or [len(cross_ranking)])
            status["last_scan_hot_count"] = len(multi.get("common_symbols", []))
    else:
        cross_ranking = multi.get("cross_ranking")
        if isinstance(cross_ranking, list) and not status.get("last_scan_results"):
            status["last_scan_results"] = cross_ranking
            timeframes = multi.get("timeframes") if isinstance(multi.get("timeframes"), dict) else {}
            totals = [int(t.get("total_scanned", 0)) for t in timeframes.values() if isinstance(t, dict) and isinstance(t.get("total_scanned"), (int, float))]
            status["last_scan_total"] = max(totals or [len(cross_ranking)])
            status["last_scan_hot_count"] = len(multi.get("common_symbols", []))
    return status


def _merge_auto_scan_snapshot(status: dict, auto_scan: object) -> dict:
    status = _ensure_dict(status)
    auto = _ensure_dict(auto_scan)
    if not auto:
        return status

    warnings = _merge_warning_lists(status.get("status_warnings"), auto.get("warnings"), auto.get("status_warnings"))
    if warnings:
        status["status_warnings"] = warnings

    if _is_newer_snapshot(auto.get("last_auto_scan"), status.get("last_auto_scan") or status.get("last_update")):
        for key in ("last_auto_scan", "last_scan_results", "last_scan_total", "last_scan_hot_count"):
            value = auto.get(key)
            if value is None:
                continue
            if key == "last_scan_results" and not isinstance(value, list):
                continue
            if key in {"last_scan_total", "last_scan_hot_count"} and not isinstance(value, (int, float)):
                continue
            status[key] = value
        if isinstance(auto.get("last_auto_scan"), str):
            status["last_update"] = auto["last_auto_scan"]
    else:
        for key in ("last_auto_scan", "last_scan_results", "last_scan_total", "last_scan_hot_count"):
            value = auto.get(key)
            if value is None or status.get(key):
                continue
            if key == "last_scan_results" and not isinstance(value, list):
                continue
            if key in {"last_scan_total", "last_scan_hot_count"} and not isinstance(value, (int, float)):
                continue
            status[key] = value

    if auto.get("scanning") is True and not status.get("bot_status"):
        status["bot_status"] = "scanning"
    return status


def _safe_float(value: object, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _safe_int(value: object, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _parse_iso_datetime(iso_str: str) -> datetime | None:
    try:
        text = str(iso_str)
    except Exception:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except Exception:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


def format_time_ago(iso_str: str) -> str:
    """Convert ISO timestamp to human-readable time ago."""
    try:
        dt = _parse_iso_datetime(iso_str)
        if dt is None:
            return "INVALID TIMESTAMP" if iso_str else "N/A"
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
        return "INVALID TIMESTAMP" if iso_str else "N/A"


def print_compact(status: dict, state: dict | None):
    """Print compact status summary."""
    status = _ensure_dict(status)
    bot = status.get("bot_status", "unknown")
    last = status.get("last_update", "")
    stale = ""
    try:
        dt = _parse_iso_datetime(last)
        if dt is None:
            raise ValueError("invalid timestamp")
        delta = (datetime.now(timezone.utc) - dt).total_seconds()
        if delta > 300:
            stale = " (STALE DATA)"
    except Exception:
        if last:
            stale = " (INVALID TIMESTAMP)"

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
    print(f"  Cycle:        #{_safe_int(status.get('cycle_count', 0))}")
    print("-" * 55)
    print(f"  Balance:      ${_safe_float(status.get('balance', 0)):,.4f}")
    print(f"  Daily PnL:    ${_safe_float(status.get('daily_pnl', 0)):,.4f}")
    print(f"  Total PnL:    ${_safe_float(status.get('total_pnl', 0)):,.4f}")
    print(f"  Unrealized:   ${_safe_float(status.get('unrealized_pnl', 0)):,.4f}")
    print(f"  Positions:    {_safe_int(status.get('open_positions_count', 0))}")
    print("-" * 55)

    decision = _ensure_dict(status.get("latest_decision"))
    print(f"  Signal:       {decision.get('signal', 'N/A')}")
    print(f"  Confidence:   {_safe_float(decision.get('confidence', 0)):.1f}%")
    print(f"  Risk:         {decision.get('risk_level', 'N/A')}")
    print(f"  Action:       {decision.get('action', 'N/A')}")

    warnings = _ensure_warning_list(status.get("status_warnings"))
    if warnings:
        print("-" * 55)
        print("  WARNINGS:")
        for warning in warnings[:5]:
            print(f"    - {warning}")

    print("-" * 55)

    dist = _ensure_dict(status.get("signal_distribution"))
    print(f"  Indicators:   BUY={_safe_int(dist.get('buy', 0))} | SELL={_safe_int(dist.get('sell', 0))} | NEUTRAL={_safe_int(dist.get('neutral', 0))}")

    perf = _ensure_dict(status.get("performance"))
    if perf and _safe_int(perf.get("total_trades", 0)) > 0:
        print("-" * 55)
        print(
            f"  Trades:       {_safe_int(perf.get('total_trades', 0))}  "
            f"(W:{_safe_int(perf.get('wins', 0))} / L:{_safe_int(perf.get('losses', 0))})"
        )
        print(f"  Win Rate:     {_safe_float(perf.get('win_rate', 0)):.1f}%")
        print(f"  Avg PnL:      ${_safe_float(perf.get('avg_pnl', 0)):,.4f}")
    print("=" * 55)


def print_full(status: dict, state: dict | None):
    """Print full status with positions and indicator details."""
    status = _ensure_dict(status)
    state = _ensure_dict(state if state is not None else {})
    print_compact(status, state)

    positions = [_ensure_dict(p) for p in _ensure_list(status.get("open_positions", []))]
    if positions:
        print("\n  OPEN POSITIONS:")
        print("  " + "-" * 53)
        for p in positions:
            print(f"    {p.get('symbol', 'N/A')} | {p.get('side', 'N/A')} | "
                  f"entry={p.get('entry_price', 0)} | qty={p.get('quantity', 0)} | "
                  f"SL={p.get('stop_loss', 0)} | TP={p.get('take_profit', 0)} | "
                  f"PnL={_safe_float(p.get('unrealized_pnl', 0)):.4f}")

    votes = [_ensure_dict(v) for v in _ensure_list(status.get("indicator_votes", []))]
    if votes:
        print(f"\n  INDICATOR SIGNALS ({len(votes)}):")
        print("  " + "-" * 53)
        for v in votes:
            score = _safe_float(v.get("score", 0))
            score_str = f"+{score}" if score > 0 else str(score)
            reason = str(v.get("reason", ""))[:50]
            print(f"    {v.get('name', 'N/A'):15s} | {v.get('signal', 'NEUTRAL'):12s} ({score_str:>3s}) | {reason}")

    history = [_ensure_dict(t) for t in _ensure_list(status.get("trade_history", []))]
    if history:
        print(f"\n  RECENT TRADES (last {min(len(history), 10)}):")
        print("  " + "-" * 53)
        for t in history[-10:]:
            print(f"    {t.get('side', 'N/A'):5s} | entry={t.get('entry_price', 0)} -> "
                  f"exit={t.get('exit_price', 'open')} | PnL={_safe_float(t.get('pnl', 0)):.4f} | "
                  f"{t.get('reason', '')[:30]}")


def main():
    parser = argparse.ArgumentParser(description="Show current bot status (read-only)")
    parser.add_argument("--full", action="store_true", help="Show full details including indicators and trades")
    parser.add_argument("--json", action="store_true", help="Output raw JSON")
    parser.add_argument("--status-file", default="runtime/dashboard_status.json", help="Path to dashboard status file")
    parser.add_argument("--state-file", default="runtime/state.json", help="Path to state file")
    parser.add_argument("--auto-scan-file", default="runtime/auto_scan_progress.json", help="Path to auto-scan progress file")
    parser.add_argument("--multi-scan-file", default="runtime/multi_scan_results.json", help="Path to multi-scan results file")
    args = parser.parse_args()

    status_arg = args.status_file.strip() if isinstance(args.status_file, str) else args.status_file
    state_arg = args.state_file.strip() if isinstance(args.state_file, str) else args.state_file
    auto_scan_arg = args.auto_scan_file.strip() if isinstance(args.auto_scan_file, str) else args.auto_scan_file
    multi_scan_arg = args.multi_scan_file.strip() if isinstance(args.multi_scan_file, str) else args.multi_scan_file
    status_path = _resolve_runtime_file(status_arg, "dashboard_status.json")
    state_path = _resolve_runtime_file(state_arg, "state.json")
    auto_scan_path = _resolve_runtime_file(auto_scan_arg, "auto_scan_progress.json")
    multi_scan_path = _resolve_runtime_file(multi_scan_arg, "multi_scan_results.json")
    if not status_path.is_absolute():
        status_path = project_root / status_path
    if not state_path.is_absolute():
        state_path = project_root / state_path
    if not auto_scan_path.is_absolute():
        auto_scan_path = project_root / auto_scan_path
    if not multi_scan_path.is_absolute():
        multi_scan_path = project_root / multi_scan_path

    status = load_json(status_path)
    state = load_json(state_path)
    auto_scan = load_json(auto_scan_path)
    multi_scan = load_json(multi_scan_path)

    if not _has_valid_status_snapshot(status) and not _has_valid_state_snapshot(state) and not _has_useful_auto_scan_snapshot(auto_scan) and not _has_useful_multi_scan_snapshot(multi_scan):
        print("ERROR: No status data found. Is the bot running?", file=sys.stderr)
        print(f"  Checked: {status_path}", file=sys.stderr)
        print(f"  Checked: {state_path}", file=sys.stderr)
        print(f"  Checked: {auto_scan_path}", file=sys.stderr)
        print(f"  Checked: {multi_scan_path}", file=sys.stderr)
        sys.exit(1)

    # Fallback: if dashboard_status.json missing, build minimal from state.json
    if not _has_valid_status_snapshot(status) and _has_valid_state_snapshot(state):
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
            "open_positions_count": len(_ensure_dict(state.get("positions", {}))),
            "open_positions": list(_ensure_dict(state.get("positions", {})).values()),
            "latest_decision": state.get("last_decision"),
            "signal_distribution": {"buy": 0, "sell": 0, "neutral": 0},
            "indicator_votes": [],
            "trade_history": _ensure_list(state.get("trade_history", [])),
            "performance": {},
            "status_warnings": _ensure_warning_list(state.get("status_warnings", [])),
        }

    if _has_useful_auto_scan_snapshot(auto_scan):
        status = _merge_auto_scan_snapshot(status, auto_scan)

    if _has_useful_multi_scan_snapshot(multi_scan):
        status = _merge_multi_scan_snapshot(status, multi_scan)

    if args.json:
        print(json.dumps(status, indent=2, default=str))
    elif args.full:
        print_full(status, state)
    else:
        print_compact(status, state)


if __name__ == "__main__":
    main()
