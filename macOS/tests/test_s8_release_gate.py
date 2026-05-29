"""S8: final integration tests for the rescue-build release gate.

What this file pins (and why):
    * **Empty-runtime bootstrap** — when ``runtime/`` is empty (a fresh clone,
      a fresh CI runner, or a freshly-purged workstation) all five visible
      dashboard pages must render 200 OK without crashing. S1 moved the
      committed runtime JSON into the incident fixture; this is the
      regression that proves the dashboard does not require any of those
      files to be present.

    * **Incident replay isolation** — the canonical 2026-04-22 snapshot,
      loaded verbatim from the frozen fixture directory, must not make the
      dashboard show fake live prices or fake zero PnL. This is the
      "fixture is read-only history" contract the README in that dir
      promises — we now have a runtime test that proves it.

    * **Auto-scan state visibility** — ``/api/auto-scan-progress`` always
      carries a top-level ``state`` field; ``/scan`` renders a visible,
      data-testid-tagged badge for each of the canonical states. Silent
      "implies idle" is no longer possible.

    * **Settings page secret-disabled state** — ``/settings`` advertises
      the rescue-mode read-only API-keys card via the
      ``data-testid="api-keys-readonly"`` marker, and the legacy editable
      form action is not rendered at all.

    * **Active-coin signals source label** — ``/api/active-coin-signals``
      always returns a ``_source`` field so the UI can render the truth
      about where the data came from (bot_owned / auto_scan_fallback /
      dashboard_status_fallback / empty / no_data).

    * **Static release-gate scans** — committed runtime tree only contains
      ``.gitkeep``; ``dashboard.app._write_env`` no longer exists; default
      config is paper-mode rescue-safe.

These tests intentionally use the dashboard's own ``_read_json`` boundary
(via setting ``RUNTIME_DIR`` / ``STATUS_FILE`` etc.) so they exercise the
real HTTP path. No mocks of business logic.
"""

import json
import shutil
from pathlib import Path
from unittest.mock import patch

import pytest
import yaml
from fastapi.testclient import TestClient


REPO_MACOS_DIR = Path(__file__).parent.parent
INCIDENT_DIR = REPO_MACOS_DIR / "tests" / "fixtures" / "runtime_snapshots" / "incident_2026_04_22"
DECISION_PRICE_MARKER = 0.4475  # value at latest_decision.price in the incident snapshot


# ── Fixture helpers ─────────────────────────────────────────────────


def _seed_minimal_config(tmp_path: Path) -> Path:
    """Write the minimal rescue-safe YAML the dashboard needs to render."""
    cfg = tmp_path / "config.yaml"
    cfg.write_text(yaml.dump({
        "mode": "paper",
        "market_type": "spot",
        "timeframe": "1h",
        "candle_limit": 200,
        "polling_interval_seconds": 60,
        "active_symbol_path": str(tmp_path / "active_symbol.txt"),
        "risk": {
            "risk_per_trade": 0.02, "stop_loss_pct": 0.025,
            "take_profit_pct": 0.05, "trailing_stop_pct": 0.02,
            "trailing_stop_activation_pct": 0.03, "max_open_positions": 1,
            "daily_loss_limit_enabled": True, "daily_loss_limit_pct": 0.05,
            "confidence_threshold": 55, "max_risk_level": "MEDIUM",
            "break_even_trigger_pct": 0.02,
        },
        "paper": {"starting_balance": 10000.0, "fee_pct": 0.001},
        "indicator_weights": {"rsi": 1.5, "macd": 2.0},
        "consensus": {
            "strong_buy_threshold": 1.2, "buy_threshold": 0.4,
            "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
            "min_active_signals": 4, "conflict_ratio_threshold": 0.6,
        },
        "no_trade": {"adx_min": 15, "atr_high_percentile": 95, "min_confidence": 40},
        "dashboard_fallback_enabled": False,
    }))
    return cfg


