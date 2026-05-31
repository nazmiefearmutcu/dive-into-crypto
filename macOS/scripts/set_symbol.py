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
from src.utils.validators import validate_symbol


def _resolve_runtime_file(value: str | Path, default_filename: str) -> Path:
    path = Path(value).expanduser()
    if path.suffix:
        return path
    return path / default_filename


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

    symbol = args.symbol.strip().upper()
    if not validate_symbol(symbol):
        print(f"ERROR: Invalid symbol '{args.symbol}'", file=sys.stderr)
        print("Symbol must be 2-20 uppercase alphanumeric characters.", file=sys.stderr)
        sys.exit(1)

    file_arg = args.file.strip() if isinstance(args.file, str) else args.file
    if file_arg in {"", "."}:
        file_arg = None
    symbol_file = _resolve_runtime_file(file_arg or "runtime/active_symbol.txt", "active_symbol.txt")
    if not symbol_file.is_absolute():
        symbol_file = project_root / symbol_file

    controller = SymbolController(str(symbol_file))
    old_symbol = controller.read_symbol()

    if controller.set_symbol(symbol):
        print(f"Symbol changed: {old_symbol or 'None'} -> {symbol}")
        print(f"File updated: {symbol_file}")
        print("The bot will pick up this change on the next cycle.")
    else:
        print(f"ERROR: Failed to update symbol file: {symbol_file}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
