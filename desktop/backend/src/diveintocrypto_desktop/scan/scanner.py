"""Market scanner — ranks the universe by indicator conviction (netNss), then
applies whale-divergence elimination + backfill (matches the Android Scanner).

Design for rate-limit reality: a full-universe scan runs in two phases.

Phase 1 (coarse) sweeps the WHOLE requested universe over the three
highest-weight timeframes (4h/12h/1d) with ADAPTIVE concurrency: the parallelism
grows on clean completions and halves (with a global cooldown pause) whenever a
429/451 rate-limit signal is seen, so a 500-symbol sweep backs off instead of
hammering the limiter.

Phase 2 (depth) fetches the remaining 9 timeframes for the top ``depth_top``
rows only, re-assembles the full 12-TF contract, and computes the whale
divergence for the ranking candidates — exactly the expensive phase the old
single-phase scanner ran over its whole (tiny) universe.

Symbols whose data fetch fails are counted in ``droppedCount`` (and logged with
the reason), never silently folded into the universe count.
"""

from __future__ import annotations

import asyncio
import logging
import time

from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import universe as uni
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import structure as st
from diveintocrypto_desktop.scan import symbol_builder as sb
from diveintocrypto_desktop.scan.constants import (
    DIVERGENCE_CANDIDATES,
    DIVERGENCE_MIN_SHOWN,
    DIVERGENCE_RANK_WEIGHT,
    PERIOD_MS,
    TIME_WEIGHTS,
)
from diveintocrypto_desktop.scan.progress import ScanProgress

_MAX_PARALLEL = 8  # initial width of the adaptive gate (phase 1)
_MIN_PARALLEL = 2
_MAX_PARALLEL_MAX = 16
_COOLDOWN_BASE = 2.0
_COOLDOWN_CAP = 30.0
_GROWTH_BATCH = 10  # clean completions per +1 width

# Phase 1 coarse sweep: the three highest-weight timeframes (TIME_WEIGHTS top).
COARSE_TFS = ["4h", "12h", "1d"]
# Phase 2 detail: the remaining nine (ALL_TFS minus the coarse three).
DETAIL_TFS = [tf for tf in kl.TF_LIST if tf not in COARSE_TFS]

DEFAULT_DEPTH_TOP = 50
MAX_DEPTH_TOP = 200

logger = logging.getLogger("trading_bot.scan.scanner")


def _is_pressure(exc: BaseException | None) -> bool:
    """True when ``exc`` looks like a Binance rate-limit / geo-block signal (429/451)."""
    if exc is None:
        return False
    status = getattr(exc, "status", None)
    if status in (429, 451):
        return True
    text = str(exc)
    return "429" in text or "451" in text


