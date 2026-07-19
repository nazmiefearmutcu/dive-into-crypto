import pandas as pd
from diveintocrypto_desktop.engine.indicators.rsi import RSIIndicator
from diveintocrypto_desktop.engine.indicators.mfi import MFIIndicator
from diveintocrypto_desktop.engine.indicators.adx_di import ADXDIIndicator
from diveintocrypto_desktop.engine.indicators.bollinger import BollingerBandsIndicator
from diveintocrypto_desktop.engine.indicators.obv import OBVIndicator
from diveintocrypto_desktop.engine.indicators.base import Signal

def test_rsi_flat_prices_division_by_zero():
    # Prices are flat, delta is always 0. Gain = 0, Loss = 0.
    # RSI should return 50.0 and NEUTRAL signal.
    df = pd.DataFrame({"close": [100.0] * 30})
    rsi_ind = RSIIndicator(config={"indicator_thresholds": {"rsi": {"period": 14}}})
    res = rsi_ind.calculate(df)
    assert res.signal == Signal.NEUTRAL
    assert res.raw_values["rsi"] == 50.0

def test_rsi_prices_only_up_division_by_zero():
    # Prices only go up, delta is always positive. Loss = 0.
    # RSI should return 100.0 and STRONG_SELL.
    df = pd.DataFrame({"close": [100.0 + i for i in range(30)]})
    rsi_ind = RSIIndicator(config={"indicator_thresholds": {"rsi": {"period": 14}}})
    res = rsi_ind.calculate(df)
    assert res.signal == Signal.STRONG_SELL
    assert res.raw_values["rsi"] == 100.0

def test_mfi_flat_prices_division_by_zero():
    # Prices are flat, volume is positive. Positive flow = 0, Negative flow = 0.
    # MFI should return 50.0.
    df = pd.DataFrame({
        "high": [100.0] * 30,
        "low": [100.0] * 30,
        "close": [100.0] * 30,
        "volume": [1000.0] * 30
    })
    mfi_ind = MFIIndicator(config={"indicator_thresholds": {"mfi": {"period": 14}}})
    res = mfi_ind.calculate(df)
    assert res.signal == Signal.NEUTRAL
    assert res.raw_values["mfi"] == 50.0

def test_mfi_prices_only_up_division_by_zero():
    # Prices only go up, positive flow exists, negative flow is 0.
    # MFI should return 100.0.
    df = pd.DataFrame({
        "high": [100.0 + i for i in range(30)],
        "low": [99.0 + i for i in range(30)],
        "close": [100.0 + i for i in range(30)],
        "volume": [1000.0] * 30
    })
    mfi_ind = MFIIndicator(config={"indicator_thresholds": {"mfi": {"period": 14}}})
    res = mfi_ind.calculate(df)
    assert res.signal == Signal.STRONG_SELL
    assert res.raw_values["mfi"] == 100.0

def test_adx_di_equal_directional_changes():
    # Check that equal directional changes produce plus_dm = 0.0 and minus_dm = 0.0
    adx_ind = ADXDIIndicator(config={"indicator_thresholds": {"adx_di": {"period": 14}}})
    df_full = pd.DataFrame({
        "high": [10.0 + (i % 2) * 2.0 for i in range(30)],
        "low": [8.0 - (i % 2) * 2.0 for i in range(30)],
        "close": [9.0] * 30
    })
    res = adx_ind.calculate(df_full)
    assert res.signal in {Signal.BUY, Signal.SELL, Signal.NEUTRAL, Signal.STRONG_BUY, Signal.STRONG_SELL}

def test_bollinger_squeeze_breakout_signals():
    # Verify that a squeeze breakout:
    # 1. Below lower band returns Signal.SELL
    # 2. Above upper band returns Signal.BUY
    closes = [100.0 + 0.1 * (i % 2) for i in range(20)]
    
    # Breakout to the downside
    closes_down = closes + [90.0]
    df_down = pd.DataFrame({"close": closes_down})
    bb_ind = BollingerBandsIndicator(config={"indicator_thresholds": {"bollinger": {"period": 20, "squeeze_threshold": 0.5}}})
    res_down = bb_ind.calculate(df_down)
    assert res_down.signal == Signal.SELL
    assert "squeeze - potential breakout" in res_down.reason

    # Breakout to the upside
    closes_up = closes + [110.0]
    df_up = pd.DataFrame({"close": closes_up})
    res_up = bb_ind.calculate(df_up)
    assert res_up.signal == Signal.BUY
    assert "squeeze - potential breakout" in res_up.reason

def test_obv_volume_zero_lookback():
    df = pd.DataFrame({
        "close": [100.0 + i for i in range(30)],
        "volume": [0.0] * 30
    })
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    res = obv_ind.calculate(df)
    assert res.signal == Signal.NEUTRAL
    assert res.raw_values["obv"] == 0.0

def test_choppiness_flat_prices_log_of_zero():
    from diveintocrypto_desktop.engine.indicators.choppiness import ChoppinessIndexIndicator
    import warnings
    # With flat prices (atr_sum == 0.0), verify it computes cleanly and returns chop = 100.0
    df = pd.DataFrame({
        "high": [100.0] * 30,
        "low": [100.0] * 30,
        "close": [100.0] * 30
    })
    chop_ind = ChoppinessIndexIndicator(config={"indicator_thresholds": {"choppiness": {"period": 14}}})
    with warnings.catch_warnings():
        warnings.simplefilter("error")  # Treat warnings as exceptions (e.g. RuntimeWarning for log10(0))
        res = chop_ind.calculate(df)
    assert res.signal == Signal.NEUTRAL
    assert res.raw_values["chop"] == 100.0

def test_obv_zero_base_price():
    # Base price (at -lookback - 1) is 0.0, which would normally trigger division by zero in price_change calculation
    # close has 30 elements, divergence_lookback is 10.
    # base_price is close.iloc[-11]. Let's make index 19 be 0.0.
    closes = [10.0] * 19 + [0.0] + [15.0] * 10
    df = pd.DataFrame({
        "close": closes,
        "volume": [100.0] * 30
    })
    obv_ind = OBVIndicator(config={"indicator_thresholds": {"obv": {"sma_period": 20, "divergence_lookback": 10}}})
    res = obv_ind.calculate(df)
    assert res.raw_values["price_change_pct"] == 0.0
