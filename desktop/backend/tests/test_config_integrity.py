"""Config integrity: every thresholds/weights key must be a real indicator name,
and every indicator must carry a weight. Regression for the `atr:` → `atr_filter:`
rename (the old key meant the ATR filter's thresholds block never applied).
"""

import pytest
import yaml

from diveintocrypto_desktop.engine.indicators.atr_filter import ATRFilterIndicator
from diveintocrypto_desktop.engine.loader import _CONFIG_PATH, load_config
from diveintocrypto_desktop.engine.signal_service import SignalService

EXPECTED_INDICATOR_COUNT = 57


@pytest.fixture(scope="module")
def config() -> dict:
    return load_config()


@pytest.fixture(scope="module")
def indicator_names(config) -> set[str]:
    return set(SignalService(config).get_indicator_names())


def test_yaml_on_disk_matches_loader():
    with _CONFIG_PATH.open() as f:
        assert yaml.safe_load(f) == load_config()


def test_every_indicator_thresholds_key_is_a_real_indicator(config, indicator_names):
    thresholds = set(config.get("indicator_thresholds", {}))
    orphans = thresholds - indicator_names
    assert not orphans, f"thresholds for unknown indicators: {sorted(orphans)}"


def test_every_indicator_weights_key_is_a_real_indicator(config, indicator_names):
    weights = set(config.get("indicator_weights", {}))
    orphans = weights - indicator_names
    assert not orphans, f"weights for unknown indicators: {sorted(orphans)}"


def test_all_indicators_have_weights(config, indicator_names):
    weights = set(config.get("indicator_weights", {}))
    missing = indicator_names - weights
    assert not missing, f"indicators without a weight: {sorted(missing)}"
    assert len(indicator_names) == EXPECTED_INDICATOR_COUNT


def test_atr_filter_thresholds_actually_apply(config):
    """The indicator looks up thresholds by its own name ('atr_filter'); a block
    keyed differently (the old `atr:`) would silently fall back to code defaults.
    """
    ind = ATRFilterIndicator(config)
    assert ind.thresholds == config["indicator_thresholds"]["atr_filter"]
    assert ind.thresholds["period"] == 14
    assert ind.thresholds["high_volatility_multiplier"] == 2.0


def test_regime_block_matches_the_overlay_contract(config):
    regime = config.get("regime")
    assert regime is not None, "regime overlay must be explicitly configurable"
    assert set(regime) == {"strong_adx", "weak_adx", "chop_trend", "chop_range"}
    # Load-time values must keep matching the module fallbacks.
    from diveintocrypto_desktop.engine.consensus import regime as rg

    out = rg.detect_regime([], config)
    assert out["regime"] == "MIXED"  # no data → MIXED, no crash
