"""Replay + IC — counterfactual analysis over the evidence archive. STRICTLY
report-only: nothing here ever writes to the engine's configuration, and the
weight suggestions file is never read back by the engine (it exists so a human
can *consider* it).

Replay (:func:`replay_grid`) re-scores every archived signal vector under a
bounded counterfactual threshold grid (buy/sell × strong × conflict) using the
SHIPPED weights map, then compares each cell's hit-rate against the shipped
defaults with a seeded bootstrap and reports out-of-sample fold stability over
contiguous time folds. Only archives stamped with the CURRENT ``weights_hash``
are replayable — records written under any other weights map are reported as
skipped (mixing weight regimes would be a lie).

IC (:func:`ic_table`) correlates each indicator's archived signal with the
graded forward return (Spearman, last 200 graded events); :func:`ic_ir` adds a
signal-to-noise read over contiguous 50-event chunks; :func:`suggest_weights`
turns that into normalized, capped weight suggestions.
"""

from __future__ import annotations

import json
import logging
import math
import os
import random
import time
from pathlib import Path

from diveintocrypto_desktop.scan import evidence as ev

logger = logging.getLogger("trading_bot.scan.replay")

# ── counterfactual grid (bounded, published) ─────────────────────────────────
BUY_SELL_GRID = [0.3, 0.4, 0.5, 0.6]
STRONG_GRID = [1.0, 1.2, 1.4]
CONFLICT_GRID = [0.4, 0.5, 0.6]
SHIPPED_DEFAULTS = {"buy": 0.4, "strong": 1.2, "conflict": 0.6}

FOLDS = 4
BOOTSTRAP_N = 1000
BOOTSTRAP_SEED = 42

# IC settings
IC_MAX_EVENTS = 200
IC_MIN_N = 50
IC_CHUNK = 50

_Signal = dict


def _load_shipped_weights() -> tuple[dict, str]:
    """Shipped weights map + its hash (lazy engine-config read, cached per call)."""
    try:
        from diveintocrypto_desktop.engine.loader import load_config

        weights = dict(load_config().get("indicator_weights", {}))
    except Exception as e:  # engine config must never be a hard dependency here
        logger.warning("replay: shipped weights unavailable — %s", str(e)[:80])
        weights = {}
    return weights, ev.weights_hash(weights)


def rescore(signals: _Signal, weights: dict, buy: float, strong: float, conflict: float) -> int:
    """Recompute the verdict direction (−2..2) from an archived signal vector.

    Mirrors engine/consensus: weighted average of signal scores (zero-weight
    names excluded), symmetric ±buy/±strong thresholds, forced NEUTRAL when the
    minority/active conflict ratio exceeds ``conflict``.
    """
    wsum = 0.0
    acc = 0.0
    buy_n = sell_n = 0
    for name, s in signals.items():
        w = float(weights.get(name, 1.0))
        if w == 0:
            continue
        try:
            s = float(s)
        except (TypeError, ValueError):
            continue
        wsum += w
        acc += s * w
        if s > 0:
            buy_n += 1
        elif s < 0:
            sell_n += 1
    if wsum <= 0:
        return 0
    ws = acc / wsum
    active = buy_n + sell_n
    if active > 0 and min(buy_n, sell_n) / active > conflict:
        return 0
    if ws >= strong:
        return 2
    if ws >= buy:
        return 1
    if ws <= -strong:
        return -2
    if ws <= -buy:
        return -1
    return 0


def _dir_of(v: int) -> int:
    return 1 if v > 0 else -1 if v < 0 else 0


def _hit_rate(wins: list[bool]) -> float | None:
    return round(sum(1 for w in wins if w) / len(wins), 4) if wins else None


