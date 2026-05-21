"""Tests for the dashboard FastAPI app."""

import json
import pytest
from pathlib import Path
from unittest.mock import patch

import yaml
from fastapi.testclient import TestClient


@pytest.fixture
def dashboard_status_file(tmp_path):
    """Create a test dashboard_status.json."""
    status = {
        "bot_status": "running",
        "mode": "paper",
        "market_type": "spot",
        "timeframe": "1h",
        "polling_interval": 60,
        "active_symbol": "BTCUSDT",
        "current_price": 50000.0,
        "last_update": "2024-01-15T14:00:00+00:00",
        "cycle_count": 42,
        "balance": 10050.0,
        "daily_pnl": 50.0,
        "total_pnl": 150.0,
        "unrealized_pnl": 25.0,
        "daily_start_balance": 10000.0,
        "open_positions_count": 1,
        "open_positions": [{
            "symbol": "BTCUSDT", "side": "LONG", "entry_price": 49500,
            "quantity": 0.1, "stop_loss": 48262, "take_profit": 51975,
            "unrealized_pnl": 50.0, "current_price": 50000,
        }],
        "latest_decision": {
            "action": "HOLD", "signal": "BUY", "confidence": 68,
            "risk_level": "LOW", "weighted_score": 0.742,
            "reason": "Holding LONG position", "should_trade": True,
        },
        "indicator_votes": [
            {"name": "rsi", "signal": "BUY", "score": 1, "reason": "RSI=38 oversold zone"},
            {"name": "macd", "signal": "BUY", "score": 1, "reason": "MACD bullish"},
            {"name": "atr_filter", "signal": "NEUTRAL", "score": 0, "reason": "Normal volatility"},
        ],
        "signal_distribution": {"buy": 8, "sell": 3, "neutral": 4},
        "score_details": [
            {"name": "rsi", "weight": 1.5, "weighted_score": 1.5},
            {"name": "macd", "weight": 2.0, "weighted_score": 2.0},
        ],
        "trade_history": [
            {"symbol": "BTCUSDT", "side": "LONG", "entry_price": 48000,
             "exit_price": 49000, "quantity": 0.05, "pnl": 45.0, "fee": 5.0,
             "reason": "take_profit", "exit_time": "2024-01-15T10:00:00+00:00"},
        ],
        "performance": {
            "total_trades": 5, "wins": 3, "losses": 2, "win_rate": 60.0,
            "avg_pnl": 10.0, "total_pnl": 50.0, "best_trade": 45.0,
            "worst_trade": -20.0, "max_drawdown": 25.0,
        },
        "bot_start_time": "2024-01-15T08:00:00+00:00",
    }
    return status


@pytest.fixture
def client(tmp_path, dashboard_status_file):
    """Create a test client with mocked file paths."""
    status_file = tmp_path / "dashboard_status.json"
    log_file = tmp_path / "bot.log"

    status_file.write_text(json.dumps(dashboard_status_file))
    log_file.write_text(
        "2024-01-15 14:00:01 | INFO     | services.bot_service | Cycle #42\n"
        "2024-01-15 14:00:02 | WARNING  | api.binance_client   | Rate limit\n"
        "2024-01-15 14:00:03 | ERROR    | trading.execution    | Order failed\n"
    )

    # Create a config file
    config_file = tmp_path / "config.yaml"
    config_file.write_text(yaml.dump({
        "mode": "paper", "market_type": "spot", "timeframe": "1h",
        "candle_limit": 200, "polling_interval_seconds": 60,
        "active_symbol_path": "runtime/active_symbol.txt",
        "risk": {"risk_per_trade": 0.02, "stop_loss_pct": 0.025,
                 "take_profit_pct": 0.05, "trailing_stop_pct": 0.02,
                 "trailing_stop_activation_pct": 0.03, "max_open_positions": 1,
                 "daily_loss_limit_pct": 0.05, "confidence_threshold": 55,
                 "max_risk_level": "MEDIUM", "break_even_trigger_pct": 0.02},
        "paper": {"starting_balance": 10000.0, "fee_pct": 0.001},
        "indicator_weights": {"rsi": 1.5, "macd": 2.0, "bollinger": 1.5},
        "consensus": {"strong_buy_threshold": 1.2, "buy_threshold": 0.4,
                      "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
                      "min_active_signals": 4, "conflict_ratio_threshold": 0.6},
        "no_trade": {"adx_min": 15, "atr_high_percentile": 95, "min_confidence": 40},
    }))

    # Create env file
    env_file = tmp_path / ".env"
    env_file.write_text("BINANCE_API_KEY=testkey123456789\nBINANCE_API_SECRET=testsecret987654\nUSE_TESTNET=false\n")

    # Create symbol file
    symbol_file = tmp_path / "active_symbol.txt"
    symbol_file.write_text("BTCUSDT\n")

    import dashboard.app as app_module
    # Patch the file paths
    original_status = app_module.STATUS_FILE
    original_log = app_module.LOG_FILE
    original_state = app_module.STATE_FILE
    original_config = app_module.CONFIG_FILE
    original_env = app_module.ENV_FILE
    original_symbol = app_module.SYMBOL_FILE
    original_pid = app_module.PID_FILE
    app_module.STATUS_FILE = status_file
    app_module.LOG_FILE = log_file
    app_module.STATE_FILE = tmp_path / "state.json"
    app_module.CONFIG_FILE = config_file
    app_module.ENV_FILE = env_file
    app_module.SYMBOL_FILE = symbol_file
    app_module.PID_FILE = tmp_path / "bot.pid"  # isolated PID file

    client = TestClient(app_module.app)
    yield client

    # Restore
    app_module.STATUS_FILE = original_status
    app_module.LOG_FILE = original_log
    app_module.STATE_FILE = original_state
    app_module.CONFIG_FILE = original_config
    app_module.ENV_FILE = original_env
    app_module.SYMBOL_FILE = original_symbol
    app_module.PID_FILE = original_pid


