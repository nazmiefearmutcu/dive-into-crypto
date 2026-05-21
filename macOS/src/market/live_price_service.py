"""LivePriceService — testable seam between live ticks and the signal stack.

Purpose
-------
S3 introduces a boundary so the dashboard can stop confusing two different
concepts of "price":

  - **signal_price**:   `df["close"].iloc[-1]` — the close of the candle the
                         decision engine just evaluated. Fixed for the life of
                         that candle (e.g. 4h on a 4h timeframe).
  - **display_price**:  what the cockpit should put on screen *now* — a fresh
                         ticker, ideally < a few seconds old. Distinct from
                         signal_price between candles.

LivePriceService is the read-only facade the rest of the app talks to. It owns
a cache of `PriceSnapshot`s keyed by symbol, fed by a swappable `adapter`.

Transport status (be explicit; do not overclaim)
------------------------------------------------
This session ships a **REST/polling rescue transport** (`RestPriceAdapter`)
backed by `binance.client.Client.get_symbol_ticker` (or
`futures_symbol_ticker`). It is not a websocket. A real websocket adapter is a
**future** addition — the `subscribe_symbol()` hook exists so wiring it in is a
drop-in swap rather than a refactor. Until that adapter lands, `subscribe_symbol`
is a side-effect-free no-op; price freshness depends on the caller invoking
`refresh()` at the cadence it cares about.

Test support
------------
`FakePriceAdapter` is the unit-test seam. It never touches the network, can be
preloaded with fixed prices, and can be paired with an injected `clock` callable
to simulate stale-cache scenarios.

API surface
-----------
  * `subscribe_symbol(symbol, market_type='spot')` — no-op today, future WS hook.
  * `refresh(symbol)`                              — pull a fresh snapshot.
  * `latest_price(symbol)`                          — last cached price.
  * `latest_bid_ask(symbol)`                        — `(bid, ask)` tuple.
  * `latest_mark_price(symbol)`                     — futures mark price.
  * `price_age_ms(symbol)`                          — age of cached snapshot.
  * `snapshot(symbol)`                              — full `PriceSnapshot`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import RLock
from typing import Any, Callable, Optional, Protocol


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class PriceSnapshot:
    """A single live-price observation for one symbol.

    Frozen so callers can pass snapshots around without worrying about
    mutation; the service replaces the whole dict entry on refresh.
    """

    symbol: str
    price: Optional[float]
    mark_price: Optional[float]
    best_bid: Optional[float]
    best_ask: Optional[float]
    fetched_at: datetime
    source: str
    # Optional clock for age computation; defaults to system UTC.
    clock: Optional[Callable[[], datetime]] = field(default=None, repr=False, compare=False)

    @property
    def age_ms(self) -> int:
        now = (self.clock or _utc_now)()
        delta = (now - self.fetched_at).total_seconds() * 1000.0
        # Negative ages (clock skew) collapse to zero so the UI never shows
        # a "−42ms" oddity.
        return max(0, int(delta))

    def to_dict(self) -> dict[str, Any]:
        return {
            "symbol": self.symbol,
            "price": self.price,
            "mark_price": self.mark_price,
            "best_bid": self.best_bid,
            "best_ask": self.best_ask,
            "fetched_at": self.fetched_at.isoformat(),
            "source": self.source,
            "age_ms": self.age_ms,
        }


class PriceAdapter(Protocol):
    """Adapter interface — fetch a fresh snapshot for one symbol."""

    def fetch(self, symbol: str) -> Optional[PriceSnapshot]:  # pragma: no cover - protocol
        ...


# ── Fake adapter (tests only) ────────────────────────────────────────


class FakePriceAdapter:
    """In-memory adapter for tests. Never touches the network."""

    def __init__(self) -> None:
        self._data: dict[str, dict[str, Any]] = {}

    def set(
        self,
        symbol: str,
        *,
        price: Optional[float],
        mark_price: Optional[float] = None,
        best_bid: Optional[float] = None,
        best_ask: Optional[float] = None,
        fetched_at: Optional[datetime] = None,
        source: str = "fake",
    ) -> None:
        self._data[symbol] = {
            "price": price,
            "mark_price": mark_price,
            "best_bid": best_bid,
            "best_ask": best_ask,
            "fetched_at": fetched_at,
            "source": source,
        }

    def clear(self, symbol: str) -> None:
        self._data.pop(symbol, None)

    def fetch(self, symbol: str) -> Optional[PriceSnapshot]:
        rec = self._data.get(symbol)
        if not rec:
            return None
        if rec["price"] is None:
            return None
        return PriceSnapshot(
            symbol=symbol,
            price=float(rec["price"]),
            mark_price=rec["mark_price"],
            best_bid=rec["best_bid"],
            best_ask=rec["best_ask"],
            fetched_at=rec["fetched_at"] or _utc_now(),
            source=rec["source"],
        )


# ── REST/polling rescue adapter (production today) ──────────────────


class RestPriceAdapter:
    """REST-poll rescue transport.

    Backed by `BinanceClient.get_ticker_price`. Only the last trade price is
    available via this endpoint — `mark_price`, `best_bid`, `best_ask` are
    deliberately left None rather than fabricated.

    A real websocket adapter is a future swap-in (see module docstring).
    """

    def __init__(self, binance_client: Any, *, source_label: str = "rest:binance") -> None:
        self._client = binance_client
        self._source = source_label

    def fetch(self, symbol: str) -> Optional[PriceSnapshot]:
        try:
            price = self._client.get_ticker_price(symbol)
        except Exception:
            return None
        if price is None:
            return None
        try:
            value = float(price)
        except (TypeError, ValueError):
            return None
        if value <= 0:
            return None
        return PriceSnapshot(
            symbol=symbol,
            price=value,
            mark_price=None,
            best_bid=None,
            best_ask=None,
            fetched_at=_utc_now(),
            source=self._source,
        )


# ── Service ───────────────────────────────────────────────────────


class LivePriceService:
    """Read-side façade over a price adapter, with an in-memory cache.

    Thread-safe: refreshes from multiple cycle/scan threads are serialized via
    an RLock around the dict mutation. Reads are also locked so the dashboard
    snapshot is internally consistent.
    """

    def __init__(
        self,
        adapter: PriceAdapter,
        *,
        clock: Optional[Callable[[], datetime]] = None,
    ) -> None:
        self.adapter = adapter
        self._clock = clock or _utc_now
        self._cache: dict[str, PriceSnapshot] = {}
        self._subscribed: set[str] = set()
        self._lock = RLock()

    # ── Subscription (forward hook for future WS adapter) ────────────

    def subscribe_symbol(self, symbol: str, market_type: str = "spot") -> None:
        """Register an interest in a symbol.

        For the REST adapter this is a side-effect-free no-op. A future WS
        adapter will use this to open subscriptions on a multiplexed socket.
        """
        with self._lock:
            self._subscribed.add(symbol)

    # ── Refresh ──────────────────────────────────────────────────────

    def refresh(self, symbol: str) -> Optional[PriceSnapshot]:
        """Pull a fresh snapshot from the adapter and update the cache.

        Returns the new snapshot on success, or None on failure. On failure
        the previous cached snapshot is preserved (so the UI can render an
        honest 'snapshot' state instead of dropping to 'unavailable' on a
        single REST hiccup).
        """
        snap = self.adapter.fetch(symbol)
        if snap is None:
            return None
        # Re-bind the service clock so `age_ms` measures relative to it.
        snap = PriceSnapshot(
            symbol=snap.symbol,
            price=snap.price,
            mark_price=snap.mark_price,
            best_bid=snap.best_bid,
            best_ask=snap.best_ask,
            fetched_at=snap.fetched_at,
            source=snap.source,
            clock=self._clock,
        )
        with self._lock:
            self._cache[symbol] = snap
        return snap

    # ── Read API ─────────────────────────────────────────────────────

    def snapshot(self, symbol: str) -> Optional[PriceSnapshot]:
        with self._lock:
            return self._cache.get(symbol)

    def latest_price(self, symbol: str) -> Optional[float]:
        snap = self.snapshot(symbol)
        return snap.price if snap else None

    def latest_bid_ask(self, symbol: str) -> tuple[Optional[float], Optional[float]]:
        snap = self.snapshot(symbol)
        if snap is None:
            return (None, None)
        return (snap.best_bid, snap.best_ask)

    def latest_mark_price(self, symbol: str) -> Optional[float]:
        snap = self.snapshot(symbol)
        return snap.mark_price if snap else None

    def price_age_ms(self, symbol: str) -> Optional[int]:
        snap = self.snapshot(symbol)
        return snap.age_ms if snap else None
