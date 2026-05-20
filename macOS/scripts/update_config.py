#!/usr/bin/env python3
"""CLI tool: Update specific config values in config/default.yaml.

Usage:
    python scripts/update_config.py --timeframe 15m
    python scripts/update_config.py --mode live
    python scripts/update_config.py --polling-interval 30
    python scripts/update_config.py --risk-per-trade 0.01 --stop-loss 0.03
    python scripts/update_config.py --confidence-threshold 60
    python scripts/update_config.py --show   (display current config)
"""

import argparse
import sys
from pathlib import Path

import yaml

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

DEFAULT_CONFIG_PATH = project_root / "config" / "default.yaml"


def load_config(path: Path) -> dict:
    with open(path) as f:
        return yaml.safe_load(f) or {}


def save_config(config: dict, path: Path):
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w") as f:
        yaml.dump(config, f, default_flow_style=False, sort_keys=False, allow_unicode=True)
    tmp.replace(path)


def main():
    parser = argparse.ArgumentParser(
        description="Update trading bot configuration (terminal control)",
    )
    parser.add_argument("--config", type=str, default=str(DEFAULT_CONFIG_PATH), help="Config file path")
    parser.add_argument("--show", action="store_true", help="Show current config and exit")
    parser.add_argument("--mode", choices=["paper", "live"], help="Trading mode")
    parser.add_argument("--market-type", choices=["spot", "futures"], help="Market type")
    parser.add_argument("--timeframe", type=str, help="Candle timeframe (1m,5m,15m,1h,4h,1d,...)")
    parser.add_argument("--polling-interval", type=int, help="Polling interval in seconds")
    parser.add_argument("--candle-limit", type=int, help="Number of candles to fetch")
    parser.add_argument("--risk-per-trade", type=float, help="Risk per trade (0.01=1%%)")
    parser.add_argument("--stop-loss", type=float, help="Stop loss percentage (0.025=2.5%%)")
    parser.add_argument("--take-profit", type=float, help="Take profit percentage (0.05=5%%)")
    parser.add_argument("--trailing-stop", type=float, help="Trailing stop percentage")
    parser.add_argument("--confidence-threshold", type=int, help="Min confidence to trade (0-100)")
    parser.add_argument("--max-risk-level", choices=["LOW", "MEDIUM", "HIGH"], help="Max risk level for trading")
    parser.add_argument("--max-positions", type=int, help="Max open positions")
    parser.add_argument("--daily-loss-limit", type=float, help="Daily loss limit percentage")

    args = parser.parse_args()
    config_path = Path(args.config)

    if not config_path.exists():
        print(f"ERROR: Config file not found: {config_path}", file=sys.stderr)
        sys.exit(1)

    config = load_config(config_path)

    if args.show:
        print(yaml.dump(config, default_flow_style=False, sort_keys=False))
        return

    changes: list[str] = []

    if args.mode:
        config["mode"] = args.mode
        changes.append(f"mode -> {args.mode}")

    if args.market_type:
        config["market_type"] = args.market_type
        changes.append(f"market_type -> {args.market_type}")

    if args.timeframe:
        valid_tf = {"1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M"}
        if args.timeframe not in valid_tf:
            print(f"ERROR: Invalid timeframe '{args.timeframe}'. Valid: {sorted(valid_tf)}", file=sys.stderr)
            sys.exit(1)
        config["timeframe"] = args.timeframe
        changes.append(f"timeframe -> {args.timeframe}")

    if args.polling_interval:
        config["polling_interval_seconds"] = args.polling_interval
        changes.append(f"polling_interval_seconds -> {args.polling_interval}")

    if args.candle_limit:
        config["candle_limit"] = args.candle_limit
        changes.append(f"candle_limit -> {args.candle_limit}")

    risk = config.setdefault("risk", {})

    if args.risk_per_trade is not None:
        risk["risk_per_trade"] = args.risk_per_trade
        changes.append(f"risk.risk_per_trade -> {args.risk_per_trade}")

    if args.stop_loss is not None:
        risk["stop_loss_pct"] = args.stop_loss
        changes.append(f"risk.stop_loss_pct -> {args.stop_loss}")

    if args.take_profit is not None:
        risk["take_profit_pct"] = args.take_profit
        changes.append(f"risk.take_profit_pct -> {args.take_profit}")

    if args.trailing_stop is not None:
        risk["trailing_stop_pct"] = args.trailing_stop
        changes.append(f"risk.trailing_stop_pct -> {args.trailing_stop}")

    if args.confidence_threshold is not None:
        risk["confidence_threshold"] = args.confidence_threshold
        changes.append(f"risk.confidence_threshold -> {args.confidence_threshold}")

    if args.max_risk_level:
        risk["max_risk_level"] = args.max_risk_level
        changes.append(f"risk.max_risk_level -> {args.max_risk_level}")

    if args.max_positions is not None:
        risk["max_open_positions"] = args.max_positions
        changes.append(f"risk.max_open_positions -> {args.max_positions}")

    if args.daily_loss_limit is not None:
        risk["daily_loss_limit_pct"] = args.daily_loss_limit
        changes.append(f"risk.daily_loss_limit_pct -> {args.daily_loss_limit}")

    if not changes:
        print("No changes specified. Use --help for options or --show to view current config.")
        return

    save_config(config, config_path)

    print(f"Config updated ({config_path}):")
    for c in changes:
        print(f"  {c}")
    print("\nThe bot will pick up changes on the next cycle (if config watching is enabled)")
    print("or on the next restart.")


if __name__ == "__main__":
    main()
