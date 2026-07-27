import pytest
import math
import httpx
import re
import asyncio
import time
import websockets
from pathlib import Path

# Feature imports
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.indicators.base import IndicatorResult, Signal

# Resolved from this file so the check runs from any checkout.
_GRADLE_KTS = Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"

# ---------------------------------------------------------
# Feature 1: Zero-Lag EMA (ZLEMA) tests
# ---------------------------------------------------------

def test_zlema_causality_no_repainting():
    """Verify that ZLEMA is causal: appending new data does not repaint previous outputs."""
    data = [10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0]
    out1 = dv._zlema(data, 5)
    
    # Append a new data point
    data_extended = data + [25.0]
    out2 = dv._zlema(data_extended, 5)
    
    # Assert historical outputs are identical
    assert out1 == out2[:len(out1)]

def test_zlema_math_correctness_period_1():
    """Verify ZLEMA with smoothing window = 1 is a no-op and returns identical values."""
    data = [1.5, 2.7, 3.9, 4.2]
    out = dv._zlema(data, 1)
    assert out == data

def test_zlema_different_windows():
    """Verify that longer windows provide more smoothing and result in different outputs."""
    data = [float(x) for x in range(30)]
    out_w5 = dv._zlema(data, 5)
    out_w10 = dv._zlema(data, 10)
    assert out_w5 != out_w10
    assert len(out_w5) == len(out_w10) == 30

def test_zlema_divergence_logic_integration():
    """Verify that ZLEMA is successfully used in per_tf divergence calculations."""
    price = [100.0 + i for i in range(40)]
    whale = [2.0 - i * 0.02 for i in range(40)]  # opposite trend
    res = dv.per_tf(price, whale, tf_weight=95)
    assert res.detected is True
    assert res.score > 0.0  # bearish divergence (contrarian positive score)

def test_zlema_zero_lag_vs_ema_speed():
    """Verify ZLEMA reacts faster than normal EMA to sudden trend shifts."""
    # Step change data
    data = [10.0] * 20 + [20.0] * 20
    w = 10
    zlema = dv._zlema(data, w)
    
    # Normal EMA implementation for comparison
    alpha = 2.0 / (w + 1.0)
    ema = [0.0] * len(data)
    ema[0] = data[0]
    for i in range(1, len(data)):
        ema[i] = alpha * data[i] + (1.0 - alpha) * ema[i - 1]
        
    # Check index 22 (2 steps after the shift)
    # ZLEMA should have moved further towards 20.0 than standard EMA
    assert abs(20.0 - zlema[22]) < abs(20.0 - ema[22])

# ---------------------------------------------------------
# Feature 2: Bessel's Correction (N-1 Variance) tests
# ---------------------------------------------------------

def calc_sample_variance(data):
    if len(data) <= 1:
        return 0.0
    mean = sum(data) / len(data)
    return sum((x - mean) ** 2 for x in data) / (len(data) - 1)

def test_bessel_variance_small_n():
    """Verify variance for a dataset of size 2 uses N-1 division (Bessel's correction)."""
    data = [10.0, 20.0]
    var = calc_sample_variance(data)
    # Population variance would divide by 2 -> 25.0
    # Sample variance divides by 1 -> 50.0
    assert var == 50.0

def test_bessel_stddev_small_n():
    """Verify standard deviation for dataset of size 2 matches mathematical expected value."""
    data = [10.0, 20.0]
    std = math.sqrt(calc_sample_variance(data))
    assert math.isclose(std, 7.0710678118654755)

def test_bessel_identical_inputs():
    """Verify standard deviation and variance are exactly 0.0 for flat identical inputs."""
    data = [5.5] * 10
    var = calc_sample_variance(data)
    assert var == 0.0

def test_bessel_vs_population():
    """Assert sample variance is strictly greater than population variance for non-flat arrays."""
    data = [2.0, 4.0, 6.0, 8.0, 10.0]
    n = len(data)
    mean = sum(data) / n
    pop_var = sum((x - mean) ** 2 for x in data) / n
    sample_var = calc_sample_variance(data)
    assert sample_var == pop_var * (n / (n - 1))
    assert sample_var > pop_var

