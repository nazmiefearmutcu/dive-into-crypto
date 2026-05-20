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
from src.services.bot_service import BotService
from src.utils.logger import setup_logger, get_logger


def main():
    env_path = project_root / ".env"
    if env_path.exists():
        load_dotenv(env_path)

    config = load_config("config/default.yaml")
    config["mode"] = "paper"  # Force paper mode

    setup_logger(log_file=config.get("log_path", "runtime/bot.log"))
    logger = get_logger("paper_trade")

    # Optional: set initial symbol from CLI
    if len(sys.argv) > 1:
        symbol = sys.argv[1].strip().upper()
        symbol_file = Path(config.get("active_symbol_path", "runtime/active_symbol.txt"))
        symbol_file.parent.mkdir(parents=True, exist_ok=True)
        symbol_file.write_text(f"{symbol}\n")
        logger.info(f"Initial symbol set to {symbol}")

    logger.info("Starting Paper Trading Bot...")
    bot = BotService(config)

    import signal
    signal.signal(signal.SIGINT, lambda s, f: bot.stop())
    signal.signal(signal.SIGTERM, lambda s, f: bot.stop())

    bot.initialize()
    bot.run()


if __name__ == "__main__":
    main()
