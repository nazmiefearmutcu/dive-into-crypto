#!/usr/bin/env python3
"""Run the trading bot.

Usage:
    python scripts/run_bot.py                     # Paper mode with default config
    python scripts/run_bot.py config/default.yaml  # Specify config file
"""

import sys
from pathlib import Path

# Add project root to Python path
project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from src.main import main


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

if __name__ == "__main__":
    config_file = _resolve_config_path(sys.argv[1] if len(sys.argv) > 1 else None)
    main(str(config_file))
