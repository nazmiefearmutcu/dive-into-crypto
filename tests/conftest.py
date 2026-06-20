import sys
import os
# Clear proxy settings for local testing to avoid routing loopback requests through the sandbox proxy
for var in ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy']:
    os.environ.pop(var, None)
os.environ['NO_PROXY'] = '127.0.0.1,localhost'
os.environ['no_proxy'] = '127.0.0.1,localhost'
from pathlib import Path

# Add backend src to sys.path
backend_src = Path("/Users/nazmi/dive-into-crypto/desktop/backend/src")
if str(backend_src) not in sys.path:
    sys.path.insert(0, str(backend_src))

import asyncio
import time
import socket
import threading
import math
import httpx
import pytest
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect

# Mock crypcodile and aiolimiter to avoid sandbox violation when importing external libraries
from unittest.mock import MagicMock
mock_backfill = MagicMock()
mock_backfill._live_fetch_klines = MagicMock()
mock_backfill.parse_klines_page = MagicMock()
mock_backfill._live_fetch_open_interest_hist = MagicMock()
mock_backfill.parse_open_interest_hist = MagicMock()
sys.modules['crypcodile'] = MagicMock()
sys.modules['crypcodile.exchanges'] = MagicMock()
sys.modules['crypcodile.exchanges.binance'] = MagicMock()
sys.modules['crypcodile.exchanges.binance.backfill'] = mock_backfill

mock_limiter = MagicMock()
mock_limiter.AsyncLimiter = MagicMock()
sys.modules['aiolimiter'] = mock_limiter

# Import modules to patch
import diveintocrypto_desktop.data.binance_klines as kl
import diveintocrypto_desktop.data.open_interest as oi_mod
import diveintocrypto_desktop.data.ratios as rat
import diveintocrypto_desktop.data.funding as fnd
import diveintocrypto_desktop.data.universe as uni
import diveintocrypto_desktop.scan.symbol_builder as sb
from diveintocrypto_desktop.api.app import create_app

# --- In-Memory Networking Mock ---
from fastapi.testclient import TestClient
import websockets
import httpx

global_app = create_app()
test_client = TestClient(global_app)

class MockWebSocket:
    def __init__(self, starlette_ws_ctx):
        self.starlette_ws_ctx = starlette_ws_ctx
        self.starlette_ws = starlette_ws_ctx.__enter__()
        self.open = True

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.close()

    async def send(self, data):
        if self.open:
            try:
                self.starlette_ws.send_text(data)
            except Exception:
                pass

    async def recv(self):
        if not self.open:
            raise Exception("Connection closed")
        try:
            return self.starlette_ws.receive_text()
        except Exception:
            self.open = False
            raise Exception("Connection closed")

    async def close(self):
        if self.open:
            self.open = False
            try:
                self.starlette_ws_ctx.__exit__(None, None, None)
            except Exception:
                pass

class MockWebsocketsConnect:
    def __init__(self, url, *args, **kwargs):
        self.url = url

    async def __aenter__(self):
        from urllib.parse import urlparse
        parsed = urlparse(self.url)
        path = parsed.path
        if parsed.query:
            path += "?" + parsed.query
        starlette_ws_ctx = test_client.websocket_connect(path)
        self.ws = MockWebSocket(starlette_ws_ctx)
        return self.ws

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if hasattr(self, 'ws'):
            await self.ws.close()

    def __await__(self):
        async def _connect():
            from urllib.parse import urlparse
            parsed = urlparse(self.url)
            path = parsed.path
            if parsed.query:
                path += "?" + parsed.query
            starlette_ws_ctx = test_client.websocket_connect(path)
            return MockWebSocket(starlette_ws_ctx)
        return _connect().__await__()

# Patch websockets.connect
websockets.connect = MockWebsocketsConnect

# Patch httpx.AsyncClient
_orig_AsyncClient = httpx.AsyncClient
def MockAsyncClient(*args, **kwargs):
    if 'app' not in kwargs and 'transport' not in kwargs:
        kwargs['transport'] = httpx.ASGITransport(app=global_app)
    elif 'app' in kwargs:
        app = kwargs.pop('app')
        kwargs['transport'] = httpx.ASGITransport(app=app)
    return _orig_AsyncClient(*args, **kwargs)
httpx.AsyncClient = MockAsyncClient

# Patch httpx.get
_orig_get = httpx.get
def MockGet(url, *args, **kwargs):
    if "127.0.0.1" in url or "localhost" in url:
        from urllib.parse import urlparse
        parsed = urlparse(url)
        path = parsed.path
        if parsed.query:
            path += "?" + parsed.query
        return test_client.get(path)
    return _orig_get(url, *args, **kwargs)
