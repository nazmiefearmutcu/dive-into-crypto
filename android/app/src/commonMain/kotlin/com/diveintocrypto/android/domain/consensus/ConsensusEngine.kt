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
    val conflictRatioThreshold: Double = 0.6,
    val confidenceThreshold: Int = 25,
    val minConfidenceForTrade: Int = 30,
    val weights: Map<String, Double> = DEFAULT_F2_WEIGHTS,
)

/** 
 * F2 Consensus Weight Matrix. 
 * Supports dynamic scaling via Regime Analysis.
 */
val DEFAULT_F2_WEIGHTS: Map<String, Double> = mapOf(
    "rsi" to 1.5,
    "stochastic" to 1.2,
    "williams_r" to 1.0,
    "cci" to 1.0,
    
    "macd" to 2.0,
    "ema_cross" to 1.8,
    "sma_cross" to 1.5,
    "ichimoku" to 1.5,
    "psar" to 1.2,
    
    "bollinger" to 1.5,
    "mfi" to 1.2,
    "obv" to 1.5,
    "roc" to 1.0,
    
    "adx_di" to 1.5,
    "atr_filter" to 0.0, // Strict Filter (0 weight)
)

class ConsensusEngine(private val settingsStore: SettingsStore? = null) {

    private val defaultSettings = SettingsData(
        confidenceThreshold = 25,
        minConfidenceForTrade = 30,
        enableRegimeMatrix = true,
        scanSurvivors = 50,
        scanParallelism = 8,
        weights = DEFAULT_F2_WEIGHTS,
        favorites = emptyList(),
        wsDataSource = "FUTURES",
        chartCandleCount = 30,
        weightTakerLs = 0.35,
        weightOiMomentum = 0.30,
        weightWhaleLs = 0.20,
        weightAccountLs = 0.15,
        language = "en"
    )

