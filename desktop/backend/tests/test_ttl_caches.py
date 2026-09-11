"""TTL caches: universe (30s, shared by /api/universe + /api/leaders + scans) and
/api/symbol (10s per symbol). Error responses are never cached.
"""

from unittest.mock import AsyncMock, patch

import pytest

from diveintocrypto_desktop.api.app import create_app
from diveintocrypto_desktop.data import universe as uni
from fastapi.testclient import TestClient


@pytest.fixture(autouse=True)
def _clean_universe_cache():
    uni.reset_universe_cache()
    yield
    uni.reset_universe_cache()


def _rows(n: int) -> list[dict]:
    return [
        {"s": f"SYM{i}USDT", "name": f"S{i}", "price": 1.0 + i, "ch": float(i), "quote_volume": 1000.0 - i}
        for i in range(n)
    ]


def _counter_mock(rows: list[dict]) -> tuple[AsyncMock, list]:
    calls: list[int] = []

    async def counted(limit=None):
        calls.append(1)
        return rows

    return AsyncMock(side_effect=counted), calls


@pytest.mark.asyncio
async def test_list_universe_served_from_ttl_cache(monkeypatch):
    mock, calls = _counter_mock(_rows(3))
    monkeypatch.setattr(uni, "_fetch_universe", mock)
    monkeypatch.setattr(uni, "_UNIVERSE_TTL", 30.0)

    first = await uni.list_universe(limit=2)
    second = await uni.list_universe()
    third = await uni.list_universe(limit=1)

    assert len(calls) == 1, "second/third reads must be served from the cache"
    assert [r["s"] for r in first] == ["SYM0USDT", "SYM1USDT"]
    assert len(second) == 3 and len(third) == 1  # limit slices the cached full list
    assert mock.await_count == 1  # exactly one upstream fetch for all three reads


@pytest.mark.asyncio
async def test_list_universe_refetches_after_ttl_expiry(monkeypatch):
    mock, calls = _counter_mock(_rows(2))
    monkeypatch.setattr(uni, "_fetch_universe", mock)
    monkeypatch.setattr(uni, "_UNIVERSE_TTL", 0.0)  # every read is stale

    await uni.list_universe()
    await uni.list_universe()

    assert len(calls) == 2


@pytest.mark.asyncio
async def test_list_universe_never_caches_errors(monkeypatch):
    attempts: list[int] = []

    async def flaky(limit=None):
        attempts.append(1)
        if len(attempts) == 1:
            raise RuntimeError("binance unreachable")
        return _rows(2)

    monkeypatch.setattr(uni, "_fetch_universe", flaky)
    monkeypatch.setattr(uni, "_UNIVERSE_TTL", 30.0)

    with pytest.raises(RuntimeError):
        await uni.list_universe()
    rows = await uni.list_universe()  # failed refresh must not poison the cache

    assert rows[0]["s"] == "SYM0USDT" and len(attempts) == 2


def test_symbol_endpoint_caches_success_for_ttl():
    app = create_app()
    build = AsyncMock(return_value={"s": "BTCUSDT", "finalSignal": "BUY", "confidence": 75})
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", build):
        with TestClient(app) as client:
            r1 = client.get("/api/symbol/BTCUSDT")
            r2 = client.get("/api/symbol/BTCUSDT")
            assert r1.status_code == r2.status_code == 200
            assert r1.json() == r2.json()
            assert build.await_count == 1  # second hit served from the 10s cache


def test_symbol_endpoint_does_not_cache_errors():
    app = create_app()
    build = AsyncMock(side_effect=[RuntimeError("binance down"),
                                   {"s": "BTCUSDT", "finalSignal": "BUY", "confidence": 75}])
    with patch("diveintocrypto_desktop.api.app.sb.build_symbol", build):
        with TestClient(app) as client:
            r1 = client.get("/api/symbol/BTCUSDT")
            r2 = client.get("/api/symbol/BTCUSDT")
            assert r1.status_code == 502
            assert r2.status_code == 200  # the 502 must not be cached
            assert build.await_count == 2