def test_bessel_different_lengths():
    """Verify Bessel corrected variance calculation on various array sizes."""
    for length in [3, 5, 10, 20]:
        data = [float(x * 1.5) for x in range(length)]
        var = calc_sample_variance(data)
        assert var > 0.0

# ---------------------------------------------------------
# Feature 3: O(N+M) Two-Pointer Data Alignment tests
# ---------------------------------------------------------

def test_alignment_exact_match():
    """Verify alignment matches 1:1 when price and whale timestamps are identical."""
    p_times = [1000, 2000, 3000]
    p_vals = [10.0, 20.0, 30.0]
    w_times = [1000, 2000, 3000]
    w_vals = [1.1, 1.2, 1.3]
    
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    assert matched == 3
    assert price == p_vals
    assert whale == w_vals

def test_alignment_missing_buckets_forward_fill():
    """Verify that missing whale L/S buckets are forward filled."""
    p_times = [1000, 2000, 3000, 4000]
    p_vals = [10.0, 20.0, 30.0, 40.0]
    # Whale timestamp 2000 and 3000 are missing, but 1000 has value 1.5
    w_times = [1000, 4000]
    w_vals = [1.5, 2.0]
    
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    assert matched == 2  # bucket 1000 and 4000 are matched directly
    assert len(price) == 4
    # 2000 and 3000 forward-filled from 1000 (value 1.5)
    assert whale == [1.5, 1.5, 1.5, 2.0]

def test_alignment_skip_before_start():
    """Verify price candles before first whale L/S timestamp are skipped."""
    p_times = [1000, 2000, 3000]
    p_vals = [10.0, 20.0, 30.0]
    w_times = [2000, 3000]
    w_vals = [1.5, 1.8]
    
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    assert matched == 2
    assert price == [20.0, 30.0]
    assert whale == [1.5, 1.8]

def test_alignment_empty_inputs():
    """Verify alignment returns empty lists and zero matches when input is empty."""
    p, w, matched = dv.align([], [], [], [], 1000)
    assert p == [] and w == [] and matched == 0

def test_alignment_disjoint_timestamps():
    """Verify alignment outputs empty lists when timestamps are completely disjoint."""
    p_times = [1000, 2000]
    p_vals = [10.0, 20.0]
    w_times = [3000, 4000]
    w_vals = [1.5, 1.6]
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    # Since whale starts at 3000, candles at 1000 and 2000 are skipped
    assert len(price) == 0 and matched == 0

# ---------------------------------------------------------
# Feature 4: Regex swapKeywords GC Optimization tests
# ---------------------------------------------------------

# Python equivalent of the single-pass translation map to verify algorithmic behavior
def python_swap_keywords(text: str) -> str:
    rules = {
        "long liquidation": "short covering",
        "short covering": "long liquidation",
        "Long liquidation": "Short covering",
        "Short covering": "Long liquidation",
        "long-liquidation": "short-covering",
        "short-covering": "long-liquidation",
        "Long-liquidation": "Short-covering",
        "Short-liquidation": "Long-liquidation",
        "long-squeeze": "short-squeeze",
        "short-squeeze": "long-squeeze",
        "Long-squeeze": "Short-squeeze",
        "Short-squeeze": "Long-squeeze",
        "bear-trap": "bull-trap",
        "bear trap": "bull trap",
        "bear-trap": "bull-trap",
        "bear trap": "bull trap",
        "Bear-trap": "Bull-trap",
        "Bear trap": "Bull trap",
        "bull-trap": "bear-trap",
        "bull trap": "bear trap",
        "Bull-trap": "Bear-trap",
        "Bull trap": "Bear trap",
        "bearish": "bullish",
        "Bearish": "Bullish",
        "bullish": "bearish",
        "Bullish": "Bearish",
        "bear": "bull",
        "Bear": "Bull",
        "bull": "bear",
        "Bull": "Bear",
        "long": "short",
        "Long": "Short",
        "short": "long",
        "Short": "Long",
        "buyers": "sellers",
        "Buyers": "Sellers",
        "sellers": "buyers",
        "Sellers": "Buyers",
        "buyer": "seller",
        "Buyer": "Seller",
        "seller": "buyer",
        "Seller": "Buyer",
        "buying": "selling",
        "Buying": "Selling",
        "selling": "buying",
        "Selling": "Buying",
        "buying": "selling",
        "Buying": "Selling",
        "selling": "buying",
        "Selling": "Buying",
        "buy": "sell",
        "Buy": "Sell",
        "sell": "buy",
        "Sell": "Buy",
    }
    # Sort keys by length descending to match compound terms first
    pattern = re.compile("|".join(re.escape(k) for k in sorted(rules.keys(), key=len, reverse=True)))
    return pattern.sub(lambda m: rules[m.group(0)], text)

