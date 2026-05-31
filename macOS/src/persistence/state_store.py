"""State persistence - save/load bot state to/from disk."""

import json
from pathlib import Path
from typing import Any, Optional
from copy import deepcopy

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

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_STATE_PATH = str(PROJECT_ROOT / "runtime" / "state.json")


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


def _resolve_state_path(state_path: str | Path) -> Path:
    text = str(state_path).strip()
    if text in {"", "."}:
        return Path(DEFAULT_STATE_PATH)

    path = Path(text).expanduser()
    if not path.is_absolute():
        path = PROJECT_ROOT / path

    if path.exists() and path.is_dir():
        return path / "state.json"
    if not path.suffix and not path.exists():
        return path / "state.json"
    return path


class StateStore:
    """Manages bot state persistence to a JSON file."""

    def __init__(self, state_path: str = DEFAULT_STATE_PATH) -> None:
        if isinstance(state_path, (str, Path)):
            self.state_path = _resolve_state_path(state_path)
        else:
            self.state_path = Path(DEFAULT_STATE_PATH)
        self.state: dict[str, Any] = deepcopy(DEFAULT_STATE)
        self._last_load_error: str | None = None
        self._last_save_error: str | None = None
        self._ensure_dir()

    def _ensure_dir(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)

    def _cleanup_stale_state_file(self, reason: str) -> None:
        try:
            if self.state_path.exists():
                self.state_path.unlink()
                logger.warning(f"Removed stale state file after strict-load failure: {reason}")
        except Exception as cleanup_exc:
            logger.warning(
                f"Failed to remove stale state after strict-load failure ({reason}): {cleanup_exc}"
            )

    def load(self) -> dict[str, Any]:
        """Load state from disk. Returns default state if file doesn't exist or is corrupt.

        Preserved from S1-S3 so first-boot and corrupt-recovery contracts hold.
        Callers that need strict failure on corrupt state should use `load_strict`.
        """
        if not self.state_path.exists():
            logger.info("No state file found, using defaults")
            self._last_load_error = None
            self.state = deepcopy(DEFAULT_STATE)
            return self.state

        try:
            with open(self.state_path, "r", encoding="utf-8") as f:
                data = json.load(f)

            if not validate_state(data):
                logger.warning("State validation failed, using defaults")
                self._last_load_error = "state validation failed"
                self.state = deepcopy(DEFAULT_STATE)
                try:
                    self.state_path.unlink(missing_ok=True)
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to remove corrupt state after validation error: {cleanup_exc}"
                    )
                return self.state

            # Merge with defaults to handle missing keys from older versions
            merged = deepcopy(DEFAULT_STATE)
            merged.update(data)
            # Stamp schema_version if upgrading from a legacy file
            merged.setdefault("schema_version", RUNTIME_SCHEMA_VERSION)
            self.state = merged
            self._last_load_error = None

            logger.info(
                f"State restored | schema={self.state.get('schema_version')} | "
                f"symbol={self.state.get('active_symbol')} | "
                f"positions={len(self.state.get('positions', {}))} | "
                f"paper_balance={self.state.get('paper_balance')}"
            )
            return self.state

        except json.JSONDecodeError as e:
            logger.error(f"State file corrupt: {e}. Using defaults.")
            self._last_load_error = str(e)
            self.state = deepcopy(DEFAULT_STATE)
            try:
                self.state_path.unlink(missing_ok=True)
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove corrupt state after JSON error: {cleanup_exc}"
                )
            return self.state
        except Exception as e:
            logger.error(f"Error loading state: {e}. Using defaults.")
            self._last_load_error = str(e)
            self.state = deepcopy(DEFAULT_STATE)
            try:
                self.state_path.unlink(missing_ok=True)
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove corrupt state after load error: {cleanup_exc}"
                )
            return self.state

    def load_strict(self) -> dict[str, Any]:
        """Load and validate state via the schema layer. Raise on failure.

        S4: authoritative reads (dashboard exporters, command processor)
        should not paper over a corrupt state file with defaults.
        """
        if not self.state_path.exists():
            raise StateLoadError(f"state file missing: {self.state_path}")
        raw = self.state_path.read_text(encoding="utf-8")
        if not raw.strip():
            self._cleanup_stale_state_file("empty state file")
            raise StateLoadError(f"state file empty: {self.state_path}")
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            self._cleanup_stale_state_file(f"malformed JSON: {exc}")
            raise StateLoadError(
                f"state file malformed JSON: {self.state_path}: {exc}"
            ) from exc
        try:
            StateSchema.from_legacy(data)
        except SchemaValidationError as exc:
            self._cleanup_stale_state_file(f"schema validation failed: {exc}")
            raise StateLoadError(str(exc)) from exc
        if not validate_state(data):
            self._cleanup_stale_state_file("state validation failed")
            raise StateLoadError(f"state validation failed: {self.state_path}")
        merged = deepcopy(DEFAULT_STATE)
        merged.update(data)
        merged.setdefault("schema_version", RUNTIME_SCHEMA_VERSION)
        self.state = merged
        return self.state

    def save(self) -> None:
        """Save current state to disk via atomic write + fsync."""
        prev_last_save_time = self.state.get("last_save_time")
        try:
            self._last_save_error = None
            self.state["schema_version"] = RUNTIME_SCHEMA_VERSION
            self.state["last_save_time"] = iso_now()
            if not validate_state(self.state):
                raise SchemaValidationError("state validation failed before save")
            atomic_write_json(self.state_path, self.state)
            logger.debug("State saved to disk")
        except Exception as e:
            if prev_last_save_time is None:
                self.state.pop("last_save_time", None)
            else:
                self.state["last_save_time"] = prev_last_save_time
            self._last_save_error = str(e)
            logger.error(f"Error saving state: {e}")
            try:
                if self.state_path.exists():
                    self.state_path.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale state after save error: {cleanup_exc}"
                )

    def update(self, **kwargs: Any) -> None:
        """Update specific state fields and save."""
        prev_state = deepcopy(self.state)
        self.state.update(kwargs)
        self.save()
        if self._last_save_error:
            self.state = prev_state
            logger.warning(f"State update rolled back after save failure: {self._last_save_error}")

    def get(self, key: str, default: Any = None) -> Any:
        """Get a state value."""
        return self.state.get(key, default)

    def reset(self, starting_balance: float = 10000.0) -> None:
        """Reset state to defaults."""
        self.state = deepcopy(DEFAULT_STATE)
        self.state["paper_balance"] = starting_balance
        self.state["daily_start_balance"] = starting_balance
        self.save()
        logger.info(f"State reset with balance={starting_balance}")
