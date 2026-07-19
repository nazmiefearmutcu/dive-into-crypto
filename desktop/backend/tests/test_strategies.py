"""Unit tests for the strategy overlays: regime-adaptive weighting + MTF confluence."""

from diveintocrypto_desktop.engine.consensus import regime as rg
from diveintocrypto_desktop.engine.indicators.base import IndicatorResult, Signal
from diveintocrypto_desktop.scan import mtf


def _res(name, signal, raw=None):
    return IndicatorResult(name=name, signal=signal, score=0, reason="", raw_values=raw)


def test_regime_trend_detected():
    results = [_res("adx_di", Signal.BUY, {"adx": 32.0}), _res("choppiness", Signal.BUY, {"chop": 30.0})]
    assert rg.detect_regime(results)["regime"] == "TREND"


def test_regime_range_detected():
    results = [_res("adx_di", Signal.NEUTRAL, {"adx": 15.0}), _res("choppiness", Signal.NEUTRAL, {"chop": 70.0})]
    assert rg.detect_regime(results)["regime"] == "RANGE"


def test_regime_mixed_when_no_data():
    assert rg.detect_regime([_res("rsi", Signal.BUY)])["regime"] == "MIXED"


def test_adaptive_weights_boost_and_damp():
    base = {"macd": 2.0, "rsi": 1.5, "atr_filter": 0.0}
    w = rg.adaptive_weights(base, "TREND")
    assert w["macd"] > 2.0        # trend family boosted
    assert w["rsi"] < 1.5         # range family damped
    assert w["atr_filter"] == 0.0  # pure filter untouched


def test_regime_evaluate_shape():
    results = [_res("adx_di", Signal.BUY, {"adx": 32.0}), _res("macd", Signal.BUY), _res("rsi", Signal.SELL)]
    out = rg.evaluate(results, {"macd": 2.0, "rsi": 1.5})
    assert out["regime"] == "TREND"
    assert "adaptive_score" in out


def test_mtf_confluence_bull_gate():
    m = [{"tf": tf, "signal": "BUY", "confidence": 80} for tf in ("1h", "4h", "1d")]
    c = mtf.confluence(m)
    assert c["direction"] == 1 and c["gate"] is True and c["score"] > 0


def test_mtf_confluence_split_no_gate():
    m = [
        {"tf": "1h", "signal": "BUY", "confidence": 70},
        {"tf": "4h", "signal": "SELL", "confidence": 70},
        {"tf": "1d", "signal": "NEUTRAL", "confidence": 0},
    ]
    assert mtf.confluence(m)["gate"] is False


def test_mtf_empty_is_neutral():
    c = mtf.confluence([])
    assert c["direction"] == 0 and c["gate"] is False and c["score"] == 0.0
