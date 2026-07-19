"""Futures-microstructure overlay — a scan-layer signal bundle over the
already-assembled ``series_data`` (OI / funding / taker / L-S). Companion to
``scan/divergence.py``: it does NOT touch the parity-locked 15-indicator consensus
or the whale-divergence verdict. It is purely additive — ``assemble()`` attaches its
result to the per-symbol contract as a ``microstructure`` field for the UI/analytics.

Every signal is a pure function over aligned float lists, strictly causal (reads only
the closed window), and returns a value in [-1, +1] (+ bullish / − bearish) or None
when there is not enough data. The bundle score is the weighted mean of the available
signals, mapped to a signed −100..+100 and a BUY/SELL/NEUTRAL label.

Data (from ``symbol_builder.build_symbol``; ~48 points at 5m cadence):
  oi      top-of-window open interest (base contracts)
  funding funding-rate history (fraction, e.g. 0.0001)
  taker   taker buy/sell ratio (≈1.0 balanced; >1 = buy aggression)
  glob    global account long/short ratio (retail / "dumb money")
  pos     top-trader position long/short ratio ("smart money")
  price   5m close series
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Params:
    window: int = 24          # bars considered for trend/z-score
    min_points: int = 8       # minimum series length to compute a signal
    z_cap: float = 3.0        # z-scores clamped to +/- this before scaling
    buy_threshold: float = 20.0   # |score| >= -> directional label
    strong_threshold: float = 55.0
    # per-signal blend weights (only the available ones are used)
    w_oi_div: float = 1.0
    w_oi_breakout: float = 1.0
    w_funding_fade: float = 1.0
    w_taker: float = 1.0
    w_crowding: float = 1.0
    w_smart_dumb: float = 0.8


# ── small numeric helpers (stdlib only) ──────────────────────────────────────
def _clean(x: list[float] | None) -> list[float]:
    if not x:
        return []
    out: list[float] = []
    for v in x:
        try:
            f = float(v)
        except (TypeError, ValueError):
            continue
        if f == f and f not in (float("inf"), float("-inf")):  # drop NaN/Inf
            out.append(f)
    return out


def _mean(a: list[float]) -> float:
    return sum(a) / len(a) if a else 0.0


def _std(a: list[float]) -> float:
    n = len(a)
    if n < 2:
        return 0.0
    m = _mean(a)
    return (sum((v - m) ** 2 for v in a) / (n - 1)) ** 0.5


def _clip(x: float, lo: float = -1.0, hi: float = 1.0) -> float:
    return lo if x < lo else hi if x > hi else x


def _sign(x: float) -> int:
    return 1 if x > 0 else -1 if x < 0 else 0


def _pct_change(a: list[float]) -> float:
    """Signed relative change first→last over the window; 0 if degenerate."""
    if len(a) < 2 or a[0] == 0.0:
        return 0.0
    return (a[-1] - a[0]) / abs(a[0])


def _zscore(a: list[float], cap: float) -> float:
    """Z-score of the last point vs the preceding window; clamped to +/- cap."""
    if len(a) < 3:
        return 0.0
    hist = a[:-1]
    s = _std(hist)
    if s == 0.0:
        return 0.0
    return _clip((a[-1] - _mean(hist)) / s, -cap, cap)


# ── individual signals: each returns (value in [-1,1] | None, reason) ─────────
def _oi_price_divergence(oi: list[float], price: list[float], p: Params):
    n = min(len(oi), len(price))
    if n < p.min_points:
        return None, "oi/price insufficient"
    oi_w, pr_w = oi[-p.window:], price[-p.window:]
    d_oi, d_pr = _pct_change(oi_w), _pct_change(pr_w)
    if abs(d_pr) < 0.002:
        return 0.0, "price flat"
    # OI rising WITH the move = real money confirms trend (+ in trend direction);
    # OI falling while price moves = short-covering / weak move -> fade the move.
    confirm = 1.0 if d_oi > 0 else -1.0
    mag = _clip(abs(d_pr) / 0.03)
    return _clip(_sign(d_pr) * confirm * mag), f"dPr={d_pr:+.3f} dOI={d_oi:+.3f}"


def _oi_breakout_confirm(oi: list[float], price: list[float], p: Params):
    n = min(len(oi), len(price))
    if n < p.min_points:
        return None, "oi/price insufficient"
    oi_w, pr_w = oi[-p.window:], price[-p.window:]
    d_oi, d_pr = _pct_change(oi_w), _pct_change(pr_w)
    if abs(d_pr) < 0.01 or d_oi <= 0:  # only fires on a real move backed by OI expansion
        return 0.0, "no OI-backed breakout"
    return _clip(_sign(d_pr) * _clip(d_oi / 0.05)), f"breakout dPr={d_pr:+.3f} dOI={d_oi:+.3f}"


def _funding_fade(funding: list[float], p: Params):
    f = funding[-p.window:]
    if len(f) < 3:
        return None, "funding insufficient"
    z = _zscore(f, p.z_cap)
    if z == 0.0:
        return 0.0, "funding neutral"
    # Extreme positive funding = crowded longs pay shorts -> contrarian bearish.
    return _clip(-z / p.z_cap), f"funding z={z:+.2f}"


def _taker_aggression(taker: list[float], p: Params):
    t = taker[-p.window:]
    if len(t) < p.min_points:
        return None, "taker insufficient"
    recent = _mean(t[-4:]) if len(t) >= 4 else _mean(t)
    level = _clip((recent - 1.0) / 0.30)          # >1 buy pressure, <1 sell pressure
    trend = _clip(_pct_change(t) / 0.10)
    return _clip(0.6 * level + 0.4 * trend), f"taker~{recent:.3f}"


def _ls_crowding_fade(glob: list[float], p: Params):
    g = glob[-p.window:]
    if len(g) < 3:
        return None, "glob insufficient"
    z = _zscore(g, p.z_cap)
    if z == 0.0:
        return 0.0, "crowd neutral"
    # Retail account L/S extreme = crowded -> contrarian.
    return _clip(-z / p.z_cap), f"crowd z={z:+.2f}"


def _smart_dumb_spread(pos: list[float], glob: list[float], p: Params):
    n = min(len(pos), len(glob))
    if n < p.min_points:
        return None, "pos/glob insufficient"
    spread = _mean(pos[-p.window:]) - _mean(glob[-p.window:])
    if abs(spread) < 0.02:
        return 0.0, "spread flat"
    # Smart money (top-trader positions) more long than retail accounts -> bullish.
    return _clip(spread / 0.5), f"smart-dumb={spread:+.3f}"


_SIGNALS = (
    ("oi_price_divergence", "w_oi_div"),
    ("oi_breakout_confirm", "w_oi_breakout"),
    ("funding_fade", "w_funding_fade"),
    ("taker_aggression", "w_taker"),
    ("ls_crowding_fade", "w_crowding"),
    ("smart_dumb_spread", "w_smart_dumb"),
)


def evaluate(series_data: dict, enabled: bool = True, params: Params = Params()) -> dict:
    """Run the microstructure signal bundle over ``series_data``.

    Returns ``{score, direction, label, active, signals:[{name,score,reason,weight}]}``.
    ``score`` is a signed −100..+100 (+bullish/−bearish); ``active`` = signals with data.
    Safe on missing/short series (those signals are skipped). ``enabled=False`` short-circuits.
    """
    if not enabled:
        return {"score": 0.0, "direction": 0, "label": "OFF", "active": 0, "signals": []}

    oi = _clean(series_data.get("oi"))
    price = _clean(series_data.get("price"))
    funding = _clean(series_data.get("funding"))
    taker = _clean(series_data.get("taker"))
    glob = _clean(series_data.get("glob"))
    pos = _clean(series_data.get("pos"))

    computed = {
        "oi_price_divergence": _oi_price_divergence(oi, price, params),
        "oi_breakout_confirm": _oi_breakout_confirm(oi, price, params),
        "funding_fade": _funding_fade(funding, params),
        "taker_aggression": _taker_aggression(taker, params),
        "ls_crowding_fade": _ls_crowding_fade(glob, params),
        "smart_dumb_spread": _smart_dumb_spread(pos, glob, params),
    }

    signals: list[dict] = []
    wsum = 0.0
    acc = 0.0
    for name, wattr in _SIGNALS:
        val, reason = computed[name]
        if val is None:
            continue
        w = getattr(params, wattr)
        signals.append({"name": name, "score": round(val, 4), "reason": reason, "weight": w})
        acc += val * w
        wsum += w

    if wsum == 0.0:
        return {"score": 0.0, "direction": 0, "label": "NEUTRAL", "active": 0, "signals": []}

    score = round(_clip(acc / wsum) * 100.0, 1)
    mag = abs(score)
    if mag < params.buy_threshold:
        label = "NEUTRAL"
        direction = 0
    else:
        direction = _sign(score)
        strong = mag >= params.strong_threshold
        label = ("STRONG_BUY" if strong else "BUY") if direction > 0 else ("STRONG_SELL" if strong else "SELL")

    return {"score": score, "direction": direction, "label": label, "active": len(signals), "signals": signals}
