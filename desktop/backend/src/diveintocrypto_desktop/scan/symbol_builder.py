"""Assemble the data-contract per-symbol object from real market data.

The canonical (parity-locked) engine produces the per-timeframe verdicts and the
15-indicator table; the divergence module produces the whale fields; everything is
shaped into the object the SGS screens already read (spec §8). All numbers are real
— nothing is synthesised. Where a microstructure value is derived (e.g. per-step
bias from the whale L/S series) it is a transform of real data, not a fabrication.

``assemble`` is pure CPU (pandas): async callers run it via ``asyncio.to_thread``
so a full scan never stalls the event loop.
"""

from __future__ import annotations

from functools import lru_cache
import logging

import asyncio

from diveintocrypto_desktop.data import basis as bs
from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import cvd as cvd_mod
from diveintocrypto_desktop.data import funding as fnd
from diveintocrypto_desktop.data import open_interest as oi_mod
from diveintocrypto_desktop.data import orderbook as ob
from diveintocrypto_desktop.data import ratios as rat
from diveintocrypto_desktop.data import spot as spot_mod
from diveintocrypto_desktop.data.http import FAPI_V1, get_json
from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.consensus import regime as rg
from diveintocrypto_desktop.engine.loader import load_config
from diveintocrypto_desktop.engine.signal_service import SignalService
from diveintocrypto_desktop.scan import cascade as cs
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import microstructure as ms
from diveintocrypto_desktop.scan import mtf as mtf_mod
from diveintocrypto_desktop.scan import planning as pl
from diveintocrypto_desktop.scan import structure as st
from diveintocrypto_desktop.scan.constants import ALL_TFS, DIVERGENCE_MIN_SHOWN, PERIOD_MS, TIME_WEIGHTS

logger = logging.getLogger("trading_bot.scan.symbol_builder")

# Primary raw value to surface per indicator in the 15-row table.
_PRIMARY_VALUE = {
    "rsi": "rsi", "macd": "histogram", "bollinger": "position", "ema_cross": "divergence_pct",
    "sma_cross": "divergence_pct", "stochastic": "k", "adx_di": "adx", "cci": "cci",
    "williams_r": "williams_r", "roc": "roc", "mfi": "mfi", "atr_filter": "atr_pct",
    "ichimoku": "tenkan", "psar": "distance_pct", "obv": "obv_trend",
}

_DIVERGENCE_BUILD_TFS = ["4h", "12h", "1d"]  # highest-weight TFs dominate forSymbol (keeps futures/data calls bounded)


@lru_cache(maxsize=1)
def _engine():
    cfg = load_config()
    return cfg, SignalService(cfg), ConsensusEngine(cfg)


def _dir(signal: str) -> int:
    if "BUY" in signal:
        return 1
    if "SELL" in signal:
        return -1
    return 0


def _tf_hours(tf: str) -> float:
    return PERIOD_MS.get(tf, 3_600_000) / 3_600_000.0


def _atr_pct(results: list) -> float | None:
    """Primary-TF ATR percent from the atr_filter indicator (None when absent)."""
    for r in results or []:
        if r.name == "atr_filter" and r.raw_values:
            v = r.raw_values.get("atr_pct")
            if isinstance(v, (int, float)):
                return float(v)
    return None


def _action(signal: str) -> str:
    return "AL" if "BUY" in signal else "SAT" if "SELL" in signal else "BEKLE"


def _num(raw: dict | None, key: str) -> float | None:
    """Primary raw value for the 15-row table. ``None`` when missing — the UI
    renders an empty cell; a fabricated number (e.g. a period) never lies here.
    """
    if not raw:
        return None
    v = raw.get(key)
    if isinstance(v, (int, float)):
        return round(float(v), 4)
    return None


