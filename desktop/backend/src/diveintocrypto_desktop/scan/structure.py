"""Market-structure module — BTC-beta, correlation and sector clustering.

Every symbol gets a rolling 90-bar 1h-return **beta vs BTCUSDT** (OLS slope) and
**Pearson correlation**. Where possible this is computed from candles the
scanner ALREADY fetched (no extra upstream request); otherwise 1h klines are
fetched once and cached for 10 minutes. Sector clusters are derived purely from
the correlation structure — greedy single-link clustering at corr > 0.6 over the
1h returns, top 8 clusters kept, each labeled by its largest member (the
highest-volume symbol inside it, since inputs arrive volume-ranked).

Report-only analytics: nothing here feeds back into the consensus verdict.
"""

from __future__ import annotations

import asyncio
import logging
import math
import time
from datetime import datetime, timezone

from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import universe as uni

BETA_WINDOW = 90        # rolling 1h-return window (bars)
MIN_RETURNS = 30        # below this the beta/corr read is noise — report None
CLUSTER_THRESHOLD = 0.6 # greedy single-link correlation cut
MAX_CLUSTERS = 8        # labeled clusters kept
_KLINE_TTL = 600.0      # seconds a fetched 1h series stays cached
_BTC_SYMBOL = "BTCUSDT"

logger = logging.getLogger("trading_bot.scan.structure")

# {symbol: (monotonic_ts, [(t_ms, close), ...])} — fetched-only 1h series cache
_kline_cache: dict[str, tuple[float, list[tuple[int, float]]]] = {}


def reset_cache() -> None:
    """Drop the fetched-kline cache (test hook)."""
    _kline_cache.clear()


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def returns_from_candles(candles: list[dict]) -> list[tuple[int, float]]:
    """``[(t_ms, close)]`` simple per-bar returns from ``[{t(ns), c}]`` candles."""
    out: list[tuple[int, float]] = []
    prev_c = math.nan
    for c in candles:
        try:
            t_ms = int(c["t"]) // 1_000_000  # ns → ms
            close = float(c["c"])
        except (KeyError, TypeError, ValueError):
            continue
        if not math.isfinite(close) or close <= 0:
            continue
        if math.isfinite(prev_c):  # flat bar → 0.0 return (kept: alignment matters)
            out.append((t_ms, close / prev_c - 1.0))
        prev_c = close
    return out[-(BETA_WINDOW):]


def _align(a: list[tuple[int, float]], b: list[tuple[int, float]]) -> tuple[list[float], list[float]]:
    """Inner-join two ``[(t, ret)]`` series on timestamp; returns the paired values."""
    b_map = dict(b)
    ra: list[float] = []
    rb: list[float] = []
    for t, v in a:
        w = b_map.get(t)
        if w is not None:
            ra.append(v)
            rb.append(w)
    return ra, rb


def _mean(x: list[float]) -> float:
    return sum(x) / len(x) if x else 0.0


def _central(x: list[float]) -> tuple[list[float], float]:
    m = _mean(x)
    return [v - m for v in x], m


def _corr_cov(a: list[float], b: list[float]) -> tuple[float, float]:
    """(correlation, covariance) of two equal-length series (population, over n)."""
    n = min(len(a), len(b))
    if n < 2:
        return 0.0, 0.0
    da, ma = _central(a[:n])
    db, mb = _central(b[:n])
    cov = sum(x * y for x, y in zip(da, db)) / n
    va = sum(x * x for x in da) / n
    vb = sum(y * y for y in db) / n
    if va <= 0 or vb <= 0:
        return 0.0, cov
    return cov / math.sqrt(va * vb), cov


def beta_corr(sym_rets: list[float], btc_rets: list[float]) -> tuple[float | None, float | None]:
    """(beta, corr) of symbol returns vs BTC returns — OLS slope + Pearson r."""
    n = min(len(sym_rets), len(btc_rets))
    if n < MIN_RETURNS:
        return None, None
    a, b = sym_rets[:n], btc_rets[:n]
    corr, cov = _corr_cov(a, b)
    db, _ = _central(b)
    var_b = sum(x * x for x in db) / len(db)
    if var_b <= 0:
        return None, None
    beta = cov / var_b
    if not (math.isfinite(beta) and math.isfinite(corr)):
        return None, None
    return round(beta, 4), round(corr, 4)


