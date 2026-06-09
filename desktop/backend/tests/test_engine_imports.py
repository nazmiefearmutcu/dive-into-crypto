import importlib
import pkgutil

import diveintocrypto_desktop.engine.indicators as ind


def test_all_indicator_modules_import():
    names = {m.name for m in pkgutil.iter_modules(ind.__path__)}
    expected = {
        "rsi", "macd", "bollinger", "ema_cross", "sma_cross", "stochastic",
        "adx_di", "cci", "williams_r", "roc", "mfi", "atr_filter",
        "ichimoku", "psar", "obv",
    }
    assert expected <= names, f"missing: {expected - names}"
    for n in names:
        importlib.import_module(f"diveintocrypto_desktop.engine.indicators.{n}")