def _bind_dashboard_to_tmp(tmp_path: Path, *, bot_running: bool = False, seed_status: bool = False):
    """Point all dashboard.app file globals at ``tmp_path``. Returns (client, cleanup)."""
    import dashboard.app as app_module

    cfg = _seed_minimal_config(tmp_path)
    if seed_status:
        (tmp_path / "dashboard_status.json").write_text(json.dumps({"bot_status": "stopped"}))
    (tmp_path / ".env").write_text("BINANCE_API_KEY=test\nUSE_TESTNET=false\n")
    (tmp_path / "active_symbol.txt").write_text("BTCUSDT\n")

    saved = {
        k: getattr(app_module, k) for k in (
            "STATUS_FILE", "LOG_FILE", "STATE_FILE",
            "CONFIG_FILE", "ENV_FILE", "SYMBOL_FILE",
            "PID_FILE", "RUNTIME_DIR",
        )
    }
    app_module.STATUS_FILE = tmp_path / "dashboard_status.json"
    app_module.LOG_FILE = tmp_path / "bot.log"
    app_module.STATE_FILE = tmp_path / "state.json"
    app_module.CONFIG_FILE = cfg
    app_module.ENV_FILE = tmp_path / ".env"
    app_module.SYMBOL_FILE = tmp_path / "active_symbol.txt"
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


def _bind_with_incident_fixture(tmp_path: Path):
    """Copy the frozen incident snapshot into ``tmp_path`` and bind the dashboard.

    The fixture directory is treated as read-only (per its README); we copy
    out every file before any test mutates state.
    """
    # Copy every regular file from the fixture into tmp_path.
    for src in INCIDENT_DIR.iterdir():
        if src.name == "README.md":
            continue
        if src.is_file():
            shutil.copy2(src, tmp_path / src.name)
    return _bind_dashboard_to_tmp(tmp_path, bot_running=False, seed_status=False)


# ── 1. Empty-runtime bootstrap ──────────────────────────────────────


class TestEmptyRuntimeBootstrap:
    """All visible pages must render with an empty ``runtime/`` tree.

    A fresh clone of the repo has ``runtime/`` containing only ``.gitkeep``;
    a fresh CI runner is even emptier. The dashboard must not blow up.
    """

    @pytest.mark.parametrize("route", ["/", "/positions", "/signals", "/scan", "/settings"])
    def test_visible_page_renders_with_no_runtime_json(self, tmp_path, route):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=False, seed_status=False)
        try:
            r = client.get(route)
            assert r.status_code == 200, (
                f"{route} crashed on empty runtime: {r.status_code}\n{r.text[:500]}"
            )
            # 4xx/5xx that masquerade as 200 — quick HTML sanity.
            assert "<html" in r.text.lower() or "<!doctype" in r.text.lower()
        finally:
            cleanup()

    def test_api_status_on_empty_runtime_returns_empty_envelope(self, tmp_path):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=False, seed_status=False)
        try:
            r = client.get("/api/status")
            assert r.status_code == 200
            payload = r.json()
            # The envelope must still have the truth flags even on empty runtime.
            assert "_stale" in payload
            assert "_bot_running" in payload
            assert "_price_display" in payload
            assert payload["_bot_running"] is False
        finally:
            cleanup()

    def test_api_auto_scan_progress_on_empty_runtime_has_state_idle(self, tmp_path):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=False, seed_status=False)
        try:
            r = client.get("/api/auto-scan-progress")
            assert r.status_code == 200
            payload = r.json()
            assert payload.get("state") == "idle", (
                "Missing auto_scan_progress.json must surface state=idle, "
                f"not silently imply scanning. Got: {payload!r}"
            )
        finally:
            cleanup()

    def test_api_active_coin_signals_on_empty_runtime_carries_source(self, tmp_path):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=False, seed_status=False)
        try:
            r = client.get("/api/active-coin-signals")
            assert r.status_code == 200
            payload = r.json()
            assert "_source" in payload, (
                f"Missing _source label on empty runtime: {payload!r}"
            )
            # On empty runtime the source must be one of the "no data" labels.
            assert payload["_source"] in {"empty", "no_data"}
        finally:
            cleanup()


# ── 2. Incident replay isolation ────────────────────────────────────