httpx.get = MockGet
# ----------------------------------

# Keep track of original functions
_orig_fetch_klines = kl.fetch_klines
_orig_fetch_oi_hist = oi_mod.fetch_oi_hist
_orig_global_account_ls = rat.global_account_ls
_orig_top_account_ls = rat.top_account_ls
_orig_top_position_ls = rat.top_position_ls
_orig_taker_ls = rat.taker_ls
_orig_position_ls = rat.position_ls_timeseries
_orig_premium_index = fnd.premium_index
_orig_funding_hist = fnd.funding_hist
_orig_list_universe = uni.list_universe
_orig_ticker_24hr = sb._ticker_24hr

def generate_mock_candles(symbol: str, interval: str, limit: int = 300) -> list[dict]:
    now_ns = int(time.time() * 1000) * 1_000_000
    period_ns = 60 * 1_000_000_000  # 1m default
    if interval == "1h":
        period_ns = 3600 * 1_000_000_000
    elif interval == "5m":
        period_ns = 300 * 1_000_000_000
    elif interval == "12h":
        period_ns = 12 * 3600 * 1_000_000_000
    elif interval == "1d":
        period_ns = 24 * 3600 * 1_000_000_000
    
    candles = []
    base_price = 50000.0
    if "ETH" in symbol:
        base_price = 3000.0
    elif "SOL" in symbol:
        base_price = 150.0

    is_rising = "RISING" in symbol
    is_falling = "FALLING" in symbol
    is_flat = "FLAT" in symbol
    is_corrupt = "CORRUPT" in symbol
    is_short = "SHORT" in symbol
    
    length = 10 if is_short else limit
    
    for i in range(length):
        t = now_ns - (length - 1 - i) * period_ns
        if is_rising:
            price = base_price + i * 10.0
        elif is_falling:
            price = base_price - i * 10.0
        elif is_flat:
            price = base_price
        else:
            price = base_price + i * 2.0
            
        if is_corrupt and i % 10 == 0:
            price = float('nan')
            
        candles.append({
            "t": t,
            "o": price - 1.0,
            "h": price + 2.0,
            "l": price - 2.0,
            "c": price,
            "v": 100.0 + i
        })
    return candles

def generate_mock_ratios(symbol: str, period: str, limit: int = 48) -> dict[str, list[float]]:
    is_rising = "RISING" in symbol
    is_falling = "FALLING" in symbol
    is_flat = "FLAT" in symbol
    is_short = "SHORT" in symbol
    
    length = 10 if is_short else limit
    pos = []
    acc = []
    glob = []
    taker = []
    
    for i in range(length):
        if is_rising:
            p = 1.9 - (i / length) * 0.5
        elif is_falling:
            p = 1.0 + (i / length) * 0.5
        elif is_flat:
            p = 1.5
        else:
            p = 1.2 + (i / length) * 0.1
        
        pos.append(p)
        acc.append(p - 0.1)
        glob.append(p - 0.2)
        taker.append(p + 0.1)
        
    return {"pos": pos, "acc": acc, "glob": glob, "taker": taker}

def generate_mock_position_ls_timeseries(symbol: str, period: str, limit: int = 60) -> dict[str, list]:
    now_ms = int(time.time() * 1000)
    period_ms = 3600 * 1000
    if period == "5m":
        period_ms = 300 * 1000
    elif period == "12h":
        period_ms = 12 * 3600 * 1000
    elif period == "1d":
        period_ms = 24 * 3600 * 1000

    is_rising = "RISING" in symbol
    is_falling = "FALLING" in symbol
    is_flat = "FLAT" in symbol
    is_short = "SHORT" in symbol
    
    length = 10 if is_short else limit
    ts = []
    vs = []
    
    for i in range(length):
        t = now_ms - (length - 1 - i) * period_ms
        if is_rising:
            v = 1.9 - (i / length) * 0.5
        elif is_falling:
            v = 1.0 + (i / length) * 0.5
        elif is_flat:
            v = 1.5
        else:
            v = 1.2 + (i / length) * 0.1
        ts.append(t)
        vs.append(v)
        
    return {"t": ts, "v": vs}

def generate_mock_oi_hist(symbol: str, period: str = "5m", limit: int = 48) -> list[dict]:
    now_ns = int(time.time() * 1000) * 1_000_000
    period_ns = 300 * 1_000_000_000
    is_short = "SHORT" in symbol
    length = 10 if is_short else limit
    out = []
    for i in range(length):
        t = now_ns - (length - 1 - i) * period_ns
        out.append({
            "t": t,
            "oi": 10000.0 + i * 100.0,
            "oi_value": 500000.0 + i * 5000.0
        })
    return out

