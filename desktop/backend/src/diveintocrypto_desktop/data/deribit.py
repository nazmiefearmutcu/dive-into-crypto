"""Deribit options slice (BTC + ETH) — a STAGED, strictly optional surface.

The Deribit REST API is a different venue entirely: its own base URL
(``DIVE_DERIBIT_BASE``), its own short timeout and its own circuit-breaker.
When Deribit is slow or unreachable the block degrades to
``{"unavailable": "deribit_unreachable"}`` and NOTHING else in the app waits
on it — no other data path ever calls into this module's session.

Reported (from public endpoints, no auth):
  * ``dvol_level``          — the DVOL volatility index level (get_index_price);
  * ``put_call_oi_ratio``   — Σ open interest of puts / calls (all listed options);
  * ``atm_iv_30d``          — mean mark_iv of the strikes nearest the underlying
                              on the expiry nearest 30 DTE;
  * ``generated_at``.

NO skew interpolation, no surface fitting — phase 2 material, deliberately out.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from datetime import datetime, timezone
from typing import Any

import aiohttp

logger = logging.getLogger("trading_bot.data.deribit")

DERIBIT_BASE = os.environ.get("DIVE_DERIBIT_BASE", "https://www.deribit.com").rstrip("/")
_API = f"{DERIBIT_BASE}/api/v2/public"

_TIMEOUT = aiohttp.ClientTimeout(total=6.0)
BREAKER_THRESHOLD = 3      # consecutive failures before the breaker opens
BREAKER_SECONDS = 60.0     # how long the breaker stays open

_CACHE_TTL = 30.0
_cache: dict[str, tuple[float, dict]] = {}
_session: aiohttp.ClientSession | None = None
_fail_streak = 0
_open_until = 0.0


def reset_state() -> None:
    """Drop cache + breaker state (test hook)."""
    global _cache, _session, _fail_streak, _open_until
    _cache = {}
    _fail_streak = 0
    _open_until = 0.0


async def close_session() -> None:
    global _session
    if _session is not None and not _session.closed:
        await _session.close()
    _session = None


async def _get(path: str, params: dict[str, Any]) -> Any:
    """GET one Deribit public document through the module's own session."""
    global _session
    if _session is None or _session.closed:
        _session = aiohttp.ClientSession(timeout=_TIMEOUT)
    async with _session.get(f"{_API}{path}", params=params) as resp:
        resp.raise_for_status()
        body = await resp.json(content_type=None)
    if isinstance(body, dict) and body.get("error"):
        raise RuntimeError(f"deribit error: {str(body['error'])[:80]}")
    return body.get("result") if isinstance(body, dict) else body


def _note_failure() -> None:
    global _fail_streak, _open_until
    _fail_streak += 1
    if _fail_streak >= BREAKER_THRESHOLD:
        _open_until = time.monotonic() + BREAKER_SECONDS


def _note_success() -> None:
    global _fail_streak, _open_until
    _fail_streak = 0
    _open_until = 0.0


def breaker_open() -> bool:
    return time.monotonic() < _open_until


# ── pure parsers (fixture-testable) ──────────────────────────────────────────
def parse_index_price(result: Any) -> float | None:
    try:
        return float(result["price"])
    except (KeyError, TypeError, ValueError):
        return None


def _parse_instrument(name: str) -> tuple[str, float, str, datetime] | None:
    """``BTC-27SEP25-60000-P`` → ``(currency, strike, type)``; dte from expiry.

    Returns None for unparseable names. The expiry date is parsed from the
    middle token (``%d%b%y``).
    """
    parts = name.split("-")
    if len(parts) != 4 or parts[3] not in ("P", "C"):
        return None
    currency = parts[0]
    try:
        strike = float(parts[2])
        expiry = datetime.strptime(parts[1], "%d%b%y").replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    return (currency, strike, parts[3], expiry)  # type: ignore[return-value]