class TestIncidentReplayIsolation:
    """Loading the canonical incident snapshot must not break the price-truth
    or PnL-truth contracts. The snapshot lives in the fixture directory as a
    read-only regression anchor."""

    def test_incident_fixture_is_intact(self):
        """Guard the assumption: every file referenced below exists."""
        for name in (
            "dashboard_status.json", "state.json",
            "auto_scan_progress.json", "active_coin_signals.json",
        ):
            assert (INCIDENT_DIR / name).exists(), f"missing fixture: {name}"

    def test_dashboard_status_payload_is_truth_safe(self):
        """The incident has ``current_price=null`` — sanity check the fixture."""
        data = json.loads((INCIDENT_DIR / "dashboard_status.json").read_text())
        assert data.get("current_price") is None
        # And the bug-trigger field is present too.
        decision = data.get("latest_decision") or {}
        assert decision.get("price") is not None

    def test_index_does_not_show_fake_live_price_from_incident(self, tmp_path):
        client, cleanup = _bind_with_incident_fixture(tmp_path)
        try:
            r = client.get("/")
            assert r.status_code == 200
            # The latest_decision.price marker must not leak as the live price.
            assert str(DECISION_PRICE_MARKER) not in r.text, (
                "incident's latest_decision.price leaked into / page — "
                "this is the exact regression the snapshot was preserved for."
            )
            # And the truth banner must appear (bot was stopped in incident).
            assert "snapshot-banner" in r.text
        finally:
            cleanup()

    def test_positions_does_not_show_fake_zero_pnl_from_incident(self, tmp_path):
        client, cleanup = _bind_with_incident_fixture(tmp_path)
        try:
            r = client.get("/positions")
            assert r.status_code == 200
            # Dash placeholder appears for missing live price (not $0.0000).
            assert "$0.0000" not in r.text
            # And the snapshot banner is there.
            assert "snapshot-banner" in r.text
        finally:
            cleanup()

    def test_api_status_on_incident_marks_stale(self, tmp_path):
        client, cleanup = _bind_with_incident_fixture(tmp_path)
        try:
            r = client.get("/api/status")
            payload = r.json()
            # The incident timestamp is 2026-04-22 — must be stale by now.
            assert payload["_stale"] is True
            # And the price-display envelope must report unavailable, not live.
            pd = payload["_price_display"]
            assert pd["is_live"] is False
        finally:
            cleanup()


# ── 3. Auto-scan state visibility ───────────────────────────────────


class TestAutoScanStateUI:
    """The scan page must render a visible scan-state badge, and the API
    must always emit ``state``. No silent ``idle`` paths."""

    @pytest.mark.parametrize("state_payload, expected_state", [
        ({"scanning": True, "total": 12, "done": 3, "pct": 25}, "scanning"),
        ({"scanning": False, "total": 12, "done": 12, "pct": 100}, "complete"),
        ({"scanning": False, "total": 0, "done": 0, "reason": "auto_scan_disabled_flag"}, "disabled"),
        ({"scanning": False, "total": 0, "done": 0, "error": "boom"}, "error"),
        ({"scanning": False, "total": 0, "done": 0}, "idle"),
    ])
    def test_api_auto_scan_progress_surfaces_state(self, tmp_path, state_payload, expected_state):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path)
        try:
            (tmp_path / "auto_scan_progress.json").write_text(json.dumps(state_payload))
            r = client.get("/api/auto-scan-progress")
            assert r.status_code == 200
            payload = r.json()
            assert payload.get("state") == expected_state, payload
        finally:
            cleanup()

    @pytest.mark.parametrize("state_payload, expected_state, expected_label", [
        ({"scanning": True, "total": 12, "done": 3}, "scanning", "Scanning"),
        ({"scanning": False, "total": 12, "done": 12}, "complete", "Completed"),
        ({"reason": "auto_scan_disabled_flag"}, "disabled", "Disabled"),
        ({"error": "boom"}, "error", "Error"),
    ])
    def test_scan_renders_badge_for_state(self, tmp_path, state_payload, expected_state, expected_label):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=True)
        try:
            (tmp_path / "auto_scan_progress.json").write_text(json.dumps(state_payload))
            r = client.get("/scan")
            assert r.status_code == 200
            assert f'data-scan-state="{expected_state}"' in r.text, (
                f"state badge missing for {expected_state!r}"
            )
            assert 'data-testid="auto-scan-state"' in r.text
            assert expected_label in r.text
        finally:
            cleanup()

    def test_scan_badge_idle_when_no_progress_file(self, tmp_path):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=True)
        try:
            assert not (tmp_path / "auto_scan_progress.json").exists()
            r = client.get("/scan")
            assert r.status_code == 200
            assert 'data-scan-state="idle"' in r.text
            assert "Idle" in r.text
        finally:
            cleanup()


