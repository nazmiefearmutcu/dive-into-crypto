package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.ConsensusOutput
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.SettingsData
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

data class ConsensusConfig(
    val strongBuyThreshold: Double = 1.2,
    val buyThreshold: Double = 0.4,
    val sellThreshold: Double = -0.4,
    val strongSellThreshold: Double = -1.2,
    // Shipped 0.4: minority/active never exceeds 0.5, so 0.6 could never fire.
    val conflictRatioThreshold: Double = 0.4,
    val confidenceThreshold: Int = 25,
    val minConfidenceForTrade: Int = 30,
    val weights: Map<String, Double> = DEFAULT_F2_WEIGHTS,
)

/** 
 * Core consensus weights (15 names). Values match the desktop reference 
 * `indicator_weights` in engine/config/default.yaml. Regime scaling is display-only (Regime.kt).
 */
val DEFAULT_F2_WEIGHTS: Map<String, Double> = mapOf(
    "rsi" to 1.5,
    "stochastic" to 1.2,
    "williams_r" to 1.0,
    "cci" to 1.0,
    
    "macd" to 2.0,
    "ema_cross" to 1.8,
    "sma_cross" to 1.8,
    "ichimoku" to 2.0,
    "psar" to 1.3,
    
    "bollinger" to 1.5,
    "mfi" to 1.2,
    "obv" to 1.2,
    "roc" to 1.0,
    
    "adx_di" to 1.5,
    "atr_filter" to 0.0, // Strict Filter (0 weight)
)