def parse_book_summary(rows: list, currency: str, now_ms: int | None = None) -> dict:
    """``{put_call_oi_ratio, atm_iv_30d}`` from a book-summary payload (pure).

    ``atm_iv_30d``: on the expiry nearest 30 DTE, the mean mark_iv of the
    put+call whose strikes are nearest the row's ``underlying_price``.
    """
    now_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    put_oi = call_oi = 0.0
    underlying = None
    by_expiry: dict[str, list] = {}
    for r in rows or []:
        parsed = _parse_instrument(str(r.get("instrument_name", "")))
        if parsed is None:
            continue
        cur, strike, kind, expiry = parsed
        if cur != currency:
            continue
        oi = r.get("open_interest")
        iv = r.get("mark_iv")
        try:
            oi_f = float(oi) if oi is not None else 0.0
        except (TypeError, ValueError):
            oi_f = 0.0
        try:
            iv_f = float(iv)
        except (TypeError, ValueError):
            iv_f = None  # type: ignore[assignment]
        if kind == "P":
            put_oi += oi_f
        else:
            call_oi += oi_f
        if underlying is None:
            try:
                underlying = float(r.get("underlying_price"))
            except (TypeError, ValueError):
                underlying = None
        if iv_f is not None:
            by_expiry.setdefault(expiry.strftime("%Y-%m-%d"), []).append(
                {"strike": strike, "iv": iv_f, "dte": (int(expiry.timestamp() * 1000) - now_ms) / 86_400_000.0}
            )

    atm_iv = None
    if underlying and by_expiry:
        # the expiry whose DTE is closest to 30 (needs |dte| ≤ 45 to mean it)
        best_day = min(
            by_expiry,
            key=lambda d: min(abs(o["dte"] - 30.0) for o in by_expiry[d]),
        )
        chain = by_expiry[best_day]
        best_dte = min(chain, key=lambda o: abs(o["dte"] - 30.0))["dte"]
        if abs(best_dte - 30.0) <= 15.0:
            atm = sorted(chain, key=lambda o: abs(o["strike"] - underlying))[:2]  # P + C at nearest strike
            if atm:
                atm_iv = round(sum(o["iv"] for o in atm) / len(atm), 2)

    if put_oi + call_oi <= 0:
        return {"unavailable": "no_options_data"}
    out: dict = {"put_call_oi_ratio": round(put_oi / call_oi, 4) if call_oi > 0 else None}
    if atm_iv is not None:
        out["atm_iv_30d"] = atm_iv
    return out


async def options_overview() -> dict:
    """The ``/api/options`` body: independent BTC + ETH blocks (staged slice)."""
    btc, eth = await asyncio.gather(options_snapshot("BTC"), options_snapshot("ETH"))
    return {"BTC": btc, "ETH": eth, "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}


async def options_snapshot(currency: str = "BTC") -> dict:
    """``{dvol_level, put_call_oi_ratio, atm_iv_30d, generated_at}`` for BTC/ETH.

    Circuit-breaker short-circuits to ``{"unavailable": "deribit_unreachable"}``
    while open; anything else failing reports the same honest block. When DVOL
    succeeds but the book summary fails, the real DVOL level is NOT hidden
    behind an unavailable marker: the block degrades to
    ``{dvol_level, put_call_oi_ratio: null, atm_iv_30d: null, partial: true}``.
    Errors are never cached — only successful (incl. partial) snapshots hold
    the 30s TTL cache, so a failure re-attempts on the very next call (the
    circuit breaker, not the cache, bounds the retry cadence).
    """
    now = time.monotonic()
    cached = _cache.get(currency)
    if cached and now - cached[0] < _CACHE_TTL:
        return cached[1]
    block: dict
    if breaker_open():
        return {"unavailable": "deribit_unreachable"}  # never cached
    dvol_res, summary_res = await asyncio.gather(
        _get("/get_index_price", {"index_name": f"{currency.lower()}_dvol"}),
        _get("/get_book_summary_by_currency", {"currency": currency, "kind": "option"}),
        return_exceptions=True,
    )
    dvol_err = isinstance(dvol_res, BaseException)
    summary_parsed = None if isinstance(summary_res, BaseException) \
        else parse_book_summary(summary_res or [], currency)
    summary_ok = summary_parsed is not None and "unavailable" not in summary_parsed
    if dvol_err:
        logger.warning("deribit: %s fetch failed — %s", currency, str(dvol_res)[:80])
        _note_failure()
        block = {"unavailable": "deribit_unreachable"}  # NOT cached
    elif summary_ok:
        block = {"dvol_level": parse_index_price(dvol_res), **summary_parsed,
                 "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        _note_success()
    else:
        # DVOL is real — never hide it behind an unavailable marker (partial)
        if isinstance(summary_res, BaseException):
            logger.warning("deribit: %s summary failed — %s", currency, str(summary_res)[:80])
        block = {"dvol_level": parse_index_price(dvol_res),
                 "put_call_oi_ratio": None, "atm_iv_30d": None,
                 "partial": True,
                 "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        _note_success()  # one healthy leg proves the venue is reachable
    if "unavailable" not in block:
        _cache[currency] = (time.monotonic(), block)
    return block
