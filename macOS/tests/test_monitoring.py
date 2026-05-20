"""Tests for the monitoring module: status_exporter, log_reader, metrics."""

import json
import pytest
from pathlib import Path

from src.monitoring.status_exporter import StatusExporter, _compute_performance
from src.monitoring.log_reader import LogReader
from src.monitoring.metrics import compute_daily_summary, compute_equity_curve, compute_streak
from src.indicators.base import IndicatorResult, Signal


# ───── StatusExporter tests ────────────────────────────────────────

class TestStatusExporter:
    def test_export_creates_file(self, tmp_path):
        out = tmp_path / "dashboard_status.json"
        exporter = StatusExporter(str(out))
        exporter.export(
            config={"mode": "paper", "market_type": "spot", "timeframe": "1h", "polling_interval_seconds": 60},
            state={"active_symbol": "BTCUSDT", "positions": {}, "daily_pnl": 0.0,
                   "total_realized_pnl": 0.0, "daily_start_balance": 10000.0, "trade_history": [],
                   "bot_start_time": "2024-01-01T00:00:00+00:00"},
            consensus={"final_signal": "BUY", "confidence": 70, "risk_level": "LOW",
                       "weighted_score": 0.8, "should_trade": True, "score_data": {"signal_details": []}},
            indicator_results=[
                IndicatorResult(name="rsi", signal=Signal.BUY, score=1, reason="test"),
                IndicatorResult(name="macd", signal=Signal.SELL, score=-1, reason="test"),
                IndicatorResult(name="atr_filter", signal=Signal.NEUTRAL, score=0, reason="test"),
            ],
            decision={"action": "OPEN_LONG", "reason": "test", "timestamp": "2024-01-01T00:00:00+00:00"},
            execution_result={"executed": True},
            balance=10000.0,
            current_price=50000.0,
            cycle_count=5,
            running=True,
        )
        assert out.exists()
        data = json.loads(out.read_text())
        assert data["bot_status"] == "running"
        assert data["active_symbol"] == "BTCUSDT"
        assert data["balance"] == 10000.0
        assert data["signal_distribution"]["buy"] == 1
        assert data["signal_distribution"]["sell"] == 1
        assert data["signal_distribution"]["neutral"] == 1
        assert len(data["indicator_votes"]) == 3

    def test_export_stopped(self, tmp_path):
        out = tmp_path / "dashboard_status.json"
        exporter = StatusExporter(str(out))
        exporter.export_stopped(
            config={"mode": "paper", "market_type": "spot", "timeframe": "1h", "polling_interval_seconds": 60},
            state={"active_symbol": "BTCUSDT", "positions": {}, "daily_pnl": 0,
                   "total_realized_pnl": 0, "daily_start_balance": 10000, "paper_balance": 10000,
                   "trade_history": [], "bot_start_time": None},
        )
        data = json.loads(out.read_text())
        assert data["bot_status"] == "stopped"

    def test_atomic_write_no_tmp_leftover(self, tmp_path):
        out = tmp_path / "status.json"
        exporter = StatusExporter(str(out))
        exporter._write_atomic({"test": True})
        assert out.exists()
        assert not out.with_suffix(".tmp").exists()

    def test_export_with_positions(self, tmp_path):
        out = tmp_path / "dashboard_status.json"
        exporter = StatusExporter(str(out))
        exporter.export(
            config={"mode": "paper", "market_type": "spot", "timeframe": "1h", "polling_interval_seconds": 60},
            state={
                "active_symbol": "BTCUSDT",
                "positions": {
                    "BTCUSDT": {
                        "symbol": "BTCUSDT", "side": "LONG", "entry_price": 50000,
                        "quantity": 0.1, "stop_loss": 48750, "take_profit": 52500,
                    }
                },
                "daily_pnl": 10.0, "total_realized_pnl": 50.0,
                "daily_start_balance": 10000, "trade_history": [], "bot_start_time": None,
            },
            consensus=None, indicator_results=None, decision=None, execution_result=None,
            balance=10050.0, current_price=51000.0, cycle_count=1, running=True,
        )
        data = json.loads(out.read_text())
        assert data["open_positions_count"] == 1
        assert data["open_positions"][0]["current_price"] == 51000.0
        assert data["unrealized_pnl"] > 0