class TestDashboardPages:
    """Verify all pages render and return 200."""

    def test_index_page(self, client):
        r = client.get("/")
        assert r.status_code == 200
        assert "BTCUSDT" in r.text

    def test_positions_page(self, client):
        r = client.get("/positions")
        assert r.status_code == 200
        assert "BTCUSDT" in r.text
        assert "LONG" in r.text

    def test_signals_page(self, client):
        r = client.get("/signals")
        assert r.status_code == 200
        assert "rsi" in r.text
        assert "BUY" in r.text

    def test_logs_page(self, client):
        r = client.get("/logs")
        assert r.status_code == 200
        assert "Cycle #42" in r.text

    def test_logs_filter_level(self, client):
        r = client.get("/logs?level=ERROR")
        assert r.status_code == 200
        assert "Order failed" in r.text

    def test_logs_filter_search(self, client):
        r = client.get("/logs?search=Rate")
        assert r.status_code == 200
        assert "Rate limit" in r.text

    def test_performance_page(self, client):
        r = client.get("/performance")
        assert r.status_code == 200
        assert "60.0%" in r.text  # win rate


class TestDashboardAPI:
    """Verify JSON API endpoints are read-only and return correct data."""

    def test_api_status(self, client):
        r = client.get("/api/status")
        assert r.status_code == 200
        data = r.json()
        assert data["bot_status"] == "running"
        assert data["active_symbol"] == "BTCUSDT"
        assert data["balance"] == 10050.0

    def test_api_logs(self, client):
        r = client.get("/api/logs?n=10")
        assert r.status_code == 200
        data = r.json()
        assert len(data) == 3
        assert data[0]["level"] == "INFO"

    def test_api_logs_filtered(self, client):
        r = client.get("/api/logs?level=ERROR")
        assert r.status_code == 200
        data = r.json()
        assert len(data) == 1
        assert data[0]["level"] == "ERROR"


