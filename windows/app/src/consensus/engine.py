"""Consensus engine - aggregates indicator signals into a final trading decision."""

from typing import Any

from src.indicators.base import IndicatorResult, Signal, SIGNAL_SCORES
from src.consensus.scorer import compute_weighted_score
from src.consensus.risk import assess_risk, RiskLevel
from src.utils.logger import get_logger
from src.utils.helpers import clamp

logger = get_logger("consensus.engine")


class ConsensusEngine:
    """Aggregates indicator results into a consensus signal with confidence and risk."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.weights = config.get("indicator_weights", {})
        self.consensus_config = config.get("consensus", {})
        self.no_trade_config = config.get("no_trade", {})

    def evaluate(self, results: list[IndicatorResult]) -> dict[str, Any]:
        """Evaluate all indicator results and produce consensus.

        Returns:
            final_signal: Signal enum value
            confidence: 0-100
            risk_level: LOW/MEDIUM/HIGH
            risk_data: detailed risk assessment
            score_data: detailed scoring breakdown
            should_trade: bool
            reason: human-readable summary
        """
        # Step 1: Compute weighted scores
        score_data = compute_weighted_score(results, self.weights)

        # Step 2: Assess risk
        risk_data = assess_risk(results, score_data, self.config)

        # Step 3: Determine consensus signal
        weighted_score = score_data["weighted_score"]
        strong_buy_th = self.consensus_config.get("strong_buy_threshold", 1.2)
        buy_th = self.consensus_config.get("buy_threshold", 0.4)
        sell_th = self.consensus_config.get("sell_threshold", -0.4)
        strong_sell_th = self.consensus_config.get("strong_sell_threshold", -1.2)

        if weighted_score >= strong_buy_th:
            final_signal = Signal.STRONG_BUY
        elif weighted_score >= buy_th:
            final_signal = Signal.BUY
        elif weighted_score <= strong_sell_th:
            final_signal = Signal.STRONG_SELL
        elif weighted_score <= sell_th:
            final_signal = Signal.SELL
        else:
            final_signal = Signal.NEUTRAL

        # Step 4: Force NEUTRAL on high conflict
        conflict_threshold = self.consensus_config.get("conflict_ratio_threshold", 0.6)
        active = score_data.get("active_signals", 0)
        if active > 0:
            minority = min(score_data["buy_count"], score_data["sell_count"])
            if minority / active > conflict_threshold:
                final_signal = Signal.NEUTRAL

        # Step 5: Calculate confidence (0-100)
        confidence = self._calculate_confidence(score_data, risk_data)

        # Step 6: Determine if trading is advised
        should_trade = self._should_trade(final_signal, confidence, risk_data)

        reason = self._build_reason(final_signal, confidence, score_data, risk_data)

        output = {
            "final_signal": final_signal.value,
            "confidence": confidence,
            "risk_level": risk_data["risk_level"],
            "risk_data": risk_data,
            "score_data": score_data,
            "should_trade": should_trade,
            "weighted_score": weighted_score,
            "reason": reason,
        }

        logger.info(
            f"Consensus: {final_signal.value} | confidence={confidence} | "
            f"risk={risk_data['risk_level']} | should_trade={should_trade} | "
            f"w_score={weighted_score:.3f} | buy={score_data['buy_count']} sell={score_data['sell_count']}"
        )

        return output

    def _calculate_confidence(
        self, score_data: dict[str, Any], risk_data: dict[str, Any]
    ) -> int:
        """Calculate confidence as a 0-100 score."""
        w_score = abs(score_data["weighted_score"])
        active = score_data["active_signals"]
        total = score_data["total_signals"]

        # Base confidence from weighted score magnitude (max 2.0 -> 100)
        base_confidence = min(w_score / 2.0, 1.0) * 70

        # Agreement bonus: if most signals agree
        if active > 0:
            majority = max(score_data["buy_count"], score_data["sell_count"])
            agreement_ratio = majority / active
            agreement_bonus = agreement_ratio * 20
        else:
            agreement_bonus = 0

        # Participation bonus: more non-neutral signals = more data
        participation = active / total if total > 0 else 0
        participation_bonus = participation * 10

        raw_confidence = base_confidence + agreement_bonus + participation_bonus

        # Risk penalty
        risk_penalty = risk_data["risk_score"] * 3
        raw_confidence -= risk_penalty

        return int(clamp(raw_confidence, 0, 100))

    def _should_trade(
        self, signal: Signal, confidence: int, risk_data: dict[str, Any]
    ) -> bool:
        """Determine if trading should occur given signal, confidence, and risk."""
        if signal == Signal.NEUTRAL:
            return False

        risk_config = self.config.get("risk", {})
        min_confidence = risk_config.get("confidence_threshold", 55)
        max_risk = risk_config.get("max_risk_level", "MEDIUM")

        if confidence < min_confidence:
            return False

        risk_order = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}
        current_risk = risk_order.get(risk_data["risk_level"], 2)
        max_risk_val = risk_order.get(max_risk, 1)

        if current_risk > max_risk_val:
            return False

        # Check no-trade conditions
        min_no_trade_confidence = self.no_trade_config.get("min_confidence", 40)
        if confidence < min_no_trade_confidence:
            return False

        return True

    def _build_reason(
        self,
        signal: Signal,
        confidence: int,
        score_data: dict[str, Any],
        risk_data: dict[str, Any],
    ) -> str:
        """Build human-readable reason string."""
        parts = [
            f"Signal={signal.value}",
            f"Conf={confidence}%",
            f"Risk={risk_data['risk_level']}",
            f"Buy={score_data['buy_count']}/Sell={score_data['sell_count']}/Neut={score_data['neutral_count']}",
            f"WScore={score_data['weighted_score']:.3f}",
        ]
        if risk_data["risk_factors"]:
            parts.append(f"Risks: {'; '.join(risk_data['risk_factors'][:3])}")
        return " | ".join(parts)