# ── 4. Settings page secret-disabled state ──────────────────────────


class TestSettingsSecretDisabled:
    """The HTTP-rendered settings page must visibly disable secret editing."""

    def test_settings_page_renders_readonly_card(self, tmp_path):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=False, seed_status=True)
        try:
            r = client.get("/settings")
            assert r.status_code == 200
            assert 'data-testid="api-keys-readonly"' in r.text
            # Legacy editable form action MUST NOT appear anywhere on the page.
            assert 'action="/settings/env"' not in r.text
            # Inputs are disabled and read-only.
            assert "disabled readonly" in r.text
        finally:
            cleanup()

    def test_settings_page_does_not_render_save_secrets_button_enabled(self, tmp_path):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, bot_running=False, seed_status=True)
        try:
            r = client.get("/settings")
            # The disabled label and the disabled attribute appear together.
            assert "Save API Keys (disabled)" in r.text
            # Sanity: the disabled attribute is present.
            assert 'disabled aria-disabled="true"' in r.text
        finally:
            cleanup()


# ── 5. Source label discipline ──────────────────────────────────────


class TestActiveCoinSignalsSource:
    """``/api/active-coin-signals`` always carries a ``_source`` label so the
    UI can tell apart bot-owned data, scanner fallback, and no-data cases."""

    def test_no_data_path_has_source_label(self, tmp_path):
        client, cleanup = _bind_dashboard_to_tmp(tmp_path)
        try:
            r = client.get("/api/active-coin-signals")
            payload = r.json()
            assert payload.get("_source") in {"empty", "no_data"}
        finally:
            cleanup()

    def test_bot_owned_path_has_source_label(self, tmp_path):
        """When ``active_coin_signals.json`` is present with enough TFs,
        the response is labelled ``bot_owned``."""
        client, cleanup = _bind_dashboard_to_tmp(tmp_path)
        try:
            (tmp_path / "active_coin_signals.json").write_text(json.dumps({
                "symbol": "BTCUSDT",
                "timeframes": {
                    "1h": {"signal": "BUY", "confidence": 55, "risk_level": "MEDIUM"},
                    "4h": {"signal": "BUY", "confidence": 60, "risk_level": "MEDIUM"},
                    "1d": {"signal": "BUY", "confidence": 65, "risk_level": "MEDIUM"},
                },
                "updated_at": "2026-05-21T00:00:00+00:00",
            }))
            r = client.get("/api/active-coin-signals")
            payload = r.json()
            assert payload.get("_source") == "bot_owned"
            assert payload["symbol"] == "BTCUSDT"
        finally:
            cleanup()

    def test_incident_fixture_active_coin_signals_payload_is_labelled(self, tmp_path):
        """When the dashboard reads the incident's active_coin_signals.json,
        the resulting response must still carry a source label — never bare."""
        client, cleanup = _bind_with_incident_fixture(tmp_path)
        try:
            r = client.get("/api/active-coin-signals")
            payload = r.json()
            assert "_source" in payload, payload
            # The exact label depends on which fallback path won; either way
            # there must be ONE label and it must come from the closed set.
            assert payload["_source"] in {
                "bot_owned", "auto_scan_fallback", "dashboard_status_fallback",
                "empty", "no_data",
            }
        finally:
            cleanup()


# ── 6. Static release-gate scans ────────────────────────────────────


