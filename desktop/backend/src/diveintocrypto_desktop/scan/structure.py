"""Market-structure module — BTC-beta, correlation and sector clustering.

Every symbol gets a rolling 90-bar 1h-return **beta vs BTCUSDT** (OLS slope) and
**Pearson correlation**. Where possible this is computed from candles the
scanner ALREADY fetched (no extra upstream request); otherwise 1h klines are
fetched once and cached for 10 minutes. Sector clusters are derived purely from
the correlation structure — leader/seed clustering at corr > 0.6 over the 1h
returns (each symbol joins the first cluster whose SEED member correlates above
the cut, else seeds its own cluster — not single-link: non-seed members never
merge clusters), top 8 clusters kept, each labeled by its largest member (the
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
CLUSTER_THRESHOLD = 0.6 # leader/seed clustering correlation cut
MAX_CLUSTERS = 8        # labeled clusters kept
_KLINE_TTL = 600.0      # seconds a fetched 1h series stays cached
_BTC_SYMBOL = "BTCUSDT"

# ── vol term structure / cone constants (published, not fitted) ──────────────
VOL_HORIZONS: dict[str, int] = {"h1": 1, "h4": 4, "h12": 12, "h24": 24}
HOURS_PER_YEAR = 365 * 24
CONE_Z = 1.0            # envelope width in σ (log-normal P0·exp(±z·σ√h))
CONE_MIN_WINDOWS = 30   # overlapping windows needed for an honest percentile
MIN_CLUSTER_MEMBERS = 3 # clusters smaller than this → cluster_agreement null

logger = logging.getLogger("trading_bot.scan.structure")

# {symbol: (monotonic_ts, [(t_ms, close), ...])} — fetched-only 1h series cache
_kline_cache: dict[str, tuple[float, list[tuple[int, float]]]] = {}
# {symbol: (monotonic_ts, [candle, ...])} — full 1h candles (vol needs h/l)
_candle_cache: dict[str, tuple[float, list[dict]]] = {}


def reset_cache() -> None:
    """Drop the fetched-kline caches (test hook)."""
    _kline_cache.clear()
    _candle_cache.clear()


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
    """Leader/seed clustering over a pairwise-correlation map.

    Symbols (in the given order — callers pass volume order) join the first
    cluster whose SEED member correlates above ``threshold``; otherwise they
    seed a new cluster. NOT single-link: only the seed's correlation gates
    membership, so two loosely-correlated members can never bridge two clusters.
    Returns ``{symbol: cluster_id}`` with ids 1..N assigned by
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


async def _one_hour_candles(symbol: str, candles_1h: list[dict] | None = None) -> list[dict]:
    """Full 1h candles (the vol block needs highs/lows) — same reuse rules as
    :func:`_one_hour_returns`."""
    if candles_1h:
        return candles_1h
    now = time.monotonic()
    cached = _candle_cache.get(symbol)
    if cached and now - cached[0] < _KLINE_TTL:
        return cached[1]
    candles = await kl.fetch_klines(symbol, "1h", limit=BETA_WINDOW)
    if candles:
        _candle_cache[symbol] = (now, candles)
    return candles


# ── vol term structure + cone (report-only; reuses the cached 1h candles) ─────
def _log_returns(closes: list[float]) -> list[float]:
    out: list[float] = []
    for a, b in zip(closes, closes[1:]):
        if a > 0 and b > 0:
            out.append(math.log(b / a))
    return out


def _std(xs: list[float]) -> float | None:
    n = len(xs)
    if n < 2:
        return None
    m = sum(xs) / n
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (n - 1))