    fun evaluate(results: List<IndicatorResult>): ConsensusOutput {
        val settings = settingsStore?.getSettings() ?: defaultSettings
        val config = ConsensusConfig(
            confidenceThreshold = settings.confidenceThreshold,
            minConfidenceForTrade = settings.minConfidenceForTrade,
            weights = settings.weights
        )
        val dynamicWeights = applyRegimeMatrix(results, config.weights, settings.enableRegimeMatrix)
        val s = Scorer.compute(results, dynamicWeights)

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
        
        val riskData = RiskAssessor.assess(results, s, RiskConfig())
        val riskPenalty = riskData.riskScore * 3.0
        rawConfidence -= riskPenalty
        
        val confidence = clampInt(rawConfidence, 0, 100)

        val shouldTrade = (signal != Signal.NEUTRAL)
            && (confidence >= config.confidenceThreshold)
            && (confidence >= config.minConfidenceForTrade)
            && (riskData.riskLevel != RiskLevel.HIGH)

        val riskStr = if (riskData.riskFactors.isNotEmpty()) " Risks: ${riskData.riskFactors.joinToString("; ")}" else ""
        val reason = "Signal=${signal.name} Conf=${confidence}% Risk=${riskData.riskLevel.name} Buy=${s.buyCount}/Sell=${s.sellCount}/Neut=${s.neutralCount} WScore=${s.weightedScore.format(3)}" + riskStr

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

    private fun applyRegimeMatrix(results: List<IndicatorResult>, baseWeights: Map<String, Double>, enable: Boolean): Map<String, Double> {
        if (!enable) return baseWeights
        val adxRes = results.find { it.name == "adx_di" }
        val adx = adxRes?.rawValues?.get("adx") ?: return baseWeights

        val dynamic = baseWeights.toMutableMap()
        
        val oscillators = listOf("rsi", "stochastic", "williams_r", "cci")
        val trendFollowers = listOf("macd", "ema_cross", "sma_cross", "ichimoku", "psar")

        if (adx < 20.0) { // Chop / Range
            oscillators.forEach { dynamic[it] = (dynamic[it] ?: 1.0) * 1.5 }
            trendFollowers.forEach { dynamic[it] = (dynamic[it] ?: 1.0) * 0.5 }
        } else if (adx > 25.0) { // Strong Trend
            oscillators.forEach { dynamic[it] = (dynamic[it] ?: 1.0) * 0.5 }
            trendFollowers.forEach { dynamic[it] = (dynamic[it] ?: 1.0) * 1.5 }
        }
        
        return dynamic
    }

    private fun clampInt(v: Double, lo: Int, hi: Int): Int = v.toInt().coerceIn(lo, hi)

    data class AlignedData(
        val oi: List<OpenInterestPoint>,
        val acc: List<LongShortRatioPoint>,
        val pos: List<LongShortRatioPoint>,
        val taker: List<TakerLongShortRatioPoint>,
        val global: List<LongShortRatioPoint>,
        val funding: List<FundingRatePoint>
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

        for (candle in candles) {
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
        }

        return AlignedData(alignedOi, alignedAcc, alignedPos, alignedTaker, alignedGlobal, alignedFunding)
    }


    private fun getDownPriceBaseScore(x: String, y: String, z: String): Triple<String, String, Double> {
        return when (x) {
            "DOWN" -> when (y) {
                "DOWN" -> when (z) {
                    "DOWN" -> Triple("Classic deleveraging washout, most consistent with long liquidation and bearish follow-through", "Bearish now, but exhaustion risk rises late in the move", -60.0)
                    "UP" -> Triple("Counterflow buying inside a still-bearish crowding regime; often short covering, not fresh trend reversal", "Tactical rebound candidate, weak structural conviction", 20.0)
                    else -> Triple("Deleveraging with worsening crowding but no rush of aggression", "Slow bearish drift", -40.0)
                }
                "UP" -> when (z) {
                    "DOWN" -> Triple("Crowd tries to get more bullish while weak longs are still being flushed", "One more flush is possible before a bounce", -30.0)
                    "UP" -> Triple("Strongest bear-trap / short-covering rebound profile in the falling-OI family", "Best rebound candidate in this block", 50.0)
                    else -> Triple("Positioning improves while leverage drains", "Neutral-to-bounce setup", 10.0)
                }
                else -> when (z) {
                    "DOWN" -> Triple("Forced exits dominate more than new conviction", "Bearish, but energy is aging", -50.0)
                    "UP" -> Triple("Deleveraging plus improving buyer aggression", "Local rebound candidate", 30.0)
                    else -> Triple("Risk is leaving and conviction is fading on both sides", "Transition or no-trade", 0.0)
                }
            }
            "UP" -> when (y) {
                "DOWN" -> when (z) {
                    "DOWN" -> Triple("Textbook new short build in a falling market", "Strongest bearish continuation", -95.0)
                    "UP" -> Triple("Aggressive buyers are being absorbed while new leverage still enters against them", "Bull trap / bearish absorption", -45.0)
                    else -> Triple("Stealth short build or hedge opening", "Bearish continuation", -70.0)
                }
                "UP" -> when (z) {
                    "DOWN" -> Triple("Crowd gets more bullish into a falling market while sellers stay aggressive", "Fragile long buildup, long-squeeze risk", -50.0)
                    "UP" -> Triple("Dip buyers are aggressive and leverage is rising, yet price still falls", "Two-sided war; either violent reversal or severe trap", 10.0)
                    else -> Triple("Bullish crowding with rising leverage, but no flowing bid strong enough to stop the drop", "Distribution / hidden weakness", -30.0)
                }
                else -> when (z) {
                    "DOWN" -> Triple("New leverage plus active sell dominance", "Clean continuation lower", -80.0)
                    "UP" -> Triple("Both sides are adding risk while price still falls", "Volatility expansion, avoid unless breakout confirms", 0.0)
                    else -> Triple("Participation rises without decisive trigger flow", "Pre-breakdown watch state", -30.0)
                }
            }
            else -> when (y) { // FLAT
                "DOWN" -> when (z) {
                    "DOWN" -> Triple("Bearish absorption or distribution without major fresh leverage", "Grind lower", -50.0)
                    "UP" -> Triple("Buyers are trying, but the tape still cannot lift while crowding worsens", "Failed-bounce risk", -10.0)
                    else -> Triple("Sentiment worsens but aggression is muted", "Fragile bearish drift", -35.0)
                }
                "UP" -> when (z) {
                    "DOWN" -> Triple("Crowd and carry improve, but active sellers still win", "Dip-buying is being absorbed", -20.0)
                    "UP" -> Triple("Best accumulation / mild covering profile in the flat-OI family", "Reversal candidate if price stops making new lows", 45.0)
                    else -> Triple("Positioning improves without trigger flow", "Neutral-to-slightly bullish, but incomplete", 15.0)
                }
                else -> when (z) {
                    "DOWN" -> Triple("Active sellers dominate against unchanged leverage", "Bearish distribution", -45.0)
                    "UP" -> Triple("Positive flow with unchanged leverage", "Early bounce candidate, low confidence", 25.0)
                    else -> Triple("Compression, transfer, or noise", "No-trade by default", 0.0)
                }
            }
        }
    }

    fun evaluateMultimodal(
        candles: List<Candle>,
        rawOi: List<OpenInterestPoint>,
        rawAcc: List<LongShortRatioPoint>,
        rawPos: List<LongShortRatioPoint>,
        rawGlobal: List<LongShortRatioPoint>,
        rawTaker: List<TakerLongShortRatioPoint>,
        rawFunding: List<FundingRatePoint>
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
            val accHistory = aligned.acc.subList(yStart, i + 1).map { it.longShortRatio }
            val globalHistory = aligned.global.subList(yStart, i + 1).map { it.longShortRatio }
            val fundingHistory = aligned.funding.subList(yStart, i + 1).map { it.fundingRate }

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
            val takerHistory = aligned.taker.subList(zStart, i + 1)
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

            val whaleHistory = aligned.pos.subList(yStart, i + 1).map { it.longShortRatio }
            val whaleMean = whaleHistory.average()
            val whaleStd = if (whaleHistory.size > 1) sqrt(whaleHistory.map { (it - whaleMean) * (it - whaleMean) }.sum() / (whaleHistory.size - 1)).coerceAtLeast(0.02) else 0.0
            val whaleZ = if (whaleStd > 0.0) (aligned.pos[i].longShortRatio - whaleMean) / whaleStd else 0.0

            val lookupY = if (priceState == "UP") {
                if (yState == "UP") "DOWN" else if (yState == "DOWN") "UP" else "FLAT"
            } else yState

            val lookupZ = if (priceState == "UP") {
                if (zState == "UP") "DOWN" else if (zState == "DOWN") "UP" else "FLAT"
            } else zState

            var (reading, biasDesc, baseScore) = if (priceState == "FLAT") {
                val desc = when (zState) {
                    "UP" -> "Range accumulation, buyers attempting breakout"
                    "DOWN" -> "Range distribution, sellers absorbing demand"
                    else -> "Mean-reverting chop or inactive tape"
                }
                val score = when (zState) {
                    "UP" -> 20.0
                    "DOWN" -> -20.0
                    else -> 0.0
                }
                Triple("Price flat (consolidation or mean reversion)", desc, score)
            } else {
                getDownPriceBaseScore(xState, lookupY, lookupZ)
            }

            if (priceState == "UP") {
                baseScore = -baseScore
                reading = swapKeywords(reading)
                biasDesc = swapKeywords(biasDesc)
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
            if (whaleOverridden) riskFactors.add("Whale-Retail positioning divergence")
            if (abs(aligned.funding[i].fundingRate) >= 0.003) riskFactors.add("Extreme carry/funding rate")

            val explanation = "Regime=$regime Conf=${confidence}% Bias=${biasDesc} Reading=${reading} Score=${finalScore.format(2)}"

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
        private val KEYWORDS_MAP = mapOf(
            "long liquidation" to "short covering",
            "short covering" to "long liquidation",
            "long-liquidation" to "short-covering",
            "short-covering" to "long-liquidation",
            "long-squeeze" to "short-squeeze",
            "short-squeeze" to "long-squeeze",
            "bear-trap" to "bull-trap",
            "bear trap" to "bull trap",
            "bull-trap" to "bear-trap",
            "bull trap" to "bear trap",
            "bearish" to "bullish",
            "Bearish" to "Bullish",
            "bullish" to "bearish",
            "Bullish" to "Bearish",
            "bear" to "bull",
            "Bear" to "Bull",
            "bull" to "bear",
            "Bull" to "Bear",
            "long" to "short",
            "Long" to "Short",
            "short" to "long",
            "Short" to "Long",
            "buyers" to "sellers",
            "Buyers" to "Sellers",
            "sellers" to "buyers",
            "Sellers" to "Buyers",
            "buyer" to "seller",
            "Buyer" to "Seller",
            "seller" to "buyer",
            "Seller" to "Buyer",
            "buying" to "selling",
            "Buying" to "Selling",
            "selling" to "buying",
            "Selling" to "Buying",
            "buy" to "sell",
            "Buy" to "Sell",
            "sell" to "buy",
            "Sell" to "Buy"
        )

        private val SWAP_REGEX = Regex(
            KEYWORDS_MAP.keys
                .sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }
        )
    }

    private fun swapKeywords(text: String): String {
        return SWAP_REGEX.replace(text) { matchResult ->
            KEYWORDS_MAP[matchResult.value] ?: matchResult.value
        }
    }
}

private class Aligner<T>(private val list: List<T>, private val getTimestamp: (T) -> Long) {
    private var idx = -1

    private fun absDiff(a: Long, b: Long): Long = if (a > b) a - b else b - a

    fun findClosest(t: Long, default: T): T {
        if (list.isEmpty()) return default
        while (idx + 1 < list.size && getTimestamp(list[idx + 1]) <= t) {
            idx++
        }
        if (idx == -1) {
            return list[0]
        }
        if (idx == list.size - 1) {
            return list[idx]
        }
        val curr = list[idx]
        val next = list[idx + 1]
        val currDiff = absDiff(getTimestamp(curr), t)
        val nextDiff = absDiff(getTimestamp(next), t)
        return if (nextDiff < currDiff) next else curr
    }

    fun findLastBeforeOrClosest(t: Long, default: T): T {
        if (list.isEmpty()) return default
        while (idx + 1 < list.size && getTimestamp(list[idx + 1]) <= t) {
            idx++
        }
        if (idx >= 0) {
            return list[idx]
        }
        return list[0]
    }
}
