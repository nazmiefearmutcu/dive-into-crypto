"""Assemble the data-contract per-symbol object from real market data.

The canonical (parity-locked) engine produces the per-timeframe verdicts and the
15-indicator table; the divergence module produces the whale fields; everything is
shaped into the object the SGS screens already read (spec §8). All numbers are real
— nothing is synthesised. Where a microstructure value is derived (e.g. per-step
bias from the whale L/S series) it is a transform of real data, not a fabrication.
"""

from __future__ import annotations

from functools import lru_cache

import asyncio

from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import funding as fnd
from diveintocrypto_desktop.data import open_interest as oi_mod
from diveintocrypto_desktop.data import ratios as rat
from diveintocrypto_desktop.data.http import FAPI_V1, get_json
from diveintocrypto_desktop.engine.consensus.engine import ConsensusEngine
from diveintocrypto_desktop.engine.consensus import regime as rg
from diveintocrypto_desktop.engine.loader import load_config
from diveintocrypto_desktop.engine.signal_service import SignalService
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import microstructure as ms
from diveintocrypto_desktop.scan import mtf as mtf_mod
from diveintocrypto_desktop.scan.constants import ALL_TFS, DIVERGENCE_MIN_SHOWN, PERIOD_MS, TIME_WEIGHTS

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


def _action(signal: str) -> str:
    return "AL" if "BUY" in signal else "SAT" if "SELL" in signal else "BEKLE"


def _num(raw: dict | None, key: str) -> float:
    if not raw:
        return 0.0
    v = raw.get(key)
    if isinstance(v, (int, float)):
        return round(float(v), 4)
    for x in raw.values():  # fallback: first numeric raw value
        if isinstance(x, (int, float)):
            return round(float(x), 4)
    return 0.0


def assemble(
    symbol: str,
    name: str,
    ch: float,
    price: float,
    candles_by_tf: dict[str, list[dict]],
    series_data: dict[str, list[float]],
    divergence_inputs: dict[str, tuple[list[float], list[float]]],
    primary_tf: str = "1h",
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
    adverse = abs(sym_div.score) >= DIVERGENCE_MIN_SHOWN and sym_div.direction == -dir_ind
    if abs(sym_div.score) < DIVERGENCE_MIN_SHOWN or sym_div.direction == 0:
        whale_regime = "neutral"
    elif adverse:
        whale_regime = "adverse"
    elif sym_div.direction == dir_ind:
        whale_regime = "confirm"
    else:
        whale_regime = "neutral"

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

    return {
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
        "microstructure": micro,
        "regime": regime,
        "mtfConfluence": mtf_conf,
    }


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
) -> dict:
    """Fetch all real inputs for ``symbol`` and assemble its data-contract object."""
    candles_by_tf, oi, ratio_series, funding_rows, meta = await asyncio.gather(
        kl.fetch_all_tf(symbol, limit=300),
        oi_mod.fetch_oi_hist(symbol, "5m", limit=48),
        rat.fetch_ratio_series(symbol, "5m", limit=48),
        fnd.funding_hist(symbol, limit=48),
        _ticker_24hr(symbol),
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

    price = price if price is not None else meta.get("price", (fivem[-1]["c"] if fivem else 0.0))
    ch = ch if ch is not None else meta.get("ch", 0.0)
    name = name or symbol.replace("USDT", "")

    return assemble(symbol, name, ch, price, candles_by_tf, series_data, div_inputs, primary_tf)
