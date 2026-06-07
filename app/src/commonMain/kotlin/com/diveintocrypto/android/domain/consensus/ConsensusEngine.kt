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
        weightAccountLs = 0.15
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

        for (candle in candles) {
            val t = candle.openTime

            val oiPoint = rawOi.firstOrNull { it.timestamp == t }
                ?: rawOi.minByOrNull { abs(it.timestamp - t) }
                ?: OpenInterestPoint(t, 0.0, 0.0)
            alignedOi.add(oiPoint.copy(timestamp = t))

            val accPoint = rawAcc.firstOrNull { it.timestamp == t }
                ?: rawAcc.minByOrNull { abs(it.timestamp - t) }
                ?: LongShortRatioPoint(t, 0.5, 0.5, 1.0)
            alignedAcc.add(accPoint.copy(timestamp = t))

            val posPoint = rawPos.firstOrNull { it.timestamp == t }
                ?: rawPos.minByOrNull { abs(it.timestamp - t) }
                ?: LongShortRatioPoint(t, 0.5, 0.5, 1.0)
            alignedPos.add(posPoint.copy(timestamp = t))

            val takerPoint = rawTaker.firstOrNull { it.timestamp == t }
                ?: rawTaker.minByOrNull { abs(it.timestamp - t) }
                ?: TakerLongShortRatioPoint(t, 1.0, 0.0, 0.0)
            alignedTaker.add(takerPoint.copy(timestamp = t))

            val globalPoint = rawGlobal.firstOrNull { it.timestamp == t }
                ?: rawGlobal.minByOrNull { abs(it.timestamp - t) }
                ?: LongShortRatioPoint(t, 0.5, 0.5, 1.0)
            alignedGlobal.add(globalPoint.copy(timestamp = t))

            val fundingPoint = rawFunding.filter { it.timestamp <= t }.maxByOrNull { it.timestamp }
                ?: rawFunding.minByOrNull { abs(it.timestamp - t) }
                ?: FundingRatePoint(t, 0.0)
            alignedFunding.add(fundingPoint.copy(timestamp = t))
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
            val variance = if (windowReturns.isNotEmpty()) windowReturns.map { (it - mean) * (it - mean) }.sum() / windowReturns.size else 0.0
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
            val oiVar = if (oiHistory.isNotEmpty()) oiHistory.map { (it - oiMean) * (it - oiMean) }.sum() / oiHistory.size else 0.0
            val oiStdDev = sqrt(oiVar).coerceAtLeast(0.005)
            val oizScore = (oiPct - oiMean) / oiStdDev

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
            val accStd = sqrt(accHistory.map { (it - accMean) * (it - accMean) }.sum() / accHistory.size).coerceAtLeast(0.02)
            val accZ = (aligned.acc[i].longShortRatio - accMean) / accStd

            val globalMean = globalHistory.average()
            val globalStd = sqrt(globalHistory.map { (it - globalMean) * (it - globalMean) }.sum() / globalHistory.size).coerceAtLeast(0.02)
            val globalZ = (aligned.global[i].longShortRatio - globalMean) / globalStd

            val fundingMean = fundingHistory.average()
            val fundingStd = sqrt(fundingHistory.map { (it - fundingMean) * (it - fundingMean) }.sum() / fundingHistory.size).coerceAtLeast(0.0001)
            val fundingZ = (aligned.funding[i].fundingRate - fundingMean) / fundingStd

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
            val netTakerStd = sqrt(netTakerHistory.map { (it - netTakerMean) * (it - netTakerMean) }.sum() / netTakerHistory.size).coerceAtLeast(0.05)
            val netTakerZ = (netTakerPct - netTakerMean) / netTakerStd

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
            val whaleStd = sqrt(whaleHistory.map { (it - whaleMean) * (it - whaleMean) }.sum() / whaleHistory.size).coerceAtLeast(0.02)
            val whaleZ = (aligned.pos[i].longShortRatio - whaleMean) / whaleStd

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

    private fun swapKeywords(text: String): String {
        return text
            // First step: Compound terms to placeholders
            .replace("long liquidation", "__P_LL__")
            .replace("short covering", "__P_SC__")
            .replace("long-liquidation", "__P_LL_H__")
            .replace("short-covering", "__P_SC_H__")
            .replace("long-squeeze", "__P_LS__")
            .replace("short-squeeze", "__P_SS__")
            .replace("bear-trap", "__P_BT__")
            .replace("bear trap", "__P_BT2__")
            .replace("bull-trap", "__P_UT__")
            .replace("bull trap", "__P_UT2__")
            
            // Second step: Individual words to placeholders
            .replace("bearish", "__P_BULLISH__")
            .replace("Bearish", "__P_BULLISH_CAP__")
            .replace("bullish", "__P_BEARISH__")
            .replace("Bullish", "__P_BEARISH_CAP__")
            .replace("bear", "__P_BULL__")
            .replace("Bear", "__P_BULL_CAP__")
            .replace("bull", "__P_BEAR__")
            .replace("Bull", "__P_BEAR_CAP__")
            .replace("long", "__P_SHORT__")
            .replace("Long", "__P_SHORT_CAP__")
            .replace("short", "__P_LONG__")
            .replace("Short", "__P_LONG_CAP__")
            .replace("buyers", "__P_SELLERS__")
            .replace("Buyers", "__P_SELLERS_CAP__")
            .replace("sellers", "__P_BUYERS__")
            .replace("Sellers", "__P_BUYERS_CAP__")
            .replace("buyer", "__P_SELLER__")
            .replace("Buyer", "__P_SELLER_CAP__")
            .replace("seller", "__P_BUYER__")
            .replace("Seller", "__P_BUYER_CAP__")
            .replace("buying", "__P_SELLING__")
            .replace("Buying", "__P_SELLING_CAP__")
            .replace("selling", "__P_BUYING__")
            .replace("Selling", "__P_BUYING_CAP__")
            .replace("buy", "__P_SELL__")
            .replace("Buy", "__P_SELL_CAP__")
            .replace("sell", "__P_BUY__")
            .replace("Sell", "__P_BUY_CAP__")

            // Third step: Placeholders to targets
            .replace("__P_LL__", "short covering")
            .replace("__P_SC__", "long liquidation")
            .replace("__P_LL_H__", "short-covering")
            .replace("__P_SC_H__", "long-liquidation")
            .replace("__P_LS__", "short-squeeze")
            .replace("__P_SS__", "long-squeeze")
            .replace("__P_BT__", "bull-trap")
            .replace("__P_BT2__", "bull trap")
            .replace("__P_UT__", "bear-trap")
            .replace("__P_UT2__", "bear trap")
            .replace("__P_BULLISH__", "bullish")
            .replace("__P_BULLISH_CAP__", "Bullish")
            .replace("__P_BEARISH__", "bearish")
            .replace("__P_BEARISH_CAP__", "Bearish")
            .replace("__P_BULL__", "bull")
            .replace("__P_BULL_CAP__", "Bull")
            .replace("__P_BEAR__", "bear")
            .replace("__P_BEAR_CAP__", "Bear")
            .replace("__P_SHORT__", "short")
            .replace("__P_SHORT_CAP__", "Short")
            .replace("__P_LONG__", "long")
            .replace("__P_LONG_CAP__", "Long")
            .replace("__P_SELLERS__", "sellers")
            .replace("__P_SELLERS_CAP__", "Sellers")
            .replace("__P_BUYERS__", "buyers")
            .replace("__P_BUYERS_CAP__", "Buyers")
            .replace("__P_SELLER__", "seller")
            .replace("__P_SELLER_CAP__", "Seller")
            .replace("__P_BUYER__", "buyer")
            .replace("__P_BUYER_CAP__", "Buyer")
            .replace("__P_SELLING__", "selling")
            .replace("__P_SELLING_CAP__", "Selling")
            .replace("__P_BUYING__", "buying")
            .replace("__P_BUYING_CAP__", "Buying")
            .replace("__P_SELL__", "sell")
            .replace("__P_SELL_CAP__", "Sell")
            .replace("__P_BUY__", "buy")
            .replace("__P_BUY_CAP__", "Buy")
    }
}
