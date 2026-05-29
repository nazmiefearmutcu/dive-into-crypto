"""Dashboard tests for the S3 canonical price contract.

`_price_display` was the S2 fix that stopped the dashboard from rendering
`latest_decision.price` as the live price. S3 adds the canonical
`display_price` field and these tests pin:

  - `_price_display` prefers `display_price` when present.
  - `display_price` may be more recent than `signal_price`/decision price.
  - When `display_price` is null but `current_price` is set (legacy snapshots
    from pre-S3 builds), `_price_display` falls back to `current_price`.
  - `display_price_source` and `price_age_ms` are surfaced in /api/status.
  - `latest_decision.price` (decision metadata) NEVER leaks into the
    displayed price text, even when `display_price` is null.
  - `price_age_ms` past the configured stale threshold downgrades the
    state to "snapshot" even if the bot says it's running.
"""

import json
from datetime import datetime, timezone, timedelta
from unittest.mock import patch

import pytest
import yaml
from fastapi.testclient import TestClient


# ── Fixtures (shaped after the S2 truth tests) ─────────────────────


_DECISION_MARKER = 0.44751337


def _make_s3_status(
    *,
    display_price=65500.0,
    signal_price=65000.0,
    display_price_source="rest:binance",
    price_age_ms=300,
    bot_status="running",
    last_update=None,
):
    now = (last_update or datetime.now(timezone.utc)).isoformat() if isinstance(
        last_update or datetime.now(timezone.utc), datetime
    ) else last_update
    return {
        "bot_status": bot_status,
        "mode": "paper",
        "market_type": "spot",
        "timeframe": "4h",
        "polling_interval": 1,
        "active_symbol": "BTCUSDT",
        "current_price": display_price,  # S2 mirror
        "display_price": display_price,
        "display_price_source": display_price_source,
        "price_age_ms": price_age_ms,
        "signal_price": signal_price,
        "mark_price": None,
        "best_bid": None,
        "best_ask": None,
        "last_update": now,
        "cycle_count": 7,
        "balance": 10000.0,
        "daily_pnl": 0.0,
        "total_pnl": 0.0,
        "unrealized_pnl": 0.0,
        "daily_start_balance": 10000.0,
        "open_positions_count": 0,
        "open_positions": [],
        "latest_decision": {
            "action": "HOLD", "signal": "BUY", "confidence": 68,
            "risk_level": "LOW", "weighted_score": 0.7,
            "should_trade": True,
            "price": signal_price,  # decision metadata = signal_price
            "leverage": 1, "timestamp": now,
        },
        "indicator_votes": [],
        "signal_distribution": {"buy": 1, "sell": 0, "neutral": 0},
        "score_details": [],
        "trade_history": [],
        "performance": {},
        "bot_start_time": now,
    }


def _build_client(tmp_path, status, bot_running=True):
    status_file = tmp_path / "dashboard_status.json"
    status_file.write_text(json.dumps(status))
    (tmp_path / "bot.log").write_text("")
    (tmp_path / "active_symbol.txt").write_text(status.get("active_symbol", "BTCUSDT") + "\n")
    (tmp_path / ".env").write_text("BINANCE_API_KEY=test\n")
    (tmp_path / "config.yaml").write_text(yaml.dump({
        "mode": "paper", "market_type": "spot", "timeframe": "1h",
        "candle_limit": 200, "polling_interval_seconds": 60,
        "active_symbol_path": "runtime/active_symbol.txt",
        "risk": {"max_open_positions": 1, "confidence_threshold": 55,
                  "stop_loss_pct": 0.025, "take_profit_pct": 0.05,
                  "trailing_stop_pct": 0.02, "trailing_stop_activation_pct": 0.03,
                  "risk_per_trade": 0.02, "daily_loss_limit_pct": 0.05,
                  "max_risk_level": "MEDIUM", "break_even_trigger_pct": 0.02},
        "paper": {"starting_balance": 10000.0, "fee_pct": 0.001},
        "indicator_weights": {"rsi": 1.5},
        "consensus": {"strong_buy_threshold": 1.2, "buy_threshold": 0.4,
                       "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
                       "min_active_signals": 4, "conflict_ratio_threshold": 0.6},
        "no_trade": {"adx_min": 15, "atr_high_percentile": 95, "min_confidence": 40},
    }))
    import dashboard.app as app_module
    saved = {k: getattr(app_module, k) for k in
             ("STATUS_FILE", "LOG_FILE", "STATE_FILE", "CONFIG_FILE",
              "ENV_FILE", "SYMBOL_FILE", "PID_FILE", "RUNTIME_DIR")}
    app_module.STATUS_FILE = status_file
    app_module.LOG_FILE = tmp_path / "bot.log"
    app_module.STATE_FILE = tmp_path / "state.json"
    app_module.CONFIG_FILE = tmp_path / "config.yaml"
    app_module.ENV_FILE = tmp_path / ".env"
    app_module.SYMBOL_FILE = tmp_path / "active_symbol.txt"
    app_module.PID_FILE = tmp_path / "bot.pid"
    app_module.RUNTIME_DIR = tmp_path
    patcher = patch("dashboard.app._is_bot_running", return_value=bot_running)
    patcher.start()

    def cleanup():
        patcher.stop()
        for k, v in saved.items():
            setattr(app_module, k, v)
    return TestClient(app_module.app), cleanup


