#!/usr/bin/env python3
"""CLI tool: Change the active trading symbol.

Usage:
    python scripts/set_symbol.py BTCUSDT
    python scripts/set_symbol.py ETHUSDT
    python scripts/set_symbol.py SOLUSDT --file runtime/active_symbol.txt
"""

import argparse
import sys
from pathlib import Path

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from src.control.symbol_controller import SymbolController


def main():
    parser = argparse.ArgumentParser(
        description="Change the active trading symbol (terminal control)",
    )
    parser.add_argument(
        "symbol",
        type=str,
        help="Trading symbol, e.g. BTCUSDT, ETHUSDT, SOLUSDT",
    )
    parser.add_argument(
        "--file",
        type=str,
        default="runtime/active_symbol.txt",
        help="Path to the active symbol file (default: runtime/active_symbol.txt)",
    )
    args = parser.parse_args()

    controller = SymbolController(args.file)
    old_symbol = controller.read_symbol()

    if controller.set_symbol(args.symbol):
        print(f"Symbol changed: {old_symbol or 'None'} -> {args.symbol.upper()}")
        print(f"File updated: {args.file}")
        print("The bot will pick up this change on the next cycle.")
    else:
        print(f"ERROR: Invalid symbol '{args.symbol}'", file=sys.stderr)
        print("Symbol must be 2-20 uppercase alphanumeric characters.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
