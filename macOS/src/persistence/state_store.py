"""State persistence - save/load bot state to/from disk."""

import json
from pathlib import Path
from typing import Any, Optional

from src.utils.logger import get_logger
from src.utils.helpers import iso_now
from src.utils.validators import validate_state

logger = get_logger("persistence.state_store")


DEFAULT_STATE: dict[str, Any] = {
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
        """Load state from disk. Returns default state if file doesn't exist or is corrupt."""
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
            self.state = merged

            logger.info(
                f"State restored | symbol={self.state.get('active_symbol')} | "
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

    def save(self) -> None:
        """Save current state to disk."""
        try:
            self.state["last_save_time"] = iso_now()
            tmp_path = self.state_path.with_suffix(".tmp")
            with open(tmp_path, "w") as f:
                json.dump(self.state, f, indent=2, default=str)
            tmp_path.replace(self.state_path)
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
