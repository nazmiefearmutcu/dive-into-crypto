#!/usr/bin/env python3
"""CLI tool: Validate the config file.

Usage:
    python scripts/validate_config.py
    python scripts/validate_config.py --config config/default.yaml
"""

import argparse
import sys
from pathlib import Path

import yaml

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from src.utils.validators import validate_config


def _resolve_config_path(config_arg: str | Path | None) -> Path:
    if config_arg is None:
        return project_root / "config" / "default.yaml"
    text = str(config_arg).strip()
    if text in {"", "."}:
        return project_root / "config" / "default.yaml"
    candidate = Path(text).expanduser()
    if not candidate.is_absolute():
        candidate = project_root / candidate
    if candidate.exists() and candidate.is_dir():
        return candidate / "default.yaml"
    if not candidate.suffix and not candidate.exists():
        return candidate / "default.yaml"
    return candidate


def main():
    parser = argparse.ArgumentParser(description="Validate the trading bot config file")
    parser.add_argument("--config", default="config/default.yaml", help="Config file path")
    args = parser.parse_args()

    config_path = _resolve_config_path(args.config)
    if not config_path.exists():
        print(f"ERROR: Config file not found: {config_path}", file=sys.stderr)
        sys.exit(1)

    try:
        with open(config_path, encoding="utf-8") as f:
            config = yaml.safe_load(f)
    except yaml.YAMLError as e:
        print(f"ERROR: Invalid YAML: {e}", file=sys.stderr)
        sys.exit(1)

    if config is None:
        config = {}
    elif not isinstance(config, dict):
        print(
            f"ERROR: Config file {config_path} must contain a top-level mapping",
            file=sys.stderr,
        )
        sys.exit(1)

    errors = validate_config(config)

    if errors:
        print(f"VALIDATION FAILED ({len(errors)} error(s)):")
        for err in errors:
            print(f"  - {err}")
        sys.exit(1)
    else:
        print(f"Config is valid: {config_path}")
        print(f"  Mode:      {config.get('mode', 'paper')}")
        print(f"  Market:    {config.get('market_type', 'spot')}")
        print(f"  Timeframe: {config.get('timeframe', '1h')}")
        print(f"  Polling:   {config.get('polling_interval_seconds', 60)}s")
        risk = config.get("risk", {})
        print(f"  Risk/Trade: {risk.get('risk_per_trade', 0.02)*100:.1f}%")
        print(f"  SL: {risk.get('stop_loss_pct', 0.025)*100:.1f}% | TP: {risk.get('take_profit_pct', 0.05)*100:.1f}%")
        print(f"  Confidence Threshold: {risk.get('confidence_threshold', 55)}")


if __name__ == "__main__":
    main()
