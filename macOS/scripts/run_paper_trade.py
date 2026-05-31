#!/usr/bin/env python3
"""Run the bot explicitly in paper trading mode.

Usage:
    python scripts/run_paper_trade.py
    python scripts/run_paper_trade.py ETHUSDT  # Start with specific symbol
"""

import sys
from pathlib import Path

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from dotenv import load_dotenv
from src.control.config_watcher import load_config
from src.control.symbol_controller import SymbolController
from src.services.bot_service import BotService
from src.utils.logger import setup_logger, get_logger
from src.utils.validators import validate_symbol


def _resolve_runtime_file(value: str | Path, default_filename: str) -> Path:
    path = Path(value).expanduser()
    if path.suffix:
        return path
    return path / default_filename


def main():
    env_path = project_root / ".env"
    if env_path.exists():
        load_dotenv(env_path)

    config = load_config(str(project_root / "config" / "default.yaml"))
    config["_config_path"] = str(project_root / "config" / "default.yaml")
    config["mode"] = "paper"  # Force paper mode

    log_arg = config.get("log_path")
    if isinstance(log_arg, str):
        log_arg = log_arg.strip()
    if log_arg in {"", "."}:
        log_arg = None
    log_path = _resolve_runtime_file(log_arg or "runtime/bot.log", "bot.log")
    if not log_path.is_absolute():
        log_path = project_root / log_path
    setup_logger(log_file=str(log_path))
    logger = get_logger("paper_trade")

    # Optional: set initial symbol from CLI
    if len(sys.argv) > 1:
        symbol = sys.argv[1].strip().upper()
        if not validate_symbol(symbol):
            print(f"ERROR: Invalid symbol '{sys.argv[1]}'", file=sys.stderr)
            print("Symbol must be 2-20 uppercase alphanumeric characters.", file=sys.stderr)
            sys.exit(1)
        symbol_arg = config.get("active_symbol_path")
        if isinstance(symbol_arg, str):
            symbol_arg = symbol_arg.strip()
        if symbol_arg in {"", "."}:
            symbol_arg = None
        symbol_file = _resolve_runtime_file(symbol_arg or "runtime/active_symbol.txt", "active_symbol.txt")
        if not symbol_file.is_absolute():
            symbol_file = project_root / symbol_file
        controller = SymbolController(str(symbol_file))
        if controller.set_symbol(symbol):
            logger.info(f"Initial symbol set to {symbol}")
        else:
            print(f"ERROR: Failed to update symbol file: {symbol_file}", file=sys.stderr)
            sys.exit(1)

    logger.info("Starting Paper Trading Bot...")
    bot = BotService(config)

    import signal
    signal.signal(signal.SIGINT, lambda s, f: bot.stop())
    signal.signal(signal.SIGTERM, lambda s, f: bot.stop())

    bot.initialize()
    bot.run()


if __name__ == "__main__":
    main()