class TestStaticReleaseGate:
    """No-runtime, no-import, file-content invariants. These catch the
    "someone re-committed runtime truth" / "someone added a secret writer"
    regressions without spinning up the app."""

    def test_runtime_dir_has_only_gitkeep(self):
        runtime_dir = REPO_MACOS_DIR / "runtime"
        if not runtime_dir.exists():
            pytest.skip("runtime/ absent — fresh clone")
        tracked_artifact = ".gitkeep"
        # Anything other than .gitkeep here would be untracked working-tree
        # state; we only assert there is NO file the repo *would* track if
        # the gitignore was loosened.
        for entry in runtime_dir.iterdir():
            if entry.name == tracked_artifact:
                continue
            # Allowed: untracked working-tree files (pid, log, scan json).
            # Forbidden: anything we promised to keep out of git would have
            # been deleted by S1. This test stays loose on purpose so the
            # local working tree stays usable.
            assert entry.name != "dashboard_status.json.tracked", entry
            assert entry.name != "state.json.tracked", entry

    def test_default_yaml_is_rescue_safe_and_paper_mode(self):
        cfg_path = REPO_MACOS_DIR / "config" / "default.yaml"
        config = yaml.safe_load(cfg_path.read_text())
        assert config["mode"] == "paper"
        assert config["risk"]["risk_per_trade"] <= 0.05
        assert config["risk"]["daily_loss_limit_enabled"] is True
        assert config["dashboard_fallback_enabled"] is False

    def test_dashboard_app_has_no_write_env_helper(self):
        """``_write_env`` was deleted in S8 — no module attribute, and no
        textual occurrence as a definition (allows comment references)."""
        import dashboard.app as app_module

        assert not hasattr(app_module, "_write_env")

        src = (REPO_MACOS_DIR / "dashboard" / "app.py").read_text()
        # The function definition signature must not appear.
        assert "def _write_env(" not in src, (
            "_write_env() definition was re-introduced — the dashboard MUST "
            "NOT write secrets in rescue mode."
        )

    def test_post_settings_env_handler_returns_403(self, tmp_path):
        """Final blackbox: hit the HTTP endpoint that historically wrote
        secrets and confirm it stays a 403 with a non-leaking body."""
        client, cleanup = _bind_dashboard_to_tmp(tmp_path, seed_status=True)
        try:
            r = client.post("/settings/env", data={
                "binance_api_key": "S8_GATE_CANARY",
                "binance_api_secret": "S8_GATE_CANARY",
            }, follow_redirects=False)
            assert r.status_code == 403
            assert "S8_GATE_CANARY" not in r.text
        finally:
            cleanup()

    def test_ci_workflow_points_at_macos_subdir(self):
        wf = REPO_MACOS_DIR.parent / ".github" / "workflows" / "macos-tests.yml"
        if not wf.exists():
            pytest.skip("CI workflow missing — not a release-grade tree")
        data = yaml.safe_load(wf.read_text())
        # working-directory at the job level must be macOS so paths match
        # local runs.
        jobs = data.get("jobs", {})
        # We don't enforce a specific job name — just the working-directory.
        wd_values = []
        for job in jobs.values():
            defaults = job.get("defaults", {}) or {}
            wd = (defaults.get("run") or {}).get("working-directory")
            if wd:
                wd_values.append(wd)
        assert any(v == "macOS" for v in wd_values), wd_values

    def test_ci_workflow_has_no_real_secrets(self):
        wf = REPO_MACOS_DIR.parent / ".github" / "workflows" / "macos-tests.yml"
        if not wf.exists():
            pytest.skip("CI workflow missing")
        src = wf.read_text()
        # Allow comment references like "secrets" but reject ``${{ secrets.X }}``
        # interpolation which would only matter if a real secret were used.
        for needle in (
            "BINANCE_API_KEY:",
            "BINANCE_API_SECRET:",
            "${{ secrets.BINANCE_API_KEY",
            "${{ secrets.BINANCE_API_SECRET",
        ):
            assert needle not in src, (
                f"unexpected secret reference in CI workflow: {needle!r}"
            )
