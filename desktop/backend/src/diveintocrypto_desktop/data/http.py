"""Shared HTTP plumbing for Binance USDT-M public endpoints.

All endpoints used here are PUBLIC market data — no API key, no auth, nothing to
sign. A single shared aiohttp session + a conservative rate limiter keep the
full-universe scan inside Binance's weight budget. Transient failures (429/5xx)
are retried with jittered exponential backoff, honoring ``Retry-After``.

Geo-restriction insurance: the host defaults to ``https://fapi.binance.com`` and
can be repointed (e.g. to a reachable mirror) via ``DIVE_FAPI_BASE`` — the single
place the base URL is defined.
"""

from __future__ import annotations

import asyncio
import os
import random
from typing import Any, Awaitable, Callable, TypeVar

import aiohttp
from aiolimiter import AsyncLimiter

# USDT-M futures REST roots (override the host via DIVE_FAPI_BASE if needed).
FAPI_BASE = os.environ.get("DIVE_FAPI_BASE", "https://fapi.binance.com").rstrip("/")
FAPI_V1 = f"{FAPI_BASE}/fapi/v1"
FAPI_DATA = FAPI_BASE  # /futures/data/* lives directly under the host

# Binance futures/data ("long-short", OI hist) endpoints are weight-limited far
# more tightly than klines; 40 requests / 60s is a safe shared ceiling.
_RATIO_LIMITERS: dict[asyncio.AbstractEventLoop, AsyncLimiter] = {}

# Retry policy for transient upstream failures (429 / 5xx).
_RETRYABLE_STATUSES = {429, 500, 502, 503, 504}
_MAX_RETRIES = 2  # retries AFTER the first attempt (3 attempts total)
_BACKOFF_BASE = 0.5
_BACKOFF_CAP = 8.0

T = TypeVar("T")


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


class TransientUpstreamError(Exception):
    """A retryable upstream failure (429/5xx), carrying ``Retry-After`` seconds."""

    def __init__(self, status: int, retry_after: float | None = None) -> None:
        self.status = status
        self.retry_after = retry_after
        super().__init__(f"upstream {status}")


def retry_delay(attempt: int, retry_after: float | None = None) -> float:
    """Backoff before retry ``attempt`` (0-based). Honors Retry-After when present."""
    if retry_after is not None and retry_after > 0:
        return min(float(retry_after), 60.0)
    jittered = _BACKOFF_BASE * (2**attempt) * (0.5 + random.random())
    return min(jittered, _BACKOFF_CAP)


async def run_with_retries(
    send: Callable[[], Awaitable[T]],
    *,
    should_retry: Callable[[Exception], bool] | None = None,
    sleep: Callable[[float], Awaitable[None]] | None = None,
    max_retries: int = _MAX_RETRIES,
) -> T:
    """Run ``send()`` retrying transient failures with jittered exponential backoff.

    ``should_retry`` decides which exceptions are transient (default: only
    :class:`TransientUpstreamError`). The last exception propagates when retries
    are exhausted.
    """
    should_retry = should_retry or (lambda e: isinstance(e, TransientUpstreamError))
    sleep = sleep or asyncio.sleep  # late-bound so tests can intercept it
    last: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return await send()
        except Exception as e:  # noqa: BLE001 - classified by should_retry
            if not should_retry(e) or attempt >= max_retries:
                raise
            last = e
            await sleep(retry_delay(attempt, getattr(e, "retry_after", None)))
    raise last  # pragma: no cover - loop always returns or raises


def _retry_after_seconds(resp: aiohttp.ClientResponse) -> float | None:
    raw = resp.headers.get("Retry-After")
    if raw is None:
        return None
    try:
        return float(raw)
    except ValueError:
        return None  # HTTP-date form: fall back to exponential backoff


async def get_json(url: str, params: dict[str, Any] | None = None, *, rate_limited: bool = False) -> Any:
    """GET a JSON document with transient-failure retries.

    Set ``rate_limited`` for futures/data endpoints (shared 40/60s limiter).
    """

    async def send() -> Any:
        session = await get_session()
        if rate_limited:
            loop = asyncio.get_running_loop()
            if loop not in _RATIO_LIMITERS:
                _RATIO_LIMITERS[loop] = AsyncLimiter(max_rate=40, time_period=60)
            limiter = _RATIO_LIMITERS[loop]
            async with limiter:
                return await _read_json(session, url, params)
        return await _read_json(session, url, params)

    return await run_with_retries(send)


async def _read_json(session: aiohttp.ClientSession, url: str, params: dict[str, Any] | None) -> Any:
    async with session.get(url, params=params) as resp:
        if resp.status in _RETRYABLE_STATUSES:
            raise TransientUpstreamError(resp.status, _retry_after_seconds(resp))
        resp.raise_for_status()
        return await resp.json()


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
