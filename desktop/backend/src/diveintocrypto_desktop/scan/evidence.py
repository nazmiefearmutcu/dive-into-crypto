"""Evidence layer — the engine grades itself.

Every scan verdict is appended to a local JSONL archive (``runtime/evidence.jsonl``
by default, path via ``DIVE_EVIDENCE_PATH``). Once a verdict is older than the
grading horizon (1h / 4h / 24h), the grader backfills what actually happened from
public klines: did price move ≥ threshold in the dominant direction before the
opposite move, and what was the forward return at the horizon. Results are
appended to a sidecar grades file (``runtime/evidence_grades.jsonl``) so grading
is resumable across restarts and capped (40 symbols per request) — a report-only
loop, never a parameter refit.

Honesty rules: archive/grade writes are append-only and failure-tolerant — the
evidence layer must never break scanning; corrupt archive lines are skipped, not
guessed; symbols whose kline backfill fails are listed as failed, never filled.

Schema v2 (desktop-0.3.0): records additionally carry ``regime``,
``micro_score``, ``mtf_gate``, ``divergence_score``, ``divergence_coverage``,
``divergence_tier``, ``cluster_id``, ``cluster_agreement`` and ``weights_hash``
(the replay keystone). Old lines missing those fields land in the explicit
``unclassified`` bucket — never imputed. Stats objects are GATED: below
``GATE_SUPPRESS_N`` samples only ``{n}`` is reported; below ``GATE_FLAG_N`` the
stats carry ``gated: true``. Proportions come with a Wilson 95% interval.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import random
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path

from diveintocrypto_desktop.scan import divergence as dv

logger = logging.getLogger("trading_bot.scan.evidence")

ENGINE_VERSION = "desktop-0.3.0"

# Grading horizons (label → ms). Forward returns are measured on 1h klines.
HORIZONS: dict[str, int] = {
    "1h": 3_600_000,
    "4h": 14_400_000,
    "24h": 86_400_000,
}
DEFAULT_HORIZON = "4h"

FORWARD_THRESHOLD = 0.01   # a ±1% move counts as a decisive step (hit/miss walk)
BACKFILL_CAP = 40          # max symbols backfilled per grade() call (resumable)
_HOUR_MS = 3_600_000
_DAY_MS = 86_400_000
_MOMENTUM_LOOKBACK_MS = 24 * _HOUR_MS

CONFIDENCE_BUCKETS = ["0-25", "25-50", "50-75", "75-100"]

# Stats gates: n < 5 → {n} only; n < 20 → values present but gated: true.
GATE_SUPPRESS_N = 5
GATE_FLAG_N = 20

# Wilson 95% interval z-value.
WILSON_Z = 1.96

# Permutation baseline for the graded edge.
PERMUTATION_K = 1000
PERMUTATION_SEED = 42

_SIGNAL_SCORES = {"STRONG_BUY": 2, "BUY": 1, "NEUTRAL": 0, "SELL": -1, "STRONG_SELL": -2}

_UNCLASSIFIED = "unclassified"

# Terminal reason written for a verdict that can never be graded from the data
# that exists (no entry price, or no completed candles covering its horizon).
_SKIP_REASON = "ungradable: no entry price or no candles covering the horizon"

# One grade() at a time: overlapping calls would grade the same verdicts twice.
_GRADE_LOCK = asyncio.Lock()
# Consecutive failed backfills per symbol (in-process). A symbol that keeps failing is
# tried after the others, so one persistently broken pair cannot hold a grade() slot
# on every call and starve the rest of the backlog.
_RECENT_FAILURES: dict[str, int] = {}
# 1000 hourly candles per Binance request (limit=1000); 999h spans fit inside it.
_FETCH_PAGE_MS = 999 * _HOUR_MS


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _default_dir() -> Path:
    # .../desktop/backend/src/diveintocrypto_desktop/scan/evidence.py → backend root
    return Path(__file__).resolve().parents[3] / "runtime"


def archive_path() -> Path:
    """JSONL archive of scan verdicts (env ``DIVE_EVIDENCE_PATH`` overrides)."""
    env = os.environ.get("DIVE_EVIDENCE_PATH")
    return Path(env) if env else _default_dir() / "evidence.jsonl"


def grades_path() -> Path:
    """JSONL archive of grading results (env ``DIVE_EVIDENCE_GRADES_PATH`` overrides)."""
    env = os.environ.get("DIVE_EVIDENCE_GRADES_PATH")
    return Path(env) if env else _default_dir() / "evidence_grades.jsonl"


def weights_hash(weights: dict) -> str:
    """Stable sha256 of the weights map — the replay keystone.

    Replay may only re-score archives written under the SAME weights map; rows
    whose hash differs are reported as skipped instead of silently mixed in.
    """
    import hashlib

    payload = json.dumps({str(k): float(v) for k, v in (weights or {}).items()}, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


# ── archive ───────────────────────────────────────────────────────────────────
def record_from_row(row: dict, ts_ms: int | None = None) -> dict:
    """One archive record per scan row — the engine's verdict at verdict time.

    ``ts`` is the snapshot instant the row's price was taken at (``snapshot_ms``
    stamped by the scan), not the later archive time. v2 fields are taken from
    the row's annotation blocks when present; anything missing stays absent here
    and is bucketed ``unclassified`` downstream.
    """
    verdict = row.get("finalSignal", "NEUTRAL")
    dom = int(row.get("dominantDir") or 0)
    multi = row.get("multiTf") or []
    total = len(multi) or 1
    if dom > 0:
        agree = sum(1 for m in multi if "BUY" in m.get("signal", ""))
    elif dom < 0:
        agree = sum(1 for m in multi if "SELL" in m.get("signal", ""))
    else:
        agree = 0
    net = float(row.get("netNss") or 0.0)
    indicators = row.get("indicators") or []
    signals = {i.get("name"): _SIGNAL_SCORES.get(i.get("signal"), 0) for i in indicators if i.get("name")}
    div_block = row.get("divergence") or {}
    div_score = div_block.get("score")
    micro = row.get("microstructure") or {}
    regime_block = row.get("regime") or {}
    mtf_block = row.get("mtfConfluence") or {}
    ts_src = ts_ms if ts_ms is not None else row.get("snapshot_ms")
    rec = {
        "ts": int(ts_src if ts_src is not None else time.time() * 1000),
        "symbol": row.get("s", ""),
        "verdict": verdict,
        "confidence": int(row.get("confidence") or 0),
        "risk": row.get("risk", "LOW"),
        "net_score": round(net * dom, 2),  # signed toward the dominant direction
        "dominant_dir": dom,
        "price": float(row.get("price") or 0.0),
        "tf_agreement": round(agree / total, 3),
        "divergence_state": row.get("whaleRegime", "neutral"),
        "engine_version": ENGINE_VERSION,
        "signals": signals,  # the 60-indicator vector at verdict time (report-only)
    }
    # ── schema v2 (additive; old rows simply lack these) ─────────────────────
    rec["regime"] = regime_block.get("regime")
    # scan rows carry no futures series, so the microstructure engine reports
    # active == 0; that is "no reading", never a neutral 0.0 score
    rec["micro_score"] = micro.get("score") if micro.get("active") else None
    rec["mtf_gate"] = mtf_block.get("gate") if isinstance(mtf_block.get("gate"), bool) else None
    rec["divergence_score"] = div_score
    rec["divergence_coverage"] = div_block.get("coverage")
    rec["divergence_tier"] = dv.tier_for(div_score) if isinstance(div_score, (int, float)) else None
    rec["cluster_id"] = row.get("cluster_id")
    rec["cluster_agreement"] = row.get("cluster_agreement")
    rec["weights_hash"] = row.get("weights_hash")
    return rec


def append_lines(path: Path, records: list[dict]) -> int:
    """Append records as JSONL (append-only, failure-tolerant). Returns count written."""
    if not records:
        return 0
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as f:
            for r in records:
                f.write(json.dumps(r, separators=(",", ":")) + "\n")
        return len(records)
    except Exception as e:  # evidence must never break scanning
        logger.warning("evidence: append to %s failed — %s", path, str(e)[:100])
        return 0


def archive_scan(scan_result: dict) -> int:
    """Archive every verdict row of a scan result (survivors + eliminated)."""
    rows = list(scan_result.get("survivors") or []) + list(scan_result.get("eliminated") or [])
    records = [record_from_row(r) for r in rows if r.get("s")]
    return append_lines(archive_path(), records)


def iter_archive(path: Path | None = None) -> list[dict]:
    """Read all archive records; corrupt/blank lines are skipped (never fatal)."""
    path = path or archive_path()
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
    except FileNotFoundError:
        return []
    except OSError as e:
        logger.warning("evidence: read %s failed — %s", path, str(e)[:100])
        return []
    out: list[dict] = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict) and obj.get("symbol") and "ts" in obj:
            try:
                int(obj["ts"])
            except (TypeError, ValueError):
                continue  # valid JSON with a non-numeric ts: corrupt, skipped like the rest
            out.append(obj)
    return out


def decisions(symbol: str | None = None, from_ms: int | None = None,
              to_ms: int | None = None, limit: int = 500) -> list[dict]:
    """Thin archive reader for the UI: records for one symbol in a ts window.

    Returns the NEWEST ``limit`` matching records, oldest first (the previous
    behaviour dropped the newest rows silently once the window exceeded limit).
    """
    rows = [r for r in iter_archive()
            if (symbol is None or r.get("symbol") == symbol)
            and (from_ms is None or int(r["ts"]) >= int(from_ms))
            and (to_ms is None or int(r["ts"]) <= int(to_ms))]
    rows.sort(key=lambda r: int(r["ts"]))
    return rows[-max(1, limit):]


# ── grading ───────────────────────────────────────────────────────────────────
def load_grades(path: Path | None = None, include_skipped: bool = False) -> list[dict]:
    """Read grade lines; corrupt lines skipped, duplicate (symbol, ts, horizon)
    lines collapsed to their first occurrence. Terminal ``skipped`` lines (verdicts
    that can never be graded) are excluded unless ``include_skipped``."""
    path = path or grades_path()
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
    except (FileNotFoundError, OSError):
        return []
    out: list[dict] = []
    seen: set[tuple] = set()
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict) and obj.get("symbol") and "ts" in obj:
            try:
                key = (obj["symbol"], int(obj["ts"]), obj.get("horizon"))
            except (TypeError, ValueError):
                continue  # valid JSON with a non-numeric ts: corrupt, skipped (never fatal)
            if key in seen:
                continue  # a concurrent double-grade wrote the same verdict twice
            seen.add(key)
            if obj.get("skipped") and not include_skipped:
                continue
            out.append(obj)
    return out


def _horizon_path(candles: list[dict], ts: int, horizon_ms: int) -> list[dict]:
    """Completed 1h candles inside the verdict's horizon ``[ts, ts + H]``.

    A candle is used only when it opens at or after the verdict instant and closes
    by the horizon end. The candle that contains ``ts`` opens BEFORE the verdict
    and holds pre-verdict prices, so it is never used (its high/low would count
    moves the verdict could not have seen). A candle still open at the horizon end
    is excluded too. An hour-aligned verdict's own candle opens exactly at ``ts``,
    so all of its prices are post-verdict and it stays in the path. For a
    non-aligned ``ts`` the first usable candle is the first full hour after ``ts``."""
    end_ts = ts + horizon_ms
    return [c for c in candles if ts <= c["t"] and c["t"] + _HOUR_MS <= end_ts]


def _horizon_exit(path: list[dict]) -> dict | None:
    """The last completed candle on the horizon path (its close is the exit)."""
    return path[-1] if path else None


def _walk_hit(entry: float, dir: int, path: list[dict]) -> bool:
    """Walk candles chronologically: did price move ≥ FORWARD_THRESHOLD in ``dir``
    before the opposite move? Both thresholds inside ONE candle → conservative
    miss (the order is unknowable from OHLC). No breach at all → miss."""
    for c in path:
        fav = ((c["h"] - entry) / entry if dir > 0 else (entry - c["l"]) / entry) >= FORWARD_THRESHOLD
        adv = ((entry - c["l"]) / entry if dir > 0 else (c["h"] - entry) / entry) >= FORWARD_THRESHOLD
        if adv and not fav:
            return False
        if fav:
            return not adv  # both breached in this candle → conservative miss
    return False


def _momentum_hit(rec: dict, candles: list[dict]) -> bool | None:
    """Did the trailing-24h return sign (at the verdict's timestamp) agree with
    the verdict direction? ``None`` when the pre-window is absent (short
    archives) or the verdict is NEUTRAL — never guessed. Only candles whose close
    is known at ``ts`` are used (a candle that opens before ``ts`` closes after it)."""
    dir = int(rec.get("dominant_dir") or 0)
    if dir == 0:
        return None
    ts = int(rec["ts"])
    before = [c for c in candles if c["t"] + _HOUR_MS <= ts - _MOMENTUM_LOOKBACK_MS]
    at = [c for c in candles if c["t"] + _HOUR_MS <= ts]
    if not before or not at:
        return None
    base = before[-1]["c"]
    now_c = at[-1]["c"]
    if base <= 0:
        return None
    ret = (now_c - base) / base
    if abs(ret) < 1e-9:
        return False
    return (ret > 0) == (dir > 0)


def _grade_one(rec: dict, candles: list[dict], horizon: str, now_ms: int) -> dict | None:
    """Grade one archived verdict against 1h candles ``[{t(ms), h, l, c}]``."""
    entry = float(rec.get("price") or 0.0)
    ts = int(rec["ts"])
    if entry <= 0:
        return None
    horizon_ms = HORIZONS[horizon]
    path = _horizon_path(candles, ts, horizon_ms)
    if not path:
        return None
    exit_c = _horizon_exit(path)
    if exit_c is None:
        return None
    dir = int(rec.get("dominant_dir") or 0)
    if dir != 0:
        hit = _walk_hit(entry, dir, path)
        forward = dir * (exit_c["c"] - entry) / entry
    else:
        # NEUTRAL: "correct" = no decisive move in EITHER direction by horizon end
        hit = abs((exit_c["c"] - entry) / entry) < FORWARD_THRESHOLD
        forward = (exit_c["c"] - entry) / entry

    # ── v2 per-event extras (additive) ───────────────────────────────────────
    signed_close = dir * (exit_c["c"] - entry) / entry if dir != 0 else (exit_c["c"] - entry) / entry
    if dir != 0:
        hit_close = signed_close >= FORWARD_THRESHOLD
        mfe = max(((c["h"] - entry) / entry if dir > 0 else (entry - c["l"]) / entry) for c in path)
        mae = max(((entry - c["l"]) / entry if dir > 0 else (c["h"] - entry) / entry) for c in path)
    else:
        hit_close = abs(signed_close) < FORWARD_THRESHOLD
        mfe = max((c["h"] - entry) / entry for c in path)
        mae = max((entry - c["l"]) / entry for c in path)

    return {
        "ts": ts,
        "symbol": rec["symbol"],
        "horizon": horizon,
        "verdict": rec.get("verdict", "NEUTRAL"),
        "dir": dir,
        "entry": entry,
        "exit": float(exit_c["c"]),
        "forward": round(forward, 6),
        "hit": bool(hit),
        # v2:
        "hit_close": bool(hit_close),
        "mfe": round(mfe, 6),
        "mae": round(mae, 6),
        "bnh_forward": round((exit_c["c"] - entry) / entry, 6),
        "momentum_hit": _momentum_hit(rec, candles),
        "graded_at": now_ms,
        "engine_version": ENGINE_VERSION,
    }


async def _default_fetcher(symbol: str, start_ms: int, end_ms: int) -> list[dict]:
    """Completed 1h candles over ``[start_ms, end_ms]``, paged so a long backlog is
    never silently truncated to Binance's first 1000 candles."""
    from diveintocrypto_desktop.data import binance_klines as kl

    out: list[dict] = []
    cur = start_ms
    while cur <= end_ms:
        page_end = min(end_ms, cur + _FETCH_PAGE_MS)
        candles = await kl.fetch_klines_range(symbol, "1h", cur, page_end)
        out.extend({"t": c["t"] // 1_000_000, "h": c["h"], "l": c["l"], "c": c["c"]} for c in candles)
        cur = page_end + 1
    return out


async def grade(
    horizon: str = DEFAULT_HORIZON,
    cap: int = BACKFILL_CAP,
    fetcher=None,
    now_ms: int | None = None,
) -> dict:
    """Backfill outcomes for matured, not-yet-graded verdicts (resumable, capped).

    Returns ``{horizon, archived, gradable, graded, skipped, symbols_graded,
    failed, remaining}``. A verdict that can never be graded (no entry price, or
    candles that do not cover its horizon) gets a terminal ``skipped`` line so it
    leaves the backlog instead of starving it; a fetch failure (or an empty answer)
    stays retryable, and a symbol that keeps failing is tried after the others so it
    cannot starve the backlog. The fetch window reaches 25h BEFORE the oldest verdict
    so the momentum baseline (the last candle closed by ``ts - 24h``) is fetched for
    every verdict; when the window has no pre-history the grade stores
    ``momentum_hit: null`` honestly.
    """
    if horizon not in HORIZONS:
        raise ValueError(f"horizon must be one of {sorted(HORIZONS)}")
    fetcher = fetcher or _default_fetcher
    now_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    horizon_ms = HORIZONS[horizon]

    async with _GRADE_LOCK:
        # blocking file I/O stays off the event loop
        records = await asyncio.to_thread(iter_archive)
        done = {(g["symbol"], int(g["ts"]))
                for g in await asyncio.to_thread(load_grades, include_skipped=True)
                if g.get("horizon") == horizon}
        mature = [r for r in records if int(r["ts"]) <= now_ms - horizon_ms]
        todo: dict[str, list[dict]] = {}
        for r in mature:
            if (r["symbol"], int(r["ts"])) in done:
                continue
            todo.setdefault(r["symbol"], []).append(r)
        # oldest verdicts first — the archive grades forward in time; symbols that failed
        # on the previous attempts go to the back so they cannot hold every slot
        symbols = sorted(
            todo,
            key=lambda s: (_RECENT_FAILURES.get(s, 0) > 0, min(int(r["ts"]) for r in todo[s])),
        )[: max(1, cap)]

        graded = 0
        skipped = 0
        failed: list[dict] = []
        new_lines: list[dict] = []
        for s in symbols:
            recs = todo[s]
            # 25h before the oldest verdict: the momentum baseline needs the last candle
            # that closed by ts - 24h, which opens 25h before ts
            start = min(int(r["ts"]) for r in recs) - _MOMENTUM_LOOKBACK_MS - _HOUR_MS
            end = max(int(r["ts"]) for r in recs) + horizon_ms + 2 * _HOUR_MS
            try:
                candles = await fetcher(s, start, end)
            except Exception as e:
                _RECENT_FAILURES[s] = _RECENT_FAILURES.get(s, 0) + 1
                failed.append({"symbol": s, "reason": str(e)[:100]})
                continue
            if not candles:  # empty response: retryable, deprioritised on later calls
                _RECENT_FAILURES[s] = _RECENT_FAILURES.get(s, 0) + 1
                failed.append({"symbol": s, "reason": "no candles returned"})
                continue
            _RECENT_FAILURES.pop(s, None)
            for rec in recs:
                g = _grade_one(rec, candles, horizon, now_ms)
                if g is None:  # candles exist but cannot grade this verdict: terminal
                    new_lines.append({
                        "ts": int(rec["ts"]), "symbol": rec["symbol"], "horizon": horizon,
                        "skipped": _SKIP_REASON, "graded_at": now_ms,
                        "engine_version": ENGINE_VERSION,
                    })
                    skipped += 1
                    continue
                new_lines.append(g)
                graded += 1

        await asyncio.to_thread(append_lines, grades_path(), new_lines)

        done_after = {(g["symbol"], int(g["ts"]))
                      for g in await asyncio.to_thread(load_grades, include_skipped=True)
                      if g.get("horizon") == horizon}
    remaining = sum(1 for r in mature if (r["symbol"], int(r["ts"])) not in done_after)
    return {
        "horizon": horizon,
        "archived": len(records),
        "gradable": len(mature),
        "graded": graded,
        "skipped": skipped,
        "symbols_graded": len(symbols) - len(failed),
        "failed": failed,
        "remaining": remaining,
    }


# ── summary ───────────────────────────────────────────────────────────────────
def _bucket(verdict: str) -> str:
    if "BUY" in verdict:
        return "LONG"
    if "SELL" in verdict:
        return "SHORT"
    return "NEUTRAL"


def _conf_bucket(conf: int) -> str:
    if conf >= 75:
        return "75-100"
    if conf >= 50:
        return "50-75"
    if conf >= 25:
        return "25-50"
    return "0-25"


def wilson_interval(hits: int, n: int, z: float = WILSON_Z) -> tuple[float | None, float | None]:
    """Wilson score interval for a binomial proportion, 95% by default.

    Formula (with p̂ = hits/n):
        center = (p̂ + z²/2n) / (1 + z²/n)
        half   = z·√( p̂(1−p̂)/n + z²/4n² ) / (1 + z²/n)
        interval = [center − half, center + half], clamped to [0, 1].
    Chosen over the naive Wald interval because it stays honest at small n
    (no zero-width intervals at p̂=0/1).
    """
    if n <= 0:
        return None, None
    p = hits / n
    z2 = z * z
    denom = 1.0 + z2 / n
    center = (p + z2 / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denom
    return (round(max(0.0, center - half), 4), round(min(1.0, center + half), 4))


def _stats(items: list[dict]) -> dict:
    """Gated stats object shared by every aggregation.

    n < GATE_SUPPRESS_N → ``{"n": n}`` only (any percentage would be noise).
    n < GATE_FLAG_N → full values + ``gated: true``. Proportions carry the
    Wilson 95% interval.
    """
    n = len(items)
    if n < GATE_SUPPRESS_N:
        return {"n": n}
    hits = sum(1 for g in items if g.get("hit"))
    forwards = [float(g["forward"]) for g in items if g.get("forward") is not None]
    lo, hi = wilson_interval(hits, n)
    return {
        "n": n,
        "hit_rate": round(hits / n, 4),
        "avg_forward": round(sum(forwards) / len(forwards), 6) if forwards else None,
        "median_forward": round(statistics.median(forwards), 6) if forwards else None,
        "wilson_lo": lo,
        "wilson_hi": hi,
        "gated": n < GATE_FLAG_N,
    }


_SLICE_NOTE = "multiple comparisons — descriptive only, not a tested hypothesis"


def _session_bucket(ts_ms: int) -> str:
    hour = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc).hour
    if hour < 8:
        return "0-8"
    if hour < 16:
        return "8-16"
    return "16-24"


def _funding_proximity_bucket(ts_ms: int) -> str:
    """Distance (in hours) from the verdict ts to the nearest funding settlement
    (Binance settles 00:00 / 08:00 / 16:00 UTC)."""
    h = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
    boundaries = [0, 8, 16, 24]
    frac = h.hour + h.minute / 60.0
    dist = min(abs(frac - b) for b in boundaries)
    if dist <= 1.0:
        return "within_1h"
    if dist <= 4.0:
        return "1h_to_4h"
    return "over_4h"


def _slice_stats(joined: list[tuple[dict, dict]], key_of) -> dict:
    buckets: dict[str, list[dict]] = {}
    for r, g in joined:
        k = key_of(r)
        if k is None:
            k = _UNCLASSIFIED
        buckets.setdefault(str(k), []).append(g)
    return {
        "note": _SLICE_NOTE,
        "buckets": {k: _stats(v) for k, v in sorted(buckets.items())},
    }


def calibration(joined: list[tuple[dict, dict]]) -> dict:
    """Reliability of ``confidence`` against realized hit-rates.

    ``ece`` = Σ_b (n_b/N)·|hit_rate_b − mean_conf_b| over the four confidence
    buckets; each bin reports the mean confidence, hit-rate and its Wilson
    interval. Gating: bins with ``n < GATE_SUPPRESS_N`` NEVER feed the ECE and
    render with ``gated: true`` (like every other small-n stat); empty bins
    carry nulls (never zeros dressed as calibration); with zero graded samples
    — or no bin large enough to feed it — ``ece`` is ``None``, not a fabricated
    0.0 pretending perfect calibration.
    """
    total = len(joined)
    bins: list[dict] = []
    ece = 0.0
    ece_n = 0
    for b in CONFIDENCE_BUCKETS:
        pairs = [(r, g) for r, g in joined if _conf_bucket(int(r.get("confidence") or 0)) == b]
        n = len(pairs)
        if n:
            mean_conf = round(sum(int(r.get("confidence") or 0) for r, _ in pairs) / n, 2)
            hits = sum(1 for _, g in pairs if g.get("hit"))
            hit_rate = hits / n
            lo, hi = wilson_interval(hits, n)
            feeds_ece = n >= GATE_SUPPRESS_N
            if feeds_ece:
                ece += (n / total) * abs(hit_rate - mean_conf / 100.0)
                ece_n += n
            bins.append({
                "bucket": b, "mean_conf": mean_conf, "hit_rate": round(hit_rate, 4),
                "wilson_lo": lo, "wilson_hi": hi, "n": n,
                "gated": not feeds_ece,
            })
        else:
            bins.append({"bucket": b, "mean_conf": None, "hit_rate": None,
                         "wilson_lo": None, "wilson_hi": None, "n": 0})
    return {"ece": round(ece, 4) if ece_n else None, "bins": bins}


def brier(joined: list[tuple[dict, dict]]) -> dict:
    """Brier score of ``confidence/100`` against realized hits + skill vs the
    base-rate (climatology) reference. Directional quality read — report-only."""
    if not joined:
        return {"score": None, "ref": None, "skill": None, "n": 0}
    hits = [1.0 if g.get("hit") else 0.0 for _, g in joined]
    confs = [min(max(int(r.get("confidence") or 0), 0), 100) / 100.0 for r, _ in joined]
    n = len(hits)
    score = sum((p - y) ** 2 for p, y in zip(confs, hits, strict=True)) / n
    base = sum(hits) / n
    ref = sum((base - y) ** 2 for y in hits) / n
    skill = round(1.0 - score / ref, 4) if ref > 0 else None
    return {"score": round(score, 4), "ref": round(ref, 4), "skill": skill, "n": n}


def baselines(joined: list[tuple[dict, dict]]) -> dict:
    """Analytic + permutation baselines for the graded forward edge.

    Under random dominant-direction signs, each signed forward flips sign, so
    the null mean is 0 and its sd is √(Σf²)/n (analytic, exact). The p-value is
    ``(1 + #{K sign-shuffled means ≥ observed}) / (K + 1)`` — the add-one floor
    keeps ``p > 0`` even when NO permutation reaches the observation (a zero
    p-value is a category error: the observed statistic is always one of the
    permutation outcomes). Deterministic via a fixed seed (printed alongside).
    """
    # Only DIRECTIONAL verdicts: their forward is dir-signed, so a random sign flip
    # is a valid null. NEUTRAL forwards are unsigned raw returns and would mix conventions.
    forwards = [float(g["forward"]) for r, g in joined
                if g.get("forward") is not None and int(r.get("dominant_dir") or 0) != 0]
    if not forwards:
        return {"coin_mean": None, "coin_sd": None, "p_value": None,
                "momentum_hit_rate": None, "bnh_forward_mean": None,
                "n_directional": 0,
                "permutations": PERMUTATION_K, "seed": PERMUTATION_SEED}
    n = len(forwards)
    coin_sd = math.sqrt(sum(f * f for f in forwards)) / n
    observed = sum(forwards) / n
    rng = random.Random(PERMUTATION_SEED)
    ge = 0
    for _ in range(PERMUTATION_K):
        s = sum(f if rng.random() < 0.5 else -f for f in forwards) / n
        if s >= observed:
            ge += 1
    momentum = [bool(g["momentum_hit"]) for _, g in joined
                if g.get("momentum_hit") is not None]
    bnh = [float(g["bnh_forward"]) for _, g in joined
           if g.get("bnh_forward") is not None]
    return {
        "coin_mean": 0.0,
        "coin_sd": round(coin_sd, 6),
        "p_value": round((1 + ge) / (PERMUTATION_K + 1), 4),
        "observed_mean": round(observed, 6),
        "momentum_hit_rate": round(sum(momentum) / len(momentum), 4) if momentum else None,
        "momentum_n": len(momentum),
        "bnh_forward_mean": round(sum(bnh) / len(bnh), 6) if bnh else None,
        "n_directional": n,
        "permutations": PERMUTATION_K,
        "seed": PERMUTATION_SEED,
    }


def _windows_key(ts_ms: int, now_ms: int) -> tuple[bool, bool]:
    return (ts_ms >= now_ms - 7 * _DAY_MS, ts_ms >= now_ms - 30 * _DAY_MS)


def summary(horizon: str = DEFAULT_HORIZON, now_ms: int | None = None) -> dict:
    """Aggregate the evidence archive + grades (computed from files, cached cheaply
    by the caller's cadence). ``stale`` = matured verdicts still awaiting grading."""
    if horizon not in HORIZONS:
        raise ValueError(f"horizon must be one of {sorted(HORIZONS)}")
    now_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    horizon_ms = HORIZONS[horizon]

    records = iter_archive()
    all_grades = load_grades(include_skipped=True)
    # terminal skips leave the backlog: they are neither graded nor "failed grades"
    skipped_keys = {(g["symbol"], int(g["ts"])) for g in all_grades
                    if g.get("horizon") == horizon and g.get("skipped")}
    grades = [g for g in all_grades if not g.get("skipped")]
    by_key = {(r["symbol"], int(r["ts"])): r for r in records}

    mature = [r for r in records if int(r["ts"]) <= now_ms - horizon_ms
              and (r["symbol"], int(r["ts"])) not in skipped_keys]
    ungradable = sum(1 for r in records if int(r["ts"]) <= now_ms - horizon_ms
                     and (r["symbol"], int(r["ts"])) in skipped_keys)

    joined: list[tuple[dict, dict]] = []
    for g in grades:
        if g.get("horizon") != horizon:
            continue
        rec = by_key.get((g["symbol"], int(g["ts"])))
        if rec is not None:
            joined.append((rec, g))

    by_verdict = {b: _stats([g for r, g in joined if _bucket(r.get("verdict", "NEUTRAL")) == b])
                  for b in ("LONG", "SHORT", "NEUTRAL")}

    by_confidence = []
    for b in CONFIDENCE_BUCKETS:
        items = [g for r, g in joined if _conf_bucket(int(r.get("confidence") or 0)) == b]
        by_confidence.append({"bucket": b, **_stats(items)})

    # per-indicator association (report-only): hit-rate when the indicator agreed
    # with the verdict's dominant direction vs. when it disagreed.
    ind_acc: dict[str, dict[str, list[dict]]] = {}
    for r, g in joined:
        signals = r.get("signals") or {}
        dir = int(r.get("dominant_dir") or 0)
        for name, val in signals.items():
            try:
                v = int(val)
            except (TypeError, ValueError):
                continue
            if dir == 0 or v == 0:
                group = "neutral"
            elif (v > 0) == (dir > 0):
                group = "agree"
            else:
                group = "disagree"
            ind_acc.setdefault(name, {"agree": [], "disagree": [], "neutral": []})[group].append(g)
    by_indicator = {
        name: {
            "n": sum(len(v) for v in groups.values()),
            "agree": _stats(groups["agree"]),
            "disagree": _stats(groups["disagree"]),
        }
        for name, groups in sorted(ind_acc.items())
    }

    # ── v2: windows ──────────────────────────────────────────────────────────
    windows: dict[str, dict] = {}
    for label, min_ts in (("7d", now_ms - 7 * _DAY_MS), ("30d", now_ms - 30 * _DAY_MS), ("all", 0)):
        sel = [(r, g) for r, g in joined if int(r["ts"]) >= min_ts]
        windows[label] = {b: _stats([g for r, g in sel if _bucket(r.get("verdict", "NEUTRAL")) == b])
                          for b in ("LONG", "SHORT", "NEUTRAL")}

    # ── v2: slices (each annotated multiple-comparisons-descriptive) ─────────
    by_regime = _slice_stats(joined, lambda r: r.get("regime"))
    by_session = _slice_stats(joined, lambda r: _session_bucket(int(r["ts"])))
    by_funding_proximity = _slice_stats(joined, lambda r: _funding_proximity_bucket(int(r["ts"])))
    by_divergence_tier = _slice_stats(joined, lambda r: r.get("divergence_tier"))

    graded_count = len({(g["symbol"], int(g["ts"])) for _, g in joined})
    gradable = len(mature)
    failed_grades = max(0, gradable - graded_count)
    ts_list = [int(r["ts"]) for r in records]
    provenance = {
        "first_ts": _iso(min(ts_list) / 1000) if ts_list else None,
        "last_ts": _iso(max(ts_list) / 1000) if ts_list else None,
        "graded_count": graded_count,
        "failed_grades": failed_grades,
        "engine_version": ENGINE_VERSION,
        "window_note": (
            f"archive {provenance_first(records)} → {provenance_last(records)}; "
            f"horizon {horizon}; slices are descriptive (multiple comparisons)"
        ) if records else "empty archive",
    }

    return {
        "horizon": horizon,
        "archived_count": len(records),
        "gradable_count": gradable,
        "ungradable_count": ungradable,
        "graded_count": graded_count,
        "coverage": round(graded_count / gradable, 4) if gradable else 0.0,
        "stale": graded_count < gradable,
        "by_verdict": by_verdict,
        "by_confidence": by_confidence,
        "by_indicator": by_indicator,
        # ── v2 ───────────────────────────────────────────────────────────────
        "calibration": calibration(joined),
        "brier": brier(joined),
        "baselines": baselines(joined),
        "windows": windows,
        "by_regime": by_regime,
        "by_session": by_session,
        "by_funding_proximity": by_funding_proximity,
        "by_divergence_tier": by_divergence_tier,
        "provenance": provenance,
        "generated_at": _iso(time.time()),
        "engine_version": ENGINE_VERSION,
    }


def provenance_first(records: list[dict]) -> str:
    ts = min((int(r["ts"]) for r in records), default=None)
    return _iso(ts / 1000) if ts is not None else "—"


def provenance_last(records: list[dict]) -> str:
    ts = max((int(r["ts"]) for r in records), default=None)
    return _iso(ts / 1000) if ts is not None else "—"


def _sign_of(x: int) -> int:
    return (x > 0) - (x < 0)


def stability(limit_per_symbol: int = 8) -> list[dict]:
    """Per-symbol verdict self-agreement over the last ``limit_per_symbol`` archived
    records — DIRECTIONAL rows only: the majority side is computed over, and
    ``agree_frac`` measured against, the rows with a non-neutral verdict; NEUTRAL
    rows are excluded from both the majority and the denominator (a neutral
    verdict is not a weak "agree"). ``agree_frac`` = share of directional rows on
    the majority side (``None`` when there are none — nothing directional to
    agree with), ``k`` = directional rows considered, ``median_gap_min`` = median
    spacing of the tail, ``last_ts``.
    """
    by_symbol: dict[str, list[dict]] = {}
    for r in iter_archive():
        by_symbol.setdefault(r.get("symbol", ""), []).append(r)
    out: list[dict] = []
    for sym, rows in sorted(by_symbol.items()):
        rows.sort(key=lambda r: int(r["ts"]))
        tail = rows[-limit_per_symbol:]
        # sign, not score: STRONG_BUY/STRONG_SELL are the same side as BUY/SELL
        dirs = [_sign_of(_SIGNAL_SCORES.get(r.get("verdict", "NEUTRAL"), 0)) for r in tail]
        directional = [d for d in dirs if d != 0]
        k = len(directional)
        if directional:
            majority = 1 if directional.count(1) >= directional.count(-1) else -1
            agree = sum(1 for d in directional if d == majority)
            agree_frac = round(agree / k, 3)
        else:
            agree_frac = None  # nothing directional to agree with — honest null
        gaps = [(int(b["ts"]) - int(a["ts"])) / 60_000.0 for a, b in zip(tail, tail[1:], strict=False)]
        out.append({
            "s": sym,
            "agree_frac": agree_frac,
            "k": k,
            "median_gap_min": round(statistics.median(gaps), 1) if gaps else None,
            "last_ts": int(tail[-1]["ts"]),
        })
    return out


def reset_state() -> None:
    """Remove archive + grades files (test hook — only legal on tmp paths)."""
    for p in (archive_path(), grades_path()):
        try:
            p.unlink()
        except FileNotFoundError:
            pass