def pair_correlation(a: list[float], b: list[float]) -> float | None:
    n = min(len(a), len(b))
    if n < MIN_RETURNS:
        return None
    corr, _ = _corr_cov(a[:n], b[:n])
    return round(corr, 4) if math.isfinite(corr) else None


def greedy_clusters(
    symbols: list[str],
    corr_of: dict[tuple[str, str], float],
    threshold: float = CLUSTER_THRESHOLD,
    top: int = MAX_CLUSTERS,
) -> dict[str, int | None]:
    """Greedy single-link clustering over a pairwise-correlation map.

    Symbols (in the given order — callers pass volume order) join the first
    cluster whose SEED member correlates above ``threshold``; otherwise they seed
    a new cluster. Returns ``{symbol: cluster_id}`` with ids 1..N assigned by
    cluster size (desc, ties by first-seen); only the top ``top`` clusters get
    ids — the rest (and unpairable symbols) map to ``None``.
    """
    clusters: list[list[str]] = []
    for s in symbols:
        placed = False
        for cl in clusters:
            key = tuple(sorted((s, cl[0])))
            c = corr_of.get(key)
            if c is not None and c > threshold:
                cl.append(s)
                placed = True
                break
        if not placed:
            clusters.append([s])

    order = sorted(range(len(clusters)), key=lambda i: (-len(clusters[i]), i))
    ids: dict[str, int | None] = {s: None for s in symbols}
    for rank, ci in enumerate(order[:top], start=1):
        for s in clusters[ci]:
            ids[s] = rank
    return ids


async def _one_hour_returns(symbol: str, candles_1h: list[dict] | None = None) -> list[tuple[int, float]]:
    """1h returns for ``symbol`` — from already-fetched scan candles when given,
    else from a cached 10-minute public kline fetch."""
    if candles_1h:
        return returns_from_candles(candles_1h)
    now = time.monotonic()
    cached = _kline_cache.get(symbol)
    if cached and now - cached[0] < _KLINE_TTL:
        return cached[1]
    candles = await kl.fetch_klines(symbol, "1h", limit=BETA_WINDOW + 1)
    rets = returns_from_candles(candles)
    if rets:
        _kline_cache[symbol] = (now, rets)
    return rets


async def attach_structure(
    rows: list[dict], candle_map: dict[str, dict[str, list[dict]]]
) -> dict[str, dict]:
    """Attach ``beta`` / ``corr_btc`` / ``cluster_id`` inputs for scan rows.

    ``candle_map`` is ``{symbol: candles_by_tf}`` from the scan itself (1h candles
    reused — no extra fetch for symbols present). Returns
    ``{symbol: {beta, corr_btc, cluster_id}}``; missing data maps to ``None``
    fields — never fabricated.
    """
    btc_1h = (candle_map.get(_BTC_SYMBOL) or {}).get("1h")
    try:
        btc_rets_ts = await _one_hour_returns(_BTC_SYMBOL, btc_1h)
    except Exception as e:
        logger.warning("structure: BTC 1h fetch failed — %s", str(e)[:80])
        btc_rets_ts = []
    btc_vals = [v for _, v in btc_rets_ts]

    rets_by_symbol: dict[str, list[tuple[int, float]]] = {}
    for r in rows:
        s = r["s"]
        if s == _BTC_SYMBOL:
            rets_by_symbol[s] = btc_rets_ts
            continue
        try:
            rets_by_symbol[s] = await _one_hour_returns(s, (candle_map.get(s) or {}).get("1h"))
        except Exception as e:
            logger.warning("structure: %s 1h fetch failed — %s", s, str(e)[:80])
            rets_by_symbol[s] = []

    out: dict[str, dict] = {}
    corr_pairs: dict[tuple[str, str], float] = {}
    aligned: dict[str, list[float]] = {}
    for r in rows:
        s = r["s"]
        sym_ts = rets_by_symbol.get(s, [])
        if s == _BTC_SYMBOL:
            out[s] = {"beta": 1.0, "corr_btc": 1.0}
            aligned[s] = btc_vals
            continue
        ra, rb = _align(sym_ts, btc_rets_ts)
        beta, corr = (None, None)
        if len(ra) >= MIN_RETURNS:
            beta, corr = beta_corr(ra, rb)
            aligned[s] = ra
        out[s] = {"beta": beta, "corr_btc": corr}

    # pairwise correlations among rows that have aligned data (cluster inputs)
    usable = [s for s in (r["s"] for r in rows) if len(aligned.get(s, [])) >= MIN_RETURNS]
    for i, a in enumerate(usable):
        for b in usable[i + 1:]:
            c = pair_correlation(aligned[a], aligned[b])
            if c is not None:
                corr_pairs[tuple(sorted((a, b)))] = c
    ids = greedy_clusters(usable, corr_pairs)
    for s, cid in ids.items():
        out.setdefault(s, {})["cluster_id"] = cid
    for s in (r["s"] for r in rows):
        out.setdefault(s, {"beta": None, "corr_btc": None, "cluster_id": None})
    return out


