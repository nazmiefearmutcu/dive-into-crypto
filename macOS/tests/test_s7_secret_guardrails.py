"""S7: dashboard secret-write must be disabled in rescue build.

Contract under test (locked by these cases):
    * ``POST /settings/env`` returns ``403``.
    * The response body never echoes the submitted form fields.
    * No ``.env`` file is created where one did not exist.
    * An existing ``.env`` is byte-for-byte unchanged after the request.
    * ``_write_env`` is never invoked from the HTTP path.
    * ``/api/env`` still returns masked values when display is requested.

The tests use a leak canary that cannot occur naturally so any partial
match in body, headers, or persisted state will fail loudly.
"""

import json
import secrets
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient


LEAK_CANARY = f"S7_LEAK_CANARY_{secrets.token_hex(8)}"


@pytest.fixture
def app_with_isolated_paths(tmp_path: Path):
    """Spin up the dashboard with every persistent path redirected to ``tmp_path``."""
    import dashboard.app as app_module

    # Seed the runtime tree the dashboard expects.
    (tmp_path / "dashboard_status.json").write_text(json.dumps({"bot_status": "stopped"}))
    (tmp_path / "bot.log").write_text("")
    (tmp_path / "state.json").write_text("{}")

    config_file = tmp_path / "default.yaml"
    config_file.write_text(yaml.dump({
        "mode": "paper",
        "market_type": "spot",
        "timeframe": "1h",
        "candle_limit": 200,
        "polling_interval_seconds": 60,
        "active_symbol_path": "runtime/active_symbol.txt",
        "risk": {"risk_per_trade": 0.02, "max_open_positions": 1},
    }))

    env_file = tmp_path / ".env"
    pristine_env_contents = (
        "BINANCE_API_KEY=ORIGINAL_KEY_OF_RECORD\n"
        "BINANCE_API_SECRET=ORIGINAL_SECRET_OF_RECORD\n"
        "USE_TESTNET=false\n"
    )
    env_file.write_text(pristine_env_contents)

    originals = {
        attr: getattr(app_module, attr)
        for attr in (
            "STATUS_FILE", "LOG_FILE", "STATE_FILE",
            "CONFIG_FILE", "ENV_FILE", "SYMBOL_FILE", "PID_FILE",
        )
    }
    app_module.STATUS_FILE = tmp_path / "dashboard_status.json"
    app_module.LOG_FILE = tmp_path / "bot.log"
    app_module.STATE_FILE = tmp_path / "state.json"
    app_module.CONFIG_FILE = config_file
    app_module.ENV_FILE = env_file
    app_module.SYMBOL_FILE = tmp_path / "active_symbol.txt"
    app_module.PID_FILE = tmp_path / "bot.pid"

    try:
        yield app_module, env_file, pristine_env_contents
    finally:
        for attr, value in originals.items():
            setattr(app_module, attr, value)


def test_post_settings_env_returns_403(app_with_isolated_paths):
    app_module, _env_file, _pristine = app_with_isolated_paths
    client = TestClient(app_module.app)
    r = client.post("/settings/env", data={
        "binance_api_key": LEAK_CANARY,
        "binance_api_secret": LEAK_CANARY,
        "use_testnet": "true",
    }, follow_redirects=False)
    assert r.status_code == 403


def test_post_settings_env_does_not_leak_canary(app_with_isolated_paths):
    """Response body, headers, and JSON payload must not contain the canary."""
    app_module, _env_file, _pristine = app_with_isolated_paths
    client = TestClient(app_module.app)
    r = client.post("/settings/env", data={
        "binance_api_key": LEAK_CANARY,
        "binance_api_secret": LEAK_CANARY,
        "binance_testnet_api_key": LEAK_CANARY,
        "binance_testnet_api_secret": LEAK_CANARY,
        "use_testnet": LEAK_CANARY,
    }, follow_redirects=False)
    # Body — string and JSON
    assert LEAK_CANARY not in r.text, "canary leaked into response body"
    # JSON envelope shape: {"error": ..., "message": ...} — message is static.
    payload = r.json()
    assert payload.get("error") == "dashboard_secret_write_disabled"
    serialised_payload = json.dumps(payload)
    assert LEAK_CANARY not in serialised_payload
    # Headers
    for header_value in r.headers.values():
        assert LEAK_CANARY not in header_value, "canary leaked into a response header"


