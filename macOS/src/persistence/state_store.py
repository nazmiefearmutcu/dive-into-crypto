"""State persistence - save/load bot state to/from disk."""

import json
from pathlib import Path
from typing import Any, Optional

from src.persistence.atomic_io import atomic_write_json
from src.persistence.schemas import (
    RUNTIME_SCHEMA_VERSION,
    SchemaValidationError,
    StateSchema,
)
from src.utils.logger import get_logger
from src.utils.helpers import iso_now
from src.utils.validators import validate_state

logger = get_logger("persistence.state_store")


class StateLoadError(RuntimeError):
    """Raised by `load_strict` when state cannot be safely loaded."""


DEFAULT_STATE: dict[str, Any] = {
    "schema_version": RUNTIME_SCHEMA_VERSION,
    "active_symbol": "BTCUSDT",
    "positions": {},
    "last_decision": None,
    "last_trade_time": None,
    "daily_pnl": 0.0,
    "daily_start_balance": 10000.0,
    "total_realized_pnl": 0.0,
    "trade_history": [],
    "bot_start_time": None,
    "paper_balance": 10000.0,
    "daily_date": None,
}


class StateStore:
    """Manages bot state persistence to a JSON file."""

    def __init__(self, state_path: str = "runtime/state.json") -> None:
        self.state_path = Path(state_path)
        self.state: dict[str, Any] = DEFAULT_STATE.copy()
        self._ensure_dir()

    def _ensure_dir(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)

    def load(self) -> dict[str, Any]:
        """Load state from disk. Returns default state if file doesn't exist or is corrupt.

        Preserved from S1-S3 so first-boot and corrupt-recovery contracts hold.
        Callers that need strict failure on corrupt state should use `load_strict`.
        """
        if not self.state_path.exists():
            logger.info("No state file found, using defaults")
            self.state = DEFAULT_STATE.copy()
            return self.state

        try:
            with open(self.state_path, "r") as f:
                data = json.load(f)

            if not validate_state(data):
                logger.warning("State validation failed, using defaults")
                self.state = DEFAULT_STATE.copy()
                return self.state

            # Merge with defaults to handle missing keys from older versions
            merged = DEFAULT_STATE.copy()
            merged.update(data)
            # Stamp schema_version if upgrading from a legacy file
            merged.setdefault("schema_version", RUNTIME_SCHEMA_VERSION)
            self.state = merged

            logger.info(
                f"State restored | schema={self.state.get('schema_version')} | "
                f"symbol={self.state.get('active_symbol')} | "
                f"positions={len(self.state.get('positions', {}))} | "
                f"paper_balance={self.state.get('paper_balance')}"
            )
            return self.state

        except json.JSONDecodeError as e:
            logger.error(f"State file corrupt: {e}. Using defaults.")
            self.state = DEFAULT_STATE.copy()
            return self.state
        except Exception as e:
            logger.error(f"Error loading state: {e}. Using defaults.")
            self.state = DEFAULT_STATE.copy()
            return self.state

    def load_strict(self) -> dict[str, Any]:
        """Load and validate state via the schema layer. Raise on failure.

        S4: authoritative reads (dashboard exporters, command processor)
        should not paper over a corrupt state file with defaults.
        """
        if not self.state_path.exists():
            raise StateLoadError(f"state file missing: {self.state_path}")
        raw = self.state_path.read_text()
        if not raw.strip():
            raise StateLoadError(f"state file empty: {self.state_path}")
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise StateLoadError(
                f"state file malformed JSON: {self.state_path}: {exc}"
            ) from exc
        try:
            StateSchema.from_legacy(data)
        except SchemaValidationError as exc:
            raise StateLoadError(str(exc)) from exc
        merged = DEFAULT_STATE.copy()
        merged.update(data)
        merged.setdefault("schema_version", RUNTIME_SCHEMA_VERSION)
        self.state = merged
        return self.state

    def save(self) -> None:
        """Save current state to disk via atomic write + fsync."""
        try:
            self.state["schema_version"] = RUNTIME_SCHEMA_VERSION
            self.state["last_save_time"] = iso_now()
            atomic_write_json(self.state_path, self.state)
            logger.debug("State saved to disk")
        except Exception as e:
            logger.error(f"Error saving state: {e}")

    def update(self, **kwargs: Any) -> None:
        """Update specific state fields and save."""
        self.state.update(kwargs)
        self.save()

    def get(self, key: str, default: Any = None) -> Any:
        """Get a state value."""
        return self.state.get(key, default)

    def reset(self, starting_balance: float = 10000.0) -> None:
        """Reset state to defaults."""
        self.state = DEFAULT_STATE.copy()
        self.state["paper_balance"] = starting_balance
        self.state["daily_start_balance"] = starting_balance
        self.save()
        logger.info(f"State reset with balance={starting_balance}")
