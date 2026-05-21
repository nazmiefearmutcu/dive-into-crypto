"""UI truth-contract tests for the dashboard.

These tests pin a single rule: the dashboard MUST NOT lie about whether a
displayed price is live. Specifically:

  - The `#live-price` element MUST come from `current_price` only.
  - Substituting `latest_decision.price` as the live price is forbidden — that
    field is captured at decision time and is stale by definition between
    cycles. We were doing this on AJAX refresh; this test family makes that
    impossible to silently reintroduce.
  - When `current_price` is null/missing, the UI must show "Veri Yok" (or
    equivalent unavailable state), never `$0.0000`, and never any other
    price-shaped number scraped from a different field.
  - When the bot is stopped or the data is stale, the snapshot banner must
    appear on the dashboard so a glance at the screen tells the truth.

The canonical reproducer is the 2026-04-22 incident snapshot:
`current_price=null`, `latest_decision.price=0.4475`, `bot_status="stopped"`,
`cycle_count=0`. That exact shape is reproduced in `incident_status`.
"""

import json
from datetime import datetime, timezone, timedelta
from unittest.mock import patch

import pytest
import yaml
from fastapi.testclient import TestClient


# ── Fixtures ────────────────────────────────────────────────────────


def _decision_price_marker() -> float:
    """A deliberately distinctive value that would only appear if the UI
    incorrectly substituted `latest_decision.price` for the live price."""
    return 0.44751337  # 8 sig figs — improbable to collide with other values


def _make_incident_status() -> dict:
    """Reproduce the shape of macOS/tests/fixtures/runtime_snapshots/incident_2026_04_22/dashboard_status.json
    that triggered the live-price truth bug. Keep keys minimal but realistic.
    """
    return {
        "bot_status": "stopped",
        "mode": "paper",
        "market_type": "futures",
        "timeframe": "4h",
        "polling_interval": 1,
        "active_symbol": "PLAYUSDT",
        "current_price": None,  # <-- the canonical null
        "last_update": "2026-04-22T23:22:51.516564+00:00",
        "cycle_count": 0,
        "balance": 285661.4854,
        "daily_pnl": -237137.95,
        "total_pnl": 3195401.15,
        "unrealized_pnl": 0.0,
        "daily_start_balance": 1754.6288,
        "open_positions_count": 1,
        "open_positions": [{
            "symbol": "DEXEUSDT", "side": "LONG",
            "entry_price": 9.417, "quantity": 197547.0,
            "stop_loss": 11.78, "take_profit": 10.36,
            "unrealized_pnl": 508487.6,
            "current_price": None,  # stopped bot — also null per position
            "leverage": 8,
            "liquidation_price": 8.27,
            "is_break_even": True,
            "warning": "take_profit",
        }],
        "latest_decision": {
            "action": "HOLD",
            "signal": "NEUTRAL",
            "confidence": 12,
            "risk_level": "LOW",
            "weighted_score": -0.1,
            "reason": "Snapshot from before bot was stopped",
            "should_trade": False,
            # THIS is the field that must NOT leak into #live-price:
            "price": _decision_price_marker(),
        },
        "indicator_votes": [],
        "signal_distribution": {"buy": 0, "sell": 0, "neutral": 0},
        "score_details": [],
        "trade_history": [],
        "performance": {},
        "bot_start_time": None,
    }


def _make_live_status() -> dict:
    """A healthy live snapshot — bot running, recent last_update, current_price set."""
    now = datetime.now(timezone.utc).isoformat()
    return {
        "bot_status": "running",
        "mode": "paper",
        "market_type": "spot",
        "timeframe": "1h",
        "polling_interval": 60,
        "active_symbol": "BTCUSDT",
        "current_price": 65432.50,  # <-- canonical live price
        "last_update": now,
        "cycle_count": 7,
        "balance": 10050.0,
        "daily_pnl": 50.0,
        "total_pnl": 150.0,
        "unrealized_pnl": 25.0,
        "daily_start_balance": 10000.0,
        "open_positions_count": 0,
        "open_positions": [],
        "latest_decision": {
            "action": "HOLD", "signal": "BUY", "confidence": 68,
            "risk_level": "LOW", "weighted_score": 0.742,
            "reason": "ok", "should_trade": True,
            "price": _decision_price_marker(),  # different from current_price
        },
        "indicator_votes": [],
        "signal_distribution": {"buy": 1, "sell": 0, "neutral": 0},
        "score_details": [],
        "trade_history": [],
        "performance": {"total_trades": 0, "wins": 0, "losses": 0, "win_rate": 0},
        "bot_start_time": now,
    }


