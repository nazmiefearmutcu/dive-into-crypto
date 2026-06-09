"""Market scanner — ranks the universe by indicator conviction (netNss), then
applies whale-divergence elimination + backfill (matches the Android Scanner).

Design for rate-limit reality: the universe sweep uses only klines (generously
limited); the expensive futures/data divergence is computed for the top
``DIVERGENCE_CANDIDATES`` only, exactly as the Android scanner does.
"""

from __future__ import annotations

import asyncio

from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import ratios as rat
from diveintocrypto_desktop.data import universe as uni
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import symbol_builder as sb
from diveintocrypto_desktop.scan.constants import (
    DIVERGENCE_CANDIDATES,
    DIVERGENCE_MIN_SHOWN,
    DIVERGENCE_RANK_WEIGHT,
    PERIOD_MS,
    TIME_WEIGHTS,
)

_MAX_PARALLEL = 8


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


async def _row(symbol: str, name: str, price: float, ch: float, sem: asyncio.Semaphore) -> dict | None:
    async with sem:
        try:
            candles_by_tf = await kl.fetch_all_tf(symbol, limit=300)
        except Exception:
            return None
    row = sb.assemble(symbol, name, ch, price, candles_by_tf, series_data={}, divergence_inputs={})
    row["_candles_by_tf"] = candles_by_tf  # kept transiently for the divergence phase
    dom, nss = net_nss(row["multiTf"])
    row["dominantDir"] = dom
    row["netNss"] = round(nss, 2)
    return row


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
    adverse = abs(sym_div.score) >= DIVERGENCE_MIN_SHOWN and sym_div.direction == -dir_ind
    row["quantBias"] = round(sym_div.score, 1)
    row["divergence"] = {"score": round(sym_div.score, 1), "tf": sym_div.best_tf, "coverage": coverage}
    row["whaleRegime"] = "adverse" if adverse else (
        "confirm" if sym_div.direction == dir_ind and abs(sym_div.score) >= DIVERGENCE_MIN_SHOWN else "neutral"
    )
    row["_adverse"] = adverse


def _rank_score(row: dict, max_net: float) -> float:
    """netNss + 0.35·divergence (README blend). Divergence is signed toward the
    dominant direction so a confirming whale lift ranks a coin up."""
    norm = (row["netNss"] / max_net) if max_net > 0 else 0.0
    div = row.get("divergence", {}).get("score", 0.0)
    aligned = div * row["dominantDir"]  # +ve when divergence confirms the call
    return norm * 100.0 + DIVERGENCE_RANK_WEIGHT * aligned


async def scan(size: int = 10, universe_limit: int = 30) -> dict:
    """Run the scan. Returns ``{survivors, eliminated, universeCount, scanned}``."""
    universe = await uni.list_universe(limit=universe_limit)
    sem = asyncio.Semaphore(_MAX_PARALLEL)
    rows = await asyncio.gather(*(_row(u["s"], u["name"], u["price"], u["ch"], sem) for u in universe))
    rows = [r for r in rows if r is not None]
    rows.sort(key=lambda r: r["netNss"], reverse=True)

    # Divergence only for the top candidates. Each candidate makes 3 rate-limited
    # futures/data calls; cap at 13 (13*3=39 ≤ the 40/60s budget) so a live scan
    # stays responsive instead of stalling on the limiter.
    candidates = rows[: min(DIVERGENCE_CANDIDATES, 13)]
    div_sem = asyncio.Semaphore(4)
    await asyncio.gather(*(_attach_divergence(r, div_sem) for r in candidates))

    max_net = max((r["netNss"] for r in candidates), default=1.0) or 1.0
    candidates.sort(key=lambda r: _rank_score(r, max_net), reverse=True)

    survivors, eliminated = [], []
    for r in candidates:
        (eliminated if r.get("_adverse") else survivors).append(r)

    survivors = survivors[:size]
    for r in survivors + eliminated:
        r.pop("_candles_by_tf", None)  # don't ship the transient candle cache

    return {
        "survivors": survivors,
        "eliminated": eliminated,
        "universeCount": len(universe),
        "scanned": len(rows) * 12,
    }