def replay_grid(
    horizon: str = ev.DEFAULT_HORIZON,
    records: list[dict] | None = None,
    grades: list[dict] | None = None,
    now_ms: int | None = None,
) -> dict:
    """Counterfactual threshold grid over archive × grades (pure over inputs).

    Returns the heatmap-shaped payload: one cell per (buy, strong, conflict)
    combination with hit-rate, directional n, per-fold hit-rates and the
    bootstrap win-rate vs the shipped-default cell (seeded, deterministic).
    """
    records = ev.iter_archive() if records is None else records
    grades = ev.load_grades() if grades is None else grades
    now_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    horizon_ms = ev.HORIZONS[horizon]

    weights, cur_hash = _load_shipped_weights()
    by_key = {(r["symbol"], int(r["ts"])): r for r in records}

    joined: list[tuple[dict, dict]] = []
    skipped_hash = 0
    skipped_no_signals = 0
    for g in grades:
        if g.get("horizon") != horizon or int(g["ts"]) > now_ms - horizon_ms:
            continue
        rec = by_key.get((g["symbol"], int(g["ts"])))
        if rec is None:
            continue
        if not rec.get("signals"):
            skipped_no_signals += 1
            continue
        if rec.get("weights_hash") != cur_hash:
            skipped_hash += 1
            continue
        joined.append((rec, g))
    joined.sort(key=lambda rg: int(rg[1]["ts"]))  # time order → contiguous folds

    dir_of = _dir_of
    ship_verdicts = [rescore(rec.get("signals") or {}, weights,
                             SHIPPED_DEFAULTS["buy"], SHIPPED_DEFAULTS["strong"],
                             SHIPPED_DEFAULTS["conflict"]) for rec, _ in joined]
    ship_wins = [dir_of(v) != 0 and dir_of(v) * float(g.get("forward") or 0.0) > 0
                 for v, (_, g) in zip(ship_verdicts, joined)]
    dir_idx_all = [i for i, v in enumerate(ship_verdicts) if v != 0]
    ship_majority = (sum(1 for i in dir_idx_all if ship_wins[i]) / len(dir_idx_all)
                     if dir_idx_all else None)

    def cell_stats(buy: float, strong: float, conflict: float) -> dict:
        verdicts = [rescore(rec.get("signals") or {}, weights, buy, strong, conflict) for rec, _ in joined]
        wins = [dir_of(v) != 0 and dir_of(v) * float(g.get("forward") or 0.0) > 0
                for v, (_, g) in zip(verdicts, joined)]
        dir_idx = [i for i, v in enumerate(verdicts) if v != 0]
        dir_wins = [wins[i] for i in dir_idx]

        # contiguous time folds over the directional subset
        fold_rates: list[float | None] = []
        if len(dir_idx) >= FOLDS:
            per = len(dir_idx) // FOLDS
            for f in range(FOLDS):
                seg = dir_idx[f * per: (f + 1) * per] if f < FOLDS - 1 else dir_idx[f * per:]
                seg_wins = [wins[i] for i in seg]
                fold_rates.append(_hit_rate(seg_wins))

        # seeded bootstrap: resample directional indices, compare vs the shipped cell
        if dir_idx:
            cell_arr = [1.0 if w else 0.0 for w in wins]
            ship_arr = [1.0 if w else 0.0 for w in ship_wins]
            rng = random.Random(BOOTSTRAP_SEED)
            win_count = 0
            for _ in range(BOOTSTRAP_N):
                cw = 0.0
                sw = 0.0
                for _i in range(len(dir_idx)):
                    j = dir_idx[rng.randrange(len(dir_idx))]
                    cw += cell_arr[j]
                    sw += ship_arr[j]
                if cw > sw:
                    win_count += 1
            boot = round(win_count / BOOTSTRAP_N, 4)
        else:
            boot = None

        hit_rate = _hit_rate(dir_wins)
        delta = round(hit_rate - ship_majority, 4) if (hit_rate is not None and ship_majority is not None) else None
        return {
            "buy": buy,
            "strong": strong,
            "conflict": conflict,
            "n_directional": len(dir_idx),
            "hit_rate": hit_rate,
            "delta_vs_shipped": delta,
            "shipped_hit_rate": round(ship_majority, 4) if ship_majority is not None else None,
            "bootstrap_win_vs_shipped": boot,
            "fold_hit_rates": fold_rates,
            "fold_spread": (
                round(max(fold_rates) - min(fold_rates), 4)
                if fold_rates and all(f is not None for f in fold_rates) else None
            ),
        }

    grid = [
        cell_stats(buy, strong, conflict)
        for buy in BUY_SELL_GRID
        for strong in STRONG_GRID
        for conflict in CONFLICT_GRID
    ]
    return {
        "report_only": True,
        "horizon": horizon,
        "engine_weights_hash": cur_hash,
        "events": {
            "replayable": len(joined),
            "skipped_weights_hash": skipped_hash,
            "skipped_no_signals": skipped_no_signals,
        },
        "grid": grid,
        "axes": {
            "buy": BUY_SELL_GRID,
            "strong": STRONG_GRID,
            "conflict": CONFLICT_GRID,
            "metric": "hit_rate",
        },
        "shipped_cell": SHIPPED_DEFAULTS,
        "bootstrap": {"n": BOOTSTRAP_N, "seed": BOOTSTRAP_SEED},
        "folds": FOLDS,
        "generated_at": ev._iso(time.time()),
    }