class ConsensusEngine(
    private val settingsStore: SettingsStore? = null,
    /**
     * Fallback weights for the no-settings-store path. Defaults to the FULL
     * 60-name map ([DEFAULT_FULL_WEIGHTS]) so a store-less engine applies the
     * same weighted consensus as production instead of silently scoring the
     * 45 extended indicators at weight 1.0.
     */
    private val fallbackWeights: Map<String, Double> = DEFAULT_FULL_WEIGHTS,
) {

    private val defaultSettings = SettingsData(
        confidenceThreshold = 25,
        minConfidenceForTrade = 30,
        scanSurvivors = 50,
        scanParallelism = 8,
        weights = fallbackWeights,
        favorites = emptyList(),
        wsDataSource = "FUTURES",
        chartCandleCount = 30,
        weightTakerLs = 0.35,
        weightOiMomentum = 0.30,
        weightWhaleLs = 0.20,
        weightAccountLs = 0.15
    )

    fun evaluate(
        results: List<IndicatorResult>,
        labels: ConsensusLabels = EnglishConsensusLabels,
    ): ConsensusOutput {
        val settings = settingsStore?.getSettings() ?: defaultSettings
        val config = ConsensusConfig(
            confidenceThreshold = settings.confidenceThreshold,
            minConfidenceForTrade = settings.minConfidenceForTrade,
            weights = settings.weights
        )
        // Canonical verdict: configured weights only. Regime scaling is observational
        // (Regime.evaluate) and must never change the verdict (desktop parity).
        val s = Scorer.compute(results, config.weights)

        var signal = when {
            s.weightedScore >= config.strongBuyThreshold -> Signal.STRONG_BUY
            s.weightedScore >= config.buyThreshold -> Signal.BUY
            s.weightedScore <= config.strongSellThreshold -> Signal.STRONG_SELL
            s.weightedScore <= config.sellThreshold -> Signal.SELL
            else -> Signal.NEUTRAL
        }

        if (s.activeSignals > 0) {
            val minority = min(s.buyCount, s.sellCount).toDouble()
            if (minority / s.activeSignals > config.conflictRatioThreshold) {
                signal = Signal.NEUTRAL
            }
        }

        val baseConfidence = min(abs(s.weightedScore) / 2.0, 1.0) * 70
        val agreementBonus = if (s.activeSignals > 0) {
            (maxOf(s.buyCount, s.sellCount).toDouble() / s.activeSignals) * 20
        } else 0.0
        val participation = if (s.totalSignals > 0) s.activeSignals.toDouble() / s.totalSignals else 0.0
        val participationBonus = participation * 10
        var rawConfidence = baseConfidence + agreementBonus + participationBonus
        
        val riskData = RiskAssessor.assess(results, s, RiskConfig(), labels)
        val riskPenalty = riskData.riskScore * 3.0
        rawConfidence -= riskPenalty
        
        val confidence = clampInt(rawConfidence, 0, 100)

        val shouldTrade = (signal != Signal.NEUTRAL)
            && (confidence >= config.confidenceThreshold)
            && (confidence >= config.minConfidenceForTrade)
            && (riskData.riskLevel != RiskLevel.HIGH)

        // Desktop reference shows at most three risk factors in the reason line (engine.py).
        val reason = labels.reasonLine(
            signal = signal.name,
            confidence = confidence,
            risk = riskData.riskLevel.name,
            buyCount = s.buyCount,
            sellCount = s.sellCount,
            neutralCount = s.neutralCount,
            weightedScore = s.weightedScore.format(3),
            risks = riskData.riskFactors.take(3),
        )

        return ConsensusOutput(
            finalSignal = signal,
            confidence = confidence,
            weightedScore = s.weightedScore,
            buyCount = s.buyCount,
            sellCount = s.sellCount,
            neutralCount = s.neutralCount,
            activeSignals = s.activeSignals,
            totalSignals = s.totalSignals,
            signalDetails = s.signalDetails,
            reason = reason,
            shouldTrade = shouldTrade,
            riskLevel = riskData.riskLevel.name,
            riskScore = riskData.riskScore.toInt(),
            riskFactors = riskData.riskFactors,
        )
    }

    private fun clampInt(v: Double, lo: Int, hi: Int): Int = v.toInt().coerceIn(lo, hi)

    /** Inclusive window [start]..[end], or empty when the window lies entirely before [start]. */
    private fun <T> windowFrom(list: List<T>, start: Int, end: Int): List<T> =
        if (start > end) emptyList() else list.subList(start, end + 1)

    data class AlignedData(
        val oi: List<OpenInterestPoint>,
        val acc: List<LongShortRatioPoint>,
        val pos: List<LongShortRatioPoint>,
        val taker: List<TakerLongShortRatioPoint>,
        val global: List<LongShortRatioPoint>,
        val funding: List<FundingRatePoint>,
        /** First candle index holding a real sample of each feed (candles.size when it never reports). */
        val accFirst: Int = 0,
        val globalFirst: Int = 0,
        val posFirst: Int = 0,
        val takerFirst: Int = 0,
        val fundingFirst: Int = 0,
    )

    private fun alignData(
        candles: List<Candle>,
        rawOi: List<OpenInterestPoint>,
        rawAcc: List<LongShortRatioPoint>,
        rawPos: List<LongShortRatioPoint>,
        rawTaker: List<TakerLongShortRatioPoint>,
        rawGlobal: List<LongShortRatioPoint>,
        rawFunding: List<FundingRatePoint>
    ): AlignedData {
        val alignedOi = ArrayList<OpenInterestPoint>(candles.size)
        val alignedAcc = ArrayList<LongShortRatioPoint>(candles.size)
        val alignedPos = ArrayList<LongShortRatioPoint>(candles.size)
        val alignedTaker = ArrayList<TakerLongShortRatioPoint>(candles.size)
        val alignedGlobal = ArrayList<LongShortRatioPoint>(candles.size)
        val alignedFunding = ArrayList<FundingRatePoint>(candles.size)

        val oiAligner = Aligner(rawOi) { it.timestamp }
        val accAligner = Aligner(rawAcc) { it.timestamp }
        val posAligner = Aligner(rawPos) { it.timestamp }
        val takerAligner = Aligner(rawTaker) { it.timestamp }
        val globalAligner = Aligner(rawGlobal) { it.timestamp }
        val fundingAligner = Aligner(rawFunding) { it.timestamp }
        // Index of each feed's first real sample (-1 until seen). Before it the feed is padded.
        var accFirst = -1
        var globalFirst = -1
        var posFirst = -1
        var takerFirst = -1
        var fundingFirst = -1

        for ((i, candle) in candles.withIndex()) {
            val t = candle.openTime

            val oiPoint = oiAligner.findClosest(t, OpenInterestPoint(t, 0.0, 0.0))
            if (oiPoint.timestamp == t) {
                alignedOi.add(oiPoint)
            } else {
                alignedOi.add(oiPoint.copy(timestamp = t))
            }

            val accPoint = accAligner.findClosest(t, LongShortRatioPoint(t, 0.5, 0.5, 1.0))
            if (accPoint.timestamp == t) {
                alignedAcc.add(accPoint)
            } else {
                alignedAcc.add(accPoint.copy(timestamp = t))
            }

            val posPoint = posAligner.findClosest(t, LongShortRatioPoint(t, 0.5, 0.5, 1.0))
            if (posPoint.timestamp == t) {
                alignedPos.add(posPoint)
            } else {
                alignedPos.add(posPoint.copy(timestamp = t))
            }

            val takerPoint = takerAligner.findClosest(t, TakerLongShortRatioPoint(t, 1.0, 0.0, 0.0))
            if (takerPoint.timestamp == t) {
                alignedTaker.add(takerPoint)
            } else {
                alignedTaker.add(takerPoint.copy(timestamp = t))
            }

            val globalPoint = globalAligner.findClosest(t, LongShortRatioPoint(t, 0.5, 0.5, 1.0))
            if (globalPoint.timestamp == t) {
                alignedGlobal.add(globalPoint)
            } else {
                alignedGlobal.add(globalPoint.copy(timestamp = t))
            }

            val fundingPoint = fundingAligner.findLastBeforeOrClosest(t, FundingRatePoint(t, 0.0))
            if (fundingPoint.timestamp == t) {
                alignedFunding.add(fundingPoint)
            } else {
                alignedFunding.add(fundingPoint.copy(timestamp = t))
            }

            if (accFirst < 0 && accAligner.hasSample()) accFirst = i
            if (globalFirst < 0 && globalAligner.hasSample()) globalFirst = i
            if (posFirst < 0 && posAligner.hasSample()) posFirst = i
            if (takerFirst < 0 && takerAligner.hasSample()) takerFirst = i
            if (fundingFirst < 0 && fundingAligner.hasSample()) fundingFirst = i
        }

        fun firstOrEnd(first: Int) = if (first < 0) candles.size else first
        return AlignedData(
            alignedOi, alignedAcc, alignedPos, alignedTaker, alignedGlobal, alignedFunding,
            accFirst = firstOrEnd(accFirst),
            globalFirst = firstOrEnd(globalFirst),
            posFirst = firstOrEnd(posFirst),
            takerFirst = firstOrEnd(takerFirst),
            fundingFirst = firstOrEnd(fundingFirst),
        )
    }


    /**
     * Narrative cell for the OI state [x] and the (possibly mirrored) taker/crowd states [y], [z]:
     * returns the cell index (see [NARRATIVE_SCORES]) and its base score for a falling price.
     */
    private fun narrativeCell(x: String, y: String, z: String): Pair<Int, Double> {
        val cell = stateIndex(x) * 9 + stateIndex(y) * 3 + stateIndex(z)
        return cell to NARRATIVE_SCORES[cell]
    }

    private fun stateIndex(state: String): Int = when (state) {
        "DOWN" -> 0
        "UP" -> 1
        else -> 2 // FLAT
    }

    fun evaluateMultimodal(
        candles: List<Candle>,
        rawOi: List<OpenInterestPoint>,
        rawAcc: List<LongShortRatioPoint>,
        rawPos: List<LongShortRatioPoint>,
        rawGlobal: List<LongShortRatioPoint>,
        rawTaker: List<TakerLongShortRatioPoint>,
        rawFunding: List<FundingRatePoint>,
        labels: ConsensusLabels = EnglishConsensusLabels,
    ): List<ConsensusOutput> {
        if (candles.isEmpty()) return emptyList()

        val aligned = alignData(candles, rawOi, rawAcc, rawPos, rawTaker, rawGlobal, rawFunding)
        val outputList = ArrayList<ConsensusOutput>(candles.size)

        for (i in candles.indices) {
            val ret = if (i > 0 && candles[i-1].close != 0.0) (candles[i].close - candles[i-1].close) / candles[i-1].close else 0.0
            val startIdx = maxOf(0, i - 14)
            val windowReturns = mutableListOf<Double>()
            for (j in startIdx until i) {
                if (j > 0 && candles[j-1].close != 0.0) {
                    windowReturns.add((candles[j].close - candles[j-1].close) / candles[j-1].close)
                }
            }
            val mean = if (windowReturns.isNotEmpty()) windowReturns.average() else 0.0
            val variance = if (windowReturns.size > 1) windowReturns.map { (it - mean) * (it - mean) }.sum() / (windowReturns.size - 1) else 0.0
            val stdDev = sqrt(variance)
            val stdDevRegularized = stdDev.coerceAtLeast(0.002)
            val volNormalizedReturn = ret / stdDevRegularized

            val slope3 = if (i >= 3) {
                val y3 = candles[i].close
                val y0 = candles[i-3].close
                (y3 - y0) / 3.0
            } else 0.0

            val priceState = when {
                volNormalizedReturn <= -0.50 && slope3 < 0.0 -> "DOWN"
                volNormalizedReturn >= 0.50 && slope3 > 0.0 -> "UP"
                else -> "FLAT"
            }

            val oiPct = if (i > 0 && aligned.oi[i-1].sumOpenInterestValue != 0.0) {
                (aligned.oi[i].sumOpenInterestValue - aligned.oi[i-1].sumOpenInterestValue) / aligned.oi[i-1].sumOpenInterestValue
            } else 0.0
            val oiStart = maxOf(0, i - 20)
            val oiHistory = mutableListOf<Double>()
            for (j in oiStart..i) {
                if (j > 0 && aligned.oi[j-1].sumOpenInterestValue != 0.0) {
                    oiHistory.add((aligned.oi[j].sumOpenInterestValue - aligned.oi[j-1].sumOpenInterestValue) / aligned.oi[j-1].sumOpenInterestValue)
                }
            }
            val oiMean = if (oiHistory.isNotEmpty()) oiHistory.average() else 0.0
            val oiVar = if (oiHistory.size > 1) oiHistory.map { (it - oiMean) * (it - oiMean) }.sum() / (oiHistory.size - 1) else 0.0
            val oiStdDev = if (oiHistory.size > 1) sqrt(oiVar).coerceAtLeast(0.005) else 0.0
            val oizScore = if (oiStdDev > 0.0) (oiPct - oiMean) / oiStdDev else 0.0

            val xState = when {
                oizScore <= -0.35 -> "DOWN"
                oizScore >= 0.35 -> "UP"
                else -> "FLAT"
            }

            val yStart = maxOf(0, i - 30)
            // Each window starts at the feed's first real sample: padded candles never enter a mean or std.
            val accHistory = windowFrom(aligned.acc, maxOf(yStart, aligned.accFirst), i).map { it.longShortRatio }
            val globalHistory = windowFrom(aligned.global, maxOf(yStart, aligned.globalFirst), i).map { it.longShortRatio }
            val fundingHistory = windowFrom(aligned.funding, maxOf(yStart, aligned.fundingFirst), i).map { it.fundingRate }

            val accMean = accHistory.average()
            val accStd = if (accHistory.size > 1) sqrt(accHistory.map { (it - accMean) * (it - accMean) }.sum() / (accHistory.size - 1)).coerceAtLeast(0.02) else 0.0
            val accZ = if (accStd > 0.0) (aligned.acc[i].longShortRatio - accMean) / accStd else 0.0

            val globalMean = globalHistory.average()
            val globalStd = if (globalHistory.size > 1) sqrt(globalHistory.map { (it - globalMean) * (it - globalMean) }.sum() / (globalHistory.size - 1)).coerceAtLeast(0.02) else 0.0
            val globalZ = if (globalStd > 0.0) (aligned.global[i].longShortRatio - globalMean) / globalStd else 0.0

            val fundingMean = fundingHistory.average()
            val fundingStd = if (fundingHistory.size > 1) sqrt(fundingHistory.map { (it - fundingMean) * (it - fundingMean) }.sum() / (fundingHistory.size - 1)).coerceAtLeast(0.0001) else 0.0
            val fundingZ = if (fundingStd > 0.0) (aligned.funding[i].fundingRate - fundingMean) / fundingStd else 0.0

            val yCombinedZ = (accZ + globalZ + fundingZ) / 3.0
            val yState = when {
                yCombinedZ <= -0.253 -> "DOWN"
                yCombinedZ >= 0.253 -> "UP"
                else -> "FLAT"
            }

            val buyVol = aligned.taker.getOrNull(i)?.buyVol ?: 0.0
            val sellVol = aligned.taker.getOrNull(i)?.sellVol ?: 0.0
            val netTakerPct = if (buyVol + sellVol > 0.0) (buyVol - sellVol) / (buyVol + sellVol) else 0.0

            val zStart = maxOf(0, i - 30)
            val takerHistory = windowFrom(aligned.taker, maxOf(zStart, aligned.takerFirst), i)
            val netTakerHistory = takerHistory.map {
                val b = it.buyVol
                val s = it.sellVol
                if (b + s > 0.0) (b - s) / (b + s) else 0.0
            }
            val netTakerMean = netTakerHistory.average()
            val netTakerStd = if (netTakerHistory.size > 1) sqrt(netTakerHistory.map { (it - netTakerMean) * (it - netTakerMean) }.sum() / (netTakerHistory.size - 1)).coerceAtLeast(0.05) else 0.0
            val netTakerZ = if (netTakerStd > 0.0) (netTakerPct - netTakerMean) / netTakerStd else 0.0

            val takerRatio = aligned.taker.getOrNull(i)?.buySellRatio ?: 1.0
            val takerRatioScore = takerRatio - 1.0

            val zScore = (takerRatioScore + netTakerZ) / 2.0
            val zState = when {
                zScore <= -0.50 -> "DOWN"
                zScore >= 0.50 -> "UP"
                else -> "FLAT"
            }

            val whaleHistory = windowFrom(aligned.pos, maxOf(yStart, aligned.posFirst), i).map { it.longShortRatio }
            val whaleMean = whaleHistory.average()
            val whaleStd = if (whaleHistory.size > 1) sqrt(whaleHistory.map { (it - whaleMean) * (it - whaleMean) }.sum() / (whaleHistory.size - 1)).coerceAtLeast(0.02) else 0.0
            val whaleZ = if (whaleStd > 0.0) (aligned.pos[i].longShortRatio - whaleMean) / whaleStd else 0.0

            val lookupY = if (priceState == "UP") {
                if (yState == "UP") "DOWN" else if (yState == "DOWN") "UP" else "FLAT"
            } else yState

            val lookupZ = if (priceState == "UP") {
                if (zState == "UP") "DOWN" else if (zState == "DOWN") "UP" else "FLAT"
            } else zState

            // A falling price reads the narrative table directly. A rising price reads the mirrored cell
            // (taker/crowd states swapped above) with the bullish-perspective text and a negated score.
            val mirrored = priceState == "UP"
            val reading: String
            val biasDesc: String
            val baseScore: Double
            if (priceState == "FLAT") {
                reading = labels.flatPriceReading()
                biasDesc = labels.flatPriceBias(zState)
                baseScore = when (zState) {
                    "UP" -> 20.0
                    "DOWN" -> -20.0
                    else -> 0.0
                }
            } else {
                val (cell, score) = narrativeCell(xState, lookupY, lookupZ)
                reading = labels.narrativeReading(cell, mirrored)
                biasDesc = labels.narrativeBias(cell, mirrored)
                baseScore = if (mirrored) -score else score
            }

            var finalScore = baseScore
            var whaleOverridden = false
            val diff = whaleZ - yCombinedZ
            if (abs(diff) >= 1.0) {
                val adjustment = diff * 20.0
                finalScore = (finalScore + adjustment).coerceIn(-100.0, 100.0)
                whaleOverridden = true
            }

            val signal = when {
                finalScore >= 60.0 -> Signal.STRONG_BUY
                finalScore >= 20.0 -> Signal.BUY
                finalScore <= -60.0 -> Signal.STRONG_SELL
                finalScore <= -20.0 -> Signal.SELL
                else -> Signal.NEUTRAL
            }

            val confidence = abs(finalScore).toInt().coerceIn(0, 100)

            val regime = when (priceState) {
                "DOWN", "UP" -> {
                    if (xState == "UP") {
                        if ((priceState == "DOWN" && zState == "DOWN") || (priceState == "UP" && zState == "UP")) {
                            "CONTINUATION_TREND"
                        } else {
                            "MANIPULATION_ANOMALY"
                        }
                    } else if (xState == "DOWN") {
                        if ((priceState == "DOWN" && zState == "UP") || (priceState == "UP" && zState == "DOWN")) {
                            "SHORT_COVERING_REBOUND"
                        } else {
                            "LONG_LIQUIDATION"
                        }
                    } else {
                        "MEAN_REVERTING_CHOP"
                    }
                }
                else -> "MEAN_REVERTING_CHOP"
            }

            val shouldTrade = confidence >= 58 && regime != "MANIPULATION_ANOMALY"

            val riskLevel = when {
                whaleOverridden -> "HIGH"
                abs(aligned.funding[i].fundingRate) >= 0.003 -> "HIGH"
                else -> "LOW"
            }

            val riskFactors = mutableListOf<String>()
            if (whaleOverridden) riskFactors.add(labels.whaleRetailDivergence())
            if (abs(aligned.funding[i].fundingRate) >= 0.003) riskFactors.add(labels.extremeFunding())

            val explanation = labels.multimodalExplanation(regime, confidence, biasDesc, reading, finalScore.format(2))

            outputList.add(
                ConsensusOutput(
                    finalSignal = signal,
                    confidence = confidence,
                    weightedScore = finalScore,
                    buyCount = if (signal == Signal.BUY || signal == Signal.STRONG_BUY) 1 else 0,
                    sellCount = if (signal == Signal.SELL || signal == Signal.STRONG_SELL) 1 else 0,
                    neutralCount = if (signal == Signal.NEUTRAL) 1 else 0,
                    activeSignals = 1,
                    totalSignals = 1,
                    signalDetails = emptyList(),
                    reason = explanation,
                    shouldTrade = shouldTrade,
                    riskLevel = riskLevel,
                    riskScore = confidence,
                    riskFactors = riskFactors
                )
            )
        }

        return outputList
    }

    companion object {
        /**
         * Base score per narrative cell (index = oi*9 + yState*3 + zState; DOWN=0, UP=1, FLAT=2),
         * for a falling price. A rising price negates it. Order matches the labels' narrative lists.
         */
        private val NARRATIVE_SCORES = doubleArrayOf(
            -60.0, 20.0, -40.0, -30.0, 50.0, 10.0, -50.0, 30.0, 0.0,
            -95.0, -45.0, -70.0, -50.0, 10.0, -30.0, -80.0, 0.0, -30.0,
            -50.0, -10.0, -35.0, -20.0, 45.0, 15.0, -45.0, 25.0, 0.0,
        )
    }
}

private class Aligner<T>(private val list: List<T>, private val getTimestamp: (T) -> Long) {
    private var idx = -1

    /**
     * Causal lookup: the last sample at or before [t]. A sample timestamped after the
     * candle is never used (look-ahead). Returns [default] while the series has no
     * sample yet; the first sample is NOT substituted for earlier candles.
     */
    fun findClosest(t: Long, default: T): T = findLastBeforeOrClosest(t, default)

    /** True once the last lookup found a real sample at or before its timestamp. */
    fun hasSample(): Boolean = idx >= 0

    fun findLastBeforeOrClosest(t: Long, default: T): T {
        if (list.isEmpty()) return default
        while (idx + 1 < list.size && getTimestamp(list[idx + 1]) <= t) {
            idx++
        }
        return if (idx >= 0) list[idx] else default
    }
}
