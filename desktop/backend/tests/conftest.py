"""Shared fixtures for the desktop backend suite (offline by default)."""

from __future__ import annotations

from unittest.mock import AsyncMock

import pytest


@pytest.fixture
def block_network(monkeypatch):
    """Fail any real Binance request immediately.

    Every data module reaches the wire through ``http.get_json`` → ``http.get_session``.
    Replacing the session factory means no ``aiohttp.ClientSession`` is created (so none
    is left unclosed) and no request leaves the process.
    """
    from diveintocrypto_desktop.data import http as http_mod

    async def _blocked():
        raise RuntimeError("offline test: real HTTP is blocked")

    monkeypatch.setattr(http_mod, "get_session", _blocked)


@pytest.fixture
def offline_scan_market(monkeypatch, block_network):
    """Stub every network-touching step ``scan()`` runs after the universe and kline
    fetches a test patches itself: the BTC 1h candle lookup, the premium-index batch,
    delivery symbols, funding history, premium klines and the spot snapshot.
    """
    from diveintocrypto_desktop.data import basis as bs
    from diveintocrypto_desktop.data import funding as fnd
    from diveintocrypto_desktop.data import spot as spot_mod
    from diveintocrypto_desktop.scan import structure as st

    monkeypatch.setattr(st, "_one_hour_candles", AsyncMock(return_value=[]))
    monkeypatch.setattr(fnd, "premium_index_all", AsyncMock(return_value={}))
    monkeypatch.setattr(bs, "delivery_symbols", AsyncMock(return_value={}))
    monkeypatch.setattr(fnd, "funding_hist", AsyncMock(return_value=[]))
    monkeypatch.setattr(bs, "premium_index_klines", AsyncMock(return_value=[]))
    monkeypatch.setattr(spot_mod, "snapshot", AsyncMock(return_value={}))
