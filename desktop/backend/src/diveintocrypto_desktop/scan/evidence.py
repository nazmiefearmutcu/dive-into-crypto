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
"""

from __future__ import annotations

import json
import logging
import os
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path

logger = logging.getLogger("trading_bot.scan.evidence")

ENGINE_VERSION = "desktop-0.1.0"

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

CONFIDENCE_BUCKETS = ["0-25", "25-50", "50-75", "75-100"]

_SIGNAL_SCORES = {"STRONG_BUY": 2, "BUY": 1, "NEUTRAL": 0, "SELL": -1, "STRONG_SELL": -2}


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


# ── archive ───────────────────────────────────────────────────────────────────
def record_from_row(row: dict, ts_ms: int | None = None) -> dict:
    """One archive record per scan row — the engine's verdict at verdict time."""
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
    return {
        "ts": int(ts_ms if ts_ms is not None else time.time() * 1000),
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
        "signals": signals,  # the 57-indicator vector at verdict time (report-only)
    }


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
            out.append(obj)
    return out


# ── grading ───────────────────────────────────────────────────────────────────
def load_grades(path: Path | None = None) -> list[dict]:
    """Read grade lines; corrupt lines skipped."""
    path = path or grades_path()
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
    except (FileNotFoundError, OSError):
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
            out.append(obj)
    return out


def _horizon_exit(window: list[dict], horizon_ms: int) -> dict | None:
    """The last candle with open time inside the grading horizon."""
    end_ts = window[0]["t"] + horizon_ms
    exit_c = None
    for c in window:
        if c["t"] > end_ts:
            break
        exit_c = c
    return exit_c


def _walk_hit(entry: float, dir: int, window: list[dict], horizon_ms: int) -> bool:
    """Walk candles chronologically: did price move ≥ FORWARD_THRESHOLD in ``dir``
    before the opposite move? Both thresholds inside ONE candle → conservative
    miss (the order is unknowable from OHLC). No breach at all → miss."""
    end_ts = window[0]["t"] + horizon_ms
    for c in window:
        if c["t"] > end_ts:
            break
        fav = ((c["h"] - entry) / entry if dir > 0 else (entry - c["l"]) / entry) >= FORWARD_THRESHOLD
        adv = ((entry - c["l"]) / entry if dir > 0 else (c["h"] - entry) / entry) >= FORWARD_THRESHOLD
        if adv and not fav:
            return False
        if fav:
            return not adv  # both breached in this candle → conservative miss
    return False


def _grade_one(rec: dict, candles: list[dict], horizon: str, now_ms: int) -> dict | None:
    """Grade one archived verdict against 1h candles ``[{t(ms), h, l, c}]``."""
    entry = float(rec.get("price") or 0.0)
    ts = int(rec["ts"])
    if entry <= 0:
        return None
    horizon_ms = HORIZONS[horizon]
    window = [c for c in candles if c["t"] >= ts]
    if not window:
        return None
    exit_c = _horizon_exit(window, horizon_ms)
    if exit_c is None:
        return None
    dir = int(rec.get("dominant_dir") or 0)
    if dir != 0:
        hit = _walk_hit(entry, dir, window, horizon_ms)
        forward = dir * (exit_c["c"] - entry) / entry
    else:
        # NEUTRAL: "correct" = no decisive move in EITHER direction by horizon end
        hit = abs((exit_c["c"] - entry) / entry) < FORWARD_THRESHOLD
        forward = (exit_c["c"] - entry) / entry
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
        "graded_at": now_ms,
        "engine_version": ENGINE_VERSION,
    }


