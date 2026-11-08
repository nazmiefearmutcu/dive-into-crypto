package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.domain.model.SignalDetail
import kotlin.math.round

data class ScoreBreakdown(
    val weightedScore: Double,
    val weightedSum: Double,
    val totalWeight: Double,
    val buyCount: Int,
    val sellCount: Int,
    val neutralCount: Int,
    val strongBuyCount: Int,
    val strongSellCount: Int,
    val activeSignals: Int,
    val totalSignals: Int,
    val signalDetails: List<SignalDetail>,
)

object Scorer {

    /** Verbatim port of the original Python reference implementation (compute_weighted_score) */
    fun compute(results: List<IndicatorResult>, weights: Map<String, Double>): ScoreBreakdown {
        var totalWeight = 0.0
        var weightedSum = 0.0
        var buy = 0; var sell = 0; var neutral = 0
        var strongBuy = 0; var strongSell = 0
        val details = ArrayList<SignalDetail>(results.size)

        for (r in results) {
            val w = weights[r.name] ?: 1.0
            if (w == 0.0) {
                details += SignalDetail(r.name, r.signal, 0.0, 0.0, r.reason)
                continue
            }
            val wScore = r.score * w
            weightedSum += wScore
            totalWeight += w

            when (r.signal) {
                Signal.STRONG_BUY -> { strongBuy++; buy++ }
                Signal.BUY -> buy++
                Signal.STRONG_SELL -> { strongSell++; sell++ }
                Signal.SELL -> sell++
                Signal.NEUTRAL -> neutral++
            }
            details += SignalDetail(r.name, r.signal, w, wScore, r.reason)
        }

        // scorer.py: weighted_score = round(weighted_avg, 4). Rounding happens BEFORE the
        // thresholds and the risk module see the value, so boundary cases agree with desktop.
        val weightedAvg = if (totalWeight > 0) weightedSum / totalWeight else 0.0
        val weightedScore = round(weightedAvg * 10000.0) / 10000.0
        return ScoreBreakdown(
            weightedScore = weightedScore,
            weightedSum = weightedSum,
            totalWeight = totalWeight,
            buyCount = buy,
            sellCount = sell,
            neutralCount = neutral,
            strongBuyCount = strongBuy,
            strongSellCount = strongSell,
            activeSignals = buy + sell,
            // scorer.py: total_signals = len(results), so zero-weight filters are counted too.
            totalSignals = results.size,
            signalDetails = details,
        )
    }
}
