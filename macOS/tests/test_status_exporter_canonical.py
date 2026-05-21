"""Canonical price-contract tests for StatusExporter.

S3 separates 'signal price' (what the decision engine acted on) from
'display price' (what the dashboard puts on screen now). This file pins:

  - The exporter writes a `display_price`, `display_price_source`,
    `price_age_ms`, and `signal_price` for every cycle.
  - `latest_decision.price` is decision metadata only. It mirrors
    `signal_price`, not `display_price`. If a live tick is fresher than
    the candle close, the two diverge — and the JSON must show that.
  - When the LivePriceService is unavailable, display_price gracefully
    falls back to signal_price with `display_price_source="cycle_close"`.
  - The pre-S3 `current_price` field stays in place (it equals
    `display_price` today), so S2 truth tests keep passing verbatim.
  - Optional `mark_price`, `best_bid`, `best_ask` are surfaced when
    present and omitted otherwise (never faked).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from src.monitoring.status_exporter import StatusExporter


@pytest.fixture
def cfg():
    return {"mode": "paper", "market_type": "spot", "timeframe": "1h",
            "polling_interval_seconds": 60}


@pytest.fixture
def state():
    return {
        "active_symbol": "BTCUSDT",
        "positions": {},
        "trade_history": [],
        "daily_pnl": 0.0,
        "total_realized_pnl": 0.0,
        "daily_start_balance": 10000.0,
        "bot_start_time": "2026-05-21T00:00:00+00:00",
    }


def _read(path: Path) -> dict:
    return json.loads(path.read_text())


# ── Canonical fields ──────────────────────────────────────────────


class TestCanonicalFields:
    def test_export_includes_display_and_signal_price(self, tmp_path, cfg, state):
        ex = StatusExporter(str(tmp_path / "ds.json"))
        ex.export(
            config=cfg, state=state, consensus=None, indicator_results=None,
            decision=None, execution_result=None, balance=10000.0,
            current_price=65000.0, cycle_count=1, running=True,
            signal_price=65000.0, display_price=65050.0,
            display_price_source="rest:binance", price_age_ms=1200,
        )
        snap = _read(tmp_path / "ds.json")
        assert snap["display_price"] == 65050.0
        assert snap["display_price_source"] == "rest:binance"
        assert snap["price_age_ms"] == 1200
        assert snap["signal_price"] == 65000.0
        # current_price stays as the display price for S2 backward compat.
        assert snap["current_price"] == 65050.0

    def test_decision_price_is_signal_price_not_display_price(self, tmp_path, cfg, state):
        """`latest_decision.price` is decision metadata. It MUST track
        signal_price, not the freshest tick. This prevents the dashboard
        from pretending a stale decision was made at the latest live price."""
        ex = StatusExporter(str(tmp_path / "ds.json"))
        decision = {"action": "HOLD", "timestamp": "now", "leverage": 1, "reason": "ok"}
        consensus = {
            "final_signal": "NEUTRAL", "confidence": 12, "risk_level": "LOW",
            "weighted_score": 0.0, "should_trade": False,
            "score_data": {},
        }
        ex.export(
            config=cfg, state=state, consensus=consensus, indicator_results=None,
            decision=decision, execution_result=None, balance=10000.0,
            current_price=65000.0, cycle_count=1, running=True,
            signal_price=65000.0, display_price=65500.0,
            display_price_source="rest:binance", price_age_ms=500,
        )
        snap = _read(tmp_path / "ds.json")
        # The decision/signal price is the candle close, NOT the live tick.
        assert snap["latest_decision"]["price"] == 65000.0
        # And display diverged.
        assert snap["display_price"] == 65500.0
        assert snap["latest_decision"]["price"] != snap["display_price"]

    def test_display_price_falls_back_to_signal_price_when_unset(self, tmp_path, cfg, state):
        ex = StatusExporter(str(tmp_path / "ds.json"))
        # No LivePriceService data — exporter must use signal_price.
        ex.export(
            config=cfg, state=state, consensus=None, indicator_results=None,
            decision=None, execution_result=None, balance=10000.0,
            current_price=65000.0, cycle_count=1, running=True,
            signal_price=65000.0,
        )
        snap = _read(tmp_path / "ds.json")
        assert snap["display_price"] == 65000.0
        assert snap["display_price_source"] == "cycle_close"
        # When no live tick is available, age is unknown — not zero, which
        # would lie about freshness.
        assert snap["price_age_ms"] is None

    def test_optional_market_microstructure_only_when_present(self, tmp_path, cfg, state):
        ex = StatusExporter(str(tmp_path / "ds.json"))
        ex.export(
            config=cfg, state=state, consensus=None, indicator_results=None,
            decision=None, execution_result=None, balance=10000.0,
            current_price=65000.0, cycle_count=1, running=True,
            signal_price=65000.0, display_price=65000.0,
            display_price_source="rest:binance",
            mark_price=65010.0, best_bid=64999.5, best_ask=65000.5,
        )
        snap = _read(tmp_path / "ds.json")
        assert snap["mark_price"] == 65010.0
        assert snap["best_bid"] == 64999.5
        assert snap["best_ask"] == 65000.5

    def test_microstructure_fields_are_none_by_default(self, tmp_path, cfg, state):
        ex = StatusExporter(str(tmp_path / "ds.json"))
        ex.export(
            config=cfg, state=state, consensus=None, indicator_results=None,
            decision=None, execution_result=None, balance=10000.0,
            current_price=65000.0, cycle_count=1, running=True,
            signal_price=65000.0,
        )
        snap = _read(tmp_path / "ds.json")
        # Fields are present as None — explicit absence is the honest signal.
        assert snap["mark_price"] is None
        assert snap["best_bid"] is None
        assert snap["best_ask"] is None

    def test_unavailable_price_keeps_display_null(self, tmp_path, cfg, state):
        """When the cycle has no price at all (no ticker, no close), the
        canonical contract is: display_price=None, signal_price=None,
        source="unavailable", age=None. The dashboard renders 'Veri Yok'."""
        ex = StatusExporter(str(tmp_path / "ds.json"))
        ex.export(
            config=cfg, state=state, consensus=None, indicator_results=None,
            decision=None, execution_result=None, balance=10000.0,
            current_price=None, cycle_count=1, running=True,
            signal_price=None, display_price=None,
        )
        snap = _read(tmp_path / "ds.json")
        assert snap["display_price"] is None
        assert snap["signal_price"] is None
        assert snap["display_price_source"] == "unavailable"
        assert snap["price_age_ms"] is None
        assert snap["current_price"] is None

    def test_decision_price_preserved_when_signal_price_omitted(self, tmp_path, cfg, state):
        """Backward compat: callers that don't yet pass signal_price still
        get latest_decision.price = current_price (the pre-S3 behaviour)."""
        ex = StatusExporter(str(tmp_path / "ds.json"))
        consensus = {"final_signal": "BUY", "confidence": 70, "risk_level": "LOW",
                     "weighted_score": 0.8, "should_trade": True, "score_data": {}}
        decision = {"action": "BUY", "timestamp": "now", "leverage": 1, "reason": ""}
        ex.export(
            config=cfg, state=state, consensus=consensus, indicator_results=None,
            decision=decision, execution_result=None, balance=10000.0,
            current_price=65000.0, cycle_count=1, running=True,
        )
        snap = _read(tmp_path / "ds.json")
        assert snap["latest_decision"]["price"] == 65000.0
        assert snap["signal_price"] == 65000.0  # exporter mirrors current_price


# ── S2 backward compatibility ─────────────────────────────────────


class TestS2BackwardCompat:
    """The S2 truth contract must still hold — current_price keeps its
    meaning and dashboard `_price_display` can keep reading it."""

    def test_current_price_mirrors_display_price(self, tmp_path, cfg, state):
        ex = StatusExporter(str(tmp_path / "ds.json"))
        ex.export(
            config=cfg, state=state, consensus=None, indicator_results=None,
            decision=None, execution_result=None, balance=10000.0,
            current_price=65000.0, cycle_count=1, running=True,
            signal_price=65000.0, display_price=65050.0,
            display_price_source="rest:binance", price_age_ms=300,
        )
        snap = _read(tmp_path / "ds.json")
        assert snap["current_price"] == snap["display_price"] == 65050.0

    def test_stopped_export_preserves_signal_decision_separation(self, tmp_path, cfg, state):
        """export_stopped is the path that produced the original incident:
        `current_price=null` and `latest_decision.price=0.4475`. Pin that
        stopped snapshots still distinguish the two."""
        state2 = dict(state)
        state2["last_decision"] = {
            "action": "HOLD", "signal": "NEUTRAL",
            "confidence": 12, "risk_level": "LOW",
            "price": 0.4475, "timestamp": "old",
        }
        ex = StatusExporter(str(tmp_path / "ds.json"))
        ex.export_stopped(cfg, state2)
        snap = _read(tmp_path / "ds.json")
        # Stopped snapshot has no live or signal price:
        assert snap["current_price"] is None
        assert snap["display_price"] is None
        assert snap["signal_price"] is None
        assert snap["display_price_source"] == "unavailable"
        # But the historical decision is preserved — and its `price` is
        # decision metadata, not display.
        assert snap["latest_decision"]["price"] == 0.4475
