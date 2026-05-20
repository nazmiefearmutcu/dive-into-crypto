"""Main entry point for the trading bot."""

import atexit
import os
import sys
import signal
from pathlib import Path

from dotenv import load_dotenv

from src.control.config_watcher import load_config
from src.services.bot_service import BotService
from src.utils.logger import setup_logger, get_logger

PID_FILE = Path("runtime/bot.pid")


def _write_pid() -> None:
    """Write current PID to file so dashboard can find and stop us."""
    PID_FILE.parent.mkdir(parents=True, exist_ok=True)
    PID_FILE.write_text(str(os.getpid()))


def _remove_pid() -> None:
    """Remove PID file on exit."""
    try:
        PID_FILE.unlink(missing_ok=True)
    except Exception:
        pass


def main(config_path: str = "config/default.yaml") -> None:
    """Initialize and run the trading bot."""
    # Write PID file for dashboard control
    _write_pid()
    atexit.register(_remove_pid)

    # Load environment variables
    env_path = Path(".env")
    if env_path.exists():
        load_dotenv(env_path)
    else:
        print("Warning: .env file not found. Using environment variables only.")

    # Load config
    config = load_config(config_path)
    config["_config_path"] = config_path  # pass path so ConfigWatcher can track it

    # Setup logging
    log_path = config.get("log_path", "runtime/bot.log")
    setup_logger(log_file=log_path)
    logger = get_logger("main")

    # Create bot service
    bot = BotService(config)

    # Register signal handlers for graceful shutdown
    def _signal_handler(sig, frame):
        logger.info(f"Received signal {sig}, initiating shutdown...")
        bot.stop()

    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)

    try:
        bot.initialize()
        bot.run()
    except Exception as e:
        logger.critical(f"Fatal error: {e}", exc_info=True)
        sys.exit(1)
    finally:
        # Remove PID file LAST — after bot._shutdown() has saved state.
        # This way dashboard can use PID file existence to know bot is truly done.
        _remove_pid()


if __name__ == "__main__":
    config_file = sys.argv[1] if len(sys.argv) > 1 else "config/default.yaml"
    main(config_file)