class TestComputePerformance:
    def test_empty_history(self):
        perf = _compute_performance([])
        assert perf["total_trades"] == 0
        assert perf["win_rate"] == 0.0

    def test_with_trades(self):
        history = [
            {"pnl": 10.0}, {"pnl": -5.0}, {"pnl": 15.0}, {"pnl": -3.0}, {"pnl": 8.0},
        ]
        perf = _compute_performance(history)
        assert perf["total_trades"] == 5
        assert perf["wins"] == 3
        assert perf["losses"] == 2
        assert perf["win_rate"] == 60.0
        assert perf["total_pnl"] == 25.0
        assert perf["best_trade"] == 15.0
        assert perf["worst_trade"] == -5.0
        assert perf["max_drawdown"] >= 0

    def test_all_wins(self):
        perf = _compute_performance([{"pnl": 10}, {"pnl": 5}])
        assert perf["win_rate"] == 100.0
        assert perf["profit_factor"] == 0.0  # no losses


# ───── LogReader tests ─────────────────────────────────────────────

class TestLogReader:
    def test_read_tail(self, tmp_path):
        log_file = tmp_path / "bot.log"
        lines = [f"2024-01-01 00:00:{i:02d} | INFO     | module | message {i}\n" for i in range(50)]
        log_file.write_text("".join(lines))

        reader = LogReader(str(log_file))
        tail = reader.read_tail(10)
        assert len(tail) == 10
        assert "message 49" in tail[-1]

    def test_read_filtered_by_level(self, tmp_path):
        log_file = tmp_path / "bot.log"
        log_file.write_text(
            "2024-01-01 00:00:01 | INFO     | mod | info msg\n"
            "2024-01-01 00:00:02 | WARNING  | mod | warn msg\n"
            "2024-01-01 00:00:03 | ERROR    | mod | error msg\n"
            "2024-01-01 00:00:04 | INFO     | mod | info msg 2\n"
        )
        reader = LogReader(str(log_file))
        entries = reader.read_filtered(100, level="WARNING")
        assert len(entries) == 1
        assert entries[0]["level"] == "WARNING"

    def test_read_filtered_by_search(self, tmp_path):
        log_file = tmp_path / "bot.log"
        log_file.write_text(
            "2024-01-01 00:00:01 | INFO     | mod | BTCUSDT price 50000\n"
            "2024-01-01 00:00:02 | INFO     | mod | ETHUSDT price 3000\n"
        )
        reader = LogReader(str(log_file))
        entries = reader.read_filtered(100, search="ETHUSDT")
        assert len(entries) == 1
        assert "ETHUSDT" in entries[0]["message"]

    def test_missing_file(self, tmp_path):
        reader = LogReader(str(tmp_path / "nonexistent.log"))
        assert reader.read_tail(10) == []
        assert reader.get_file_size() == 0

    def test_parse_line(self, tmp_path):
        reader = LogReader(str(tmp_path / "x.log"))
        entry = reader._parse_line("2024-01-01 10:00:00 | WARNING  | my.module | Something happened")
        assert entry["timestamp"] == "2024-01-01 10:00:00"
        assert entry["level"] == "WARNING"
        assert entry["module"] == "my.module"
        assert entry["message"] == "Something happened"


# ───── Metrics tests ───────────────────────────────────────────────

class TestMetrics:
    def test_daily_summary(self):
        history = [
            {"pnl": 10.0, "exit_time": "2024-01-15T10:00:00+00:00"},
            {"pnl": -5.0, "exit_time": "2024-01-15T14:00:00+00:00"},
            {"pnl": 8.0,  "exit_time": "2024-01-16T09:00:00+00:00"},
        ]
        summaries = compute_daily_summary(history)
        assert len(summaries) == 2
        assert summaries[0]["date"] == "2024-01-15"
        assert summaries[0]["trades"] == 2
        assert summaries[0]["pnl"] == 5.0
        assert summaries[1]["date"] == "2024-01-16"
        assert summaries[1]["trades"] == 1

    def test_equity_curve(self):
        history = [{"pnl": 10.0}, {"pnl": -3.0}, {"pnl": 5.0}]
        curve = compute_equity_curve(10000.0, history)
        assert len(curve) == 4
        assert curve[0]["equity"] == 10000.0
        assert curve[-1]["equity"] == 10012.0

    def test_streak(self):
        history = [{"pnl": 1}, {"pnl": 2}, {"pnl": 3}, {"pnl": -1}, {"pnl": -2}]
        s = compute_streak(history)
        assert s["max_win_streak"] == 3
        assert s["max_loss_streak"] == 2
        assert s["current_streak"] == -2  # ending on loss streak

    def test_empty_streak(self):
        s = compute_streak([])
        assert s["current_streak"] == 0
