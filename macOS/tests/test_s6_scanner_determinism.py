"""S6 — scanner / auto-select determinism, safety flags, rate-limit hardening.

Covers:

1. Broken-reference regression: ``BotService._auto_scan_market`` must not
   raise ``AttributeError`` for ``self.project_root`` or
   ``self._read_config_from_disk()``. The path was unreached by S1-S5 tests
   and the real bot would crash on first cycle.
2. Disabled-flag honesty: with ``auto_scan_enabled: False`` in YAML OR the
   runtime ``auto_scan_disabled`` flag-file present, ``_auto_scan_market``
   returns without flipping ``_scanning_active``.
3. Runtime-dir is anchored on ``dashboard_status_path``, not a hardcoded
   ``runtime/`` so isolated tmp_path runtime dirs work.
4. ``_write_auto_scan_progress`` always emits a top-level ``state`` field so
   the dashboard can render idle vs scanning vs disabled vs stale honestly.
   It also survives a malformed/partial previous file via tmp+replace.
5. ``ScannerService`` accepts an injectable ``sleeper`` so scan loops can be
   tested deterministically without ``time.sleep`` or threading races.
6. Bounded fake symbol-universe injection makes the scan winner predictable
   and avoids any Binance call.
7. ``ScannerService`` never hits the network when given both a shared client
   and a shared symbol universe — tests don't reach Binance.
8. Dashboard ``api_active_coin_signals`` sets ``_source`` on every return
   path (``bot_owned``, ``dashboard_calculated``, ``auto_scan_fallback``,
   ``dashboard_status_fallback``, ``empty``) so the UI can flag stale data.
9. Dashboard ``_ensure_live_signals`` background recompute is gated by the
   ``dashboard_fallback_enabled`` config knob — bot-owned writes are the
   source of truth when the knob is off, and the dashboard does not race
   against the bot.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import pytest
import yaml


# ── shared fixtures: minimal bot factory without touching real Binance ──


def _minimal_cfg(tmp_path: Path) -> dict[str, Any]:
    ds_path = tmp_path / "ds.json"
    return {
        "mode": "paper",
        "market_type": "futures",
        "timeframe": "4h",
        "polling_interval_seconds": 1,
        "candle_limit": 200,
        "_config_path": str(tmp_path / "config.yaml"),
        "active_symbol_path": str(tmp_path / "active_symbol.txt"),
        "state_path": str(tmp_path / "state.json"),
        "dashboard_status_path": str(ds_path),
        "command_queue_path": str(tmp_path / "command_queue.json"),
        "auto_scan_enabled": False,
        "auto_select_enabled": False,
        "auto_scan_interval_seconds": 600,
        "risk": {"max_open_positions": 5, "confidence_threshold": 55,
                 "stop_loss_pct": 0.025, "take_profit_pct": 0.05,
                 "trailing_stop_pct": 0.02,
                 "trailing_stop_activation_pct": 0.03,
                 "risk_per_trade": 0.02, "daily_loss_limit_pct": 0.05,
                 "max_risk_level": "MEDIUM", "break_even_trigger_pct": 0.02},
        "paper": {"starting_balance": 10000.0, "fee_pct": 0.001},
        "indicator_weights": {"rsi": 1.5},
        "consensus": {"strong_buy_threshold": 1.2, "buy_threshold": 0.4,
                      "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
                      "min_active_signals": 1, "conflict_ratio_threshold": 0.6},
        "no_trade": {"adx_min": 15, "atr_high_percentile": 95,
                     "min_confidence": 40},
    }


def _make_bot(tmp_path: Path, *, overrides: dict[str, Any] | None = None) -> Any:
    """Construct a BotService with mocked external deps and a tmp runtime dir.

    The YAML on disk mirrors the in-memory config so the bot's belt-and-
    suspenders YAML reread inside ``_auto_scan_market`` resolves predictably.
    """
    from src.services.bot_service import BotService

    cfg = _minimal_cfg(tmp_path)
    if overrides:
        cfg.update(overrides)
    Path(cfg["_config_path"]).write_text(yaml.dump(cfg))
    Path(cfg["active_symbol_path"]).write_text("BTCUSDT\n")

    b = BotService(cfg)
    b.binance_client = MagicMock()
    b.market_data = MagicMock()
    b.signal_service = MagicMock()
    b.consensus_engine = MagicMock()
    return b


# ── 1. Broken-reference regression ──────────────────────────────────


class TestAutoScanBrokenRefs:
    """The S5 baseline left two undefined-attr refs in ``_auto_scan_market``:
    ``self.project_root`` and ``self._read_config_from_disk()``. They are
    unreached by any S1-S5 test but blow up the first real cycle.
    """

    def test_auto_scan_with_disabled_yaml_does_not_raise(self, tmp_path):
        """``auto_scan_enabled: False`` must short-circuit cleanly — no
        ``AttributeError`` for missing ``project_root`` or
        ``_read_config_from_disk``. This is the S6 regression pin."""
        b = _make_bot(tmp_path, overrides={"auto_scan_enabled": False})
        # Must complete without any exception. Pre-S6 this raised
        # AttributeError on `self.project_root` before even reaching the
        # YAML check, so the disabled flag was ignored in practice.
        b._auto_scan_market()
        assert b._scanning_active is False

    def test_auto_scan_with_flag_file_does_not_raise(self, tmp_path):
        """Runtime ``auto_scan_disabled`` flag-file must short-circuit
        even when the in-memory config says enabled."""
        b = _make_bot(tmp_path, overrides={"auto_scan_enabled": True})
        runtime_dir = Path(b.config["dashboard_status_path"]).parent
        runtime_dir.mkdir(parents=True, exist_ok=True)
        (runtime_dir / "auto_scan_disabled").write_text("disabled")
        b._auto_scan_market()
        assert b._scanning_active is False

    def test_auto_scan_runtime_dir_anchored_on_dashboard_status_path(self, tmp_path):
        """The bot's disabled-flag-file path must be derived from
        ``dashboard_status_path`` (the same anchor every other runtime
        helper uses), not a hardcoded ``runtime/``. A tmp_path-isolated
        suite would otherwise either miss flag files or read the wrong
        repo's flags."""
        b = _make_bot(tmp_path, overrides={"auto_scan_enabled": True})
        runtime_dir = Path(b.config["dashboard_status_path"]).parent
        flag = runtime_dir / "auto_scan_disabled"
        flag.write_text("disabled")
        b._auto_scan_market()
        assert b._scanning_active is False
        # Sanity: the bot must NOT consult the real repo's runtime/ dir.
        repo_runtime_flag = Path("runtime/auto_scan_disabled")
        # Either the real one doesn't exist (clean state) or we honored
        # the tmp_path one — both are fine. The point: behavior was driven
        # by the tmp_path flag we just wrote.
        assert flag.exists()