def assemble(
    symbol: str,
    name: str,
    ch: float,
    price: float,
    candles_by_tf: dict[str, list[dict]],
    series_data: dict[str, list[float]],
    divergence_inputs: dict[str, tuple[list[float], list[float]]],
    primary_tf: str = "1h",
    cvd: dict | None = None,
    panel: dict | None = None,
) -> dict:
    cfg, signal_svc, consensus = _engine()
    weights = cfg.get("indicator_weights", {})

    # ── per-TF consensus → multiTf ───────────────────────────────────────────
    multi_tf: list[dict] = []
    primary_results = None
    primary_consensus = None
    for tf in ALL_TFS:
        candles = candles_by_tf.get(tf)
        if not candles or len(candles) < 60:
            multi_tf.append({"tf": tf, "signal": "NEUTRAL", "confidence": 0})
            continue
        results = signal_svc.calculate_all(kl.to_dataframe(candles))
        out = consensus.evaluate(results)
        multi_tf.append({"tf": tf, "signal": out["final_signal"], "confidence": int(out["confidence"])})
        if tf == primary_tf:
            primary_results, primary_consensus = results, out

    if primary_consensus is None:  # primary TF missing → fall back to the first usable TF
        for tf in ALL_TFS:
            candles = candles_by_tf.get(tf)
            if candles and len(candles) >= 60:
                primary_results = signal_svc.calculate_all(kl.to_dataframe(candles))
                primary_consensus = consensus.evaluate(primary_results)
                break

    buy = sum(1 for m in multi_tf if "BUY" in m["signal"])
    sell = sum(1 for m in multi_tf if "SELL" in m["signal"])
    neutral = len(multi_tf) - buy - sell

    # ── 15-indicator table (primary TF) ──────────────────────────────────────
    indicators = []
    if primary_results:
        for r in primary_results:
            indicators.append(
                {
                    "name": r.name,
                    "signal": r.signal.value,
                    "weight": float(weights.get(r.name, 1.0)),
                    "value": _num(r.raw_values, _PRIMARY_VALUE.get(r.name, "")),
                }
            )

    final_signal = primary_consensus["final_signal"] if primary_consensus else "NEUTRAL"
    confidence = int(primary_consensus["confidence"]) if primary_consensus else 0
    risk = primary_consensus["risk_level"] if primary_consensus else "LOW"
    reason = primary_consensus.get("reason", "") if primary_consensus else ""

    # ── whale divergence ─────────────────────────────────────────────────────
    per_tf_res = {}
    for tf, (p, w) in divergence_inputs.items():
        weight = TIME_WEIGHTS.get(tf, 50)
        per_tf_res[tf] = dv.per_tf(p, w, weight)
    sym_div = dv.for_symbol(per_tf_res)
    coverage = sum(1 for r in per_tf_res.values() if r.detected)

    dir_ind = _dir(final_signal)
    whale_regime, _adverse = dv.whale_regime_for(sym_div, dir_ind, DIVERGENCE_MIN_SHOWN)

    # ── series (real; bias derived from the real whale-position lean) ─────────
    pos = series_data.get("pos", [])
    bias = [max(-96.0, min(96.0, (v - 1.0) * 80.0)) for v in pos]
    series = {
        "oi": series_data.get("oi", []),
        "glob": series_data.get("glob", []),
        "acc": series_data.get("acc", []),
        "pos": pos,
        "taker": series_data.get("taker", []),
        "funding": series_data.get("funding", []),
        "price": series_data.get("price", []),
        "bias": bias,
    }

    # ── strategy overlays (all additive; do NOT alter the canonical consensus) ─
    micro = ms.evaluate(series_data, enabled=cfg.get("microstructure", {}).get("enabled", True))
    regime = rg.evaluate(primary_results or [], weights, cfg)
    mtf_conf = mtf_mod.confluence(multi_tf)

    primary_candles = candles_by_tf.get(primary_tf) or next((c for c in candles_by_tf.values() if c), [])

    # ── v0.3 additive blocks (report-only; everything degrades honestly) ─────
    candles_1h = candles_by_tf.get("1h") or []
    if candles_1h:
        vol_block = st.vol_term_structure(candles_1h)
        cone = st.vol_cone(candles_1h)
    else:
        vol_block = {"unavailable": "insufficient_history"}
        cone = None

    out = {
        "s": symbol,
        "name": name,
        "price": price,
        "ch": ch,
        "candles": primary_candles[-120:],
        "multiTf": multi_tf,
        "buy": buy,
        "sell": sell,
        "neutral": neutral,
        "indicators": indicators,
        "finalSignal": final_signal,
        "confidence": confidence,
        "action": _action(final_signal),
        "reason": reason,
        "risk": risk,
        "series": series,
        "quantBias": round(sym_div.score, 1),
        "whaleRegime": whale_regime,
        "divergence": {"score": round(sym_div.score, 1), "tf": sym_div.best_tf, "coverage": coverage},
        "divergence_tier": dv.tier_for(sym_div.score),
        "microstructure": micro,
        "regime": regime,
        "mtfConfluence": mtf_conf,
        "weights_hash": evidence_weights_hash(weights),
        "planning": pl.planning_strip(_atr_pct(primary_results), _tf_hours(primary_tf)),
        "vol": vol_block,
    }
    if cone is not None:
        out["cone"] = cone
    # cascade proxy — needs the OI/taker series; omitted when they weren't fetched
    if series_data.get("oi"):
        out["cascade"] = cs.cascade_proxy(series_data.get("oi"), candles_by_tf.get("5m"), series_data.get("taker"))
    # CVD snapshot (rolling cumulative volume delta from public aggTrades) —
    # an honest {"unavailable": reason} object when the feed fails. Additive.
    out["cvd"] = cvd if cvd is not None else {"unavailable": "not_fetched"}
    # panel-only async extras (order book, L/S term structure, basis, funding lens,
    # spot lead/lag) — passed through untouched; absent keys are simply omitted.
    for key in ("book", "ls_term", "basis", "funding_lens", "spot_perp"):
        if panel and panel.get(key) is not None:
            out[key] = panel[key]
    return out


