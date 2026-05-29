"""S4 — dashboard control-plane endpoints publish commands, not state mutations.

Covers:
- POST /api/position/close enqueues a `manual_close` command and does NOT
  mutate `state.json` or `dashboard_status.json`.
- POST /api/paper/reset enqueues a `paper_reset` command and does NOT
  overwrite state files.
- Double-click on /api/position/close with same idempotency key collapses
  to one queue entry.
- Endpoint returns 500 with a clear error when the queue file is corrupt
  (no silent fallback).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient


def _seed_status(status_file: Path) -> dict:
    payload = {
        "bot_status": "running",
        "mode": "paper",
        "market_type": "futures",
        "timeframe": "1h",
        "polling_interval": 60,
        "active_symbol": "BTCUSDT",
        "current_price": 50000.0,
        "last_update": "2026-05-21T10:00:00+00:00",
        "cycle_count": 7,
        "balance": 10000.0,
        "daily_pnl": 0.0,
        "total_pnl": 0.0,
        "unrealized_pnl": 0.0,
        "daily_start_balance": 10000.0,
        "open_positions_count": 1,
        "open_positions": [{
            "symbol": "BTCUSDT", "side": "LONG", "entry_price": 49000.0,
            "quantity": 0.1, "stop_loss": 48000.0, "take_profit": 51000.0,
            "unrealized_pnl": 100.0, "current_price": 50000.0,
        }],
        "latest_decision": {},
        "indicator_votes": [],
        "signal_distribution": {},
        "score_details": [],
        "trade_history": [],
        "performance": {},
        "bot_start_time": "2026-05-21T08:00:00+00:00",
    }
    status_file.write_text(json.dumps(payload))
    return payload


def _seed_state(state_file: Path) -> dict:
    payload = {
        "schema_version": 1,
        "active_symbol": "BTCUSDT",
        "positions": {
            "BTCUSDT": {
                "symbol": "BTCUSDT",
                "side": "LONG",
                "entry_price": 49000.0,
                "quantity": 0.1,
                "leverage": 1,
                "open_time": "2026-05-21T08:30:00+00:00",
            }
        },
        "paper_balance": 5100.0,
        "daily_pnl": 0.0,
        "total_realized_pnl": 0.0,
        "trade_history": [],
    }
    state_file.write_text(json.dumps(payload))
    return payload


@pytest.fixture
def dashboard_client(tmp_path):
    import dashboard.app as app_module

    status_file = tmp_path / "dashboard_status.json"
    state_file = tmp_path / "state.json"
    queue_file = tmp_path / "command_queue.json"
    log_file = tmp_path / "bot.log"
    config_file = tmp_path / "config.yaml"
    env_file = tmp_path / ".env"
    symbol_file = tmp_path / "active_symbol.txt"

    _seed_status(status_file)
    _seed_state(state_file)
    log_file.write_text("")
    config_file.write_text(yaml.dump({"mode": "paper", "market_type": "futures"}))
    env_file.write_text("BINANCE_API_KEY=test\nUSE_TESTNET=false\n")
    symbol_file.write_text("BTCUSDT\n")

    originals = {
        "STATUS_FILE": app_module.STATUS_FILE,
        "STATE_FILE": app_module.STATE_FILE,
        "LOG_FILE": app_module.LOG_FILE,
        "CONFIG_FILE": app_module.CONFIG_FILE,
        "ENV_FILE": app_module.ENV_FILE,
        "SYMBOL_FILE": app_module.SYMBOL_FILE,
        "PID_FILE": app_module.PID_FILE,
        "RUNTIME_DIR": app_module.RUNTIME_DIR,
        "COMMAND_QUEUE_FILE": app_module.COMMAND_QUEUE_FILE,
    }
    app_module.STATUS_FILE = status_file
    app_module.STATE_FILE = state_file
    app_module.LOG_FILE = log_file
    app_module.CONFIG_FILE = config_file
    app_module.ENV_FILE = env_file
    app_module.SYMBOL_FILE = symbol_file
    app_module.PID_FILE = tmp_path / "bot.pid"
    app_module.RUNTIME_DIR = tmp_path
    app_module.COMMAND_QUEUE_FILE = queue_file

    client = TestClient(app_module.app)
    try:
        yield client, status_file, state_file, queue_file
    finally:
        for name, val in originals.items():
            setattr(app_module, name, val)


class TestCloseEndpointEnqueues:
    def test_close_returns_enqueued_with_command_id(self, dashboard_client):
        client, _status, _state, queue_file = dashboard_client
        r = client.post("/api/position/close", data={"symbol": "BTCUSDT"})
        assert r.status_code == 200, r.text
        payload = r.json()
        assert payload["status"] == "enqueued"
        assert payload["kind"] == "manual_close"
        assert payload["symbol"] == "BTCUSDT"
        assert len(payload["command_id"]) >= 8
        # Queue file written atomically.
        assert queue_file.exists()
        raw = json.loads(queue_file.read_text())
        assert len(raw["commands"]) == 1
        assert raw["commands"][0]["kind"] == "manual_close"
        assert raw["commands"][0]["payload"] == {"symbol": "BTCUSDT"}

    def test_close_does_not_mutate_state_file(self, dashboard_client):
        client, status_file, state_file, _queue = dashboard_client
        before_state = json.loads(state_file.read_text())
        before_status = json.loads(status_file.read_text())
        r = client.post("/api/position/close", data={"symbol": "BTCUSDT"})
        assert r.status_code == 200
        after_state = json.loads(state_file.read_text())
        after_status = json.loads(status_file.read_text())
        # No direct mutation — bot owns these files now.
        assert after_state == before_state
        assert after_status == before_status

    def test_close_double_submit_dedupes(self, dashboard_client):
        client, _status, _state, queue_file = dashboard_client
        first = client.post(
            "/api/position/close",
            data={"symbol": "BTCUSDT", "idempotency_key": "dup-close-key-1"},
        )
        second = client.post(
            "/api/position/close",
            data={"symbol": "BTCUSDT", "idempotency_key": "dup-close-key-1"},
        )
        assert first.status_code == 200
        assert second.status_code == 200
        assert first.json()["command_id"] == second.json()["command_id"]
        raw = json.loads(queue_file.read_text())
        assert len(raw["commands"]) == 1

    def test_close_default_key_collapses_pending_resubmit(self, dashboard_client):
        client, _status, _state, queue_file = dashboard_client
        a = client.post("/api/position/close", data={"symbol": "BTCUSDT"})
        b = client.post("/api/position/close", data={"symbol": "BTCUSDT"})
        assert a.json()["command_id"] == b.json()["command_id"]
        raw = json.loads(queue_file.read_text())
        assert len(raw["commands"]) == 1

    def test_close_rejects_empty_symbol(self, dashboard_client):
        client, *_ = dashboard_client
        r = client.post("/api/position/close", data={"symbol": "  "})
        assert r.status_code == 400

    def test_close_surfaces_corrupt_queue(self, dashboard_client):
        client, _status, _state, queue_file = dashboard_client
        # Pre-poison the queue file.
        queue_file.parent.mkdir(parents=True, exist_ok=True)
        queue_file.write_text("{not valid json")
        r = client.post("/api/position/close", data={"symbol": "BTCUSDT"})
        assert r.status_code == 500
        assert "queue" in r.json()["error"].lower()


class TestPaperResetEnqueues:
    def test_paper_reset_returns_enqueued(self, dashboard_client):
        client, _status, _state, queue_file = dashboard_client
        r = client.post("/api/paper/reset", data={"balance": "8500"})
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["status"] == "enqueued"
        assert body["kind"] == "paper_reset"
        assert body["balance"] == 8500.0
        raw = json.loads(queue_file.read_text())
        assert len(raw["commands"]) == 1
        assert raw["commands"][0]["payload"] == {"balance": 8500.0}

    def test_paper_reset_does_not_mutate_state_or_status(self, dashboard_client):
        client, status_file, state_file, _queue = dashboard_client
        before_state = json.loads(state_file.read_text())
        before_status = json.loads(status_file.read_text())
        r = client.post("/api/paper/reset", data={"balance": "12000"})
        assert r.status_code == 200
        assert json.loads(state_file.read_text()) == before_state
        assert json.loads(status_file.read_text()) == before_status

    def test_paper_reset_rejects_non_positive(self, dashboard_client):
        client, *_ = dashboard_client
        r = client.post("/api/paper/reset", data={"balance": "-1"})
        assert r.status_code == 400
