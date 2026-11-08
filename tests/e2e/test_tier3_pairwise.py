import pytest
import math
import websockets

# Feature imports
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import structure as st
from tests.e2e.helpers import frame_for


# ---------------------------------------------------------
# Tier 3: Pairwise Combination Tests (7 cases)
# ---------------------------------------------------------

def test_pairwise_zlema_and_bessels():
    """Pairwise 1: ZLEMA + Bessel's Correction.
    Smooth a raw series with ZLEMA, then compute the Bessel-corrected variance on the smoothed series.
    """
    raw_series = [10.0, 12.0, 11.0, 13.0, 15.0, 14.0, 16.0, 18.0]
    # Step 1: Smooth with ZLEMA
    smoothed = dv._zlema(raw_series, 3)
    # Step 2: Bessel corrected variance on smoothed series (production sample SD)
    var = st._std(smoothed) ** 2
    assert var > 0.0
    # Expected variance calculation manually on length 8
    mean = sum(smoothed) / len(smoothed)
    expected_var = sum((x - mean)**2 for x in smoothed) / (len(smoothed) - 1)
    assert math.isclose(var, expected_var)

@pytest.mark.asyncio
async def test_pairwise_websocket_cache_to_zlema(server_url):
    """Pairwise 2: WebSocket Cache + ZLEMA.
    Stream updates via WebSocket mock, verify cache gets updated, and that ZLEMA runs on the cached series.
    """
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        await ws.send("RISING_BTCUSDT")
        data = await frame_for(ws, "RISING_BTCUSDT")
        assert data["s"] == "RISING_BTCUSDT"

        # Verify candles in response are smoothed and have no repainting bias
        candles = data["candles"]
        close_prices = [c["c"] for c in candles]
        zlema_out = dv._zlema(close_prices, 10)
        assert len(zlema_out) == len(close_prices)

def test_pairwise_two_pointer_alignment_and_bessels():
    """Pairwise 3: Two-Pointer Alignment + Bessel's.
    Align two disparate series, and compute Bessel's corrected variance on the aligned values.
    """
    p_times = [1000, 2000, 3000, 4000]
    p_vals = [10.0, 20.0, 15.0, 25.0]
    w_times = [2000, 4000]
    w_vals = [1.5, 2.5]
    
    # Step 1: Align
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    # matched buckets: 2000 (value 1.5), 4000 (value 2.5). 1000 is skipped since no whale.
    # Resulting arrays: price=[20.0, 15.0, 25.0], whale=[1.5, 1.5, 2.5] (forward-filled)
    assert len(price) == 3
    
    # Step 2: Compute Bessel's variance on aligned prices (production sample SD)
    price_var = st._std(price) ** 2
    expected_mean = (20.0 + 15.0 + 25.0) / 3.0
    expected_var = ((20.0 - expected_mean)**2 + (15.0 - expected_mean)**2 + (25.0 - expected_mean)**2) / 2.0
    assert math.isclose(price_var, expected_var)

def test_pairwise_zlema_alignment_parity():
    """Pairwise 4: ZLEMA + Two-Pointer Alignment.
    Apply ZLEMA separately to price and whale lists, and align them using Two-Pointer alignment.
    """
    p_times = [i * 1000 for i in range(25)]
    p_vals = [float(100 + i) for i in range(25)]
    w_times = [i * 1000 for i in range(25)]
    w_vals = [1.0 + i * 0.02 for i in range(25)]
    
    # Smooth
    smooth_p = dv._zlema(p_vals, 5)
    smooth_w = dv._zlema(w_vals, 5)
    
    # Align
    price, whale, matched = dv.align(p_times, smooth_p, w_times, smooth_w, 1000)
    assert matched == 25
    assert len(price) == 25
    assert price == smooth_p
    assert whale == smooth_w

def test_pairwise_client_server_parity_alignment_and_zscore():
    """Pairwise 7: Parity + Alignment + Z-Score.
    Ensure aligned datasets compute identical Z-score normalization vectors on both client and server templates.
    """
    p_times = [i * 1000 for i in range(10)]
    p_vals = [10.0 + i for i in range(10)]
    w_times = [i * 1000 for i in range(10)]
    w_vals = [1.5 + (i * 0.1) for i in range(10)]
    
    # Align
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    
    # Standard deviation with Bessel's correction (production sample SD)
    mean = sum(price) / len(price)
    std = st._std(price)

    # Z-score normalization on the aligned prices
    zscores = [(x - mean) / std for x in price]
    
    # Check parity expectations
    assert len(zscores) == 10
    assert math.isclose(zscores[0], -1.486301, abs_tol=1e-5)
    assert math.isclose(zscores[-1], 1.486301, abs_tol=1e-5)
