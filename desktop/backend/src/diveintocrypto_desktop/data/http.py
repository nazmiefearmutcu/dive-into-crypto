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
_RATIO_LIMITER = AsyncLimiter(max_rate=40, time_period=60)

_session: aiohttp.ClientSession | None = None
_session_lock = asyncio.Lock()


async def get_session() -> aiohttp.ClientSession:
    """Return a lazily-created process-wide aiohttp session."""
    global _session
    if _session is None or _session.closed:
        async with _session_lock:
            if _session is None or _session.closed:
                timeout = aiohttp.ClientTimeout(total=20)
                _session = aiohttp.ClientSession(timeout=timeout)
    return _session


async def close_session() -> None:
    global _session
    if _session is not None and not _session.closed:
        await _session.close()
    _session = None


async def get_json(url: str, params: dict[str, Any] | None = None, *, rate_limited: bool = False) -> Any:
    """GET a JSON document. Set ``rate_limited`` for futures/data endpoints."""
    session = await get_session()
    if rate_limited:
        async with _RATIO_LIMITER:
            async with session.get(url, params=params) as resp:
                resp.raise_for_status()
                return await resp.json()
    async with session.get(url, params=params) as resp:
        resp.raise_for_status()
        return await resp.json()
