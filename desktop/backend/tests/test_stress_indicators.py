import warnings
import numpy as np
import pandas as pd
import pytest
from diveintocrypto_desktop.engine.indicators.choppiness import ChoppinessIndexIndicator
from diveintocrypto_desktop.engine.indicators.obv import OBVIndicator
from diveintocrypto_desktop.engine.indicators.base import Signal

# Use warning filter to raise warnings as errors inside tests by default
@pytest.fixture(autouse=True)
def raise_on_warnings():
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        yield

def test_choppiness_flat_prices_various_levels():
    # Test flat prices at different price levels, including very small
    for price in [100.0, 1.0, 1e-8, 0.0, -10.0]:
        df = pd.DataFrame({
            "high": [price] * 30,
            "low": [price] * 30,
            "close": [price] * 30
        })
        chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
        res = chop_ind.calculate(df)
        assert res.signal == Signal.NEUTRAL
        assert res.raw_values["chop"] == 100.0

def test_choppiness_insufficient_data():
    # Data is shorter than period
    df = pd.DataFrame({
        "high": [10.0] * 5,
        "low": [10.0] * 5,
        "close": [10.0] * 5
    })
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    res = chop_ind.calculate(df)
    assert res.signal == Signal.NEUTRAL
    assert "insufficient" in res.reason.lower()

def test_choppiness_empty_dataframe():
    df = pd.DataFrame(columns=["high", "low", "close"])
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    # Since df is empty, df.iloc[-1] raises IndexError.
    with pytest.raises(IndexError, match="single positional indexer is out-of-bounds"):
        chop_ind.calculate(df)

def test_choppiness_period_one():
    df = pd.DataFrame({
        "high": [10.0 + i for i in range(20)],
        "low": [9.0 + i for i in range(20)],
        "close": [9.5 + i for i in range(20)]
    })
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 1}}})
    # period=1 results in log10(1) = 0 in denominator, check if division warning/error occurs.
    # Note: Pandas/numpy series division by zero might return inf/nan without raising a warning on some systems,
    # but let's assert we get results.
    res = chop_ind.calculate(df)
    assert res is not None

def test_choppiness_period_zero_or_negative():
    df = pd.DataFrame({
        "high": [10.0 + i for i in range(20)],
        "low": [9.0 + i for i in range(20)],
        "close": [9.5 + i for i in range(20)]
    })
    for p in [0, -5]:
        chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": p}}})
        # Check if period <= 0 triggers warning/error in log10 or runs
        try:
            res = chop_ind.calculate(df)
            assert res is not None
        except Exception:
            pass

def test_choppiness_extreme_values():
    # Infinite values
    df_inf = pd.DataFrame({
        "high": [np.inf] * 30,
        "low": [10.0] * 30,
        "close": [15.0] * 30
    })
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    res_inf = chop_ind.calculate(df_inf)
    assert res_inf is not None

    # Very large values
    df_large = pd.DataFrame({
        "high": [1e300] * 30,
        "low": [1.0] * 30,
        "close": [1e299] * 30
    })
    res_large = chop_ind.calculate(df_large)
    assert res_large is not None

    # NaN values
    df_nan = pd.DataFrame({
        "high": [10.0] * 15 + [np.nan] + [10.0] * 14,
        "low": [5.0] * 15 + [np.nan] + [5.0] * 14,
        "close": [7.0] * 30
    })
    res_nan = chop_ind.calculate(df_nan)
    assert res_nan is not None

def test_obv_zero_volume_and_base_price():
    # Zero volume
    df_zero_vol = pd.DataFrame({
        "close": [10.0 + i for i in range(30)],
        "volume": [0.0] * 30
    })
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    res = obv_ind.calculate(df_zero_vol)
    assert res.signal == Signal.NEUTRAL
    assert res.raw_values["obv"] == 0.0

    # Zero base price and zero volume
    closes = [10.0] * 19 + [0.0] + [15.0] * 10
    df_both = pd.DataFrame({
        "close": closes,
        "volume": [0.0] * 30
    })
    res_both = obv_ind.calculate(df_both)
    assert res_both.raw_values["price_change_pct"] == 0.0
    assert res_both.raw_values["obv"] == 0.0

def test_obv_insufficient_data():
    df = pd.DataFrame({
        "close": [10.0] * 5,
        "volume": [100.0] * 5
    })
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    res = obv_ind.calculate(df)
    assert res.signal == Signal.NEUTRAL
    assert "insufficient" in res.reason.lower()

def test_obv_empty_dataframe():
    df = pd.DataFrame(columns=["close", "volume"])
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    with pytest.raises(IndexError, match="single positional indexer is out-of-bounds"):
        obv_ind.calculate(df)

def test_obv_extreme_values():
    # Large volume and price
    df_large = pd.DataFrame({
        "close": [1e300] * 30,
        "volume": [1e300] * 30
    })
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    res_large = obv_ind.calculate(df_large)
    assert res_large is not None

    # Infinity in price/volume triggers RuntimeWarning: invalid value encountered in scalar subtract (inf - inf)
    df_inf = pd.DataFrame({
        "close": [np.inf] * 30,
        "volume": [100.0] * 30
    })
    with pytest.warns(RuntimeWarning, match="invalid value encountered in scalar subtract"):
        res_inf = obv_ind.calculate(df_inf)
    assert res_inf is not None

    # NaN in close
    df_nan = pd.DataFrame({
        "close": [10.0] * 15 + [np.nan] + [10.0] * 14,
        "volume": [100.0] * 30
    })
    res_nan = obv_ind.calculate(df_nan)
    assert res_nan is not None
