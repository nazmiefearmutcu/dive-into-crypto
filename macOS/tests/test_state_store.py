"""Tests for the state store."""

import pytest
import json
from pathlib import Path

from src.persistence.state_store import StateStore, DEFAULT_STATE


@pytest.fixture
def state_file(tmp_path):
    return tmp_path / "state.json"


@pytest.fixture
def store(state_file):
    return StateStore(str(state_file))


class TestStateStore:
    def test_load_creates_defaults(self, store, state_file):
        state = store.load()
        assert state["active_symbol"] == "BTCUSDT"
        assert state["paper_balance"] == 10000.0
        assert state["positions"] == {}

    def test_save_and_load(self, store, state_file):
        store.update(active_symbol="ETHUSDT", paper_balance=9500.0)
        store.save()

        new_store = StateStore(str(state_file))
        state = new_store.load()
        assert state["active_symbol"] == "ETHUSDT"
        assert state["paper_balance"] == 9500.0

    def test_corrupt_file_uses_defaults(self, store, state_file):
        state_file.write_text("not valid json {{{")
        state = store.load()
        assert state["active_symbol"] == "BTCUSDT"

    def test_update_persists(self, store):
        store.load()
        store.update(daily_pnl=50.5)
        assert store.get("daily_pnl") == 50.5

    def test_reset(self, store):
        store.load()
        store.update(paper_balance=5000.0, daily_pnl=-200.0)
        store.reset(starting_balance=20000.0)
        assert store.get("paper_balance") == 20000.0
        assert store.get("daily_pnl") == 0.0

    def test_missing_keys_merged_with_defaults(self, store, state_file):
        # Save partial state
        state_file.write_text(json.dumps({
            "active_symbol": "SOLUSDT",
            "positions": {},
            "paper_balance": 8000.0,
        }))
        state = store.load()
        assert state["active_symbol"] == "SOLUSDT"
        assert state["paper_balance"] == 8000.0
        # Default keys should be present
        assert "daily_pnl" in state
        assert "trade_history" in state

    def test_positions_preserved(self, store, state_file):
        pos_data = {
            "BTCUSDT": {
                "symbol": "BTCUSDT",
                "side": "LONG",
                "entry_price": 50000.0,
                "quantity": 0.1,
                "stop_loss": 48750.0,
                "take_profit": 52500.0,
                "trailing_stop": 0.02,
                "trailing_stop_price": None,
                "highest_price": 50000.0,
                "open_time": "2024-01-01T00:00:00+00:00",
                "unrealized_pnl": 0.0,
                "realized_pnl": 0.0,
                "is_break_even": False,
            }
        }
        store.load()
        store.update(positions=pos_data)

        new_store = StateStore(str(state_file))
        state = new_store.load()
        assert "BTCUSDT" in state["positions"]
        assert state["positions"]["BTCUSDT"]["entry_price"] == 50000.0

    def test_atomic_write(self, store, state_file):
        """Ensure save uses atomic write (tmp + rename)."""
        store.load()
        store.update(paper_balance=7777.0)
        # tmp file should not exist after save
        tmp_file = state_file.with_suffix(".tmp")
        assert not tmp_file.exists()
        # No leftover *.tmp* sidecars from the shared atomic helper either.
        leftovers = list(state_file.parent.glob(f"{state_file.name}.*.tmp"))
        assert leftovers == []
        # Main file should be valid
        data = json.loads(state_file.read_text())
        assert data["paper_balance"] == 7777.0

    def test_save_stamps_schema_version(self, store, state_file):
        store.load()
        store.update(paper_balance=9999.0)
        data = json.loads(state_file.read_text())
        assert data["schema_version"] >= 1

    def test_load_strict_raises_on_corrupt(self, store, state_file):
        from src.persistence.state_store import StateLoadError

        state_file.write_text("{not json")
        with pytest.raises(StateLoadError):
            store.load_strict()

    def test_load_strict_raises_on_missing(self, store, state_file):
        from src.persistence.state_store import StateLoadError

        with pytest.raises(StateLoadError):
            store.load_strict()

    def test_load_strict_raises_on_missing_required(self, store, state_file):
        from src.persistence.state_store import StateLoadError

        # Missing `active_symbol` and `paper_balance` — must fail loudly.
        state_file.write_text(json.dumps({"positions": {}}))
        with pytest.raises(StateLoadError):
            store.load_strict()

    def test_load_strict_accepts_legacy(self, store, state_file):
        state_file.write_text(json.dumps({
            "active_symbol": "BTCUSDT",
            "positions": {},
            "paper_balance": 10000.0,
        }))
        state = store.load_strict()
        assert state["active_symbol"] == "BTCUSDT"