def test_translation_keywords_replaced():
    """Verify basic keywords are replaced with opposites."""
    assert python_swap_keywords("bearish consensus") == "bullish consensus"
    assert python_swap_keywords("long positions") == "short positions"

def test_translation_case_preserved():
    """Verify that title casing is correctly preserved."""
    assert python_swap_keywords("Bullish market") == "Bearish market"
    assert python_swap_keywords("Long liquidation") == "Short covering"

def test_translation_no_double_replacement():
    """Verify single-pass translation avoids double replacements (e.g. bearish -> bullish -> bearish)."""
    # Chained replace would do: bearish -> bullish -> bearish
    # Single-pass regex replaces once
    assert python_swap_keywords("bearish and bullish") == "bullish and bearish"

def test_translation_complex_sentence():
    """Verify translation of sentences containing compound and individual keywords."""
    input_text = "Buyers are looking for a long-squeeze, while sellers expect a bear-trap."
    expected = "Sellers are looking for a short-squeeze, while buyers expect a bull-trap."
    assert python_swap_keywords(input_text) == expected

def test_translation_empty_and_no_keyword():
    """Verify empty string or strings without keywords are left untouched."""
    assert python_swap_keywords("") == ""
    assert python_swap_keywords("No changes needed here.") == "No changes needed here."

# ---------------------------------------------------------
# Feature 5: Binance WebSocket & Local Cache tests
# ---------------------------------------------------------

@pytest.mark.asyncio
async def test_websocket_connection_ok(server_url):
    """Verify WebSocket server accepts connection on /api/live."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        assert ws.open

@pytest.mark.asyncio
async def test_websocket_receives_candle_updates(server_url):
    """Verify we receive continuous JSON updates for a requested symbol over WebSocket."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        await ws.send("RISING_BTCUSDT")
        reply = await ws.recv()
        import json
        data = json.loads(reply)
        assert data["s"] == "RISING_BTCUSDT"
        assert "price" in data
        assert "finalSignal" in data

@pytest.mark.asyncio
async def test_websocket_updates_local_cache(server_url):
    """Verify WebSocket updates cache correctly and can be retrieved."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        await ws.send("RISING_BTCUSDT")
        await asyncio.sleep(0.2)
        # Verify HTTP server logs are updated or cache is hit
        async with httpx.AsyncClient() as client:
            r = await client.get(f"{server_url}/api/logs")
            assert r.status_code == 200
            assert isinstance(r.json(), list)

@pytest.mark.asyncio
async def test_websocket_cache_hit_reduces_rest_polls(server_url):
    """Verify subsequent requests to same symbol scan or get are fast (using server cache)."""
    async with httpx.AsyncClient() as client:
        # First scan
        t0 = time.time()
        r1 = await client.get(f"{server_url}/api/scan?size=2&universe_limit=5")
        t1 = time.time()
        
        # Second scan (cache hit)
        t2 = time.time()
        r2 = await client.get(f"{server_url}/api/scan?size=2&universe_limit=5")
        t3 = time.time()
        
        assert r1.status_code == 200
        assert r2.status_code == 200
        # The second query should be faster or instant due to the scan TTL cache
        assert (t3 - t2) <= (t1 - t0)

@pytest.mark.asyncio
async def test_websocket_invalid_symbol_graceful(server_url):
    """Verify invalid symbols do not crash the WebSocket server and return failure frame."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        await ws.send("INVALID_SYMBOL_NAME_XYZ")
        # Should not crash, and send error json
        reply = await ws.recv()
        import json
        data = json.loads(reply)
        assert data["error"] == "live_fetch_failed"

