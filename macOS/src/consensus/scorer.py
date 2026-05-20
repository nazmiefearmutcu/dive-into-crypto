"""Weighted scoring system for indicator consensus."""

from typing import Any

from src.indicators.base import IndicatorResult, Signal, SIGNAL_SCORES
from src.utils.logger import get_logger

logger = get_logger("consensus.scorer")


def compute_weighted_score(
    results: list[IndicatorResult],
    weights: dict[str, float],
) -> dict[str, Any]:
    """Compute weighted consensus score from indicator results.

    Returns a dict with:
    - weighted_score: float (weighted average of signal scores)
    - total_weight: float
    - buy_count / sell_count / neutral_count
    - signal_details: list of per-indicator breakdowns
    """
    total_weight = 0.0
    weighted_sum = 0.0
    buy_count = 0
    sell_count = 0
    neutral_count = 0
    strong_buy_count = 0
    strong_sell_count = 0
    signal_details: list[dict[str, Any]] = []

    for result in results:
        weight = weights.get(result.name, 1.0)

        # ATR filter has 0 weight for direction
        if weight == 0:
            signal_details.append({
                "name": result.name,
                "signal": result.signal.value,
                "score": result.score,
                "weight": 0.0,
                "weighted_score": 0.0,
                "reason": result.reason,
            })
            continue

        w_score = result.score * weight
        weighted_sum += w_score
        total_weight += weight

        if result.signal == Signal.STRONG_BUY:
            strong_buy_count += 1
            buy_count += 1
        elif result.signal == Signal.BUY:
            buy_count += 1
        elif result.signal == Signal.STRONG_SELL:
            strong_sell_count += 1
            sell_count += 1
        elif result.signal == Signal.SELL:
            sell_count += 1
        else:
            neutral_count += 1

        signal_details.append({
            "name": result.name,
            "signal": result.signal.value,
            "score": result.score,
            "weight": weight,
            "weighted_score": round(w_score, 4),
            "reason": result.reason,
        })

    weighted_avg = weighted_sum / total_weight if total_weight > 0 else 0.0

    return {
        "weighted_score": round(weighted_avg, 4),
        "weighted_sum": round(weighted_sum, 4),
        "total_weight": round(total_weight, 4),
        "buy_count": buy_count,
        "sell_count": sell_count,
        "neutral_count": neutral_count,
        "strong_buy_count": strong_buy_count,
        "strong_sell_count": strong_sell_count,
        "active_signals": buy_count + sell_count,
        "total_signals": len(results),
        "signal_details": signal_details,
    }
