"""Configuration loader and watcher."""

from pathlib import Path
from typing import Any, Optional

import yaml

from src.utils.logger import get_logger
from src.utils.validators import validate_config

logger = get_logger("control.config_watcher")

DEFAULT_CONFIG_PATH = "config/default.yaml"


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
    path = Path(config_path or DEFAULT_CONFIG_PATH)

    if not path.exists():
        logger.error(f"Config file not found: {path}")
        raise FileNotFoundError(f"Config file not found: {path}")

    with open(path, "r") as f:
        config = yaml.safe_load(f) or {}

    errors = validate_config(config)
    if errors:
        for err in errors:
            logger.error(f"Config validation error: {err}")
        raise ValueError(f"Config validation failed: {'; '.join(errors)}")

    logger.info(f"Config loaded from {path} | mode={config.get('mode')} | timeframe={config.get('timeframe')}")
    return config


class ConfigWatcher:
    """Watches config file for changes and reloads when modified."""

    def __init__(self, config_path: Optional[str] = None) -> None:
        self.config_path = Path(config_path or DEFAULT_CONFIG_PATH)
        self._last_mtime: float = 0.0
        self.config: dict[str, Any] = {}

    def load(self) -> dict[str, Any]:
        """Initial config load."""
        self.config = load_config(str(self.config_path))
        self._last_mtime = self.config_path.stat().st_mtime
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
                self.config = load_config(str(self.config_path))
                self._last_mtime = current_mtime
                return True, self.config
        except Exception as e:
            logger.error(f"Error checking config: {e}")

        return False, self.config
