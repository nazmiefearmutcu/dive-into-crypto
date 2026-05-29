"""S7: rescue-safety config validator must catch dangerous defaults.

Contract under test:
    * ``validate_rescue_safety`` returns ``(errors, warnings)`` tuple.
    * It never raises and never mutates the input config.
    * It surfaces warnings for: live mode, live+futures pair, aggressive
      ``risk_per_trade``, oversized ``max_open_positions``, disabled daily
      loss limit, and the S6 ``dashboard_fallback_enabled`` gate.
    * The repo's existing ``config/default.yaml`` produces warnings — proof
      that the validator wakes up on the real dangerous defaults the rescue
      build inherits.
    * ``validate_config`` (the existing schema validator) is unchanged for
      a known-good config (no regression in S1–S6 behaviour).
    * ``load_config`` logs warnings without raising.
"""

import copy
import logging
from pathlib import Path

import pytest
import yaml

from src.utils.validators import (
    RESCUE_MAX_OPEN_POSITIONS_MAX,
    RESCUE_RISK_PER_TRADE_MAX,
    validate_config,
    validate_rescue_safety,
)


REPO_DEFAULT_YAML = Path(__file__).parent.parent / "config" / "default.yaml"


@pytest.fixture
def safe_config() -> dict:
    """A config that produces zero warnings/errors from the rescue validator."""
    return {
        "mode": "paper",
        "market_type": "spot",
        "timeframe": "1h",
        "active_symbol_path": "runtime/active_symbol.txt",
        "risk": {
            "risk_per_trade": 0.02,
            "stop_loss_pct": 0.04,
            "take_profit_pct": 0.1,
            "confidence_threshold": 25,
            "max_open_positions": 2,
            "daily_loss_limit_enabled": True,
            "daily_loss_limit_pct": 0.05,
        },
        "dashboard_fallback_enabled": False,
    }


class TestReturnShape:
    def test_returns_tuple_of_two_lists(self, safe_config):
        result = validate_rescue_safety(safe_config)
        assert isinstance(result, tuple)
        assert len(result) == 2
        errors, warnings = result
        assert isinstance(errors, list)
        assert isinstance(warnings, list)

    def test_safe_config_produces_no_warnings(self, safe_config):
        errors, warnings = validate_rescue_safety(safe_config)
        assert errors == []
        assert warnings == [], f"unexpected warnings on safe config: {warnings!r}"

    def test_does_not_mutate_input(self, safe_config):
        snapshot = copy.deepcopy(safe_config)
        validate_rescue_safety(safe_config)
        assert safe_config == snapshot, "validator mutated input config"

    def test_never_raises_on_missing_keys(self):
        # Empty dict, half-empty, fully-empty risk subtree — all must be safe.
        for bad_input in ({}, {"risk": {}}, {"mode": "paper"}):
            errors, warnings = validate_rescue_safety(bad_input)
            assert isinstance(errors, list)
            assert isinstance(warnings, list)

    def test_errors_slot_is_currently_empty(self, safe_config):
        """``errors`` is reserved for future S8 promotions — empty for now."""
        # Live + 35% risk is the most extreme legal config and still produces
        # only warnings; if S8 promotes any of these, this test will fail and
        # force an explicit migration note.
        extreme = {
            "mode": "live", "market_type": "futures",
            "risk": {
                "risk_per_trade": 0.35,
                "max_open_positions": 50,
                "daily_loss_limit_enabled": False,
            },
            "dashboard_fallback_enabled": True,
        }
        errors, warnings = validate_rescue_safety(extreme)
        assert errors == [], (
            f"errors slot used unexpectedly: {errors!r}. "
            "If a warning was promoted to an error, update this test and "
            "document the migration."
        )
        assert len(warnings) >= 4