def vol_term_structure(candles_1h: list[dict], btc_vol_1h: float | None = None) -> dict:
    """``vol`` block — annualized close-to-close term structure from 1h candles.

    ``curve`` uses overlapping h-hour log-return windows (step 1h — the standard
    realized-vol trick; autocorrelation makes the long legs indicative, which the
    UI should surface as descriptive). ``slope`` is the relative change h1→h24;
    ``inverted`` is True when long-horizon vol prices BELOW short. ``vol_of_vol``
    is the coefficient of variation of the rolling 24-bar σ. ``parkinson`` is the
    high-low estimator, annualized. ``ratio_btc`` compares the symbol's 1h vol to
    BTC's (null when BTC's vol is unavailable). Fewer than MIN_RETURNS hourly
    returns → ``{"unavailable": "insufficient_history"}``.
    """
    closes = []
    for c in candles_1h:
        try:
            v = float(c["c"])
        except (KeyError, TypeError, ValueError):
            continue
        if math.isfinite(v) and v > 0:
            closes.append(v)
    rets = _log_returns(closes)
    if len(rets) < MIN_RETURNS:
        return {"unavailable": "insufficient_history"}

    curve: dict[str, float | None] = {}
    for name, h in VOL_HORIZONS.items():
        if h == 1:
            sd = _std(rets)
        else:
            sums = [sum(rets[i:i + h]) for i in range(len(rets) - h + 1)]
            sd = _std(sums)
        curve[name] = round(sd * math.sqrt(HOURS_PER_YEAR / h), 4) if sd is not None else None

    c1, c24 = curve["h1"], curve["h24"]
    slope = None
    if c1 is not None and c24 is not None and c1 > 0:
        slope = round((c24 - c1) / c1, 4)

    rolling: list[float] = []
    for i in range(24, len(rets) + 1):
        sd = _std(rets[i - 24:i])
        if sd is not None:
            rolling.append(sd)
    vov = None
    if len(rolling) >= MIN_RETURNS:
        m = sum(rolling) / len(rolling)
        if m > 0:
            vov = round(_std(rolling) / m, 4)  # type: ignore[arg-type]

    pk_terms = []
    for c in candles_1h:
        try:
            h, lo = float(c["h"]), float(c["l"])
        except (KeyError, TypeError, ValueError):
            continue
        if h > 0 and lo > 0 and h >= lo:
            pk_terms.append(math.log(h / lo) ** 2)
    parkinson = None
    if len(pk_terms) >= MIN_RETURNS:
        parkinson = round(math.sqrt(sum(pk_terms) / len(pk_terms) / (4 * math.log(2)) * HOURS_PER_YEAR), 4)

    ratio_btc = None
    if btc_vol_1h and c1 is not None:
        ratio_btc = round(c1 / btc_vol_1h, 3)

    return {
        "curve": curve,
        "slope": slope,
        "inverted": bool(c1 is not None and c24 is not None and c24 < c1),
        "vol_of_vol": vov,
        "parkinson": parkinson,
        "ratio_btc": ratio_btc,
    }


def vol_cone(candles_1h: list[dict]) -> dict | None:
    """``cone`` block — log-normal expected-move envelope, P0·exp(±z·σ√h).

    ``sigma_1h`` is the per-bar (1h) log-return σ; ``env_24h``/``env_48h`` carry
    the up/down fractional bounds at z=1; ``percentile`` ranks the latest 24h
    absolute log-move within the trailing overlapping 24h distribution
    (null below CONE_MIN_WINDOWS). Returns None when returns are insufficient —
    the caller then OMITS the field (never ships a fake cone).
    """
    closes = []
    for c in candles_1h:
        try:
            v = float(c["c"])
        except (KeyError, TypeError, ValueError):
            continue
        if math.isfinite(v) and v > 0:
            closes.append(v)
    rets = _log_returns(closes)
    if len(rets) < MIN_RETURNS:
        return None
    sigma = _std(rets)
    if sigma is None or sigma <= 0:
        return None

    def env(h: int) -> dict[str, float]:
        drift_z = CONE_Z * sigma * math.sqrt(h)
        return {
            "up": round(math.exp(drift_z) - 1.0, 4),
            "down": round(math.exp(-drift_z) - 1.0, 4),
        }

    windows24 = [abs(sum(rets[i:i + 24])) for i in range(len(rets) - 24 + 1)]
    percentile = None
    if len(windows24) >= CONE_MIN_WINDOWS:
        last = windows24[-1]
        below = sum(1 for w in windows24 if w <= last)
        percentile = round(below / len(windows24) * 100.0, 1)

    return {
        "sigma_1h": round(sigma, 6),
        "env_24h": env(24),
        "env_48h": env(48),
        "percentile": percentile,
    }


