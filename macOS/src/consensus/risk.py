"""Risk assessment module for the consensus engine."""

from typing import Any
from enum import Enum

from src.indicators.base import IndicatorResult
from src.utils.logger import get_logger

logger = get_logger("consensus.risk")


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


def assess_risk(
    results: list[IndicatorResult],
    score_data: dict[str, Any],
    config: dict[str, Any],
) -> dict[str, Any]:
    """Assess overall risk based on indicator results and scoring.

    Returns:
        risk_level: LOW/MEDIUM/HIGH
        risk_factors: list of contributing factors
        position_size_modifier: multiplier to adjust position size (0.0-1.0)
    """
    risk_factors: list[str] = []
    risk_score = 0  # Higher = more risk

    no_trade_config = config.get("no_trade", {})

    # 1. Check ATR volatility
    for r in results:
        if r.name == "atr_filter" and r.raw_values:
            volatility = r.raw_values.get("volatility", "NORMAL")
            if volatility == "HIGH":
                risk_score += 3
                risk_factors.append("High ATR volatility")
            elif volatility == "LOW":
                risk_factors.append("Low ATR volatility (squeeze potential)")

    # 2. Check ADX trend strength
    for r in results:
        if r.name == "adx_di" and r.raw_values:
            adx_val = r.raw_values.get("adx", 25)
            adx_min = no_trade_config.get("adx_min", 15)
            if adx_val < adx_min:
                risk_score += 2
                risk_factors.append(f"Weak trend (ADX={adx_val:.1f} < {adx_min})")

    # 3. Signal conflict analysis
    buy_count = score_data.get("buy_count", 0)
    sell_count = score_data.get("sell_count", 0)
    active = score_data.get("active_signals", 0)

    if active > 0:
        minority = min(buy_count, sell_count)
        conflict_ratio = minority / active
        conflict_threshold = config.get("consensus", {}).get("conflict_ratio_threshold", 0.6)

        if conflict_ratio > conflict_threshold:
            risk_score += 3
            risk_factors.append(f"High signal conflict (ratio={conflict_ratio:.2f})")
        elif conflict_ratio > 0.3:
            risk_score += 1
            risk_factors.append(f"Moderate signal conflict (ratio={conflict_ratio:.2f})")

    # 4. Minimum active signals check
    min_active = config.get("consensus", {}).get("min_active_signals", 4)
    if active < min_active:
        risk_score += 1
        risk_factors.append(f"Few active signals ({active} < {min_active})")

    # 5. Weighted score magnitude
    w_score = abs(score_data.get("weighted_score", 0))
    if w_score < 0.3:
        risk_score += 1
        risk_factors.append(f"Weak conviction (score={w_score:.3f})")

    # Determine risk level
    if risk_score >= 5:
        risk_level = RiskLevel.HIGH
    elif risk_score >= 2:
        risk_level = RiskLevel.MEDIUM
    else:
        risk_level = RiskLevel.LOW

    # Position size modifier
    if risk_level == RiskLevel.HIGH:
        position_size_modifier = 0.25
    elif risk_level == RiskLevel.MEDIUM:
        position_size_modifier = 0.6
    else:
        position_size_modifier = 1.0

    return {
        "risk_level": risk_level.value,
        "risk_score": risk_score,
        "risk_factors": risk_factors,
        "position_size_modifier": position_size_modifier,
    }
