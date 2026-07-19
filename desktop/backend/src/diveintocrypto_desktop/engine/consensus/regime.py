"""Regime-Adaptive Weighting — strategy overlay.

Detects the market regime (TREND / RANGE / MIXED) from the already-computed ADX and
Choppiness outputs, then re-weights the indicator vote to emphasise the families that work
in that regime: trend/breakout indicators when trending, oscillators/mean-reversion when
ranging. This is ADDITIVE — it surfaces a regime label and an adaptively-weighted score
next to the parity-locked consensus; it never replaces the canonical verdict.
"""

from __future__ import annotations

from typing import Any

from diveintocrypto_desktop.engine.consensus.scorer import compute_weighted_score

# Indicator families by the regime they thrive in.
_TREND_FAMILY = {
    "macd", "ema_cross", "sma_cross", "ichimoku", "psar", "adx_di", "supertrend", "vortex",
    "aroon_oscillator", "schaff_trend_cycle", "trix", "kst", "coppock_curve", "linreg_slope",
    "kalman_trend", "donchian_breakout", "keltner_breakout", "vwma_cross", "hurst", "rolling_sharpe",
    "elder_ray", "wavetrend",
}
_RANGE_FAMILY = {
    "rsi", "stochastic", "williams_r", "cci", "bollinger", "bollinger_percent_b", "mfi",
    "connors_rsi", "stoch_rsi", "ultimate_oscillator", "zscore_reversion", "half_life_reversion",
    "relative_vigor_index", "fisher_transform", "cmo", "hist_vol_percentile",
}


def _raw(results: list, name: str, key: str) -> float | None:
    for r in results:
        if r.name == name and r.raw_values:
            v = r.raw_values.get(key)
            if isinstance(v, (int, float)):
                return float(v)
    return None


def detect_regime(results: list, cfg: dict | None = None) -> dict[str, Any]:
    cfg = (cfg or {}).get("regime", {})
    strong_adx = float(cfg.get("strong_adx", 25.0))
    weak_adx = float(cfg.get("weak_adx", 20.0))
    chop_trend = float(cfg.get("chop_trend", 38.2))
    chop_range = float(cfg.get("chop_range", 61.8))

    adx = _raw(results, "adx_di", "adx")
    chop = _raw(results, "choppiness", "chop")
    t = r = 0
    if adx is not None:
        if adx >= strong_adx:
            t += 1
        elif adx <= weak_adx:
            r += 1
    if chop is not None:
        if chop <= chop_trend:
            t += 1
        elif chop >= chop_range:
            r += 1
    regime = "TREND" if t > r else "RANGE" if r > t else "MIXED"
    return {"regime": regime, "adx": adx, "chop": chop}


def adaptive_weights(base_weights: dict, regime: str, boost: float = 1.5, damp: float = 0.6) -> dict:
    if regime == "TREND":
        up, dn = _TREND_FAMILY, _RANGE_FAMILY
    elif regime == "RANGE":
        up, dn = _RANGE_FAMILY, _TREND_FAMILY
    else:
        return dict(base_weights)
    out = dict(base_weights)
    for k, w in list(out.items()):
        if w == 0:  # keep pure filters (e.g. atr_filter) at 0
            continue
        if k in up:
            out[k] = w * boost
        elif k in dn:
            out[k] = w * damp
    return out


def evaluate(results: list, base_weights: dict, cfg: dict | None = None) -> dict[str, Any]:
    """Return regime + adaptively-weighted score (observational; consensus is untouched)."""
    reg = detect_regime(results, cfg)
    w = adaptive_weights(base_weights, reg["regime"])
    score = compute_weighted_score(results, w)
    return {
        "regime": reg["regime"],
        "adx": reg["adx"],
        "chop": reg["chop"],
        "adaptive_score": score["weighted_score"],
    }