def cluster_confirmation(
    rows: list[dict],
    ids: dict[str, int | None],
) -> dict[str, dict]:
    """``cluster_agreement`` + ``cluster_rel_strength`` per symbol.

    ``cluster_agreement``: share of the row's cluster-mates (size ≥
    MIN_CLUSTER_MEMBERS, else null) whose ``dominantDir`` matches the row's own
    — a read of how much the sector backs the call. ``cluster_rel_strength``:
    the row's ``netNss`` z-score vs its cluster's mean/sd (null when the cluster
    has <2 readings or zero spread). Pure, null-guarded, no fabrication.
    """
    members_by_cluster: dict[int, list[dict]] = {}
    for r in rows:
        cid = ids.get(r["s"])
        if cid is not None:
            members_by_cluster.setdefault(cid, []).append(r)

    out: dict[str, dict] = {}
    for r in rows:
        cid = ids.get(r["s"])
        agreement = None
        rel_strength = None
        if cid is not None:
            members = members_by_cluster.get(cid, [])
            if len(members) >= MIN_CLUSTER_MEMBERS and r.get("dominantDir") is not None:
                same = sum(
                    1 for m in members
                    if m.get("dominantDir") is not None and int(m["dominantDir"]) == int(r["dominantDir"])
                )
                agreement = round(same / len(members), 3)
                # rel strength uses the same quorum: tiny clusters carry no signal
                strengths = [float(m["netNss"]) for m in members if m.get("netNss") is not None]
                own = r.get("netNss")
                if own is not None and len(strengths) >= 2:
                    mean = sum(strengths) / len(strengths)
                    sd = _std(strengths)
                    if sd is not None and sd > 0:
                        rel_strength = round((float(own) - mean) / sd, 3)
        out[r["s"]] = {"cluster_agreement": agreement, "cluster_rel_strength": rel_strength}
    return out


async def attach_structure(
    rows: list[dict], candle_map: dict[str, dict[str, list[dict]]]
) -> dict[str, dict]:
    """Attach ``beta`` / ``corr_btc`` / ``cluster_id`` / cluster confirmation /
    ``vol`` / ``cone`` inputs for scan rows.

    ``candle_map`` is ``{symbol: candles_by_tf}`` from the scan itself (1h candles
    reused — no extra fetch for symbols present). Returns
    ``{symbol: {beta, corr_btc, cluster_id, cluster_agreement,
    cluster_rel_strength, vol, cone}}``; missing data maps to ``None`` fields —
    never fabricated.
    """
    btc_1h = (candle_map.get(_BTC_SYMBOL) or {}).get("1h")
    try:
        btc_rets_ts = await _one_hour_returns(_BTC_SYMBOL, btc_1h)
    except Exception as e:
        logger.warning("structure: BTC 1h fetch failed — %s", str(e)[:80])
        btc_rets_ts = []
    btc_vals = [v for _, v in btc_rets_ts]

    # BTC 1h vol (for per-symbol ratio_btc) — from the same reused candles.
    btc_candles = btc_1h or []
    try:
        if not btc_candles:
            btc_candles = await _one_hour_candles(_BTC_SYMBOL)
    except Exception as e:
        logger.warning("structure: BTC vol candles failed — %s", str(e)[:80])
    btc_vol = None
    btc_vol_block = vol_term_structure(btc_candles)
    if "curve" in btc_vol_block:
        btc_vol = btc_vol_block["curve"].get("h1")

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

    # cluster confirmation (needs dominantDir + netNss on the rows)
    for s, conf in cluster_confirmation(rows, ids).items():
        out.setdefault(s, {}).update(conf)

    # vol term structure + cone — computed from the SAME reused 1h candles
    for r in rows:
        s = r["s"]
        try:
            candles = (candle_map.get(s) or {}).get("1h")
            if not candles:
                candles = await _one_hour_candles(s)
        except Exception as e:
            logger.warning("structure: %s vol fetch failed — %s", s, str(e)[:80])
            candles = []
        vol = vol_term_structure(candles, btc_vol_1h=btc_vol) if candles else \
            {"unavailable": "insufficient_history"}
        out.setdefault(s, {})
        out[s]["vol"] = vol
        cone = vol_cone(candles) if candles else None
        out[s]["cone"] = cone  # omitted downstream when None
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
    try:
        from diveintocrypto_desktop.data import index_info as ii

        verification = await ii.verify_cluster_labels(by_cluster)
    except Exception as e:  # verification is an annotation — never fatal
        logger.warning("structure: index verification failed — %s", str(e)[:80])
        verification = {}
    for cid in sorted(by_cluster):
        members = by_cluster[cid]  # volume-ordered (universe order) → member[0] is largest
        ver = verification.get(cid, {})
        clusters.append(
            {
                "cluster_id": cid,
                "label": members[0].replace("USDT", ""),
                "size": len(members),
                "index_verified": bool(ver.get("index_verified")),
                "verified_name": ver.get("verified_name"),
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
