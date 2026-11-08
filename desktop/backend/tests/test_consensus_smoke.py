"""Smoke test: the ported engine runs end-to-end on a synthetic OHLCV frame."""

import math

import pandas as pd

import pytest

from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.consensus.risk import assess_risk
from diveintocrypto_desktop.engine.indicators.base import IndicatorResult, Signal
from diveintocrypto_desktop.engine.loader import load_config
from diveintocrypto_desktop.engine.signal_service import SignalService
from diveintocrypto_desktop.engine.utils.helpers import clamp


def _synthetic_ohlcv(n: int = 300) -> pd.DataFrame:
    """Deterministic gently-trending candle series (no RNG, reproducible)."""
    rows = []
    price = 100.0
    for i in range(n):
        drift = math.sin(i / 11.0) * 0.6 + 0.05  # oscillating up-bias
        open_ = price
        close = max(price + drift, 1.0)
        high = max(open_, close) + abs(math.sin(i / 5.0)) * 0.4
        low = min(open_, close) - abs(math.cos(i / 7.0)) * 0.4
        vol = 1000 + (i % 50) * 7
        rows.append({"open": open_, "high": high, "low": low, "close": close, "volume": vol})
        price = close
    return pd.DataFrame(rows)


def test_signal_service_returns_expected_results():
    df = _synthetic_ohlcv()
    service = SignalService(load_config())
    results = service.calculate_all(df)
    assert len(results) == len(service.indicators)
    assert all(isinstance(r, IndicatorResult) for r in results)
    names = {r.name for r in results}
    assert {"rsi", "macd", "adx_di", "atr_filter", "ichimoku"} <= names


def test_consensus_evaluate_shape():
    df = _synthetic_ohlcv()
    cfg = load_config()
    results = SignalService(cfg).calculate_all(df)
    out = ConsensusEngine(cfg).evaluate(results)
    for key in ("final_signal", "confidence", "risk_level", "weighted_score", "reason"):
        assert key in out, f"missing consensus output key: {key}"
    assert out["final_signal"] in {"STRONG_BUY", "BUY", "NEUTRAL", "SELL", "STRONG_SELL"}
    assert 0 <= out["confidence"] <= 100
    assert out["risk_level"] in {"LOW", "MEDIUM", "HIGH"}


def _res(name: str, signal: Signal, score: int, raw: dict | None = None) -> IndicatorResult:
    return IndicatorResult(name=name, signal=signal, score=score, reason="", raw_values=raw or {})


def _even_split() -> list[IndicatorResult]:
    """3 STRONG_BUY vs 3 STRONG_SELL: minority/active = 0.5 (the largest possible)."""
    return [
        _res("macd", Signal.STRONG_BUY, 2),
        _res("ichimoku", Signal.STRONG_BUY, 2),
        _res("supertrend", Signal.STRONG_BUY, 2),
        _res("rsi", Signal.STRONG_SELL, -2),
        _res("cci", Signal.STRONG_SELL, -2),
        _res("mfi", Signal.STRONG_SELL, -2),
    ]


def test_shipped_config_forces_neutral_on_even_split():
    """Regression: the shipped conflict threshold (0.6) was unreachable, so a
    50/50 split with a strong weighted score produced a tradable BUY."""
    out = ConsensusEngine(load_config()).evaluate(_even_split())
    assert out["final_signal"] == "NEUTRAL"
    assert out["should_trade"] is False


def test_shipped_config_reports_high_conflict_risk():
    out = ConsensusEngine(load_config()).evaluate(_even_split())
    assert any(f.startswith("High signal conflict") for f in out["risk_data"]["risk_factors"])


def test_clamp_maps_nan_to_lower_bound():
    assert clamp(float("nan"), 0, 100) == 0
    assert clamp(150.0, 0, 100) == 100
    assert clamp(-5.0, 0, 100) == 0
    assert clamp(42.0, 0, 100) == 42


def test_nonfinite_indicator_weight_is_rejected():
    with pytest.raises(ValueError):
        ConsensusEngine({"indicator_weights": {"rsi": float("inf")}})


def test_unknown_max_risk_level_is_rejected_but_case_is_normalised():
    with pytest.raises(ValueError):
        ConsensusEngine({"risk": {"max_risk_level": "EXTREME"}})
    engine = ConsensusEngine({"risk": {"max_risk_level": "low"}})
    # LOW gate: a MEDIUM-risk setup must now be blocked, not silently allowed
    assert engine._should_trade(Signal.BUY, 80, {"risk_level": "MEDIUM"}) is False


def test_confidence_fallbacks_match_shipped_defaults():
    engine = ConsensusEngine({})
    # shipped confidence_threshold is 25 and no_trade.min_confidence is 30
    assert engine._should_trade(Signal.BUY, 30, {"risk_level": "LOW"}) is True
    assert engine._should_trade(Signal.BUY, 24, {"risk_level": "LOW"}) is False


def test_low_atr_factor_requires_squeeze_ratio():
    """atr_filter labels 0.5-0.8 as LOW, but only ratio < 0.5 is a squeeze."""
    score = {"buy_count": 4, "sell_count": 0, "active_signals": 4, "weighted_score": 1.0}
    normal = [_res("atr_filter", Signal.NEUTRAL, 0, {"volatility": "LOW", "atr_ratio": 0.6})]
    squeeze = [_res("atr_filter", Signal.NEUTRAL, 0, {"volatility": "LOW", "atr_ratio": 0.3})]
    cfg = load_config()
    assert not any("squeeze" in f for f in assess_risk(normal, score, cfg)["risk_factors"])
    assert any("squeeze" in f for f in assess_risk(squeeze, score, cfg)["risk_factors"])

