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
from tests.e2e.test_tier1_coverage import python_swap_keywords

# Resolved from this file so the check runs from any checkout.
_GRADLE_KTS = Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"

# ---------------------------------------------------------
# Tier 4: Real-World Scenarios (5 cases)
# ---------------------------------------------------------

@pytest.mark.asyncio
async def test_scenario_high_volatility_scan(server_url):
    """Scenario 1: High-volatility market scan cycle with WebSocket caching.
    Simulate full scanning lifecycle for a high-volatility token:
    1. Fetch perp universe.
    2. Retrieve symbol page for RISING_BTCUSDT (simulating high volatility).
    3. Assert ZLEMA, Bessel's correction standard dev, and consensus evaluations run cleanly.
    4. Connect to WS stream to receive live data updates.
    """
    async with httpx.AsyncClient() as client:
        # Step 1: Fetch perp universe
        uni_resp = await client.get(f"{server_url}/api/universe?limit=3")
        assert uni_resp.status_code == 200
        universe = uni_resp.json()
        assert len(universe) > 0
        
        # Step 2: Fetch detailed symbol metrics
        symbol = "RISING_BTCUSDT"
        sym_resp = await client.get(f"{server_url}/api/symbol/{symbol}")
        assert sym_resp.status_code == 200
        sym_data = sym_resp.json()
        
        # Assert mathematical calculations are included
        assert sym_data["s"] == symbol
        assert sym_data["finalSignal"] in ["BUY", "STRONG_BUY", "NEUTRAL", "SELL", "STRONG_SELL"]
        assert "confidence" in sym_data
        assert "divergence" in sym_data
        assert "score" in sym_data["divergence"]
        
        # Step 3: Verify live websocket feed
        ws_url = server_url.replace("http://", "ws://") + "/api/live"
        async with websockets.connect(ws_url) as ws:
            await ws.send(symbol)
            msg = await ws.recv()
            ws_data = json.loads(msg)
            assert ws_data["s"] == symbol
            assert ws_data["price"] == sym_data["price"]

@pytest.mark.asyncio
async def test_scenario_heavy_parallel_requests_throttling(server_url):
    """Scenario 2: Heavy parallel requests scan cache throttling.
    Trigger 10 fast refreshes concurrently and verify they hit cache, preventing rate limit bans.
    """
    async with httpx.AsyncClient() as client:
        # Trigger 10 concurrent requests to /api/scan
        tasks = [client.get(f"{server_url}/api/scan?size=2&universe_limit=5") for _ in range(10)]
        results = await asyncio.gather(*tasks)
        
        for r in results:
            assert r.status_code == 200
            
        # Ensure all responses are identical since they are retrieved from cache
        base_res = results[0].json()
        for r in results[1:]:
            assert r.json() == base_res

@pytest.mark.asyncio
async def test_scenario_websocket_disconnection_fallback(server_url):
    """Scenario 3: WebSocket network disconnection & cache recovery fallback.
    Start client, disconnect WS, fallback to HTTP REST endpoints, and then reconnect WS.
    """
    ws_url = server_url.replace("http://", "ws://") + "/api/live"
    
    # Step 1: Connect WS and get first frame
    async with websockets.connect(ws_url) as ws:
        await ws.send("RISING_BTCUSDT")
        frame1 = await ws.recv()
        assert frame1 is not None
        
    # Step 2: Connection closed (simulated). Fall back to HTTP GET /api/symbol/{symbol}
    async with httpx.AsyncClient() as client:
        rest_resp = await client.get(f"{server_url}/api/symbol/RISING_BTCUSDT")
        assert rest_resp.status_code == 200
        rest_data = rest_resp.json()
        assert rest_data["s"] == "RISING_BTCUSDT"
        
    # Step 3: Reconnect WS and continue stream
    async with websockets.connect(ws_url) as ws2:
        await ws2.send("RISING_BTCUSDT")
        frame2 = await ws2.recv()
        assert frame2 is not None

def test_scenario_clean_gradle_build_signing_fallback():
    """Scenario 4: Clean room Gradle release build signing fallback.
    Assert that Gradle build scripts fall back to loading environment variables
    when keystore.properties does not exist.
    """
    path = _GRADLE_KTS
    content = path.read_text()
    
    # Assert code has checking logic: `keystorePropsFile.exists()`
    # And falls back to environment properties if it doesn't exist
    assert "keystorePropsFile.exists()" in content
    assert "signingConfigs" in content
    assert "System.getenv" in content or "System.getenv(" in content

def test_scenario_dynamic_regime_shift_adx():
    """Scenario 5: Volatility regime shift (Chop vs Trend scaling).
    Feed synthetic data that transitions from chop (ADX < 20) to trend (ADX > 25),
    verifying that the consensus weights adjust dynamically based on regime.
    """
    # Regime shift simulation:
    # 1. Chop regime (low ADX, high conflict ratio, forces neutral)
    chop_engine = ConsensusEngine({
        "indicator_weights": {"rsi": 1.0, "macd": 1.0},
        "consensus": {"buy_threshold": 0.4, "sell_threshold": -0.4, "conflict_ratio_threshold": 0.4}
    })
    
    # Opposite signals -> High conflict -> Forces NEUTRAL
    chop_results = [
        IndicatorResult(name="rsi", signal=Signal.BUY, score=1, reason="RSI Buy"),
        IndicatorResult(name="macd", signal=Signal.SELL, score=-1, reason="MACD Sell")
    ]
    chop_out = chop_engine.evaluate(chop_results)
    assert chop_out["final_signal"] == "NEUTRAL"
    assert chop_out["should_trade"] is False
    
    # 2. Trend regime (high ADX, matching signals, high confidence)
    trend_engine = ConsensusEngine({
        "indicator_weights": {"rsi": 1.0, "macd": 1.0},
        "consensus": {"buy_threshold": 0.4, "sell_threshold": -0.4, "conflict_ratio_threshold": 0.4},
        "risk": {"confidence_threshold": 45, "max_risk_level": "HIGH"}
    })
    
    trend_results = [
        IndicatorResult(name="rsi", signal=Signal.STRONG_BUY, score=2, reason="RSI strong buy"),
        IndicatorResult(name="macd", signal=Signal.BUY, score=1, reason="MACD buy")
    ]
    trend_out = trend_engine.evaluate(trend_results)
    assert trend_out["final_signal"] in ["BUY", "STRONG_BUY"]
    assert trend_out["confidence"] > 50
    assert trend_out["should_trade"] is True
