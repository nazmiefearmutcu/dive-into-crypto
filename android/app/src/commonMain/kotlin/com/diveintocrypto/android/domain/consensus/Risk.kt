// from the original Python reference implementation — verbatim port
package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.min

/** Three-level risk classification — verbatim from risk.py:12-15 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * Output of [RiskAssessor.assess]. Mirrors the Python dict shape returned by
 * `assess_risk(...)` at risk.py:99-104.
 *
 * - [riskLevel] string in Python; enum here.
 * - [riskScore] integer score in Python (`risk_score`).
 * - [riskFactors] list of human-readable contributing factors.
 * - [details] holds the structured payload mirroring the Python dict
 *   (`risk_level`, `risk_score`, `risk_factors`)
 *   so downstream code (UI / serialization) can mimic the Python `.get(...)`
 *   access without re-deriving the values.
 */
data class RiskAssessment(
    val riskLevel: RiskLevel,
    val riskScore: Double,
    val riskFactors: List<String>,
    val details: Map<String, Any?>,
)

/**
 * Configurable thresholds — matches the keys that `config.get(...)` reads
 * out of the Python config dict (risk.py:33, 49, 62, 72).
 *
 * Defaults mirror the Python `.get(key, default)` defaults verbatim.
 */
data class RiskConfig(
    /** `no_trade.adx_min` (risk.py:49). */
    val adxMin: Double = 15.0,
    /**
     * `consensus.conflict_ratio_threshold` (risk.py:62). Shipped 0.4: the
     * minority/active ratio never exceeds 0.5, so a 0.6 threshold was unreachable.
     */
    val conflictRatioThreshold: Double = 0.4,
    /** `consensus.min_active_signals` (risk.py:72). */
    val minActiveSignals: Int = 4,
)

object RiskAssessor {

    /**
     * Verbatim port of `assess_risk(results, score_data, config)` from
     * the original Python reference implementation.
     *
     * Signature adapted to Kotlin:
     *  - `score_data` → [ScoreBreakdown]
     *  - `config`     → [RiskConfig]
     *  - `labels`     → [ConsensusLabels] (English by default)
     *
     * Volatility (Python reads a string from `raw_values["volatility"]`):
     * encoded numerically in Kotlin because [IndicatorResult.rawValues] is
     * `Map<String, Double?>`. Convention: 2.0 → HIGH, 0.0 → LOW, anything
     * else (including missing / null) → NORMAL.
     */
    fun assess(
        results: List<IndicatorResult>,
        scoreBreakdown: ScoreBreakdown,
        config: RiskConfig,
        labels: ConsensusLabels = EnglishConsensusLabels,
    ): RiskAssessment {
        val riskFactors: MutableList<String> = mutableListOf()
        var riskScore = 0  // Higher = more risk

        // 1. Check ATR volatility (risk.py:36-43)
        for (r in results) {
            if (r.name == "atr_filter" && r.rawValues.isNotEmpty()) {
                val vol = r.rawValues["volatility"]
                when {
                    vol == 2.0 -> {
                        riskScore += 3
                        riskFactors += labels.highAtrVolatility()
                    }
                    // atr_filter labels LOW only when atr_ratio < 0.5; 0.5-0.8 is NORMAL.
                    vol == 0.0 && (r.rawValues["atr_ratio"] ?: 0.0) < 0.5 -> {
                        riskFactors += labels.lowAtrVolatility()
                    }
                    // else: NORMAL — no risk contribution
                }
            }
        }

        // 2. Check ADX trend strength (risk.py:46-52)
        for (r in results) {
            if (r.name == "adx_di" && r.rawValues.isNotEmpty()) {
                val adxVal = r.rawValues["adx"] ?: 25.0
                val adxMin = config.adxMin
                if (adxVal < adxMin) {
                    riskScore += 2
                    riskFactors += labels.weakTrend(adxVal.format(1), formatThreshold(adxMin))
                }
            }
        }

        // 3. Signal conflict analysis (risk.py:55-69)
        val buyCount = scoreBreakdown.buyCount
        val sellCount = scoreBreakdown.sellCount
        val active = scoreBreakdown.activeSignals

        if (active > 0) {
            val minority = min(buyCount, sellCount).toDouble()
            val conflictRatio = minority / active.toDouble()
            val conflictThreshold = config.conflictRatioThreshold

            if (conflictRatio > conflictThreshold) {
                riskScore += 3
                riskFactors += labels.highSignalConflict(conflictRatio.format(2))
            } else if (conflictRatio > 0.3) {
                riskScore += 1
                riskFactors += labels.moderateSignalConflict(conflictRatio.format(2))
            }
        }

        // 4. Minimum active signals check (risk.py:72-75)
        val minActive = config.minActiveSignals
        if (active < minActive) {
            riskScore += 1
            riskFactors += labels.fewActiveSignals(active, minActive)
        }

        // 5. Weighted score magnitude (risk.py:78-81)
        val wScore = abs(scoreBreakdown.weightedScore)
        if (wScore < 0.3) {
            riskScore += 1
            riskFactors += labels.weakConviction(wScore.format(3))
        }

        // Determine risk level (risk.py:84-89)
        val riskLevel = when {
            riskScore >= 5 -> RiskLevel.HIGH
            riskScore >= 2 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // Mirror Python dict (risk.py:99-104).
        val details: Map<String, Any?> = mapOf(
            "risk_level" to riskLevel.name,
            "risk_score" to riskScore,
            "risk_factors" to riskFactors.toList(),
        )

        return RiskAssessment(
            riskLevel = riskLevel,
            riskScore = riskScore.toDouble(),
            riskFactors = riskFactors.toList(),
            details = details,
        )
    }

    /** Render the ADX minimum without a trailing `.0` when it's an integer. */
    private fun formatThreshold(v: Double): String {
        return if (v == v.toLong().toDouble()) v.toLong().toString() else v.format(1)
    }
}
