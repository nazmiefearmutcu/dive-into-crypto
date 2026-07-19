"""FastAPI service tests. Offline: health/logs. @live: real symbol/scan/universe."""

import asyncio
from unittest.mock import AsyncMock, patch
from fastapi import WebSocketDisconnect
from fastapi.testclient import TestClient

from diveintocrypto_desktop.api.app import create_app

client = TestClient(create_app())


def test_health_ok():
    r = client.get("/api/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["service"] == "dive-into-crypto-desktop"


def test_logs_endpoint_returns_list():
    r = client.get("/api/logs")
    assert r.status_code == 200
    assert isinstance(r.json(), list)


def test_routes_registered():
    paths = {r.path for r in create_app().routes}
    assert {"/api/health", "/api/universe", "/api/scan", "/api/symbol/{symbol}", "/api/leaders", "/api/logs"} <= paths


def test_symbol_endpoint_ok():
    app = create_app()
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", new_callable=AsyncMock) as mock_build:
        mock_build.return_value = {"s": "BTCUSDT", "finalSignal": "BUY", "confidence": 75}
        with TestClient(app) as test_client:
            r = test_client.get("/api/symbol/BTCUSDT")
            assert r.status_code == 200
            assert r.json() == {"s": "BTCUSDT", "finalSignal": "BUY", "confidence": 75}
            mock_build.assert_called_once_with("BTCUSDT")


def test_symbol_endpoint_error():
    app = create_app()
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", new_callable=AsyncMock) as mock_build:
        mock_build.side_effect = Exception("Failed fetching data")
        with TestClient(app) as test_client:
            r = test_client.get("/api/symbol/BTCUSDT")
            assert r.status_code == 502
            assert r.json() == {"error": "symbol_fetch_failed", "symbol": "BTCUSDT"}


def test_leaders_endpoint():
    app = create_app()
    with patch("diveintocrypto_desktop.api.app.uni.list_universe", new_callable=AsyncMock) as mock_list:
        mock_list.return_value = [
            {"s": "BTCUSDT", "ch": 2.5},
            {"s": "ETHUSDT", "ch": -1.2},
            {"s": "SOLUSDT", "ch": 5.0},
        ]
        with TestClient(app) as test_client:
            r = test_client.get("/api/leaders?limit=2")
            assert r.status_code == 200
            body = r.json()
            assert len(body["gainers"]) == 2
            assert len(body["losers"]) == 2
            assert body["gainers"][0]["s"] == "SOLUSDT"
            assert body["losers"][0]["s"] == "ETHUSDT"


def test_websocket_live_endpoint_happy_path():
    app = create_app()
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", new_callable=AsyncMock) as mock_build:
        mock_build.return_value = {"s": "BTCUSDT", "finalSignal": "NEUTRAL", "confidence": 23}
        
        original_sleep = asyncio.sleep
        calls = 0
        async def mock_sleep(seconds, *args, **kwargs):
            nonlocal calls
            if seconds == 5:
                calls += 1
                if calls == 1:
                    # Wait briefly to let the loop execute and check for received text
                    await original_sleep(0.01)
                else:
                    raise WebSocketDisconnect()
            else:
                await original_sleep(seconds, *args, **kwargs)
                
        with patch("diveintocrypto_desktop.api.app.asyncio.sleep", side_effect=mock_sleep):
            with TestClient(app) as test_client:
                with test_client.websocket_connect("/api/live") as websocket:
                    # First frame: default BTCUSDT
                    data = websocket.receive_json()
                    assert data["s"] == "BTCUSDT"
                    assert data["finalSignal"] == "NEUTRAL"
                    
                    # Update symbol to ETHUSDT
                    websocket.send_text("ETHUSDT")
                    
                    # Second frame should query for ETHUSDT
                    mock_build.return_value = {"s": "ETHUSDT", "finalSignal": "BUY", "confidence": 80}
                    data = websocket.receive_json()
                    assert data["s"] == "ETHUSDT"
                    assert data["finalSignal"] == "BUY"


def test_websocket_live_endpoint_error_handling():
    app = create_app()
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", new_callable=AsyncMock) as mock_build:
        mock_build.side_effect = Exception("Binance API offline")
        original_sleep = asyncio.sleep
        async def mock_sleep(seconds, *args, **kwargs):
            if seconds == 5:
                raise WebSocketDisconnect()
            await original_sleep(seconds, *args, **kwargs)
            
        with patch("diveintocrypto_desktop.api.app.asyncio.sleep", side_effect=mock_sleep):
            with TestClient(app) as test_client:
                with test_client.websocket_connect("/api/live") as websocket:
                    data = websocket.receive_json()
                    assert data == {"error": "live_fetch_failed", "symbol": "BTCUSDT"}


# NOTE: live API behaviour is verified by a real subprocess end-to-end (T9), not
# via TestClient — TestClient runs each request on a fresh event loop, which the
# process-wide aiohttp session (correct under uvicorn's single loop) does not
# support. See tests/e2e_smoke.sh.