async def structure_summary(limit: int = 60) -> dict:
    """BTC-beta/correlation + sector clusters over the top-``limit`` universe.

    Served by ``GET /api/structure``. Everything is computed from real 1h klines
    (scan cache when warm, else a cached fetch); symbols whose fetch fails are
    listed under ``unavailable`` — never substituted.
    """
    universe = await uni.list_universe(limit=limit)
    rets_by_symbol: dict[str, list[tuple[int, float]]] = {}

    async def one(u: dict) -> None:
        try:
            rets_by_symbol[u["s"]] = await _one_hour_returns(u["s"])
        except Exception as e:
            logger.warning("structure: %s 1h fetch failed — %s", u["s"], str(e)[:80])

    await asyncio.gather(*(one(u) for u in universe))

    btc_ts = rets_by_symbol.get(_BTC_SYMBOL, [])
    rows_meta: dict[str, dict] = {}
    aligned: dict[str, list[float]] = {}
    for u in universe:
        s = u["s"]
        if not rets_by_symbol.get(s):
            continue
        if s == _BTC_SYMBOL:
            rows_meta[s] = {"beta": 1.0, "corr_btc": 1.0}
            aligned[s] = [v for _, v in btc_ts]
            continue
        ra, rb = _align(rets_by_symbol[s], btc_ts)
        if len(ra) >= MIN_RETURNS:
            beta, corr = beta_corr(ra, rb)
            rows_meta[s] = {"beta": beta, "corr_btc": corr}
            aligned[s] = ra

    usable = [u["s"] for u in universe if len(aligned.get(u["s"], [])) >= MIN_RETURNS]
    corr_pairs: dict[tuple[str, str], float] = {}
    for i, a in enumerate(usable):
        for b in usable[i + 1:]:
            c = pair_correlation(aligned[a], aligned[b])
            if c is not None:
                corr_pairs[tuple(sorted((a, b)))] = c
    ids = greedy_clusters(usable, corr_pairs)

    by_cluster: dict[int, list[str]] = {}
    unclustered: list[str] = []
    for s in usable:
        cid = ids.get(s)
        if cid is None:
            unclustered.append(s)
        else:
            by_cluster.setdefault(cid, []).append(s)

    clusters = []
    for cid in sorted(by_cluster):
        members = by_cluster[cid]  # volume-ordered (universe order) → member[0] is largest
        clusters.append(
            {
                "cluster_id": cid,
                "label": members[0].replace("USDT", ""),
                "size": len(members),
                "members": [
                    {"s": s, "beta": rows_meta.get(s, {}).get("beta"), "corr_btc": rows_meta.get(s, {}).get("corr_btc")}
                    for s in members
                ],
            }
        )

    return {
        "generated_at": _iso(time.time()),
        "count": len(universe),
        "btc_symbol": _BTC_SYMBOL,
        "window_bars": BETA_WINDOW,
        "cluster_threshold": CLUSTER_THRESHOLD,
        "clusters": clusters,
        "unclustered": [
            {"s": s, "beta": rows_meta.get(s, {}).get("beta"), "corr_btc": rows_meta.get(s, {}).get("corr_btc")}
            for s in unclustered
        ],
        "unavailable": sorted(s for u in universe for s in [u["s"]] if not rets_by_symbol.get(s)),
    }