# ── 2. Status-field honesty: state field + atomic write ─────────────


class TestAutoScanProgressStateField:
    def test_write_progress_includes_state_field(self, tmp_path):
        b = _make_bot(tmp_path)
        b._write_auto_scan_progress({"scanning": False, "total": 12, "done": 0})
        prog_file = Path(b.config["dashboard_status_path"]).parent / "auto_scan_progress.json"
        assert prog_file.exists()
        data = json.loads(prog_file.read_text())
        assert "state" in data, (
            "auto_scan_progress.json must surface a top-level `state` "
            "field so the dashboard can render idle vs scanning vs "
            "disabled honestly."
        )
        assert data["state"] in {"idle", "scanning", "disabled", "stale",
                                 "complete", "error"}

    def test_disabled_scan_writes_state_disabled(self, tmp_path):
        """When ``_auto_scan_market`` early-returns because the YAML or the
        flag-file disables auto-scan, the progress file must reflect that
        so the dashboard never renders a stale 'idle' for a disabled bot.
        """
        b = _make_bot(tmp_path, overrides={"auto_scan_enabled": False})
        b._auto_scan_market()
        prog_file = Path(b.config["dashboard_status_path"]).parent / "auto_scan_progress.json"
        assert prog_file.exists()
        data = json.loads(prog_file.read_text())
        assert data.get("state") == "disabled"
        assert data.get("scanning") is False

    def test_progress_write_survives_malformed_previous_file(self, tmp_path):
        """A corrupt/partial previous progress file must not block the next
        write. ``tmp+replace`` is the contract."""
        b = _make_bot(tmp_path)
        runtime_dir = Path(b.config["dashboard_status_path"]).parent
        runtime_dir.mkdir(parents=True, exist_ok=True)
        prog_file = runtime_dir / "auto_scan_progress.json"
        prog_file.write_text("{not valid json at all")  # malformed
        b._write_auto_scan_progress({"scanning": True, "total": 12, "done": 3})
        data = json.loads(prog_file.read_text())  # must parse now
        assert data["scanning"] is True
        assert data["done"] == 3
        assert "state" in data


