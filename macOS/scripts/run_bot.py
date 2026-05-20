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

if __name__ == "__main__":
    config_file = sys.argv[1] if len(sys.argv) > 1 else "config/default.yaml"
    main(config_file)
