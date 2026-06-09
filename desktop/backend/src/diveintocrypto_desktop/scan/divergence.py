"""Whale L/S divergence — faithful port of the Android `WhaleDivergence` +
`DivergenceAlignment` (domain/divergence/*.kt).

Empirical CONTRARIAN: over a window, when PRICE and WHALE L/S (top-trader position
ratio) trend in OPPOSITE directions, that is a divergence. The predictive/ranking
sign = patternDirection × whalePredictiveSign (default -1). Magnitude is a
whale-primary weighted SUM (not a product). See the Kotlin source for the full
rationale; constants are calibrated via the backtest and kept identical here.
"""

from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class Params:
    min_bars: int = 20
    smooth: int = 1
    trend_window: int = 36
    min_price_move: float = 0.008
    min_whale_move: float = 0.015
    price_ref: float = 0.06
    whale_ref: float = 0.16
    whale_weight: float = 0.80
    gamma: float = 1.4
    w_max: float = 95.0
    agg_bonus: float = 0.10
    whale_predictive_sign: int = -1


@dataclass(frozen=True)
class TfResult:
    score: float = 0.0          # SIGNED -100..+100
    magnitude: float = 0.0      # 0..1
    rise_pct: float = 0.0       # |price Δ|
    whale_drop: float = 0.0     # |whale Δ|
    detected: bool = False
    direction: int = 0          # predictive/ranking sign
    pattern_direction: int = 0  # descriptive raw whale movement
    tf_weight: int = 0


NONE_TF = TfResult()


@dataclass(frozen=True)
class SymbolResult:
    score: float = 0.0
    best_tf: str | None = None
    best_score: float = 0.0
    best_rise_pct: float = 0.0
    best_whale_drop: float = 0.0
    direction: int = 0
    pattern_direction: int = 0


NONE_SYMBOL = SymbolResult()


def _sanitize(x: list[float], n: int) -> list[float] | None:
    """Forward-fill NaN/Inf; return None if >20% corrupt."""
    out: list[float] = [math.nan] * n
    bad = 0
    last_good = math.nan
    for i in range(n):
        v = x[i]
        if v is None or math.isnan(v) or math.isinf(v):
            bad += 1
            out[i] = last_good
        else:
            out[i] = v
            last_good = v
    if bad > n // 5:
        return None
    if any(math.isnan(v) for v in out):
        first_good = next((v for v in out if not math.isnan(v)), None)
        if first_good is None:
            return None
        out = [first_good if math.isnan(v) else v for v in out]
    return out


def _sma_centered(x: list[float], w: int) -> list[float]:
    if w <= 1:
        return list(x)
    half = w // 2
    out = [0.0] * len(x)
    for i in range(len(x)):
        s = 0.0
        cnt = 0
        for k in range(i - half, i + half + 1):
            if 0 <= k < len(x):
                s += x[k]
                cnt += 1
        out[i] = s / cnt
    return out


def per_tf(price: list[float], whale_ls: list[float], tf_weight: int, params: Params = Params()) -> TfResult:
    n = min(len(price), len(whale_ls))
    if n < params.min_bars:
        return NONE_TF
    p = _sanitize(price, n)
    ls = _sanitize(whale_ls, n)
    if p is None or ls is None:
        return NONE_TF
    s_price = _sma_centered(p, params.smooth)
    s_whale = _sma_centered(ls, params.smooth)

    w = min(n, params.trend_window)
    s = max(0, min(n - w, n - 1))
    p_start, p_end = s_price[s], s_price[n - 1]
    w_start, w_end = s_whale[s], s_whale[n - 1]
    if p_start <= 0.0 or w_start <= 0.0:
        return NONE_TF

    price_d = (p_end - p_start) / p_start
    whale_d = (w_end - w_start) / w_start
    if abs(price_d) < params.min_price_move:
        return NONE_TF
    if abs(whale_d) < params.min_whale_move:
        return NONE_TF
    # divergence = opposite direction
    if _sign(price_d) == _sign(whale_d):
        return NONE_TF

    pattern_direction = -1 if whale_d < 0.0 else 1
    direction = pattern_direction * params.whale_predictive_sign

    price_strength = min(max(abs(price_d) / params.price_ref, 0.0), 1.0)
    whale_strength = min(max(abs(whale_d) / params.whale_ref, 0.0), 1.0)
    pw = min(max(params.whale_weight, 0.0), 1.0)
    magnitude = min(max(pw * whale_strength + (1.0 - pw) * price_strength, 0.0), 1.0)
    if magnitude <= 0.0:
        return NONE_TF

    tf_factor = min(max(tf_weight / params.w_max, 0.0), 1.0) ** params.gamma
    score = min(max(direction * 100.0 * magnitude * tf_factor, -100.0), 100.0)
    return TfResult(
        score=score, magnitude=magnitude, rise_pct=abs(price_d), whale_drop=abs(whale_d),
        detected=True, direction=direction, pattern_direction=pattern_direction, tf_weight=tf_weight,
    )


def for_symbol(per_tf_results: dict[str, TfResult], params: Params = Params()) -> SymbolResult:
    detected = {k: v for k, v in per_tf_results.items() if v.detected}
    if not detected:
        return NONE_SYMBOL
    # highest TF (largest weight); tie broken by |score|
    best_key = max(detected, key=lambda k: (detected[k].tf_weight, abs(detected[k].score)))
    best = detected[best_key]
    best_sign = best.direction if best.direction != 0 else (1 if best.score >= 0 else -1)
    corr = max(
        (abs(v.score) for k, v in detected.items() if k != best_key and v.direction == best.direction),
        default=0.0,
    )
    mag = min(max(abs(best.score) + params.agg_bonus * corr, 0.0), 100.0)
    return SymbolResult(
        score=best_sign * mag, best_tf=best_key, best_score=best.score,
        best_rise_pct=best.rise_pct, best_whale_drop=best.whale_drop,
        direction=best.direction, pattern_direction=best.pattern_direction,
    )


def align(
    price_times: list[int], price_vals: list[float],
    ls_times: list[int], ls_vals: list[float], period_ms: int,
) -> tuple[list[float], list[float], int]:
    """Align price & whale-L/S by timestamp bucket (t // period_ms). Forward-fill
    missing L/S buckets; skip candles before L/S data starts. Returns
    (price, whale, matched).
    """
    if period_ms <= 0:
        return [], [], 0
    n = min(len(price_times), len(price_vals))
    m = min(len(ls_times), len(ls_vals))
    if n == 0 or m == 0:
        return [], [], 0
    ls_by_bucket: dict[int, float] = {}
    for i in range(m):
        ls_by_bucket[ls_times[i] // period_ms] = ls_vals[i]
    price: list[float] = []
    whale: list[float] = []
    last_whale = math.nan
    matched = 0
    for i in range(n):
        w = ls_by_bucket.get(price_times[i] // period_ms)
        if w is not None:
            last_whale = w
            matched += 1
        if not math.isnan(last_whale):
            price.append(price_vals[i])
            whale.append(last_whale)
    return price, whale, matched


def _sign(x: float) -> float:
    return (x > 0) - (x < 0)