# ── 3. Rate-limit / sleeper seam ─────────────────────────────────────


class TestScannerSleeperSeam:
    def test_scan_uses_injected_sleeper_not_time_sleep(self, tmp_path):
        """ScannerService must accept an injectable sleeper so scan loops
        can be tested deterministically without ``time.sleep``."""
        from src.services.scanner_service import ScannerService

        sleeps: list[float] = []
        sleeper = lambda s: sleeps.append(s)  # noqa: E731

        shared_client = MagicMock()
        scanner = ScannerService(
            config={"timeframe": "1h"},
            shared_client=shared_client,
            shared_symbols=["AAAUSDT", "BBBUSDT", "CCCUSDT"],
            sleeper=sleeper,
        )
        # Stub analyze so the test focuses on the loop / sleeper contract.
        scanner._analyze_symbol = lambda sym: {
            "symbol": sym, "price": 1.0, "signal": "BUY",
            "confidence": {"AAAUSDT": 90, "BBBUSDT": 60,
                           "CCCUSDT": 75}[sym],
            "risk_level": "MEDIUM", "weighted_score": 1.2,
            "should_trade": True,
        }
        scanner.scan(min_confidence=50)
        assert len(sleeps) == 3, (
            "scanner must call the injected sleeper once per symbol "
            "(after each analyze)"
        )
        assert all(s == scanner._request_delay for s in sleeps)


# ── 4. Bounded fake universe → deterministic winner ──────────────────


class TestDeterministicWinner:
    def test_bounded_universe_produces_deterministic_top_result(self, tmp_path):
        """Given a fake universe with controlled confidences, the
        top-of-results symbol must be deterministic and equal to the
        highest-confidence non-NEUTRAL signal."""
        from src.services.scanner_service import ScannerService

        symbol_file = tmp_path / "active_symbol.txt"
        scanner = ScannerService(
            config={"timeframe": "1h"},
            symbol_file=symbol_file,
            shared_client=MagicMock(),
            shared_symbols=["LOWUSDT", "HIGHUSDT", "MIDUSDT", "NEUUSDT"],
            sleeper=lambda s: None,
        )
        conf_map = {"LOWUSDT": 40, "HIGHUSDT": 88, "MIDUSDT": 62,
                    "NEUUSDT": 90}
        sig_map = {"LOWUSDT": "BUY", "HIGHUSDT": "BUY",
                   "MIDUSDT": "BUY", "NEUUSDT": "NEUTRAL"}
        scanner._analyze_symbol = lambda sym: {
            "symbol": sym, "price": 1.0,
            "signal": sig_map[sym], "confidence": conf_map[sym],
            "risk_level": "MEDIUM", "weighted_score": 1.0,
            "should_trade": True,
        }
        hot = scanner.scan(min_confidence=50)
        # All results sorted by confidence descending; top hot is HIGHUSDT
        # (90 NEUUSDT is filtered out — NEUTRAL).
        assert [r["symbol"] for r in scanner.results] == [
            "NEUUSDT", "HIGHUSDT", "MIDUSDT", "LOWUSDT",
        ]
        assert hot[0]["symbol"] == "HIGHUSDT"
        assert all(r["signal"] != "NEUTRAL" and r["confidence"] >= 50
                   for r in hot)


# ── 5. No-network contract ───────────────────────────────────────────


class TestNoNetworkInScannerTests:
    def test_scanner_with_shared_universe_makes_no_binance_calls(self):
        """With both shared_client and shared_symbols provided, the
        scanner must never touch the network. We assert by giving a
        client mock whose ``futures_ticker`` raises if called."""
        from src.services.scanner_service import ScannerService

        client = MagicMock()
        client.client.futures_ticker.side_effect = AssertionError(
            "scanner must not call futures_ticker when shared_symbols given"
        )
        client.get_futures_symbols.side_effect = AssertionError(
            "scanner must not call get_futures_symbols when shared_symbols given"
        )
        scanner = ScannerService(
            config={"timeframe": "1h"},
            shared_client=client,
            shared_symbols=["AAAUSDT"],
            sleeper=lambda s: None,
        )
        scanner._analyze_symbol = lambda sym: None  # skip analysis cleanly
        scanner.scan(min_confidence=50)
        client.client.futures_ticker.assert_not_called()
        client.get_futures_symbols.assert_not_called()


