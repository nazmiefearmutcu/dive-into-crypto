"""Configuration loader and watcher."""

from pathlib import Path
from typing import Any, Optional

import yaml

from src.utils.logger import get_logger
from src.utils.validators import (
    _rescue_safety_messages,
    validate_config,
    validate_rescue_safety,
)

logger = get_logger("control.config_watcher")

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_CONFIG_PATH = str(PROJECT_ROOT / "config" / "default.yaml")


def _resolve_config_path(config_path: Optional[str] = None) -> Path:
    """Resolve config path relative to project root when not absolute."""
    if config_path is None:
        return Path(DEFAULT_CONFIG_PATH)
    elif isinstance(config_path, str):
        text = config_path.strip()
        if text in {"", "."}:
            return Path(DEFAULT_CONFIG_PATH)
        candidate = Path(text).expanduser()
    else:
        candidate = Path(config_path).expanduser()
    if not candidate.is_absolute():
        candidate = PROJECT_ROOT / candidate
    if candidate.exists() and candidate.is_dir():
        return candidate / "default.yaml"
    if not candidate.suffix and not candidate.exists():
        return candidate / "default.yaml"
    return candidate


def load_config(config_path: Optional[str] = None) -> dict[str, Any]:
    """Load configuration from YAML file.

    Args:
        config_path: Path to config file. Uses default if None.

    Returns:
        Configuration dictionary.

    Raises:
        FileNotFoundError: If config file doesn't exist.
        ValueError: If config validation fails.
    """
    path = _resolve_config_path(config_path)

    if not path.exists():
        logger.error(f"Config file not found: {path}")
        raise FileNotFoundError(f"Config file not found: {path}")

    with open(path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)
        if config is None:
            config = {}
    if not isinstance(config, dict):
        logger.error(
            f"Config root must be a mapping, got {type(config).__name__} at {path}"
        )
        raise ValueError(f"Config root must be a mapping: {path}")

    errors = validate_config(config)
    if errors:
        for err in errors:
            logger.error(f"Config validation error: {err}")
        raise ValueError(f"Config validation failed: {'; '.join(errors)}")

    # S7 rescue-safety pass: surface risky-but-legal config as warnings.
    # Never raises and never mutates config.
    rescue_errors, rescue_warnings = validate_rescue_safety(config)
    for warning in rescue_warnings:
        logger.warning(f"Rescue safety: {warning}")
    for err in rescue_errors:
        logger.error(f"Rescue safety: {err}")

    logger.info(f"Config loaded from {path} | mode={config.get('mode')} | timeframe={config.get('timeframe')}")
    return config


class ConfigWatcher:
    """Watches config file for changes and reloads when modified."""

    def __init__(self, config_path: Optional[str] = None) -> None:
        self.config_path = _resolve_config_path(config_path)
        self._last_mtime: float = 0.0
        self.config: dict[str, Any] = {}
        self.last_error: str | None = None
        self.last_warnings: list[str] = []

    def load(self) -> dict[str, Any]:
        """Initial config load."""
        self.config = load_config(str(self.config_path))
        self._last_mtime = self.config_path.stat().st_mtime
        self.last_error = None
        _, rescue_warnings = _rescue_safety_messages(self.config)
        self.last_warnings = [f"Rescue safety: {warning}" for warning in rescue_warnings]
        return self.config

    def check_for_changes(self) -> tuple[bool, dict[str, Any]]:
        """Check if config file has been modified and reload if so.

        Returns:
            (changed: bool, config: dict)
        """
        try:
            current_mtime = self.config_path.stat().st_mtime
            if current_mtime > self._last_mtime:
                logger.info("Config file change detected, reloading...")
                try:
                    self.config = load_config(str(self.config_path))
                except Exception as exc:
                    self.last_error = str(exc)
                    self.last_warnings = []
                    raise
                self._last_mtime = current_mtime
                self.last_error = None
                _, rescue_warnings = _rescue_safety_messages(self.config)
                self.last_warnings = [f"Rescue safety: {warning}" for warning in rescue_warnings]
                return True, self.config
        except Exception as e:
            logger.error(f"Error checking config: {e}")
            self.last_error = str(e)
            self.last_warnings = []

        return False, self.config