# ── IC + weight suggestions ───────────────────────────────────────────────────
def _ranks(xs: list[float]) -> list[float]:
    """Ordinal ranks with tied averages."""
    order = sorted(range(len(xs)), key=lambda i: xs[i])
    ranks = [0.0] * len(xs)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and xs[order[j + 1]] == xs[order[i]]:
            j += 1
        avg = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    return ranks


def spearman(a: list[float], b: list[float]) -> float | None:
    """Spearman ρ (Pearson over ranks); None when either side is constant/short."""
    n = min(len(a), len(b))
    if n < 3:
        return None
    ra, rb = _ranks(a[:n]), _ranks(b[:n])
    ma, mb = sum(ra) / n, sum(rb) / n
    da = [x - ma for x in ra]
    db = [x - mb for x in rb]
    va = sum(x * x for x in da)
    vb = sum(x * x for x in db)
    if va <= 0 or vb <= 0:
        return None
    return sum(x * y for x, y in zip(da, db)) / (va * vb) ** 0.5


def ic_table(
    records: list[dict] | None = None,
    grades: list[dict] | None = None,
    horizon: str = ev.DEFAULT_HORIZON,
    max_events: int = IC_MAX_EVENTS,
    min_n: int = IC_MIN_N,
    weights_hash_expected: str | None = None,
) -> dict:
    """Per-indicator Spearman IC of archived signal vs graded forward return.

    Last ``max_events`` graded events (oldest→newest tail); fewer than
    ``min_n`` → the indicator reports ``ic: "n/a"``; constant signal columns →
    ``"n/a"`` (a constant carries no information, honestly).
    """
    records = ev.iter_archive() if records is None else records
    grades = ev.load_grades() if grades is None else grades
    by_key = {(r["symbol"], int(r["ts"])): r for r in records}
    joined = []
    for g in grades:
        if g.get("horizon") != horizon:
            continue
        rec = by_key.get((g["symbol"], int(g["ts"])))
        if rec is None or not rec.get("signals"):
            continue
        if weights_hash_expected is not None and rec.get("weights_hash") != weights_hash_expected:
            continue
        joined.append((rec, g))
    joined.sort(key=lambda rg: int(rg[1]["ts"]))
    joined = joined[-max_events:]

    names: set[str] = set()
    for rec, _ in joined:
        names.update((rec.get("signals") or {}).keys())

    out: dict[str, dict] = {}
    for name in sorted(names):
        xs = [float((rec.get("signals") or {}).get(name) or 0.0) for rec, _ in joined]
        ys = [float(g.get("forward") or 0.0) for _, g in joined]
        if len(xs) < min_n:
            out[name] = {"ic": "n/a", "ic_ir": None, "n": len(xs)}
            continue
        rho = spearman(xs, ys)
        if rho is None:
            out[name] = {"ic": "n/a", "ic_ir": None, "n": len(xs)}
            continue
        # IC-IR: mean / sd of per-chunk ICs (contiguous IC_CHUNK-sized slices)
        chunk_ics: list[float] = []
        for i in range(0, len(xs) - IC_CHUNK + 1, IC_CHUNK):
            c = spearman(xs[i:i + IC_CHUNK], ys[i:i + IC_CHUNK])
            if c is not None:
                chunk_ics.append(c)
        ic_ir = None
        if len(chunk_ics) >= 2:
            m = sum(chunk_ics) / len(chunk_ics)
            sd = math.sqrt(sum((c - m) ** 2 for c in chunk_ics) / (len(chunk_ics) - 1))
            ic_ir = round(m / sd, 4) if sd > 0 else None
        out[name] = {"ic": round(rho, 4), "ic_ir": ic_ir, "n": len(xs)}
    return {"horizon": horizon, "n": len(joined), "min_n": min_n, "indicators": out}


