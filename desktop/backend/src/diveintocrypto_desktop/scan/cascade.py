"""Liquidation-cascade **proxy** — a pure composite over data the symbol build
already fetched (OI history, kline wicks, taker buy/sell ratio). NO liquidation
feed is consulted (Binance's forced-order stream is websocket-only and
unreliable); every field is therefore labeled a proxy, here and in the contract.

Cascade anatomy: a cascade flush is (1) OI CONTRACTING sharply (positions being
force-closed — expansion never counts; OI rising into a move is trend
continuation, not a flush) while (2) price wicks hard against the crowded side
and (3) taker flow runs one-way. The score blends the three normalized
components (0-100); ``direction`` is the side that was flushed: ``long_flush``
(price down / OI down) or ``short_flush``. ``since_min`` reports how long ago
the strongest cascade bar closed.

Pure over its inputs — no I/O, no randomness; the ONE clock read is
``since_min``, measured against the wall clock at call time (so repeated calls
on unchanged inputs may differ only in that field).
"""

from __future__ import annotations

_MIN_POINTS = 8          # minimum series length per component
_OI_DROP_REF = 0.05      # 5% OI contraction over the window → full component
_WICK_REF = 0.004        # 0.4% adverse wick per bar → full component
_TAKER_REF = 0.25        # taker ratio 0.75/1.25 deviation → full component
_STRONG_SCORE = 45.0     # |component-blend| at/above this within the window ⇒ cascade bar
_WINDOW = 24             # bars scanned for the strongest cascade bar (5m bars → 2h)


def _clean(x: list[float] | None) -> list[float]:
    out: list[float] = []
    for v in x or []:
        try:
            f = float(v)
        except (TypeError, ValueError):
            continue
        if f == f and f not in (float("inf"), float("-inf")):
            out.append(f)
    return out


def _clip(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if x < lo else hi if x > hi else x


def cascade_proxy(
    oi: list[float] | None,
    candles: list[dict] | None,
    taker: list[float] | None,
) -> dict:
    """Composite cascade score from already-fetched inputs.

    ``oi``: open-interest history (oldest→newest). ``candles``: OHLC candles
    (any cadence the caller fetched — the symbol build uses 5m) ``{t, h, l, c}``
    with ``t`` in ms or ns. ``taker``: taker buy/sell ratio (≈1 balanced).

    Returns ``{score, direction, since_min, proxy}`` or
    ``{"unavailable": reason}`` — components with insufficient data are skipped,
    a cascade needs at least the OI + price pair. ``since_min`` is measured
    against the wall clock at call time (the module's only clock read).
    """
    oi_s = _clean(oi)
    taker_s = _clean(taker)
    rows: list[dict] = []
    for c in candles or []:
        try:
            t = int(c["t"])
            h, lo, cl = float(c["h"]), float(c["l"]), float(c["c"])
        except (KeyError, TypeError, ValueError):
            continue
        if h <= 0 or cl <= 0 or lo > h:
            continue
        rows.append({"t": t, "h": h, "l": lo, "c": cl})
    rows = rows[-_WINDOW:]

    if len(oi_s) < _MIN_POINTS or len(rows) < _MIN_POINTS:
        return {"unavailable": "insufficient_history"}

    n = min(len(oi_s), len(rows))
    oi_w, rows_w = oi_s[-n:], rows[-n:]

    # (1) OI flush component — DIRECTIONAL: only CONTRACTION (negative change,
    # positions force-closing) contributes magnitude; expansion into a move is
    # trend continuation and NEVER counts as a flush. (Wick/taker legs keep
    # their magnitude-only |·| by design.)
    oi_change = (oi_w[-1] - oi_w[0]) / oi_w[0] if oi_w[0] > 0 else 0.0
    oi_mag = _clip(-oi_change / _OI_DROP_REF)
    if oi_mag <= 0.0:
        return {"unavailable": "oi_stable"}

    # (2) adverse-wick component per bar: the wick against the OI-move side
    price_down = rows_w[-1]["c"] < rows_w[0]["c"]
    wick_mags: list[float] = []
    for c in rows_w:
        rng = c["h"] - c["l"]
        if rng <= 0:
            wick_mags.append(0.0)
            continue
        # price falling → lower wick = longs flushed; rising → upper wick
        adverse = (c["c"] - c["l"]) / rng if price_down else (c["h"] - c["c"]) / rng
        wick_mags.append(_clip(adverse * ((c["h"] - c["l"]) / c["c"]) / _WICK_REF))
    wick = max(wick_mags)

    # (3) one-sided taker flow aligned with the flush direction
    taker_dev = 0.0
    if len(taker_s) >= _MIN_POINTS:
        recent = sum(taker_s[-4:]) / len(taker_s[-4:])
        taker_dev = _clip(abs(recent - 1.0) / _TAKER_REF)
        # only counts when flow runs WITH the flush (selling into a long flush)
        if price_down and recent > 1.0:
            taker_dev = 0.0
        if (not price_down) and recent < 1.0:
            taker_dev = 0.0

    score = round(100.0 * (0.5 * oi_mag + 0.3 * wick + 0.2 * taker_dev), 1)

    # the cascade bar = the bar with the max wick that still shows OI contraction
    best_i = wick_mags.index(max(wick_mags))
    strongest = rows_w[best_i]
    if score < _STRONG_SCORE:
        return {"unavailable": "below_threshold", "score": score}

    t_ms = strongest["t"] // 1_000_000 if strongest["t"] > 10**14 else strongest["t"]
    import time as _time

    since_min = max(0.0, round((_time.time() * 1000 - t_ms) / 60_000.0, 1))
    return {
        "score": score,
        "direction": "long_flush" if price_down else "short_flush",
        "since_min": since_min,
        "proxy": True,
    }