class TestSettingsPage:
    """Verify settings page renders and config can be updated."""

    def test_settings_page_renders(self, client):
        r = client.get("/settings")
        assert r.status_code == 200
        # Dashboard UI is intentionally Turkish — "Ayarlar" = "Settings".
        # If you ever localize this back to English, change to "Settings".
        assert "Ayarlar" in r.text
        assert "paper" in r.text

    def test_update_config(self, client):
        r = client.post("/settings/config", data={
            "mode": "paper", "market_type": "spot", "timeframe": "15m",
            "polling_interval_seconds": 120, "candle_limit": 200,
            "risk_per_trade": 0.01, "stop_loss_pct": 0.03,
            "take_profit_pct": 0.06, "trailing_stop_pct": 0.02,
            "trailing_stop_activation_pct": 0.03, "max_open_positions": 2,
            "daily_loss_limit_pct": 0.05, "confidence_threshold": 60,
            "max_risk_level": "HIGH", "break_even_trigger_pct": 0.02,
            "starting_balance": 5000, "fee_pct": 0.001,
            "strong_buy_threshold": 1.2, "buy_threshold": 0.4,
            "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
            "min_active_signals": 4, "conflict_ratio_threshold": 0.6,
            "adx_min": 15, "atr_high_percentile": 95, "min_confidence": 40,
        }, follow_redirects=False)
        assert r.status_code == 303
        # Verify config was updated
        r2 = client.get("/api/config")
        data = r2.json()
        assert data["timeframe"] == "15m"
        assert data["risk"]["risk_per_trade"] == 0.01
        assert data["risk"]["max_open_positions"] == 2

    def test_update_symbol(self, client):
        r = client.post("/settings/symbol", data={"symbol": "ETHUSDT"}, follow_redirects=False)
        assert r.status_code == 303
        r2 = client.get("/api/symbol")
        assert r2.json()["symbol"] == "ETHUSDT"

    def test_invalid_symbol_rejected(self, client):
        r = client.post("/settings/symbol", data={"symbol": "inv@lid!"}, follow_redirects=False)
        assert r.status_code == 303
        assert "error_symbol" in r.headers["location"]

    def test_update_weights(self, client):
        r = client.post("/settings/weights", data={
            "weight_rsi": 2.0, "weight_macd": 3.0, "weight_bollinger": 1.0,
        }, follow_redirects=False)
        assert r.status_code == 303
        r2 = client.get("/api/config")
        data = r2.json()
        assert data["indicator_weights"]["rsi"] == 2.0
        assert data["indicator_weights"]["macd"] == 3.0

    def test_update_env_disabled_in_rescue_build(self, client):
        """S7 contract: POST /settings/env must return 403 without mutating .env.

        Replaces the legacy ``test_update_env`` which exercised the now-removed
        write path. Comprehensive secret-leak / on-disk coverage lives in
        ``test_s7_secret_guardrails.py``.
        """
        r = client.post("/settings/env", data={
            "binance_api_key": "newkey12345678901234",
            "binance_api_secret": "",
            "binance_testnet_api_key": "",
            "binance_testnet_api_secret": "",
            "use_testnet": "true",
        }, follow_redirects=False)
        assert r.status_code == 403
        # USE_TESTNET must still be the original "false" from the fixture
        r2 = client.get("/api/env")
        assert r2.json()["USE_TESTNET"] == "false"

    def test_api_config_endpoint(self, client):
        r = client.get("/api/config")
        assert r.status_code == 200
        data = r.json()
        assert data["mode"] == "paper"
        assert "risk" in data

    def test_api_env_masks_secrets(self, client):
        r = client.get("/api/env")
        assert r.status_code == 200
        data = r.json()
        # Keys should be masked
        if data.get("BINANCE_API_KEY"):
            assert "*" in data["BINANCE_API_KEY"]


class TestBotControl:
    """Verify bot start/stop API endpoints."""

    def test_bot_status_initially_stopped(self, client):
        r = client.get("/api/bot/status")
        assert r.status_code == 200
        data = r.json()
        assert data["running"] is False

    def test_index_shows_start_button(self, client):
        r = client.get("/")
        assert r.status_code == 200
        # Dashboard UI is intentionally Turkish — "Botu Başlat" = "Start Bot",
        # "Bot durdu" = "Bot is stopped".
        assert "Botu Başlat" in r.text
        assert "Bot durdu" in r.text

    def test_bot_stop_when_not_running(self, client):
        # `_stop_bot()` has two paths: (1) PID file says alive → SIGTERM it;
        # (2) no PID file → pgrep fallback finds orphan `src.main` /
        # `run_bot.py` processes and SIGTERMs them. The client fixture isolates
        # PID_FILE to tmp_path, but pgrep still runs against the host. To prove
        # the "nothing to stop → not_running" contract honestly, we have to
        # make the pgrep fallback return empty so the test does not pick up
        # whatever happens to be running on the developer's machine.
        import subprocess as _subprocess
        empty = _subprocess.CompletedProcess(args=[], returncode=1, stdout="", stderr="")
        with patch("dashboard.app.subprocess.run", return_value=empty):
            r = client.post("/api/bot/stop")
        assert r.status_code == 200
        data = r.json()
        assert data["status"] == "not_running"