def evidence_weights_hash(weights: dict) -> str:
    """Lazy import shim so scanner/archive share ONE weights-hash definition."""
    from diveintocrypto_desktop.scan.evidence import weights_hash

    return weights_hash(weights)


async def _divergence_inputs(symbol: str, candles_by_tf: dict[str, list[dict]]) -> dict[str, tuple[list[float], list[float]]]:
    """Fetch top-trader position L/S per high-weight TF and align it to that TF's
    candles by timestamp bucket → ``{tf: (price, whaleLS)}`` for the divergence engine.
    """
    async def one(tf: str):
        candles = candles_by_tf.get(tf)
        if not candles:
            return tf, None
        ls = await rat.position_ls_timeseries(symbol, tf, limit=80)
        price_times = [c["t"] // 1_000_000 for c in candles]  # ns → ms
        price_vals = [c["c"] for c in candles]
        p, w, matched = dv.align(price_times, price_vals, ls["t"], ls["v"], PERIOD_MS.get(tf, 0))
        if matched < 10:
            return tf, None
        return tf, (p, w)

    pairs = await asyncio.gather(*(one(tf) for tf in _DIVERGENCE_BUILD_TFS))
    return {tf: val for tf, val in pairs if val is not None}


async def _ticker_24hr(symbol: str) -> dict:
    try:
        d = await get_json(f"{FAPI_V1}/ticker/24hr", {"symbol": symbol})
        return {"price": float(d["lastPrice"]), "ch": float(d["priceChangePercent"])}
    except Exception:
        return {}


async def build_symbol(
    symbol: str,
    name: str | None = None,
    ch: float | None = None,
    price: float | None = None,
    primary_tf: str = "1h",
    end_ms: int | None = None,
) -> dict:
    """Fetch all real inputs for ``symbol`` and assemble its data-contract object.

    ``end_ms`` (optional) fetches HISTORICAL candles: every timeframe is pulled
    with that instant as ``endTime`` (klines truncated at ``end_ms``), so the
    verdicts describe the market *as of* ``end_ms`` (the anti-repaint rule still
    applies — no future data leaks).

    Historical views carry NO live current-state extras (docs/api.md): the 24h
    ticker (live price/ch), the aggTrades CVD (inherently now-windowed) and the
    panel-only live blocks (funding lens, basis, order book, L/S term structure,
    spot lead/lag) are skipped entirely. ``price`` is the honest last close of
    the truncated window (labeled via ``price_note``); ``ch`` is omitted — a
    24h change measured NOW says nothing about the past window; ``cvd`` ships
    as the explicit ``{"unavailable": "historical_view"}`` marker.
    """
    historical = end_ms is not None
    if historical:
        meta: dict = {}
        cvd_snap = {"unavailable": "historical_view"}
        candles_by_tf, oi, ratio_series, funding_rows = await asyncio.gather(
            kl.fetch_all_tf(symbol, limit=300, end_ms=end_ms),
            oi_mod.fetch_oi_hist(symbol, "5m", limit=48),
            rat.fetch_ratio_series(symbol, "5m", limit=48),
            fnd.funding_hist(symbol, limit=48),
        )
    else:
        candles_by_tf, oi, ratio_series, funding_rows, meta, cvd_snap = await asyncio.gather(
            kl.fetch_all_tf(symbol, limit=300, end_ms=end_ms),
            oi_mod.fetch_oi_hist(symbol, "5m", limit=48),
            rat.fetch_ratio_series(symbol, "5m", limit=48),
            fnd.funding_hist(symbol, limit=48),
            _ticker_24hr(symbol),
            cvd_mod.snapshot(symbol),
        )
    div_inputs = await _divergence_inputs(symbol, candles_by_tf)

    fivem = candles_by_tf.get("5m") or []
    series_data = {
        "oi": [p["oi"] for p in oi],
        "glob": ratio_series.get("glob", []),
        "acc": ratio_series.get("acc", []),
        "pos": ratio_series.get("pos", []),
        "taker": ratio_series.get("taker", []),
        "funding": [r["funding_rate"] for r in funding_rows],
        "price": [c["c"] for c in fivem[-48:]],
    }

    # HISTORICAL price = last close of the truncated window (never the live
    # ticker — meta is empty above); ``ch`` stays None → omitted downstream.
    last_close = fivem[-1]["c"] if fivem else 0.0
    price = price if price is not None else (last_close if historical else meta.get("price", last_close))
    ch = ch if ch is not None else (None if historical else meta.get("ch", 0.0))
    name = name or symbol.replace("USDT", "")

    # ── panel-only extras (each fails independently, never blocks the build) ──
    panel = await _panel_extras(
        symbol, price, series_data, funding_rows, candles_by_tf.get("1h") or [], end_ms
    )

    obj = await asyncio.to_thread(
        assemble, symbol, name, ch, price, candles_by_tf, series_data, div_inputs, primary_tf, cvd_snap, panel
    )
    if historical:
        obj.pop("ch", None)  # a live 24h change is meaningless for a past window
        if last_close:
            obj["price_note"] = "last close of the kline window ending at end_ms (historical view)"
    return obj


async def _panel_extras(
    symbol: str,
    price: float,
    series_data: dict,
    funding_rows: list[dict],
    candles_1h: list[dict],
    end_ms: int | None = None,
) -> dict:
    """Panel-only async blocks for ``GET /api/symbol`` (NOT scan rows).

    * ``funding_lens`` — predicted/settled/APR/countdown/regime;
    * ``basis`` — full block incl. the premiumIndexKlines z-score;
    * ``book`` — order-book imbalance panel (10s cache);
    * ``ls_term`` — 4 L/S families × 4 periods (rate-limited, panel-only);
    * ``spot_perp`` — spot lead/lag (perp-only listings are a normal state).

    Every extra is wrapped so its failure degrades to its own honest block or is
    simply omitted — a failed extra never fails the symbol build. Historical
    views (``end_ms``) skip the live *current-state* extras (docs/api.md): a
    funding lens with a settlement countdown, an order-book snapshot, a term
    structure or a spot lead/lag measured NOW say nothing about the past — none
    of them are fetched (or attached) for ``end_ms`` builds.
    """
    panel: dict = {}

    if end_ms:
        return panel  # historical view: no live book / countdown / term structure

    # funding lens — the settled tail was already fetched above
    try:
        prem = await fnd.premium_index(symbol)
        panel["funding_lens"] = fnd.funding_lens(
            prem["last_funding_rate"],
            [r["funding_rate"] for r in funding_rows],
            int(prem.get("next_funding_time") or 0) or None,
        )
        series_data["funding_predicted"] = prem["last_funding_rate"]
        if funding_rows:
            series_data["funding_last_settled"] = funding_rows[-1]["funding_rate"]
    except Exception as e:
        logger.warning("symbol: funding lens failed — %s", str(e)[:80])

    # basis (full block with z-score) — needs the trailing premium history
    try:
        prem = await fnd.premium_index(symbol)
        hist = await bs.premium_index_klines(symbol, "5m", 200)
        deliveries = (await bs.delivery_symbols()).get(symbol, {})
        panel["basis"] = await bs.basis_block(
            symbol, prem, deliveries, [h["premium_pct"] for h in hist]
        )
        series_data["basis_zscore"] = panel["basis"].get("zscore")
    except Exception as e:
        logger.warning("symbol: basis failed — %s", str(e)[:80])

    async def _book():
        panel["book"] = await ob.snapshot(symbol)

    async def _ls_term():
        panel["ls_term"] = await rat.ratio_term_structure(symbol)

    async def _spot():
        closes = [c["c"] for c in candles_1h]
        panel["spot_perp"] = await spot_mod.snapshot(symbol, price, closes)

    await asyncio.gather(_book(), _ls_term(), _spot())
    return panel