def suggest_weights(ic: dict, shipped: dict | None = None, cap: float = 3.0) -> dict:
    """Normalized, capped weight suggestions from the IC table.

    raw_i = shipped_i · max(0.25, 1 + tanh(ic_ir_i)) — positive, stable IC-IR
    pulls a weight up, negative pulls it down (floor ¼×); indicators without a
    measurable IC keep their shipped weight. raw is normalized to the shipped
    total, then each suggestion is capped at ``cap``× shipped (one renormalize
    pass; documented approximation). Report-only.
    """
    if shipped is None:
        shipped, _hash = _load_shipped_weights()
    shipped_total = sum(float(v) for v in shipped.values() if float(v) > 0) or 1.0
    indicators = (ic or {}).get("indicators", {})

    raw: dict[str, float] = {}
    meta: dict[str, dict] = {}
    for name, w in shipped.items():
        w = float(w)
        if w <= 0:
            continue
        info = indicators.get(name) or {}
        ic_ir = info.get("ic_ir") if isinstance(info, dict) else None
        factor = 1.0
        if isinstance(ic_ir, (int, float)):
            factor = max(0.25, 1.0 + math.tanh(float(ic_ir)))
        raw[name] = w * factor
        meta[name] = {"shipped": round(w, 4), "ic": info.get("ic"), "ic_ir": ic_ir,
                      "n": info.get("n")}

    total_raw = sum(raw.values()) or 1.0
    scale = shipped_total / total_raw
    suggestions: dict[str, dict] = {}
    for name, m in meta.items():
        s = raw[name] * scale
        s = min(s, cap * m["shipped"])
        suggestions[name] = {
            **m,
            "suggested": round(s, 4),
        }
    disclaimer = (
        "Report-only analytics. NEVER read by the engine — shipped weights stay "
        "authoritative unless a human edits the config explicitly."
    )
    return {"suggestions": suggestions, "cap_multiple": cap, "disclaimer": disclaimer}


def suggestions_path() -> Path:
    """Where suggestions persist (env ``DIVE_WEIGHT_SUGGESTIONS_PATH`` overrides).

    The engine never reads this file — nothing in the consensus path touches it.
    """
    env = os.environ.get("DIVE_WEIGHT_SUGGESTIONS_PATH")
    if env:
        return Path(env)
    return ev._default_dir() / "weight_suggestions.json"


def write_suggestions(payload: dict) -> Path | None:
    """Persist the suggestions payload; failure-tolerant (returns None)."""
    path = suggestions_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return path
    except OSError as e:
        logger.warning("replay: write suggestions failed — %s", str(e)[:100])
        return None
