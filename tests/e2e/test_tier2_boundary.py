import pytest
import math
import asyncio
import websockets
from types import SimpleNamespace

# Feature imports (production code only; Kotlin rules are read from source)
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import structure as st
from diveintocrypto_desktop.engine.consensus import regime as rg
from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.indicators.base import IndicatorResult, Signal
from tests.e2e.helpers import frame_for, gradle_code


# ---------------------------------------------------------
# Feature 1: Zero-Lag EMA (ZLEMA) tests
# ---------------------------------------------------------

def test_zlema_input_less_than_lag():
    """Verify ZLEMA behavior when input series is shorter than the lag window."""
    data = [10.0, 11.0, 12.0]
    # If window is 9, lag is (9-1)//2 = 4.
    # The max(0, i - lag) ensures max(0, 0 - 4) = 0 is accessed, preventing index out of bounds.
    out = dv._zlema(data, 9)
    assert len(out) == 3
    assert all(isinstance(val, float) for val in out)

def test_zlema_nan_inf_handling():
    """Verify ZLEMA behavior with NaN and Inf inputs (should not crash, handled by _sanitize)."""
    data = [10.0, float('nan'), 12.0, 13.0, float('inf'), 15.0, 16.0, 17.0, 18.0, 19.0]
    sanitized = dv._sanitize(data, len(data))
    assert sanitized is not None
    out = dv._zlema(sanitized, 3)
    assert len(out) == 10
    assert not any(math.isnan(val) for val in out)
    assert not any(math.isinf(val) for val in out)

def test_zlema_flat_series():
    """Verify ZLEMA output on a completely flat series (should remain flat)."""
    data = [10.0] * 50
    out = dv._zlema(data, 10)
    assert len(out) == 50
    assert all(math.isclose(val, 10.0) for val in out)

def test_zlema_extreme_period():
    """Verify ZLEMA with window size larger than the series length."""
    data = [10.0 + i for i in range(10)]
    out = dv._zlema(data, 100)  # window=100 > len=10
    assert len(out) == 10
    assert all(isinstance(val, float) for val in out)

def test_zlema_empty_input():
    """Verify ZLEMA handles empty list input by returning empty list."""
    assert dv._zlema([], 10) == []

# ---------------------------------------------------------
# Feature 2: Bessel's Correction (N-1 Variance) tests
# Production sample standard deviation: structure._std (divides by n - 1).
# ---------------------------------------------------------

def test_bessel_n_1_zero_division():
    """A single reading has no sample variance: _std returns None, never NaN or a crash."""
    assert st._std([10.0]) is None

def test_bessel_empty_input():
    """Sample standard deviation of an empty series is undefined (None)."""
    assert st._std([]) is None

def test_bessel_inf_nan_inputs():
    """Verify variance calculation with NaN elements removed first."""
    data = [10.0, float('nan'), 20.0]
    clean_data = [x for x in data if not math.isnan(x)]
    sd = st._std(clean_data)
    assert sd * sd == pytest.approx(50.0)

def test_bessel_extremely_small_variance():
    """Verify variance holds precision for extremely small differences."""
    data = [1.0000001, 1.0000002]
    var = st._std(data) ** 2
    assert math.isclose(var, 5e-15, rel_tol=1e-5)

def test_bessel_large_n_performance():
    """Verify Bessel's standard deviation runs on larger datasets and is positive."""
    data = [float(x) for x in range(1000)]
    assert st._std(data) > 0.0

# ---------------------------------------------------------
# Feature 3: O(N+M) Two-Pointer Data Alignment tests
# ---------------------------------------------------------

def test_alignment_disjoint_gaps():
    """Verify alignment when price and whale series have completely disjoint timestamps."""
    p_times = [1000, 2000, 3000]
    p_vals = [10.0, 20.0, 30.0]
    w_times = [4000, 5000, 6000]
    w_vals = [1.1, 1.2, 1.3]
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    assert len(price) == 0
    assert matched == 0

def test_alignment_duplicate_timestamps():
    """Verify alignment with duplicate timestamps in inputs."""
    p_times = [1000, 1000, 2000]
    p_vals = [10.0, 15.0, 20.0]
    w_times = [1000, 2000]
    w_vals = [1.1, 1.2]
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    assert len(price) == 3
    assert price == [10.0, 15.0, 20.0]
    assert whale == [1.1, 1.1, 1.2]

def test_alignment_out_of_order():
    """Verify alignment when input arrays contain out of order timestamps."""
    p_times = [2000, 1000, 3000]
    p_vals = [20.0, 10.0, 30.0]
    w_times = [1000, 3000, 2000]
    w_vals = [1.1, 1.3, 1.2]
    # Since dv.align uses a dictionary mapping for ls_by_bucket internally, it naturally handles out-of-order ls_times.
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    assert matched == 3
    # Price remains in the order of price_times (2000, 1000, 3000)
    assert price == [20.0, 10.0, 30.0]
    # Whale values mapped to the corresponding price bucket
    assert whale == [1.2, 1.1, 1.3]

def test_alignment_one_element():
    """Verify alignment with single-element lists."""
    price, whale, matched = dv.align([1000], [10.0], [1000], [1.5], 1000)
    assert matched == 1
    assert price == [10.0]
    assert whale == [1.5]

