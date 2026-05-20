"""Tests for CLI tools: set_symbol, show_status, update_config, validate_config."""

import json
import subprocess
import sys
import pytest
from pathlib import Path

import yaml

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def run_script(script_name: str, args: list[str] = None) -> subprocess.CompletedProcess:
    """Run a script from the scripts/ directory."""
    cmd = [sys.executable, str(PROJECT_ROOT / "scripts" / script_name)] + (args or [])
    return subprocess.run(cmd, capture_output=True, text=True, cwd=str(PROJECT_ROOT), timeout=10)


class TestSetSymbol:
    def test_set_valid_symbol(self, tmp_path):
        sym_file = tmp_path / "active_symbol.txt"
        sym_file.write_text("BTCUSDT\n")
        result = run_script("set_symbol.py", ["ETHUSDT", "--file", str(sym_file)])
        assert result.returncode == 0
        assert "ETHUSDT" in result.stdout
        assert sym_file.read_text().strip() == "ETHUSDT"

    def test_set_invalid_symbol(self, tmp_path):
        sym_file = tmp_path / "active_symbol.txt"
        sym_file.write_text("BTCUSDT\n")
        result = run_script("set_symbol.py", ["inv@lid!", "--file", str(sym_file)])
        assert result.returncode != 0
        assert "ERROR" in result.stderr


class TestShowStatus:
    def test_show_status_no_data(self, tmp_path, monkeypatch):
        # With nonexistent files it should error
        result = run_script("show_status.py", [
            "--status-file", str(tmp_path / "nope.json"),
            "--state-file", str(tmp_path / "nope2.json"),
        ])
        assert result.returncode != 0

    def test_show_status_with_state(self, tmp_path):
        state_file = tmp_path / "state.json"
        state_file.write_text(json.dumps({
            "active_symbol": "BTCUSDT",
            "paper_balance": 9500.0,
            "daily_pnl": -10.0,
            "total_realized_pnl": 50.0,
            "positions": {},
            "last_decision": {"action": "NO_ACTION", "signal": "NEUTRAL", "confidence": 30, "risk_level": "LOW"},
            "trade_history": [],
            "last_save_time": "2024-01-15T10:00:00+00:00",
        }))
        result = run_script("show_status.py", [
            "--status-file", str(tmp_path / "nope.json"),
            "--state-file", str(state_file),
        ])
        assert result.returncode == 0
        assert "BTCUSDT" in result.stdout

    def test_show_status_json_mode(self, tmp_path):
        status_file = tmp_path / "dashboard_status.json"
        status_file.write_text(json.dumps({
            "bot_status": "running",
            "active_symbol": "ETHUSDT",
            "last_update": "2024-01-15T10:00:00+00:00",
            "balance": 10000,
            "daily_pnl": 0,
            "total_pnl": 0,
            "unrealized_pnl": 0,
            "open_positions_count": 0,
            "cycle_count": 5,
            "latest_decision": {},
            "signal_distribution": {"buy": 0, "sell": 0, "neutral": 0},
            "performance": {},
        }))
        result = run_script("show_status.py", [
            "--status-file", str(status_file),
            "--json",
        ])
        assert result.returncode == 0
        data = json.loads(result.stdout)
        assert data["active_symbol"] == "ETHUSDT"


class TestUpdateConfig:
    def test_update_timeframe(self, tmp_path):
        cfg = tmp_path / "config.yaml"
        cfg.write_text(yaml.dump({
            "mode": "paper", "market_type": "spot", "timeframe": "1h",
            "active_symbol_path": "runtime/active_symbol.txt",
            "risk": {"risk_per_trade": 0.02, "stop_loss_pct": 0.025,
                     "take_profit_pct": 0.05, "confidence_threshold": 55},
        }))
        result = run_script("update_config.py", ["--config", str(cfg), "--timeframe", "15m"])
        assert result.returncode == 0
        assert "timeframe -> 15m" in result.stdout
        loaded = yaml.safe_load(cfg.read_text())
        assert loaded["timeframe"] == "15m"

    def test_update_risk_params(self, tmp_path):
        cfg = tmp_path / "config.yaml"
        cfg.write_text(yaml.dump({
            "mode": "paper", "market_type": "spot", "timeframe": "1h",
            "active_symbol_path": "runtime/active_symbol.txt",
            "risk": {"risk_per_trade": 0.02, "stop_loss_pct": 0.025,
                     "take_profit_pct": 0.05, "confidence_threshold": 55},
        }))
        result = run_script("update_config.py", [
            "--config", str(cfg),
            "--risk-per-trade", "0.01",
            "--confidence-threshold", "70",
        ])
        assert result.returncode == 0
        loaded = yaml.safe_load(cfg.read_text())
        assert loaded["risk"]["risk_per_trade"] == 0.01
        assert loaded["risk"]["confidence_threshold"] == 70

    def test_show_config(self, tmp_path):
        cfg = tmp_path / "config.yaml"
        cfg.write_text(yaml.dump({"mode": "paper", "timeframe": "4h"}))
        result = run_script("update_config.py", ["--config", str(cfg), "--show"])
        assert result.returncode == 0
        assert "paper" in result.stdout

    def test_invalid_timeframe(self, tmp_path):
        cfg = tmp_path / "config.yaml"
        cfg.write_text(yaml.dump({"mode": "paper", "timeframe": "1h"}))
        result = run_script("update_config.py", ["--config", str(cfg), "--timeframe", "99x"])
        assert result.returncode != 0


class TestValidateConfig:
    def test_valid_config(self):
        result = run_script("validate_config.py", ["--config", "config/default.yaml"])
        assert result.returncode == 0
        assert "valid" in result.stdout.lower()

    def test_missing_config(self, tmp_path):
        result = run_script("validate_config.py", ["--config", str(tmp_path / "nope.yaml")])
        assert result.returncode != 0
