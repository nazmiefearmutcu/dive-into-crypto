"""Smoke test: the ported engine runs end-to-end on a synthetic OHLCV frame."""

import math

import pandas as pd

from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.indicators.base import IndicatorResult
from diveintocrypto_desktop.engine.loader import load_config
from diveintocrypto_desktop.engine.signal_service import SignalService


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


def test_signal_service_returns_15_results():
    df = _synthetic_ohlcv()
    results = SignalService(load_config()).calculate_all(df)
    assert len(results) == 15
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
