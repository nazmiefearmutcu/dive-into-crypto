"""Shared HTTP plumbing for Binance USDT-M public endpoints.

All endpoints used here are PUBLIC market data — no API key, no auth, nothing to
sign. A single shared aiohttp session + a conservative rate limiter keep the
full-universe scan inside Binance's weight budget.
"""

from __future__ import annotations

import asyncio
from typing import Any

import aiohttp
from aiolimiter import AsyncLimiter

# USDT-M futures REST roots.
FAPI_V1 = "https://fapi.binance.com/fapi/v1"
FAPI_DATA = "https://fapi.binance.com"  # /futures/data/* lives directly under the host

# Binance futures/data ("long-short", OI hist) endpoints are weight-limited far
# more tightly than klines; 40 requests / 60s is a safe shared ceiling.
_RATIO_LIMITERS: dict[asyncio.AbstractEventLoop, AsyncLimiter] = {}

class LoopBoundLock:
    def __init__(self) -> None:
        self._locks: dict[asyncio.AbstractEventLoop, asyncio.Lock] = {}

    def _get_lock(self) -> asyncio.Lock:
        loop = asyncio.get_running_loop()
        if loop not in self._locks:
            self._locks[loop] = asyncio.Lock()
        return self._locks[loop]

    async def __aenter__(self) -> None:
        await self._get_lock().__aenter__()

    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        await self._get_lock().__aexit__(exc_type, exc_val, exc_tb)

_session: aiohttp.ClientSession | None = None
_session_lock = LoopBoundLock()


async def get_session() -> aiohttp.ClientSession:
    """Return a lazily-created process-wide aiohttp session."""
    global _session
    if _session is None or _session.closed:
        async with _session_lock:
            if _session is None or _session.closed:
                timeout = aiohttp.ClientTimeout(total=30, sock_connect=10)
                connector = aiohttp.TCPConnector(limit=24, ttl_dns_cache=300)
                _session = aiohttp.ClientSession(timeout=timeout, connector=connector)
    return _session


async def close_session() -> None:
    global _session
    async with _session_lock:
        if _session is not None and not _session.closed:
            await _session.close()
        _session = None


async def get_json(url: str, params: dict[str, Any] | None = None, *, rate_limited: bool = False) -> Any:
    """GET a JSON document. Set ``rate_limited`` for futures/data endpoints."""
    session = await get_session()
    if rate_limited:
        loop = asyncio.get_running_loop()
        if loop not in _RATIO_LIMITERS:
            _RATIO_LIMITERS[loop] = AsyncLimiter(max_rate=40, time_period=60)
        limiter = _RATIO_LIMITERS[loop]
        async with limiter:
            async with session.get(url, params=params) as resp:
                resp.raise_for_status()
                return await resp.json()
    async with session.get(url, params=params) as resp:
        resp.raise_for_status()
        return await resp.json()