class TestSpecificWarnings:
    def test_live_mode_warned(self, safe_config):
        safe_config["mode"] = "live"
        _, warnings = validate_rescue_safety(safe_config)
        assert any("mode=live" in w for w in warnings), warnings

    def test_live_plus_futures_pair_warned(self, safe_config):
        safe_config["mode"] = "live"
        safe_config["market_type"] = "futures"
        _, warnings = validate_rescue_safety(safe_config)
        joined = " || ".join(warnings)
        assert "mode=live" in joined
        assert "market_type=futures" in joined, warnings

    def test_paper_mode_does_not_warn_about_futures(self, safe_config):
        """Futures + paper is fine — no warning needed."""
        safe_config["mode"] = "paper"
        safe_config["market_type"] = "futures"
        _, warnings = validate_rescue_safety(safe_config)
        for w in warnings:
            assert "market_type=futures" not in w, (
                f"futures+paper should not warn but did: {w!r}"
            )

    def test_aggressive_risk_per_trade_warned(self, safe_config):
        safe_config["risk"]["risk_per_trade"] = RESCUE_RISK_PER_TRADE_MAX + 0.01
        _, warnings = validate_rescue_safety(safe_config)
        assert any("risk_per_trade" in w for w in warnings), warnings

    def test_risk_per_trade_at_boundary_is_safe(self, safe_config):
        safe_config["risk"]["risk_per_trade"] = RESCUE_RISK_PER_TRADE_MAX
        _, warnings = validate_rescue_safety(safe_config)
        assert not any("risk_per_trade" in w for w in warnings), warnings

    def test_max_open_positions_warned(self, safe_config):
        safe_config["risk"]["max_open_positions"] = RESCUE_MAX_OPEN_POSITIONS_MAX + 1
        _, warnings = validate_rescue_safety(safe_config)
        assert any("max_open_positions" in w for w in warnings), warnings

    def test_daily_loss_limit_disabled_warned(self, safe_config):
        safe_config["risk"]["daily_loss_limit_enabled"] = False
        _, warnings = validate_rescue_safety(safe_config)
        assert any("daily_loss_limit_enabled=false" in w for w in warnings), warnings

    def test_dashboard_fallback_enabled_warned(self, safe_config):
        safe_config["dashboard_fallback_enabled"] = True
        _, warnings = validate_rescue_safety(safe_config)
        assert any("dashboard_fallback_enabled=true" in w for w in warnings), warnings

    def test_dashboard_fallback_default_warned(self, safe_config):
        """Default is opt-out (True). Absence should also warn."""
        safe_config.pop("dashboard_fallback_enabled", None)
        _, warnings = validate_rescue_safety(safe_config)
        assert any("dashboard_fallback_enabled=true" in w for w in warnings), warnings


class TestRepoDefaultYaml:
    """The shipped ``config/default.yaml`` must be rescue-safe.

    S7 asserted the validator *woke up* on dangerous defaults
    (risk_per_trade=0.35, daily_loss_limit_enabled=false, fallback=true).
    S8 flips the contract: the on-disk default is now itself rescue-safe,
    so a stock checkout produces zero warnings. The "does the seam fire?"
    proof now lives in ``TestSpecificWarnings`` against explicit configs.
    """

    def test_repo_default_yaml_is_rescue_safe(self):
        config = yaml.safe_load(REPO_DEFAULT_YAML.read_text())
        errors, warnings = validate_rescue_safety(config)
        assert errors == []
        assert warnings == [], (
            "S8 contract: config/default.yaml must produce zero warnings. "
            f"Got: {warnings!r}. Either tighten the default or update "
            "the migration note at the top of config/default.yaml."
        )

    def test_repo_default_yaml_is_paper_mode(self):
        """A stock checkout must never default to live trading."""
        config = yaml.safe_load(REPO_DEFAULT_YAML.read_text())
        assert config.get("mode") == "paper"

    def test_repo_default_yaml_dashboard_fallback_explicit_false(self):
        """S8: opt-out key is now explicitly set so absence is a real bug."""
        config = yaml.safe_load(REPO_DEFAULT_YAML.read_text())
        assert "dashboard_fallback_enabled" in config, (
            "dashboard_fallback_enabled MUST be set explicitly in S8+. "
            "Leaving it absent silently re-enables fallback (legacy default True)."
        )
        assert config["dashboard_fallback_enabled"] is False

    def test_repo_default_yaml_risk_caps(self):
        config = yaml.safe_load(REPO_DEFAULT_YAML.read_text())
        risk = config.get("risk", {})
        assert risk.get("risk_per_trade", 1.0) <= RESCUE_RISK_PER_TRADE_MAX
        assert risk.get("max_open_positions", 99) <= RESCUE_MAX_OPEN_POSITIONS_MAX
        assert risk.get("daily_loss_limit_enabled") is True

    def test_repo_default_yaml_passes_schema_validator(self):
        """Existing validate_config() must still accept the shipped default."""
        config = yaml.safe_load(REPO_DEFAULT_YAML.read_text())
        errors = validate_config(config)
        assert errors == [], f"schema regression on default.yaml: {errors!r}"


class TestLoadConfigIntegration:
    """``load_config`` should log warnings via the rescue validator but never raise."""

    def test_load_config_logs_warnings_but_does_not_raise(self, tmp_path, caplog):
        from src.control.config_watcher import load_config

        cfg_path = tmp_path / "default.yaml"
        cfg_path.write_text(yaml.dump({
            "mode": "paper",
            "market_type": "spot",
            "timeframe": "1h",
            "active_symbol_path": "runtime/active_symbol.txt",
            "risk": {
                "risk_per_trade": 0.5,            # aggressive → warn
                "stop_loss_pct": 0.04,
                "take_profit_pct": 0.1,
                "confidence_threshold": 25,
                "daily_loss_limit_enabled": False,  # warn
            },
            "dashboard_fallback_enabled": True,   # warn
        }))

        with caplog.at_level(logging.WARNING, logger="control.config_watcher"):
            cfg = load_config(str(cfg_path))

        assert cfg["mode"] == "paper"
        warning_records = [r.message for r in caplog.records if r.levelno >= logging.WARNING]
        assert any("Rescue safety" in m for m in warning_records), warning_records
        # At least three distinct warnings should have surfaced.
        assert sum("Rescue safety" in m for m in warning_records) >= 3