def _make_stale_status() -> dict:
    """A stale snapshot — current_price set but last_update is hours old.
    Stale-but-numeric must render as 'snapshot' not 'live'."""
    old = (datetime.now(timezone.utc) - timedelta(hours=6)).isoformat()
    s = _make_live_status()
    s["last_update"] = old
    s["bot_status"] = "running"  # even if file says running, stale wins
    return s


def _build_client(tmp_path, status: dict, bot_running: bool = False):
    """Build a TestClient with isolated runtime files, then override _is_bot_running
    so we control bot-running state from the test."""
    status_file = tmp_path / "dashboard_status.json"
    log_file = tmp_path / "bot.log"
    status_file.write_text(json.dumps(status))
    log_file.write_text("")

    config_file = tmp_path / "config.yaml"
    config_file.write_text(yaml.dump({
        "mode": "paper", "market_type": "spot", "timeframe": "1h",
        "candle_limit": 200, "polling_interval_seconds": 60,
        "active_symbol_path": "runtime/active_symbol.txt",
        "risk": {
            "risk_per_trade": 0.02, "stop_loss_pct": 0.025,
            "take_profit_pct": 0.05, "trailing_stop_pct": 0.02,
            "trailing_stop_activation_pct": 0.03, "max_open_positions": 1,
            "daily_loss_limit_pct": 0.05, "confidence_threshold": 55,
            "max_risk_level": "MEDIUM", "break_even_trigger_pct": 0.02,
        },
        "paper": {"starting_balance": 10000.0, "fee_pct": 0.001},
        "indicator_weights": {"rsi": 1.5, "macd": 2.0},
        "consensus": {
            "strong_buy_threshold": 1.2, "buy_threshold": 0.4,
            "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
            "min_active_signals": 4, "conflict_ratio_threshold": 0.6,
        },
        "no_trade": {"adx_min": 15, "atr_high_percentile": 95, "min_confidence": 40},
    }))

    env_file = tmp_path / ".env"
    env_file.write_text("BINANCE_API_KEY=test\nUSE_TESTNET=false\n")
    symbol_file = tmp_path / "active_symbol.txt"
    symbol_file.write_text(status.get("active_symbol", "BTCUSDT") + "\n")

    import dashboard.app as app_module
    saved = {
        "STATUS_FILE": app_module.STATUS_FILE,
        "LOG_FILE": app_module.LOG_FILE,
        "STATE_FILE": app_module.STATE_FILE,
        "CONFIG_FILE": app_module.CONFIG_FILE,
        "ENV_FILE": app_module.ENV_FILE,
        "SYMBOL_FILE": app_module.SYMBOL_FILE,
        "PID_FILE": app_module.PID_FILE,
        "RUNTIME_DIR": app_module.RUNTIME_DIR,
    }
    app_module.STATUS_FILE = status_file
    app_module.LOG_FILE = log_file
    app_module.STATE_FILE = tmp_path / "state.json"
    app_module.CONFIG_FILE = config_file
    app_module.ENV_FILE = env_file
    app_module.SYMBOL_FILE = symbol_file
    app_module.PID_FILE = tmp_path / "bot.pid"
    app_module.RUNTIME_DIR = tmp_path

    patcher = patch("dashboard.app._is_bot_running", return_value=bot_running)
    patcher.start()

    client = TestClient(app_module.app)

    def cleanup():
        patcher.stop()
        for k, v in saved.items():
            setattr(app_module, k, v)

    return client, cleanup


# ── Direct helper tests (no HTTP) ──────────────────────────────────