async def _default_fetcher(symbol: str, start_ms: int, end_ms: int) -> list[dict]:
    from diveintocrypto_desktop.data import binance_klines as kl

    candles = await kl.fetch_klines_range(symbol, "1h", start_ms, end_ms)
    return [{"t": c["t"] // 1_000_000, "h": c["h"], "l": c["l"], "c": c["c"]} for c in candles]


async def grade(
    horizon: str = DEFAULT_HORIZON,
    cap: int = BACKFILL_CAP,
    fetcher=None,
    now_ms: int | None = None,
) -> dict:
    """Backfill outcomes for matured, not-yet-graded verdicts (resumable, capped).

    Returns ``{horizon, archived, graded, symbols_graded, failed, remaining}``.
    """
    if horizon not in HORIZONS:
        raise ValueError(f"horizon must be one of {sorted(HORIZONS)}")
    fetcher = fetcher or _default_fetcher
    now_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    horizon_ms = HORIZONS[horizon]

    records = iter_archive()
    done = {(g["symbol"], int(g["ts"])) for g in load_grades() if g.get("horizon") == horizon}
    mature = [r for r in records if int(r["ts"]) <= now_ms - horizon_ms]
    todo: dict[str, list[dict]] = {}
    for r in mature:
        if (r["symbol"], int(r["ts"])) in done:
            continue
        todo.setdefault(r["symbol"], []).append(r)
    # oldest verdicts first — the archive grades forward in time
    symbols = sorted(todo, key=lambda s: min(int(r["ts"]) for r in todo[s]))[: max(1, cap)]

    graded = 0
    failed: list[dict] = []
    new_lines: list[dict] = []
    for s in symbols:
        recs = todo[s]
        start = min(int(r["ts"]) for r in recs) - _HOUR_MS
        end = max(int(r["ts"]) for r in recs) + horizon_ms + 2 * _HOUR_MS
        try:
            candles = await fetcher(s, start, end)
        except Exception as e:
            failed.append({"symbol": s, "reason": str(e)[:100]})
            continue
        for rec in recs:
            g = _grade_one(rec, candles, horizon, now_ms)
            if g is None:
                continue
            new_lines.append(g)
            graded += 1

    append_lines(grades_path(), new_lines)

    done_after = {(g["symbol"], int(g["ts"])) for g in load_grades() if g.get("horizon") == horizon}
    remaining = sum(1 for r in mature if (r["symbol"], int(r["ts"])) not in done_after)
    return {
        "horizon": horizon,
        "archived": len(records),
        "gradable": len(mature),
        "graded": graded,
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


def _stats(items: list[dict]) -> dict:
    n = len(items)
    hits = sum(1 for g in items if g.get("hit"))
    forwards = [float(g["forward"]) for g in items if g.get("forward") is not None]
    return {
        "n": n,
        "hit_rate": round(hits / n, 4) if n else None,
        "avg_forward": round(sum(forwards) / len(forwards), 6) if forwards else None,
        "median_forward": round(statistics.median(forwards), 6) if forwards else None,
    }


def summary(horizon: str = DEFAULT_HORIZON, now_ms: int | None = None) -> dict:
    """Aggregate the evidence archive + grades (computed from files, cached cheaply
    by the caller's cadence). ``stale`` = matured verdicts still awaiting grading."""
    if horizon not in HORIZONS:
        raise ValueError(f"horizon must be one of {sorted(HORIZONS)}")
    now_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    horizon_ms = HORIZONS[horizon]

    records = iter_archive()
    grades = load_grades()
    by_key = {(r["symbol"], int(r["ts"])): r for r in records}

    grade_keys = {(g["symbol"], int(g["ts"])) for g in grades if g.get("horizon") == horizon}
    mature = [r for r in records if int(r["ts"]) <= now_ms - horizon_ms]

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

    graded_count = len({(g["symbol"], int(g["ts"])) for _, g in joined})
    gradable = len(mature)
    return {
        "horizon": horizon,
        "archived_count": len(records),
        "gradable_count": gradable,
        "graded_count": graded_count,
        "coverage": round(graded_count / gradable, 4) if gradable else 0.0,
        "stale": graded_count < gradable,
        "by_verdict": by_verdict,
        "by_confidence": by_confidence,
        "by_indicator": by_indicator,
        "generated_at": _iso(time.time()),
        "engine_version": ENGINE_VERSION,
    }


def reset_state() -> None:
    """Remove archive + grades files (test hook — only legal on tmp paths)."""
    for p in (archive_path(), grades_path()):
        try:
            p.unlink()
        except FileNotFoundError:
            pass