# ── Helper-level tests ────────────────────────────────────────────


class TestPriceDisplayPrefersDisplayPrice:
    def test_helper_uses_display_price_when_available(self):
        from dashboard.app import _price_display
        s = _make_s3_status(display_price=65500.0, signal_price=65000.0)
        out = _price_display(s, bot_running=True)
        assert out["state"] == "live"
        assert out["raw"] == 65500.0  # the LIVE tick, not signal
        # And it must not be the decision price.
        assert out["raw"] != s["latest_decision"]["price"]

    def test_helper_falls_back_to_current_price_when_display_missing(self):
        """A pre-S3 snapshot (no display_price key) must still render
        correctly — _price_display falls back to current_price."""
        from dashboard.app import _price_display
        s = _make_s3_status()
        s.pop("display_price", None)
        s.pop("display_price_source", None)
        # current_price still set to display value
        out = _price_display(s, bot_running=True)
        assert out["state"] == "live"
        assert out["raw"] == 65500.0

    def test_helper_unavailable_when_display_null_and_current_null(self):
        from dashboard.app import _price_display
        s = _make_s3_status()
        s["display_price"] = None
        s["current_price"] = None
        out = _price_display(s, bot_running=False)
        assert out["state"] == "unavailable"
        assert out["text"] == "No Data"
        # Even with display_price null, signal_price/decision must NOT leak.
        assert _DECISION_MARKER not in {out.get("raw"), out["text"]}
        # Defensive: explicitly add decision marker to make the contract obvious
        s["latest_decision"]["price"] = _DECISION_MARKER
        out2 = _price_display(s, bot_running=False)
        assert out2["state"] == "unavailable"
        assert _DECISION_MARKER not in {out2.get("raw"), out2["text"]}

    def test_helper_marks_stale_via_price_age_ms(self):
        """If price_age_ms is past the configured stale threshold, the
        UI must downgrade to 'snapshot' even when bot_status=='running'."""
        from dashboard.app import _price_display
        s = _make_s3_status(price_age_ms=10 * 60 * 1000)  # 10 minutes old
        out = _price_display(s, bot_running=True)
        assert out["state"] == "snapshot"
        assert out["is_live"] is False

    def test_helper_live_for_fresh_price_age(self):
        from dashboard.app import _price_display
        s = _make_s3_status(price_age_ms=500)
        out = _price_display(s, bot_running=True)
        assert out["state"] == "live"
        assert out["is_live"] is True


# ── /api/status surface ──────────────────────────────────────────


class TestApiStatusSurface:
    def test_api_includes_canonical_fields(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_s3_status(), bot_running=True)
        try:
            r = client.get("/api/status")
            assert r.status_code == 200
            data = r.json()
            for k in ("display_price", "display_price_source",
                      "price_age_ms", "signal_price"):
                assert k in data, f"missing canonical field {k!r}"
            assert data["display_price"] == 65500.0
            assert data["signal_price"] == 65000.0
            assert data["display_price_source"] == "rest:binance"
        finally:
            cleanup()

    def test_api_price_display_uses_display_price(self, tmp_path):
        s = _make_s3_status(display_price=65500.0, signal_price=65000.0)
        client, cleanup = _build_client(tmp_path, s, bot_running=True)
        try:
            r = client.get("/api/status")
            pd = r.json()["_price_display"]
            assert pd["state"] == "live"
            assert pd["raw"] == 65500.0
        finally:
            cleanup()

    def test_api_does_not_leak_decision_price_when_display_null(self, tmp_path):
        s = _make_s3_status()
        s["display_price"] = None
        s["current_price"] = None
        s["latest_decision"]["price"] = _DECISION_MARKER
        client, cleanup = _build_client(tmp_path, s, bot_running=False)
        try:
            r = client.get("/api/status")
            data = r.json()
            pd = data["_price_display"]
            # The display must be honest:
            assert pd["state"] == "unavailable"
            assert pd["text"] == "No Data"
            # And the decision-price marker must not leak into _price_display.
            assert _DECISION_MARKER not in {pd.get("raw"), pd.get("text")}
            # latest_decision.price is exposed (it's just metadata) but the
            # display field never references it.
            assert data["latest_decision"]["price"] == _DECISION_MARKER
        finally:
            cleanup()


# ── Hard regression guard ─────────────────────────────────────────


class TestNoDecisionPriceLeak:
    def test_helper_never_reads_latest_decision_price_even_with_canonical_fields(self):
        """Cover the hostile case: display_price is None, current_price is
        None, but latest_decision.price is a plausible-looking number.
        The helper MUST return 'unavailable' / 'No Data' and MUST NOT
        produce the decision-price marker anywhere in its output."""
        from dashboard.app import _price_display
        s = _make_s3_status()
        s["display_price"] = None
        s["current_price"] = None
        s["signal_price"] = None
        s["latest_decision"]["price"] = _DECISION_MARKER
        out = _price_display(s, bot_running=True)
        assert out["state"] == "unavailable"
        assert out["raw"] is None
        assert _DECISION_MARKER not in {out.get("raw"), out.get("text")}