class TestPriceDisplayHelper:
    """Verify the `_price_display` helper directly — fastest, most precise check."""

    def test_unavailable_when_current_price_is_null(self):
        from dashboard.app import _price_display
        out = _price_display(_make_incident_status(), bot_running=False)
        assert out["state"] == "unavailable"
        assert out["is_live"] is False
        assert out["text"] == "Veri Yok"
        # Critically: helper must NEVER fall back to latest_decision.price
        assert _decision_price_marker() not in {out.get("raw"), out["text"]}

    def test_unavailable_for_zero_price(self):
        from dashboard.app import _price_display
        s = _make_incident_status()
        s["current_price"] = 0.0  # also forbidden — explicit zero is not a price
        out = _price_display(s, bot_running=True)
        assert out["state"] == "unavailable"
        assert out["is_live"] is False

    def test_unavailable_for_garbage_price(self):
        from dashboard.app import _price_display
        s = _make_incident_status()
        s["current_price"] = "n/a"
        out = _price_display(s, bot_running=True)
        assert out["state"] == "unavailable"

    def test_snapshot_when_bot_stopped_but_price_present(self):
        from dashboard.app import _price_display
        s = _make_live_status()
        s["bot_status"] = "stopped"
        out = _price_display(s, bot_running=False)
        assert out["state"] == "snapshot"
        assert out["is_live"] is False
        assert out["text"].startswith("$")
        assert "65432" in out["text"]  # the real current_price

    def test_snapshot_when_data_is_stale(self):
        from dashboard.app import _price_display
        out = _price_display(_make_stale_status(), bot_running=True)
        assert out["state"] == "snapshot"
        assert out["is_live"] is False

    def test_live_when_running_and_fresh(self):
        from dashboard.app import _price_display
        out = _price_display(_make_live_status(), bot_running=True)
        assert out["state"] == "live"
        assert out["is_live"] is True
        assert "65432" in out["text"]

    def test_helper_ignores_latest_decision_price_when_current_price_null(self):
        """The whole point: even if latest_decision.price screams 'I am a price!',
        the helper does not look at it. Pin that contract."""
        from dashboard.app import _price_display
        s = _make_incident_status()
        # Sanity check the marker is present
        assert s["latest_decision"]["price"] == _decision_price_marker()
        out = _price_display(s, bot_running=True)
        # Helper output never references the decision price
        assert _decision_price_marker() not in {out.get("raw"), out["text"]}
        assert out["state"] == "unavailable"


# ── HTTP / template tests ───────────────────────────────────────────