class AdaptiveGate:
    """Dynamic-width async gate with rate-limit-aware global pacing.

    Width starts at ``initial``; every ``_GROWTH_BATCH`` clean completions widen
    it by one (up to ``maximum``). A 429/451 failure halves the width (floor
    ``minimum``) and starts a global cooldown during which no new work enters —
    the backoff doubles per consecutive pressure event (capped), so a limiter
    bite slows the whole sweep, not just the unlucky request.
    """

    def __init__(
        self,
        initial: int = _MAX_PARALLEL,
        minimum: int = _MIN_PARALLEL,
        maximum: int = _MAX_PARALLEL_MAX,
        cooldown_base: float = _COOLDOWN_BASE,
        cooldown_cap: float = _COOLDOWN_CAP,
    ) -> None:
        self._cond = asyncio.Condition()
        self._active = 0
        self._limit = initial
        self._minimum = max(1, minimum)
        self._maximum = max(initial, maximum)
        self._cooldown_base = cooldown_base
        self._cooldown_cap = cooldown_cap
        self._cooldown_until = 0.0
        self._pressure_streak = 0
        self._clean = 0
        # Honest observability for tests/logs.
        self.pressure_events = 0
        self.last_pressure: str | None = None

    @property
    def width(self) -> int:
        return self._limit

    async def acquire(self) -> None:
        async with self._cond:
            while True:
                if time.monotonic() >= self._cooldown_until and self._active < self._limit:
                    self._active += 1
                    return
                # A pure time predicate never gets a notify — wake when the
                # cooldown expires (or immediately on the next release).
                timeout = max(0.0, self._cooldown_until - time.monotonic())
                try:
                    await asyncio.wait_for(self._cond.wait(), timeout=timeout if timeout > 0 else None)
                except asyncio.TimeoutError:
                    pass

    async def release(self, exc: BaseException | None = None) -> None:
        async with self._cond:
            self._active -= 1
            if _is_pressure(exc):
                self.pressure_events += 1
                self._pressure_streak += 1
                self._clean = 0
                self._limit = max(self._minimum, self._limit // 2)
                delay = min(self._cooldown_cap, self._cooldown_base * (2 ** (self._pressure_streak - 1)))
                self._cooldown_until = max(self._cooldown_until, time.monotonic() + delay)
                self.last_pressure = f"{type(exc).__name__}: {str(exc)[:80]}"
                logger.warning("scan pacing: %s — width→%d, cooldown %.1fs", self.last_pressure, self._limit, delay)
            else:
                self._pressure_streak = 0
                self._clean += 1
                if self._clean >= _GROWTH_BATCH and self._limit < self._maximum:
                    self._limit += 1
                    self._clean = 0
            self._cond.notify_all()


class _GateSlot:
    """Async context manager: acquires the gate, releases with the failure (if any)."""

    def __init__(self, gate: AdaptiveGate) -> None:
        self._gate = gate
        self.exc: BaseException | None = None

    async def __aenter__(self) -> "_GateSlot":
        await self._gate.acquire()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> bool:
        await self._gate.release(exc_val)
        return False


def net_nss(multi_tf: list[dict]) -> tuple[int, float]:
    """(dominantDir, netNss) — winning side's Σ(confidence²·timeWeight/100)."""
    buy = sell = 0.0
    for m in multi_tf:
        tw = TIME_WEIGHTS.get(m["tf"], 50)
        score = (m["confidence"] ** 2) * tw / 100.0
        if "BUY" in m["signal"]:
            buy += score
        elif "SELL" in m["signal"]:
            sell += score
    return (1, buy) if buy >= sell else (-1, sell)


async def _row_phase(
    u: dict,
    tfs: list[str],
    gate: AdaptiveGate,
    progress: ScanProgress | None,
) -> tuple[dict | None, dict, str | None]:
    """Build one scan row over ``tfs``. Returns ``(row, candles_by_tf, failure_reason)``;
    ``row`` is None (with the reason set) when the fetch failed."""
    symbol, name, price, ch = u["s"], u["name"], u["price"], u["ch"]
    candles_by_tf: dict = {}
    async with _GateSlot(gate) as slot:
        try:
            candles_by_tf = await kl.fetch_all_tf(symbol, limit=300, intervals=tfs)
        except Exception as e:
            slot.exc = e
    if not candles_by_tf:
        return None, {}, str(slot.exc or "fetch failed")[:120]
    # assemble() is pure pandas — keep it off the event loop.
    row = await asyncio.to_thread(sb.assemble, symbol, name, ch, price, candles_by_tf, {}, {})
    dom, nss = net_nss(row["multiTf"])
    row["dominantDir"] = dom
    row["netNss"] = round(nss, 2)
    if progress:
        progress.tick()
    return row, candles_by_tf, None


async def _attach_divergence(row: dict, sem: asyncio.Semaphore) -> None:
    symbol = row["s"]
    candles_by_tf = row.get("_candles_by_tf", {})
    # Default to "no divergence" so a slow/failed futures-data call degrades
    # gracefully (the row stays, just without a whale verdict) rather than
    # failing the whole scan.
    row.setdefault("quantBias", 0.0)
    row.setdefault("divergence", {"score": 0.0, "tf": None, "coverage": 0})
    row.setdefault("whaleRegime", "neutral")
    row.setdefault("_adverse", False)
    try:
        async with sem:
            div_inputs = await sb._divergence_inputs(symbol, candles_by_tf)
    except Exception:
        return
    per_tf_res = {tf: dv.per_tf(p, w, TIME_WEIGHTS.get(tf, 50)) for tf, (p, w) in div_inputs.items()}
    sym_div = dv.for_symbol(per_tf_res)
    coverage = sum(1 for r in per_tf_res.values() if r.detected)
    dir_ind = row["dominantDir"]
    whale_regime, adverse = dv.whale_regime_for(sym_div, dir_ind, DIVERGENCE_MIN_SHOWN)
    row["quantBias"] = round(sym_div.score, 1)
    row["divergence"] = {"score": round(sym_div.score, 1), "tf": sym_div.best_tf, "coverage": coverage}
    row["whaleRegime"] = whale_regime
    row["_adverse"] = adverse


def _rank_score(row: dict, max_net: float) -> float:
    """netNss + 0.35·divergence (README blend). Divergence is signed toward the
    dominant direction so a confirming whale lift ranks a coin up."""
    norm = (row["netNss"] / max_net) if max_net > 0 else 0.0
    div = row.get("divergence", {}).get("score", 0.0)
    aligned = div * row["dominantDir"]  # +ve when divergence confirms the call
    return norm * 100.0 + DIVERGENCE_RANK_WEIGHT * aligned


async def scan(
    size: int = 10,
    universe_limit: int = 30,
    depth_top: int = DEFAULT_DEPTH_TOP,
    progress: ScanProgress | None = None,
) -> dict:
    """Run the two-phase scan.

    Returns ``{survivors, eliminated, universeCount, scanned, scannedCount,
    droppedCount, coarseCount, depthTop}``. Phase 1 sweeps ``universe_limit``
    symbols over the coarse TFs; the top ``depth_top`` rows get the full 12-TF
    assembly + whale divergence. Symbols whose data fetch failed are counted in
    ``droppedCount`` (and logged with the reason) — never synthesised.
    """
    if progress:
        progress.set_phase("universe", total=1)
    universe = await uni.list_universe(limit=universe_limit)
    by_symbol = {u["s"]: u for u in universe}
    gate = AdaptiveGate()

    # ── phase 1: coarse sweep over the whole requested universe ──────────────
    if progress:
        progress.set_phase("phase1_coarse", total=len(universe))
    phase1 = await asyncio.gather(*(_row_phase(u, COARSE_TFS, gate, progress) for u in universe))
    rows: list[dict] = []
    candles_by_symbol: dict[str, dict] = {}
    dropped = 0
    for (row, candles, reason), u in zip(phase1, universe):
        if row is None:
            dropped += 1
            logger.warning("scan: dropped %s (coarse) — %s", u["s"], reason)
            continue
        rows.append(row)
        candles_by_symbol[u["s"]] = candles
    coarse_count = len(rows)

    # ── phase 2: full 12-TF depth for the top depth_top rows ────────────────
    rows.sort(key=lambda r: r["netNss"], reverse=True)
    depth_top = max(1, min(int(depth_top), MAX_DEPTH_TOP))
    selected_set = {id(r) for r in rows[:depth_top]}
    selected = [r for r in rows if id(r) in selected_set]
    for r in rows:
        if id(r) not in selected_set:  # free the coarse candles we no longer need
            candles_by_symbol.pop(r["s"], None)

    if progress:
        progress.set_phase("phase2_detail", total=len(selected))

    async def deepen(row: dict) -> None:
        u = by_symbol[row["s"]]
        new_row, detail, reason = await _row_phase(u, DETAIL_TFS, gate, progress)
        if new_row is None:
            row["_drop_reason"] = reason
            return
        merged = {**candles_by_symbol.get(row["s"], {}), **detail}
        full = await asyncio.to_thread(sb.assemble, row["s"], row["name"], row["ch"], row["price"], merged, {}, {})
        dom, nss = net_nss(full["multiTf"])
        full["dominantDir"] = dom
        full["netNss"] = round(nss, 2)
        full["_candles_by_tf"] = merged  # kept transiently for divergence + structure
        row.update(full)

    await asyncio.gather(*(deepen(r) for r in selected))
    deep_failed = [r for r in selected if "_drop_reason" in r]
    if deep_failed:
        failed_ids = {id(r) for r in deep_failed}
        selected = [r for r in selected if id(r) not in failed_ids]
        rows = [r for r in rows if id(r) not in failed_ids]
        for r in deep_failed:
            candles_by_symbol.pop(r["s"], None)
            dropped += 1
            logger.warning("scan: dropped %s (depth) — %s", r["s"], r.pop("_drop_reason"))
    rows.sort(key=lambda r: r["netNss"], reverse=True)
    selected.sort(key=lambda r: r["netNss"], reverse=True)

    # Divergence only for the top candidates of the deepened rows. Each candidate
    # makes 3 rate-limited futures/data calls; cap at 13 (13*3=39 ≤ the 40/60s
    # budget) so a live scan stays responsive instead of stalling on the limiter.
    candidates = selected[: min(DIVERGENCE_CANDIDATES, 13)]
    if progress:
        progress.set_phase("divergence", total=len(candidates))
    div_sem = asyncio.Semaphore(4)
    await asyncio.gather(*(_attach_divergence(r, div_sem) for r in candidates))
    if progress:
        progress.tick(len(candidates))

    max_net = max((r["netNss"] for r in candidates), default=1.0) or 1.0
    candidates.sort(key=lambda r: _rank_score(r, max_net), reverse=True)

    survivors, eliminated = [], []
    for r in candidates:
        (eliminated if r.get("_adverse") else survivors).append(r)

    survivors = survivors[:size]

    # ── market structure (BTC beta / correlation / clusters) for the returned rows ──
    if progress:
        progress.set_phase("structure", total=1)
    candle_map = {r["s"]: r.get("_candles_by_tf", {}) for r in candidates}
    structure_fields = {}
    try:
        structure_fields = await st.attach_structure(candidates, candle_map)
    except Exception as e:  # structure is an annotation — never break the scan
        logger.warning("scan: structure attach failed — %s", str(e)[:120])
    for r in candidates:
        f = structure_fields.get(r["s"], {})
        r["beta"] = f.get("beta")
        r["corr_btc"] = f.get("corr_btc")
        r["cluster_id"] = f.get("cluster_id")
    if progress:
        progress.tick()

    for r in survivors + eliminated:
        r.pop("_candles_by_tf", None)  # don't ship the transient candle cache

    return {
        "survivors": survivors,
        "eliminated": eliminated,
        "universeCount": len(universe),
        "scanned": len(selected) * 12,  # legacy field: TF verdicts of fully-scanned rows
        "scannedCount": len(selected),  # symbols with the full 12-TF assembly
        "droppedCount": dropped,
        "coarseCount": coarse_count,    # symbols that passed the coarse phase-1 sweep
        "depthTop": depth_top,
    }