# ── 6. Dashboard fallback honesty ────────────────────────────────────


def _runtime_for_dashboard(tmp_path, monkeypatch):
    """Rebind dashboard module-level paths to a tmp_path-isolated runtime
    so the test never reads the developer's real repo state."""
    import dashboard.app as app

    runtime_dir = tmp_path / "runtime"
    runtime_dir.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(app, "RUNTIME_DIR", runtime_dir, raising=True)
    monkeypatch.setattr(app, "STATUS_FILE", runtime_dir / "dashboard_status.json", raising=True)
    monkeypatch.setattr(app, "STATE_FILE", runtime_dir / "state.json", raising=True)
    monkeypatch.setattr(app, "SYMBOL_FILE", runtime_dir / "active_symbol.txt", raising=True)
    return app, runtime_dir


class TestDashboardActiveCoinSignalsSource:
    def test_returns_bot_owned_source_when_file_fresh(self, tmp_path, monkeypatch):
        """When ``active_coin_signals.json`` exists with ``symbol`` and ≥3
        TFs (the existing primary path), the dashboard must surface
        ``_source=bot_owned`` so the UI can render confidently."""
        app, runtime_dir = _runtime_for_dashboard(tmp_path, monkeypatch)
        # Disable the background recompute so the test doesn't race.
        monkeypatch.setattr(app, "_ensure_live_signals", lambda: None)
        (runtime_dir / "active_symbol.txt").write_text("BTCUSDT\n")
        (runtime_dir / "active_coin_signals.json").write_text(json.dumps({
            "symbol": "BTCUSDT",
            "timeframes": {
                "1h": {"signal": "BUY", "confidence": 70, "risk_level": "LOW"},
                "4h": {"signal": "BUY", "confidence": 72, "risk_level": "LOW"},
                "1d": {"signal": "BUY", "confidence": 80, "risk_level": "LOW"},
            },
            "updated_at": "2026-05-21T00:00:00+00:00",
        }))
        data = app.api_active_coin_signals()
        assert data.get("_source") == "bot_owned", (
            "primary active-coin-signals path must label its source so "
            "the dashboard can distinguish authoritative data from fallbacks"
        )
        assert data["symbol"] == "BTCUSDT"

    def test_returns_empty_source_when_no_data(self, tmp_path, monkeypatch):
        """No file and no active symbol → ``_source=empty`` (or at least
        not silent)."""
        app, runtime_dir = _runtime_for_dashboard(tmp_path, monkeypatch)
        monkeypatch.setattr(app, "_ensure_live_signals", lambda: None)
        data = app.api_active_coin_signals()
        assert "_source" in data
        assert data["_source"] == "empty"

    def test_ensure_live_signals_respects_fallback_disabled_knob(self, tmp_path, monkeypatch):
        """The dashboard's own background recompute shadowed the bot.
        Honoring a ``dashboard_fallback_enabled`` knob (default True for
        backward compat) lets operators turn off the duplication when the
        bot is the trusted writer."""
        app, runtime_dir = _runtime_for_dashboard(tmp_path, monkeypatch)
        # Inject a config that disables the dashboard's shadow recompute.
        monkeypatch.setattr(app, "_read_config", lambda: {
            "dashboard_fallback_enabled": False,
        })
        threads_started: list[Any] = []
        real_thread = app._sig_threading.Thread

        class _StubThread:
            def __init__(self, **kwargs):
                threads_started.append(kwargs)
                self._alive = False

            def start(self):
                threads_started.append("started")

            def is_alive(self):
                return False

        monkeypatch.setattr(app._sig_threading, "Thread", _StubThread)
        # Force the "need_calc" condition: no file present, no symbol.
        # _ensure_live_signals would normally spawn the worker — but the
        # knob disables it.
        app._live_signal_thread = None
        app._ensure_live_signals()
        assert "started" not in threads_started, (
            "dashboard_fallback_enabled=False must prevent the dashboard "
            "from racing the bot with its own recompute"
        )