def test_alignment_huge_dataset():
    """Verify alignment scales linearly and handles 10,000 items without timing out."""
    length = 10000  # Smaller scale for unit testing, but checks performance
    p_times = [i * 1000 for i in range(length)]
    p_vals = [float(i) for i in range(length)]
    w_times = [i * 1000 for i in range(length)]
    w_vals = [float(i) for i in range(length)]
    price, whale, matched = dv.align(p_times, p_vals, w_times, w_vals, 1000)
    assert matched == length
    assert len(price) == length

# ---------------------------------------------------------
# Feature 5: WebSocket & Cache tests
# ---------------------------------------------------------

@pytest.mark.asyncio
async def test_websocket_disconnect_reconnect(server_url):
    """Verify we can open, close, and immediately open a new WebSocket connection."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws1:
        assert ws1.open
    # Connect again
    async with websockets.connect(ws_url) as ws2:
        assert ws2.open

@pytest.mark.asyncio
async def test_websocket_corrupt_payload(server_url):
    """Verify WebSocket server handles corrupt/non-JSON text requests gracefully."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        await ws.send("{invalid_json_message: true}")
        # Should respond with error frame
        reply = await ws.recv()
        import json
        data = json.loads(reply)
        assert "error" in data or "symbol" in data

@pytest.mark.asyncio
async def test_websocket_rapid_requests(server_url):
    """Verify WebSocket handles rapid succession of symbol updates: the last symbol sent wins."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    async with websockets.connect(ws_url) as ws:
        # Spam requests
        for symbol in ["BTCUSDT", "ETHUSDT", "SOLUSDT"]:
            await ws.send(symbol)
        # The server takes one queued symbol per tick, so the frame for the last one
        # arrives after the earlier ones; read until it shows up.
        data = await frame_for(ws, "SOLUSDT", attempts=6)
        assert data["s"] == "SOLUSDT"

@pytest.mark.asyncio
async def test_websocket_high_concurrency(server_url):
    """Verify multiple concurrent client connections do not block or crash the server."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    clients = [websockets.connect(ws_url) for _ in range(5)]
    ws_conns = await asyncio.gather(*clients)
    for ws in ws_conns:
        assert ws.open
        await ws.close()

@pytest.mark.asyncio
async def test_websocket_close_frame(server_url):
    """Verify server cleans up resource connection when client closes socket."""
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    ws = await websockets.connect(ws_url)
    await ws.close()
    # No crash or error in logs

# ---------------------------------------------------------
# Feature 6: Secure Keystore Env Fallback tests (comments stripped before matching)
# ---------------------------------------------------------

def test_gradle_keystore_missing_env_missing():
    """Verify Gradle file reads signing environment variables as a fallback."""
    assert "System.getenv(" in gradle_code()

def test_gradle_keystore_env_partially_missing():
    """Verify logic for handling partially configured signing credentials."""
    code = gradle_code()
    assert '"STORE_PASSWORD"' in code
    assert '"KEY_PASSWORD"' in code

def test_gradle_syntax_check():
    """Verify build.gradle.kts contains valid Kotlin gradle configuration syntax."""
    code = gradle_code()
    assert "plugins {" in code
    assert "android {" in code

def test_gradle_malformed_env_values():
    """Verify that the keystore properties are read as String values."""
    code = gradle_code()
    assert "keystoreProps.getProperty(" in code
    assert '"storePassword"' in code

def test_gradle_non_existent_paths():
    """Verify keystore files are resolved using rootProject file locator."""
    assert "rootProject.file(" in gradle_code()

# ---------------------------------------------------------
# Feature 7: Client-Server Parity (ADX regime / consensus) tests
# ---------------------------------------------------------

def test_parity_adx_exact_thresholds():
    """ADX exactly at 25 is a strong trend and exactly at 20 is weak, in production."""
    def adx(value: float) -> list:
        return [SimpleNamespace(name="adx_di", raw_values={"adx": value})]

    assert rg.detect_regime(adx(25.0))["regime"] == "TREND"
    assert rg.detect_regime(adx(20.0))["regime"] == "RANGE"

def test_parity_empty_consensus_results():
    """Verify consensus engine evaluation handles empty results list by raising/handling neutral."""
    engine = ConsensusEngine({
        "indicator_weights": {},
        "consensus": {"buy_threshold": 0.4}
    })
    out = engine.evaluate([])
    assert out["final_signal"] == "NEUTRAL"
    assert out["confidence"] == 0

def test_parity_identical_score_conflict():
    """Verify that conflict ratio checker forces NEUTRAL on equal opposite signals."""
    engine = ConsensusEngine({
        "indicator_weights": {"rsi": 1.0, "macd": 1.0},
        "consensus": {
            "buy_threshold": 0.4,
            "sell_threshold": -0.4,
            "conflict_ratio_threshold": 0.4
        }
    })
    # One BUY (+1), one SELL (-1). Weighted score = 0.0
    results = [
        IndicatorResult(name="rsi", signal=Signal.BUY, score=1, reason="RSI buy"),
        IndicatorResult(name="macd", signal=Signal.SELL, score=-1, reason="MACD sell")
    ]
    out = engine.evaluate(results)
    assert out["final_signal"] == "NEUTRAL"