def test_post_settings_env_does_not_mutate_env_file(app_with_isolated_paths):
    """Pristine .env must be byte-for-byte unchanged after a write attempt."""
    app_module, env_file, pristine_env_contents = app_with_isolated_paths
    client = TestClient(app_module.app)
    before_bytes = env_file.read_bytes()
    before_mtime = env_file.stat().st_mtime_ns

    r = client.post("/settings/env", data={
        "binance_api_key": LEAK_CANARY,
        "binance_api_secret": LEAK_CANARY,
        "use_testnet": "true",
    }, follow_redirects=False)
    assert r.status_code == 403

    after_bytes = env_file.read_bytes()
    after_mtime = env_file.stat().st_mtime_ns
    assert after_bytes == before_bytes, ".env contents changed after disabled POST"
    assert after_bytes.decode() == pristine_env_contents
    assert after_mtime == before_mtime, ".env mtime changed (atomic-replace happened?)"


def test_post_settings_env_does_not_create_env_file(tmp_path, monkeypatch):
    """If no .env existed pre-request, the POST must not create one."""
    import dashboard.app as app_module

    env_file = tmp_path / ".env"
    assert not env_file.exists()

    monkeypatch.setattr(app_module, "ENV_FILE", env_file)
    monkeypatch.setattr(app_module, "STATUS_FILE", tmp_path / "dashboard_status.json")
    monkeypatch.setattr(app_module, "LOG_FILE", tmp_path / "bot.log")
    monkeypatch.setattr(app_module, "STATE_FILE", tmp_path / "state.json")
    (tmp_path / "dashboard_status.json").write_text("{}")
    (tmp_path / "bot.log").write_text("")
    (tmp_path / "state.json").write_text("{}")

    client = TestClient(app_module.app)
    r = client.post("/settings/env", data={
        "binance_api_key": LEAK_CANARY,
    }, follow_redirects=False)
    assert r.status_code == 403
    assert not env_file.exists(), "POST created a .env where none existed"
    # And no leftover .tmp from a partial atomic-replace.
    assert not (tmp_path / ".tmp").exists()
    assert not (tmp_path / ".env.tmp").exists()


def test_write_env_helper_is_removed_from_dashboard_module():
    """S8: ``_write_env`` was deleted as unreachable dead code.

    Earlier rescue stages disabled the only HTTP caller; the function was
    then verifiably never invoked. S8 quarantines it by deletion so any
    future regression that tries to graft a secret writer back in fails
    at import time (NameError) instead of silently re-enabling writes.
    """
    import dashboard.app as app_module

    assert not hasattr(app_module, "_write_env"), (
        "dashboard.app._write_env must remain deleted in the rescue build. "
        "If you need to write secrets from a sidecar tool, do it outside "
        "the FastAPI process — never re-introduce this helper."
    )


def test_post_settings_env_takes_no_form_parameters():
    """The handler MUST NOT declare ``Form(...)`` deps — that would bind submitted
    secrets to Python locals and risk leaks into logs/tracebacks/handlers down
    the chain. The signature is inspected directly so a future refactor that
    re-adds parameters fails this test."""
    import inspect

    import dashboard.app as app_module

    sig = inspect.signature(app_module.save_env)
    # Only ``request`` is allowed; specifically no form-bound parameter that
    # would pull values out of the request body.
    for name, param in sig.parameters.items():
        assert name == "request", (
            f"save_env grew an unexpected parameter ``{name}`` — submitted "
            "secrets would be bound to a Python local. Strip it back to "
            "``request: Request`` only."
        )
        # Default sentinel for ``Form(...)`` params is a fastapi Form instance.
        from fastapi.params import Form as _Form
        assert not isinstance(param.default, _Form), (
            f"save_env parameter ``{name}`` uses Form(...) — body is being parsed."
        )


def test_api_env_still_masks_existing_secrets(app_with_isolated_paths):
    """GET /api/env keeps the read path: existing keys are returned masked."""
    app_module, _env_file, _pristine = app_with_isolated_paths
    client = TestClient(app_module.app)
    r = client.get("/api/env")
    assert r.status_code == 200
    payload = r.json()
    # Original values must NOT appear in the clear.
    assert payload["BINANCE_API_KEY"] != "ORIGINAL_KEY_OF_RECORD"
    assert payload["BINANCE_API_SECRET"] != "ORIGINAL_SECRET_OF_RECORD"
    assert "*" in payload["BINANCE_API_KEY"]
    assert "*" in payload["BINANCE_API_SECRET"]
    # Non-secret flag passes through unmasked.
    assert payload["USE_TESTNET"] == "false"


def test_settings_html_reflects_disabled_state(app_with_isolated_paths):
    """The settings page must surface the disabled state to the operator."""
    app_module, _env_file, _pristine = app_with_isolated_paths
    client = TestClient(app_module.app)
    r = client.get("/settings")
    assert r.status_code == 200
    text = r.text
    # Read-only marker we added in S7.
    assert 'data-testid="api-keys-readonly"' in text
    # The legacy editable form action must NOT be rendered.
    assert 'action="/settings/env"' not in text
    # Inputs are disabled in the rescue UI.
    assert "disabled readonly" in text