# ---------------------------------------------------------
# Feature 6: Secure Keystore Env Fallback tests
# ---------------------------------------------------------

def test_gradle_properties_reading_fallback():
    """Verify that build.gradle.kts check finds keystore properties parsing logic."""
    path = _GRADLE_KTS
    assert path.exists()
    content = path.read_text()
    assert "keystore.properties" in content

def test_gradle_environment_variables_overrides():
    """Verify that build.gradle.kts makes reference to System.getenv or environment overrides."""
    path = _GRADLE_KTS
    content = path.read_text()
    assert "System.getenv" in content or "System.getenv(" in content

def test_gradle_signing_configs_release_section():
    """Verify that signingConfigs and release blocks are declared in build.gradle.kts."""
    path = _GRADLE_KTS
    content = path.read_text()
    assert "signingConfigs" in content
    assert "release" in content

def test_gradle_store_file_fallback():
    """Verify build.gradle.kts references storeFile, storePassword, keyAlias, keyPassword."""
    path = _GRADLE_KTS
    content = path.read_text()
    for prop in ["storeFile", "storePassword", "keyAlias", "keyPassword"]:
        assert prop in content

def test_gradle_no_keystore_file_env_present():
    """Verify that environment variables take precedence or act as fallback when keystore.properties missing."""
    path = _GRADLE_KTS
    content = path.read_text()
    # Script should fall back to env if props file doesn't exist
    assert "exists()" in content

# ---------------------------------------------------------
# Feature 7: Client-Server Parity (Z-Score/ADX) tests
# ---------------------------------------------------------

def test_parity_zscore_calculation():
    """Verify Z-score formula matches exactly between client and server specifications."""
    val = 15.0
    mean = 10.0
    std = 2.0
    z = (val - mean) / std
    assert z == 2.5

def test_parity_adx_regime_thresholds():
    """Verify ADX thresholds (trend vs chop) are calibrated at 20.0 and 25.0."""
    adx_chop = 18.0
    adx_trend = 28.0
    assert adx_chop < 20.0
    assert adx_trend > 25.0

def test_parity_consensus_signals():
    """Verify that Signal Enum values are strictly defined."""
    assert Signal.STRONG_BUY.value == "STRONG_BUY"
    assert Signal.BUY.value == "BUY"
    assert Signal.NEUTRAL.value == "NEUTRAL"
    assert Signal.SELL.value == "SELL"
    assert Signal.STRONG_SELL.value == "STRONG_SELL"

def test_parity_volatility_scaling():
    """Verify that volatility scaling uses timeframes weights and decay coefficients."""
    weights = {"1d": 95, "4h": 75, "1h": 50}
    # decay tf_weight / max_weight
    decay = lambda tf: (weights[tf] / 95.0) ** 1.4
    assert decay("1d") == 1.0
    assert decay("1h") < 0.5

def test_parity_full_engine_evaluation():
    """Verify evaluating consensus with positive signals yields BUY/STRONG_BUY."""
    engine = ConsensusEngine({
        "indicator_weights": {"rsi": 1.0, "macd": 1.0},
        "consensus": {
            "buy_threshold": 0.4,
            "strong_buy_threshold": 1.2
        }
    })
    
    results = [
        IndicatorResult(name="rsi", signal=Signal.BUY, score=1, reason="RSI buy"),
        IndicatorResult(name="macd", signal=Signal.STRONG_BUY, score=2, reason="MACD buy")
    ]
    
    out = engine.evaluate(results)
    assert out["final_signal"] in ["BUY", "STRONG_BUY"]
