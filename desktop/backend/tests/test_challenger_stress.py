import pytest
import warnings
import pandas as pd
import numpy as np
from diveintocrypto_desktop.engine.indicators.choppiness import ChoppinessIndexIndicator
from diveintocrypto_desktop.engine.indicators.obv import OBVIndicator
from diveintocrypto_desktop.engine.indicators.base import Signal

def test_choppiness_flat_prices_detailed():
    # Verify Choppiness Index with flat prices where high == low == close
    df = pd.DataFrame({
        "high": [50.0] * 30,
        "low": [50.0] * 30,
        "close": [50.0] * 30
    })
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        res = chop_ind.calculate(df)
        
    assert res is not None
    assert res.raw_values["chop"] == 100.0
    assert res.signal == Signal.NEUTRAL

def test_choppiness_insufficient_data():
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    
    # 1. Empty DataFrame (handling error)
    df_empty = pd.DataFrame(columns=["high", "low", "close"])
    with pytest.raises(Exception):
        chop_ind.calculate(df_empty)
        
    # 2. Length < period
    df_short = pd.DataFrame({
        "high": [10.0] * 10,
        "low": [9.0] * 10,
        "close": [9.5] * 10
    })
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        res = chop_ind.calculate(df_short)
    assert res.signal == Signal.NEUTRAL
    assert "insufficient" in res.reason.lower()

def test_choppiness_extreme_values_infinity():
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    
    # Infinite high/low values
    df_inf = pd.DataFrame({
        "high": [np.inf] * 30,
        "low": [10.0] * 30,
        "close": [20.0] * 30
    })
    
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        # Check if calculating on infinite values raises a RuntimeWarning
        try:
            res = chop_ind.calculate(df_inf)
            print("Infinity test response signal:", res.signal)
        except RuntimeWarning as e:
            pytest.fail(f"RuntimeWarning triggered: {e}")

def test_choppiness_extreme_values_large():
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    
    # Extremely large numbers
    df_large = pd.DataFrame({
        "high": [1e300] * 30,
        "low": [-1e300] * 30,
        "close": [0.0] * 30
    })
    
    # Let's make them varying to avoid flat check logic
    df_large.iloc[0, df_large.columns.get_loc("high")] = 1.1e300
    df_large.iloc[1, df_large.columns.get_loc("low")] = -1.1e300
    
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        try:
            res = chop_ind.calculate(df_large)
            print("Large values test response signal:", res.signal)
        except RuntimeWarning as e:
            pytest.fail(f"RuntimeWarning triggered: {e}")

def test_obv_zero_base_and_volume():
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    
    # Zero volume and zero price change
    df_zero = pd.DataFrame({
        "close": [10.0] * 30,
        "volume": [0.0] * 30
    })
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        res = obv_ind.calculate(df_zero)
        
    assert res.raw_values["obv"] == 0.0
    assert res.raw_values["price_change_pct"] == 0.0
    assert res.signal == Signal.NEUTRAL

def test_obv_extreme_values():
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    
    # Infinite close price
    df_inf = pd.DataFrame({
        "close": [np.inf] * 30,
        "volume": [100.0] * 30
    })
    
    with pytest.raises(RuntimeWarning):
        with warnings.catch_warnings():
            warnings.simplefilter("error", RuntimeWarning)
            obv_ind.calculate(df_inf)

def test_choppiness_underflow_to_zero():
    # Verify that underflow to zero is mathematically prevented and does not raise RuntimeWarning.
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    
    df = pd.DataFrame({
        "high": [10.0 + 1e-321] * 30,
        "low": [10.0] * 30,
        "close": [10.0] * 30
    })
    
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        res = chop_ind.calculate(df)
    assert res is not None
    assert res.raw_values["chop"] == 100.0

def test_obv_sma_overflow_warning():
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    
    closes = [10.0] + [11.0] * 29
    volumes = [0.0, 1.5e307] + [0.0] * 28
    
    df = pd.DataFrame({
        "close": closes,
        "volume": volumes
    })
    
    with pytest.raises(RuntimeWarning):
        with warnings.catch_warnings():
            warnings.simplefilter("error", RuntimeWarning)
            obv_ind.calculate(df)