class TestDashboardLivePriceRender:
    """Verify the rendered HTML matches the helper output."""

    def test_index_does_not_render_decision_price_as_live(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/")
            assert r.status_code == 200
            # Live price element must NOT show the decision-price marker
            assert str(_decision_price_marker()) not in r.text
            assert "0.44751337" not in r.text
            # Live price element MUST show the honest "Veri Yok"
            assert "Veri Yok" in r.text
            assert 'id="live-price"' in r.text
            # And the element carries the unavailable state attribute
            assert 'data-state="unavailable"' in r.text
        finally:
            cleanup()

    def test_index_does_not_show_dollar_zero_for_null_price(self, tmp_path):
        """`$0.0000` was the pre-fix initial-render output. Pin that it's gone."""
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/")
            # The live-price span specifically — not other PnL/balance fields,
            # which legitimately can be $0.00. Extract the span and check.
            import re
            m = re.search(
                r'<span class="value price-value[^"]*" id="live-price"[^>]*>([^<]*)</span>',
                r.text,
            )
            assert m, "live-price span not found in rendered HTML"
            price_text = m.group(1).strip()
            assert price_text == "Veri Yok", (
                f"#live-price should show 'Veri Yok' for null current_price, "
                f"got {price_text!r}"
            )
        finally:
            cleanup()

    def test_index_shows_snapshot_banner_when_bot_stopped(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/")
            assert "snapshot-banner" in r.text
            # Banner text mentions Bot durdu (the actual stopped reason)
            assert "Bot durdu" in r.text
        finally:
            cleanup()

    def test_index_no_banner_when_live_and_running(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_live_status(), bot_running=True)
        try:
            r = client.get("/")
            # Banner must NOT appear when state is healthy
            assert "snapshot-banner" not in r.text
            # And the live price element shows the real current_price
            assert 'data-state="live"' in r.text
            assert "65432" in r.text
        finally:
            cleanup()

    def test_index_shows_dash_for_zero_cycle(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/")
            # Pre-fix output was "#0" — misleading; show "—" instead.
            assert '<span class="value" id="live-cycle">—</span>' in r.text
            assert '<span class="value" id="live-cycle">#0</span>' not in r.text
        finally:
            cleanup()


class TestApiStatusPriceContract:
    """The AJAX refresh reads /api/status. It MUST receive a _price_display
    field so JS can use the same source-of-truth as initial render."""

    def test_api_status_includes_price_display(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/api/status")
            assert r.status_code == 200
            data = r.json()
            assert "_price_display" in data, (
                "API response must include _price_display for AJAX refresh."
            )
            pd = data["_price_display"]
            assert pd["state"] == "unavailable"
            assert pd["is_live"] is False
            assert pd["text"] == "Veri Yok"
            # And the API must surface _bot_running so JS can drive the banner
            assert data["_bot_running"] is False
            assert data["_stale"] is True
        finally:
            cleanup()

    def test_api_status_live_state(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_live_status(), bot_running=True)
        try:
            r = client.get("/api/status")
            data = r.json()
            assert data["_price_display"]["state"] == "live"
            assert data["_price_display"]["is_live"] is True
            assert data["_bot_running"] is True
            assert data["_stale"] is False
        finally:
            cleanup()

    def test_api_status_snapshot_state(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_stale_status(), bot_running=True)
        try:
            r = client.get("/api/status")
            data = r.json()
            assert data["_price_display"]["state"] == "snapshot"
            assert data["_price_display"]["is_live"] is False
            assert data["_stale"] is True
        finally:
            cleanup()


class TestPositionsCardTruth:
    """When a position has no current_price (stopped bot), the card must not
    pretend the price is $0 with a -100% PnL — show '—' instead."""

    def test_positions_card_dash_when_no_current_price(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/positions")
            assert r.status_code == 200
            # Card must contain SNAPSHOT chip somewhere on the PnL bar
            assert "SNAPSHOT" in r.text
            # And the page banner mentions the bot is stopped
            assert "Bot durdu" in r.text
            # Güncel cell shows the muted dash, not $0.0000
            assert 'class="pos-info-value muted"' in r.text
            assert "—" in r.text
            # The decision-price marker must not leak into the page
            assert str(_decision_price_marker()) not in r.text
        finally:
            cleanup()


class TestSignalsAndPerformanceBanners:
    """Other pages should also honor the snapshot/stopped banner contract."""

    def test_signals_shows_snapshot_banner_when_stopped(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/signals")
            assert "snapshot-banner" in r.text
            assert "Bot durdu" in r.text
        finally:
            cleanup()

    def test_performance_shows_snapshot_banner_when_stopped(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/performance")
            assert "snapshot-banner" in r.text
            assert "Bot durdu" in r.text
        finally:
            cleanup()

    def test_tarama_shows_snapshot_banner_when_stopped(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/tarama")
            assert "snapshot-banner" in r.text
            assert "Bot durdu" in r.text
        finally:
            cleanup()


class TestAppJsContract:
    """Static-content sanity: the JS bundle must use _price_display and must
    NOT contain a `latest_decision.price` read into #live-price. This is a
    text-level guard against silent reintroduction of the bug."""

    def test_app_js_does_not_read_latest_decision_price_for_live_price(self):
        from pathlib import Path
        js = Path(__file__).parent.parent / "dashboard" / "static" / "app.js"
        src = js.read_text()
        # The forbidden pre-fix expression
        assert "s.latest_decision.price" not in src, (
            "app.js still reads latest_decision.price for #live-price — "
            "this is the canonical UI truth bug. Use s._price_display instead."
        )
        # And the canonical expression IS present
        assert "_price_display" in src, (
            "app.js must read s._price_display to keep AJAX refresh honest."
        )


class TestReverseAlertGate:
    """The audible reverse-signal alert (server-side afplay loop) MUST NOT
    fire when the bot is stopped or the snapshot is stale. The signals are
    frozen at the last cycle — they aren't 'now' — so any reverse condition
    is historical, not actionable. We pin this contract via the inline JS
    template content because the alert poll lives in index.html."""

    def test_index_js_gates_reverse_alert_on_bot_running(self, tmp_path):
        client, cleanup = _build_client(tmp_path, _make_incident_status(), bot_running=False)
        try:
            r = client.get("/")
            assert r.status_code == 200
            # The alert gate uses the canonical fields from /api/status:
            assert "s._bot_running === false" in r.text, (
                "index.html must gate _checkReverseAlert on s._bot_running === false."
            )
            assert "s._stale === true" in r.text, (
                "index.html must also gate _checkReverseAlert on s._stale === true."
            )
        finally:
            cleanup()
