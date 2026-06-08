package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.domain.model.SignalDetail

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
        var totalDirectional = 0
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
            totalDirectional++

            when (r.signal) {
                Signal.STRONG_BUY -> { strongBuy++; buy++ }
                Signal.BUY -> buy++
                Signal.STRONG_SELL -> { strongSell++; sell++ }
                Signal.SELL -> sell++
                Signal.NEUTRAL -> neutral++
            }
            details += SignalDetail(r.name, r.signal, w, wScore, r.reason)
        }

        val weightedAvg = if (totalWeight > 0) weightedSum / totalWeight else 0.0
        return ScoreBreakdown(
            weightedScore = weightedAvg,
            weightedSum = weightedSum,
            totalWeight = totalWeight,
            buyCount = buy,
            sellCount = sell,
            neutralCount = neutral,
            strongBuyCount = strongBuy,
            strongSellCount = strongSell,
            activeSignals = buy + sell,
            totalSignals = totalDirectional,
            signalDetails = details,
        )
    }
}
