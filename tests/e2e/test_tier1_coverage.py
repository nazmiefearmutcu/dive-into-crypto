import pytest
import re
import httpx
import websockets
from types import SimpleNamespace

# Feature imports (production code only; Kotlin rules are read from source)
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import scanner
from diveintocrypto_desktop.scan import structure as st
from diveintocrypto_desktop.engine.consensus import regime as rg
from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.indicators.base import IndicatorResult, Signal
from tests.e2e.helpers import frame_for, gradle_code


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
# Production sample standard deviation: structure._std (divides by n - 1).
# ---------------------------------------------------------

def test_bessel_variance_small_n():
    """Verify variance for a dataset of size 2 uses N-1 division (Bessel's correction)."""
    sd = st._std([10.0, 20.0])
    # Population variance would divide by 2 -> 25.0
    # Sample variance divides by 1 -> 50.0
    assert sd * sd == pytest.approx(50.0)

def test_bessel_stddev_small_n():
    """Verify standard deviation for dataset of size 2 matches mathematical expected value."""
    assert st._std([10.0, 20.0]) == pytest.approx(7.0710678118654755)

def test_bessel_identical_inputs():
    """Verify standard deviation is exactly 0.0 for flat identical inputs."""
    assert st._std([5.5] * 10) == 0.0

def test_bessel_vs_population():
    """Assert sample variance is strictly greater than population variance for non-flat arrays."""
    data = [2.0, 4.0, 6.0, 8.0, 10.0]
    n = len(data)
    mean = sum(data) / n
    pop_var = sum((x - mean) ** 2 for x in data) / n
    sample_var = st._std(data) ** 2
    assert sample_var == pytest.approx(pop_var * (n / (n - 1)))
    assert sample_var > pop_var

def test_bessel_different_lengths():
    """Verify Bessel corrected standard deviation on various array sizes."""
    for length in [3, 5, 10, 20]:
        data = [float(x * 1.5) for x in range(length)]
        assert st._std(data) > 0.0

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
        data = await frame_for(ws, "RISING_BTCUSDT")
        assert data["s"] == "RISING_BTCUSDT"
        assert "price" in data
        assert "finalSignal" in data

@pytest.mark.asyncio
async def test_websocket_frame_then_logs_endpoint(server_url):
    """A symbol frame arrives over /api/live and /api/logs still answers afterwards.
    (Cache behaviour is asserted in test_scan_cache_hit_does_not_rerun_scan.)"""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        await ws.send("RISING_BTCUSDT")
        await frame_for(ws, "RISING_BTCUSDT")
    async with httpx.AsyncClient() as client:
        r = await client.get(f"{server_url}/api/logs")
        assert r.status_code == 200
        assert isinstance(r.json(), list)

@pytest.mark.asyncio
async def test_scan_cache_hit_does_not_rerun_scan(server_url, monkeypatch):
    """A second identical /api/scan inside the TTL is served from cache: scanner.scan runs
    once for the pair, and both response bodies are identical."""
    calls = {"n": 0}
    real_scan = scanner.scan

    async def counting_scan(*args, **kwargs):
        calls["n"] += 1
        return await real_scan(*args, **kwargs)

    monkeypatch.setattr(scanner, "scan", counting_scan)
    async with httpx.AsyncClient() as client:
        r1 = await client.get(f"{server_url}/api/scan?size=2&universe_limit=5")
        after_first = calls["n"]
        r2 = await client.get(f"{server_url}/api/scan?size=2&universe_limit=5")

    assert r1.status_code == 200
    assert r2.status_code == 200
    assert after_first == 1, "the first request must run the scan"
    assert calls["n"] == 1, "the cache hit re-ran the scan"
    assert r2.json() == r1.json()

@pytest.mark.asyncio
async def test_websocket_invalid_symbol_graceful(server_url):
    """Verify invalid symbols do not crash the WebSocket server and return failure frame."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        await ws.send("INVALID_SYMBOL_NAME_XYZ")
        # Should not crash, and send error json
        data = await frame_for(ws, "INVALID_SYMBOL_NAME_XYZ")
        assert data["error"] == "live_fetch_failed"

# ---------------------------------------------------------
# Feature 6: Secure Keystore Env Fallback tests (comments stripped before matching)
# ---------------------------------------------------------

def _signing_block() -> str:
    code = gradle_code()
    start = code.index("signingConfigs {")
    return code[start:code.index("defaultConfig {", start)]

def test_gradle_properties_reading_fallback():
    """Verify that build.gradle.kts reads keystore.properties as a fallback source."""
    assert re.search(r'rootProject\.file\(\s*"keystore\.properties"\s*\)', gradle_code())

def test_gradle_environment_variables_overrides():
    """Verify that build.gradle.kts reads the signing environment variables."""
    code = gradle_code()
    assert "System.getenv(" in code
    for env in ["STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"]:
        assert f'"{env}"' in code, env

def test_gradle_signing_configs_release_section():
    """Verify that a release signing config is declared in build.gradle.kts."""
    assert "signingConfigs {" in gradle_code()
    assert re.search(r'create\(\s*"release"\s*\)', _signing_block())

def test_gradle_store_file_fallback():
    """The release signing config takes every credential from the env-first resolved value."""
    block = _signing_block()
    assert re.search(r"storeFile\s*=\s*rootProject\.file\(\s*finalStoreFile", block)
    assert re.search(r"storePassword\s*=\s*finalStorePassword\b", block)
    assert re.search(r"keyAlias\s*=\s*finalKeyAlias\b", block)
    assert re.search(r"keyPassword\s*=\s*finalKeyPassword\b", block)

def test_gradle_no_keystore_file_env_present():
    """A missing keystore.properties must not fail the build: the load is guarded by exists()."""
    assert re.search(r"if\s*\(\s*keystorePropsFile\.exists\(\)\s*\)", gradle_code())

# ---------------------------------------------------------
# Feature 7: Client-Server Parity (ADX regime / consensus) tests
# ---------------------------------------------------------

def test_parity_adx_regime_thresholds():
    """ADX regime calls are calibrated at 20 (weak) and 25 (strong) in production."""
    def adx(value: float) -> list:
        return [SimpleNamespace(name="adx_di", raw_values={"adx": value})]

    assert rg.detect_regime(adx(28.0))["regime"] == "TREND"
    assert rg.detect_regime(adx(18.0))["regime"] == "RANGE"
    assert rg.detect_regime(adx(22.0))["regime"] == "MIXED"

def test_parity_consensus_signals():
    """Verify that Signal Enum values are strictly defined."""
    assert Signal.STRONG_BUY.value == "STRONG_BUY"
    assert Signal.BUY.value == "BUY"
    assert Signal.NEUTRAL.value == "NEUTRAL"
    assert Signal.SELL.value == "SELL"
    assert Signal.STRONG_SELL.value == "STRONG_SELL"

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
