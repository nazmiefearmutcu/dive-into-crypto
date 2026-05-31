"""Main entry point for the trading bot."""

import atexit
import os
import tempfile
import sys
import signal
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

from src.control.config_watcher import load_config
from src.services.bot_service import BotService
from src.utils.logger import setup_logger, get_logger

DEFAULT_PID_FILE = Path("runtime/bot.pid")
DEFAULT_LOG_FILE = Path("runtime/bot.log")
PID_FILE = DEFAULT_PID_FILE


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as tmp_file:
            tmp_file.write(content)
            tmp_file.flush()
            os.fsync(tmp_file.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError as cleanup_exc:
            sys.stderr.write(f"Warning: failed to remove temp file {tmp_name}: {cleanup_exc}\n")
        raise


def _is_valid_app_root(candidate: Path) -> bool:
    required_files = (
        candidate / "src" / "main.py",
        candidate / "dashboard" / "app.py",
        candidate / "config" / "default.yaml",
        candidate / "src" / "persistence" / "atomic_io.py",
        candidate / "src" / "persistence" / "command_queue.py",
        candidate / "src" / "persistence" / "schemas.py",
        candidate / "src" / "services" / "command_processor.py",
        candidate / "src" / "market" / "live_price_service.py",
    )
    if not candidate.is_dir():
        return False
    return all(path.is_file() for path in required_files)


def _project_root() -> Path:
    """Resolve writable project root for packaged and developer runs."""
    if hasattr(sys, "_MEIPASS"):
        exe_root = Path(sys.executable).resolve().parent
        external = exe_root / "app"
        if _is_valid_app_root(external):
            return external
        internal = Path(sys._MEIPASS).resolve() / "app"  # type: ignore[attr-defined]
        if _is_valid_app_root(internal):
            if external.exists():
                try:
                    import shutil
                    if external.is_dir():
                        shutil.rmtree(external)
                    else:
                        external.unlink()
                except Exception as exc:
                    print(
                        f"Warning: failed to clean writable app bundle {external}: {exc}",
                        file=sys.stderr,
                    )
            try:
                import shutil
                shutil.copytree(internal, external)
            except Exception as exc:
                raise RuntimeError(f"Failed to prepare writable app bundle at {external}") from exc
            if _is_valid_app_root(external):
                return external
            raise RuntimeError(f"Copied app bundle at {external} is invalid.")
        raise RuntimeError("Could not locate a valid app bundle in packaged environment.")
    return Path(__file__).resolve().parent.parent


def _default_pid_file() -> Path:
    return _project_root() / DEFAULT_PID_FILE


def _default_log_file() -> Path:
    return _project_root() / DEFAULT_LOG_FILE


def _as_project_path(value: str | Path | None, *, fallback: Path | None = None) -> Path:
    """Resolve a string/path value relative to project root when not absolute."""
    project_root = _project_root()
    if value is None:
        if fallback is None:
            return project_root
        return fallback if fallback.is_absolute() else project_root / fallback
    try:
        text = str(value).strip()
    except Exception:
        if fallback is None:
            return project_root
        return fallback if fallback.is_absolute() else project_root / fallback
    if not text:
        if fallback is None:
            return project_root
        return fallback if fallback.is_absolute() else project_root / fallback
    candidate = Path(text).expanduser()
    if not candidate.is_absolute():
        candidate = project_root / candidate
    return candidate


def _as_file_in_project_root(value: str | Path | None, *, fallback: Path, default_filename: str) -> Path:
    """Resolve a runtime artifact path, treating bare directories as folders.

    If the configured value has no suffix (likely a folder), append the default
    filename so callers can pass either a full file path or directory path.
    """
    candidate = _as_project_path(value, fallback=fallback)
    if isinstance(candidate, Path) and candidate.suffix:
        return candidate
    if candidate != Path("."):
        return candidate / default_filename
    return fallback


def _resolve_config_path(config_path: str | Path | None = None) -> Path:
    """Resolve config path in a cwd-safe way."""
    if config_path is None:
        return _project_root() / "config" / "default.yaml"
    try:
        text = str(config_path).strip()
    except Exception:
        return _project_root() / "config" / "default.yaml"
    if text in {"", "."}:
        return _project_root() / "config" / "default.yaml"
    candidate = _as_project_path(
        config_path,
        fallback=_project_root() / "config" / "default.yaml",
    )
    if candidate.exists() and candidate.is_dir():
        return candidate / "default.yaml"
    if not candidate.suffix and not candidate.exists():
        return candidate / "default.yaml"
    return candidate


def _resolve_pid_file(config: dict[str, Any] | None = None) -> Path:
    """Resolve PID file path from explicit pid_path or dashboard_status_path."""
    if config is None:
        return _default_pid_file()
    if not isinstance(config, dict):
        return _default_pid_file()

    try:
        pid_path = config.get("pid_path")
        if isinstance(pid_path, (str, Path)):
            text = str(pid_path).strip()
            if text not in {"", "."}:
                return _as_file_in_project_root(
                    pid_path,
                    fallback=_default_pid_file(),
                    default_filename="bot.pid",
                )

        dashboard_status_path = config.get("dashboard_status_path")
        if isinstance(dashboard_status_path, (str, Path)):
            text = str(dashboard_status_path).strip()
            if text in {".", ""}:
                return _default_pid_file()
            return _as_file_in_project_root(
                dashboard_status_path,
                fallback=_default_pid_file(),
                default_filename="bot.pid",
            ).parent / "bot.pid"
    except Exception:
        return _default_pid_file()

    return _default_pid_file()


def _resolve_log_path(config: dict[str, Any] | None = None) -> str:
    """Resolve bot log path from config, preferring explicit config path."""
    if not isinstance(config, dict):
        return str(_default_log_file())
    log_path = config.get("log_path")
    if not isinstance(log_path, (str, Path)):
        return str(_default_log_file())
    if not log_path:
        return str(_default_log_file())
    return str(
        _as_file_in_project_root(
            log_path,
            fallback=_default_log_file(),
            default_filename="bot.log",
        )
    )


def _write_pid() -> None:
    """Write current PID to file so dashboard can find and stop us."""
    try:
        _atomic_write_text(PID_FILE, str(os.getpid()))
    except Exception:
        try:
            PID_FILE.unlink(missing_ok=True)
        except Exception as cleanup_exc:
            print(f"Warning: failed to remove stale PID file {PID_FILE}: {cleanup_exc}", file=sys.stderr)
        raise


def _remove_pid() -> None:
    """Remove PID file on exit."""
    try:
        PID_FILE.unlink(missing_ok=True)
    except Exception as exc:
        print(f"Warning: failed to remove PID file {PID_FILE}: {exc}", file=sys.stderr)


def main(config_path: str = "config/default.yaml") -> None:
    """Initialize and run the trading bot."""
    # Load environment variables
    env_path = _project_root() / ".env"
    if env_path.exists():
        load_dotenv(env_path)
    else:
        print("Warning: .env file not found. Using environment variables only.")

    resolved_config_path = _resolve_config_path(config_path)
    # Load config
    config = load_config(str(resolved_config_path))
    config["_config_path"] = str(resolved_config_path)  # pass path so ConfigWatcher can track it

    # Write PID file for dashboard control
    global PID_FILE
    PID_FILE = _resolve_pid_file(config)
    _write_pid()
    atexit.register(_remove_pid)

    # Setup logging
    log_path = _resolve_log_path(config)
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
    config_file = _resolve_config_path(sys.argv[1] if len(sys.argv) > 1 else None)
    main(str(config_file))
