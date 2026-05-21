"""Tests for the S3 LivePriceService seam.

The bot loop has been confusing 'decision/signal price' (last close of the
4h candle the indicators saw) with 'live/display price' (what the screen
should reflect). LivePriceService is the boundary that fixes this:

  - It exposes the live tick / mark / bid-ask separately from the close.
  - It owns price freshness (`price_age_ms`).
  - It has a fake adapter for tests so we never touch Binance from CI.
  - The REST adapter is the production "rescue" transport this session;
    a real websocket is a future adapter (the seam exists so adding WS
    is a drop-in swap, not a rewrite).

These tests pin that contract.
"""

import time
from datetime import datetime, timezone, timedelta

import pytest

from src.market.live_price_service import (
    LivePriceService,
    FakePriceAdapter,
    PriceSnapshot,
    RestPriceAdapter,
)


def _utc(secs_ago: float = 0) -> datetime:
    return datetime.now(timezone.utc) - timedelta(seconds=secs_ago)


# ── PriceSnapshot ───────────────────────────────────────────────────


class TestPriceSnapshot:
    def test_age_ms_grows_with_clock(self):
        # Fixed fetched_at, ask for age — should be non-negative and roughly
        # proportional to the seconds elapsed since fetched_at.
        fetched = datetime.now(timezone.utc) - timedelta(milliseconds=250)
        snap = PriceSnapshot(
            symbol="BTCUSDT", price=65000.0, mark_price=None,
            best_bid=None, best_ask=None,
            fetched_at=fetched, source="fake",
        )
        # Allow a generous upper bound for slow CI hosts.
        assert 200 <= snap.age_ms <= 5000

    def test_snapshot_serializes_to_dict(self):
        fetched = datetime.now(timezone.utc)
        snap = PriceSnapshot(
            symbol="BTCUSDT", price=65000.0, mark_price=65010.0,
            best_bid=64999.0, best_ask=65001.0,
            fetched_at=fetched, source="fake",
        )
        d = snap.to_dict()
        assert d["symbol"] == "BTCUSDT"
        assert d["price"] == 65000.0
        assert d["mark_price"] == 65010.0
        assert d["best_bid"] == 64999.0
        assert d["best_ask"] == 65001.0
        assert d["source"] == "fake"
        # fetched_at must be ISO 8601 so it survives JSON round-trips
        datetime.fromisoformat(d["fetched_at"])


# ── FakePriceAdapter ───────────────────────────────────────────────


class TestFakePriceAdapter:
    def test_fetch_returns_none_for_unset_symbol(self):
        a = FakePriceAdapter()
        assert a.fetch("UNKNOWN") is None

    def test_fetch_returns_set_price(self):
        a = FakePriceAdapter()
        a.set("BTCUSDT", price=65000.0)
        snap = a.fetch("BTCUSDT")
        assert snap is not None
        assert snap.symbol == "BTCUSDT"
        assert snap.price == 65000.0
        assert snap.source == "fake"

    def test_fetch_carries_bid_ask_and_mark(self):
        a = FakePriceAdapter()
        a.set("BTCUSDT", price=65000.0, mark_price=65005.0,
              best_bid=64999.5, best_ask=65000.5)
        snap = a.fetch("BTCUSDT")
        assert snap.mark_price == 65005.0
        assert snap.best_bid == 64999.5
        assert snap.best_ask == 65000.5


# ── LivePriceService ─────────────────────────────────────────────


