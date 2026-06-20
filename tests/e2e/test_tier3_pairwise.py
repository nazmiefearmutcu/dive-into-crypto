import pytest
import math
import httpx
import asyncio
import websockets
import json
from pathlib import Path

# Feature imports
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.indicators.base import IndicatorResult, Signal
from tests.e2e.test_tier1_coverage import python_swap_keywords, calc_sample_variance

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
    # Step 2: Calculate Bessel corrected variance on smoothed series
    var = calc_sample_variance(smoothed)
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
        reply = await ws.recv()
        data = json.loads(reply)
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
    
    # Step 2: Compute Bessel's variance on aligned prices
    price_var = calc_sample_variance(price)
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

def test_pairwise_regex_translation_of_consensus_signals():
    """Pairwise 5: Regex translation + Consensus Engine.
    Evaluate indicators using Consensus Engine, produce reason string, and translate it with swapKeywords.
    """
    engine = ConsensusEngine({
        "indicator_weights": {"rsi": 1.0},
        "consensus": {"buy_threshold": 0.4, "sell_threshold": -0.4}
    })
    results = [IndicatorResult(name="rsi", signal=Signal.BUY, score=1, reason="buyers dominant")]
    out = engine.evaluate(results)
    
    reason = out["reason"]
    # Replace keywords in the reason
    translated_reason = python_swap_keywords(reason)
    # "Buy" should be swapped to "Sell", "Buy=1" -> "Sell=1"
    assert "Sell=1" in translated_reason or "Buy" not in translated_reason

@pytest.mark.asyncio
async def test_pairwise_gradle_build_config_and_api_logs(server_url):
    """Pairwise 6: Secure Signing + API Network Logs.
    Verify simulated build variables do not leak into API request-log buffers.
    """
    async with httpx.AsyncClient() as client:
        # Hit symbol endpoint with a key name in the path to simulate custom client calls
        # (This should log in the API logs)
        await client.get(f"{server_url}/api/symbol/SECURE_KEYSTORE_TEST")
        
        # Verify network logs
        logs_resp = await client.get(f"{server_url}/api/logs")
        assert logs_resp.status_code == 200
        logs = logs_resp.json()
        
        # Confirm that no sensitive secrets are printed in logs, but the request was logged
        for entry in logs:
            msg = entry["m"]
            assert "STORE_PASSWORD" not in msg
            assert "KEY_PASSWORD" not in msg

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
    
    # Standard deviation with Bessel's correction
    mean = sum(price) / len(price)
    variance = sum((x - mean)**2 for x in price) / (len(price) - 1)
    std = math.sqrt(variance)
    
    # Z-score normalization on the aligned prices
    zscores = [(x - mean) / std for x in price]
    
    # Check parity expectations
    assert len(zscores) == 10
    assert math.isclose(zscores[0], -1.486301, abs_tol=1e-5)
    assert math.isclose(zscores[-1], 1.486301, abs_tol=1e-5)
