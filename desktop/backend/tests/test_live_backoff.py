"""/api/live rebuild sharing + failure backoff: a fresh build is shared across
connections (4s freshness), and a failing symbol backs off exponentially (capped
at 60s) instead of re-hitting upstream on every 5s cadence tick. Success resets
the backoff streak.

The tests shrink the 5s cadence sleep so sessions tear down quickly; backoff
windows are patched to milliseconds and waited out with a (real) thread sleep.
"""

import asyncio
import time
from unittest.mock import AsyncMock, patch

import pytest

from diveintocrypto_desktop.api.app import create_app
from fastapi.testclient import TestClient

_ERR = {"error": "live_fetch_failed", "symbol": "BTCUSDT"}


@pytest.fixture(autouse=True)
def _fast_cadence(monkeypatch):
    """Shrink the /api/live 5s cadence (and any in-test await) to ~10ms ticks."""
    real_sleep = asyncio.sleep

    async def fast_sleep(seconds, *args, **kwargs):
        await real_sleep(min(float(seconds), 0.01), *args, **kwargs)

    monkeypatch.setattr(asyncio, "sleep", fast_sleep)


@pytest.fixture(autouse=True)
def _short_backoff(monkeypatch):
    monkeypatch.setattr("diveintocrypto_desktop.api.app._LIVE_BACKOFF_BASE", 0.01)


def test_live_backoff_suppresses_upstream_attempts_within_window(monkeypatch):
    """Two connections inside the (default 2s) backoff window → exactly ONE
    upstream attempt, even though the watcher keeps ticking behind the scenes."""
    monkeypatch.setattr("diveintocrypto_desktop.api.app._LIVE_BACKOFF_BASE", 2.0)
    build = AsyncMock(side_effect=RuntimeError("binance offline"))
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", build):
        with TestClient(create_app()) as client:
            with client.websocket_connect("/api/live") as ws:
                assert ws.receive_json() == _ERR
            with client.websocket_connect("/api/live") as ws:
                assert ws.receive_json() == _ERR  # resent from cache, no new attempt
            assert build.await_count == 1


def test_live_resumes_attempts_after_backoff_expires():
    build = AsyncMock(side_effect=[RuntimeError("binance offline"),
                                   {"s": "BTCUSDT", "finalSignal": "BUY", "confidence": 80}])
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", build):
        with TestClient(create_app()) as client:
            with client.websocket_connect("/api/live") as ws:
                assert ws.receive_json() == _ERR
            time.sleep(0.1)  # backoff window (10ms) elapses while disconnected
            with client.websocket_connect("/api/live") as ws:
                frame = ws.receive_json()
            assert frame["s"] == "BTCUSDT" and frame["finalSignal"] == "BUY"
            assert build.await_count == 2


def test_live_success_resets_streak_and_shares_fresh_object():
    build = AsyncMock(side_effect=[RuntimeError("binance offline"),
                                   {"s": "BTCUSDT", "finalSignal": "BUY", "confidence": 80}])
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", build):
        with TestClient(create_app()) as client:
            with client.websocket_connect("/api/live") as ws:
                assert ws.receive_json() == _ERR
            time.sleep(0.1)  # let the short backoff expire
            with client.websocket_connect("/api/live") as ws:
                assert ws.receive_json()["s"] == "BTCUSDT"  # attempt 2 succeeds
            with client.websocket_connect("/api/live") as ws:
                # Freshly built (<4s ago) → shared from cache, zero new attempts.
                assert ws.receive_json()["s"] == "BTCUSDT"
            assert build.await_count == 2


def test_live_shares_one_fresh_build_across_connections():
    build = AsyncMock(return_value={"s": "BTCUSDT", "finalSignal": "SELL", "confidence": 40})
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", build):
        with TestClient(create_app()) as client:
            with client.websocket_connect("/api/live") as ws:
                f1 = ws.receive_json()
            with client.websocket_connect("/api/live") as ws:
                f2 = ws.receive_json()
            assert f1 == f2
            assert build.await_count == 1