class TestLivePriceServiceFreshness:
    def test_subscribe_is_a_noop_for_rest_adapter(self):
        """subscribe_symbol() exists today as a forward hook for the future WS
        adapter. With the REST adapter it must be a side-effect-free no-op —
        never raise, never block, never touch the network."""
        svc = LivePriceService(FakePriceAdapter())
        svc.subscribe_symbol("BTCUSDT")
        svc.subscribe_symbol("BTCUSDT", market_type="futures")
        # idempotent
        svc.subscribe_symbol("BTCUSDT")
        # Still nothing cached because refresh wasn't called.
        assert svc.latest_price("BTCUSDT") is None

    def test_latest_price_empty_cache_returns_none(self):
        svc = LivePriceService(FakePriceAdapter())
        assert svc.latest_price("BTCUSDT") is None
        assert svc.latest_bid_ask("BTCUSDT") == (None, None)
        assert svc.latest_mark_price("BTCUSDT") is None
        assert svc.price_age_ms("BTCUSDT") is None
        assert svc.snapshot("BTCUSDT") is None

    def test_refresh_populates_cache(self):
        adapter = FakePriceAdapter()
        adapter.set("BTCUSDT", price=65000.0, mark_price=65010.0,
                    best_bid=64999.0, best_ask=65001.0)
        svc = LivePriceService(adapter)

        snap = svc.refresh("BTCUSDT")

        assert snap is not None
        assert svc.latest_price("BTCUSDT") == 65000.0
        assert svc.latest_mark_price("BTCUSDT") == 65010.0
        assert svc.latest_bid_ask("BTCUSDT") == (64999.0, 65001.0)
        assert svc.snapshot("BTCUSDT") is snap
        age = svc.price_age_ms("BTCUSDT")
        assert age is not None and age >= 0

    def test_refresh_returns_none_when_adapter_returns_none(self):
        svc = LivePriceService(FakePriceAdapter())
        # Adapter has no data for the symbol
        assert svc.refresh("UNKNOWN") is None
        assert svc.latest_price("UNKNOWN") is None

    def test_refresh_failure_does_not_wipe_existing_cache(self):
        """If a poll fails we must keep the last good snapshot so the UI can
        render an honest 'snapshot' state rather than dropping to 'unavailable'
        the moment one REST call hiccups."""
        adapter = FakePriceAdapter()
        adapter.set("BTCUSDT", price=65000.0)
        svc = LivePriceService(adapter)
        svc.refresh("BTCUSDT")

        # Adapter now fails for the same symbol.
        adapter.clear("BTCUSDT")
        svc.refresh("BTCUSDT")

        # Cache still holds the last good price.
        assert svc.latest_price("BTCUSDT") == 65000.0
        # But age keeps growing.
        assert svc.price_age_ms("BTCUSDT") is not None

    def test_set_age_via_fake_clock_for_stale_test(self):
        """A test can dial the adapter's clock backwards to make a snapshot
        look stale on demand."""
        clock_now = [datetime.now(timezone.utc)]
        svc = LivePriceService(FakePriceAdapter(), clock=lambda: clock_now[0])

        adapter = svc.adapter  # type: ignore[attr-defined]
        adapter.set("BTCUSDT", price=65000.0,
                    fetched_at=clock_now[0] - timedelta(seconds=10))
        svc.refresh("BTCUSDT")

        # Advance the service clock by 1 second; age should reflect 11s, not 1s
        clock_now[0] = clock_now[0] + timedelta(seconds=1)
        age = svc.price_age_ms("BTCUSDT")
        assert age is not None and age >= 10_000


class TestLivePriceServiceMultipleSymbols:
    def test_caches_are_independent(self):
        adapter = FakePriceAdapter()
        adapter.set("BTCUSDT", price=65000.0)
        adapter.set("ETHUSDT", price=3500.0)
        svc = LivePriceService(adapter)

        svc.refresh("BTCUSDT")
        svc.refresh("ETHUSDT")

        assert svc.latest_price("BTCUSDT") == 65000.0
        assert svc.latest_price("ETHUSDT") == 3500.0

    def test_refresh_one_symbol_does_not_touch_other(self):
        adapter = FakePriceAdapter()
        adapter.set("BTCUSDT", price=65000.0)
        svc = LivePriceService(adapter)
        svc.refresh("BTCUSDT")

        first_age = svc.price_age_ms("BTCUSDT")
        # Bump time forward a hair, then refresh ETH
        time.sleep(0.02)
        adapter.set("ETHUSDT", price=3500.0)
        svc.refresh("ETHUSDT")

        second_age = svc.price_age_ms("BTCUSDT")
        # BTC age must be >= initial age — wasn't re-fetched
        assert second_age is not None and first_age is not None
        assert second_age >= first_age


# ── RestPriceAdapter ────────────────────────────────────────────────


class _StubBinanceClient:
    def __init__(self, price: float | None):
        self.price = price
        self.calls = 0

    def get_ticker_price(self, symbol: str):
        self.calls += 1
        return self.price


class TestRestPriceAdapter:
    def test_fetch_uses_binance_ticker(self):
        client = _StubBinanceClient(price=65000.0)
        a = RestPriceAdapter(client)
        snap = a.fetch("BTCUSDT")
        assert snap is not None
        assert snap.price == 65000.0
        assert snap.symbol == "BTCUSDT"
        assert snap.source.startswith("rest:")
        # mark_price / bid / ask are NOT available via simple ticker —
        # the field MUST be None to keep the API honest (do not fake them).
        assert snap.mark_price is None
        assert snap.best_bid is None
        assert snap.best_ask is None
        assert client.calls == 1

    def test_fetch_returns_none_on_missing_price(self):
        client = _StubBinanceClient(price=None)
        a = RestPriceAdapter(client)
        assert a.fetch("BTCUSDT") is None


# ── Honesty contract ────────────────────────────────────────────────


class TestHonestyContract:
    def test_module_docstring_marks_ws_as_future(self):
        """Code must NOT pretend there is a production WebSocket transport.
        The module docstring must explicitly call out that REST polling is
        the active rescue and WS is a future adapter."""
        import src.market.live_price_service as mod
        text = (mod.__doc__ or "").lower()
        assert "rest" in text and "poll" in text
        assert "websocket" in text or "ws" in text
        assert "future" in text

    def test_rest_adapter_source_string_says_rest(self):
        """source label must say 'rest:...' so the dashboard can render an
        honest transport tag rather than something that looks like WS."""
        a = RestPriceAdapter(_StubBinanceClient(price=1.0))
        snap = a.fetch("ANY")
        assert snap is not None
        assert snap.source.startswith("rest:")