def generate_mock_funding_hist(symbol: str, limit: int = 48) -> list[dict]:
    now_ms = int(time.time() * 1000)
    period_ms = 8 * 3600 * 1000
    is_short = "SHORT" in symbol
    length = 10 if is_short else limit
    out = []
    for i in range(length):
        t = now_ms - (length - 1 - i) * period_ms
        out.append({
            "t": t,
            "funding_rate": 0.0001 + (i % 3) * 0.00005
        })
    return out

async def mock_list_universe(limit: int | None = None) -> list[dict]:
    return [
        {"s": "BTCUSDT", "name": "BTC", "price": 95000.0, "ch": 1.2, "quote_volume": 100000000.0},
        {"s": "ETHUSDT", "name": "ETH", "price": 3000.0, "ch": -0.8, "quote_volume": 50000000.0},
        {"s": "SOLUSDT", "name": "SOL", "price": 150.0, "ch": 5.4, "quote_volume": 20000000.0},
    ][:limit]

async def mock_ticker_24hr(symbol: str) -> dict:
    return {"price": 95000.0, "ch": 1.2}

@pytest.fixture(scope="session", autouse=True)
def mock_data_layer():
    def check_symbol(symbol):
        s = symbol.upper()
        if not ("BTC" in s or "ETH" in s or "SOL" in s or "KEYSTORE" in s):
            raise ValueError(f"Invalid symbol: {symbol}")

    async def mock_fetch_klines(symbol, interval, limit=300):
        check_symbol(symbol)
        return generate_mock_candles(symbol, interval, limit)
    async def mock_fetch_oi_hist(symbol, period="5m", limit=48):
        check_symbol(symbol)
        return generate_mock_oi_hist(symbol, period, limit)
    async def mock_global_account_ls(symbol, period="5m", limit=48):
        check_symbol(symbol)
        return generate_mock_ratios(symbol, period, limit)["glob"]
    async def mock_top_account_ls(symbol, period="5m", limit=48):
        check_symbol(symbol)
        return generate_mock_ratios(symbol, period, limit)["acc"]
    async def mock_top_position_ls(symbol, period="5m", limit=48):
        check_symbol(symbol)
        return generate_mock_ratios(symbol, period, limit)["pos"]
    async def mock_taker_ls(symbol, period="5m", limit=48):
        check_symbol(symbol)
        return generate_mock_ratios(symbol, period, limit)["taker"]
    async def mock_position_ls_timeseries(symbol, period, limit=60):
        check_symbol(symbol)
        return generate_mock_position_ls_timeseries(symbol, period, limit)
    async def mock_premium_index(symbol):
        check_symbol(symbol)
        return {"mark_price": 95000.0, "index_price": 94900.0, "last_funding_rate": 0.0001, "next_funding_time": 0}
    async def mock_funding_hist(symbol, limit=48):
        check_symbol(symbol)
        return generate_mock_funding_hist(symbol, limit)

    kl.fetch_klines = mock_fetch_klines
    oi_mod.fetch_oi_hist = mock_fetch_oi_hist
    rat.global_account_ls = mock_global_account_ls
    rat.top_account_ls = mock_top_account_ls
    rat.top_position_ls = mock_top_position_ls
    rat.taker_ls = mock_taker_ls
    rat.position_ls_timeseries = mock_position_ls_timeseries
    fnd.premium_index = mock_premium_index
    fnd.funding_hist = mock_funding_hist
    uni.list_universe = mock_list_universe
    sb._ticker_24hr = mock_ticker_24hr
    
    yield
    
    kl.fetch_klines = _orig_fetch_klines
    oi_mod.fetch_oi_hist = _orig_fetch_oi_hist
    rat.global_account_ls = _orig_global_account_ls
    rat.top_account_ls = _orig_top_account_ls
    rat.top_position_ls = _orig_top_position_ls
    rat.taker_ls = _orig_taker_ls
    rat.position_ls_timeseries = _orig_position_ls
    fnd.premium_index = _orig_premium_index
    fnd.funding_hist = _orig_funding_hist
    uni.list_universe = _orig_list_universe
    sb._ticker_24hr = _orig_ticker_24hr

def get_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]

@pytest.fixture(scope="session")
def server_port():
    return get_free_port()

@pytest.fixture(scope="session")
def server_url(server_port):
    yield f"http://127.0.0.1:{server_port}"
