package com.diveintocrypto.android.ui.i18n

import com.diveintocrypto.android.domain.alerts.AlertMessages
import com.diveintocrypto.android.domain.consensus.ConsensusLabels

/**
 * Adapters that feed the language-neutral domain formatters (consensus reason /
 * risk text, alert messages) from the active [DiveStrings] catalog. The domain
 * keeps English defaults ([com.diveintocrypto.android.domain.consensus.EnglishConsensusLabels],
 * [com.diveintocrypto.android.domain.alerts.EnglishAlertMessages]) for tests and the
 * background worker; UI and the app-scoped alert engine pass these instead.
 */
fun consensusLabelsFor(strings: DiveStrings): ConsensusLabels = DiveConsensusLabels(strings)

/** Alert-message formatter for the active catalog (banner, history and notifications). */
fun alertMessagesFor(strings: DiveStrings): AlertMessages = DiveAlertMessages(strings)

private class DiveConsensusLabels(private val s: DiveStrings) : ConsensusLabels {

    override fun reasonLine(
        signal: String,
        confidence: Int,
        risk: String,
        buyCount: Int,
        sellCount: Int,
        neutralCount: Int,
        weightedScore: String,
        risks: List<String>,
    ): String = s.i18nConsReasonLine(
        signal = signal,
        confidence = confidence,
        risk = risk,
        buyCount = buyCount,
        sellCount = sellCount,
        neutralCount = neutralCount,
        weightedScore = weightedScore,
        risks = risks,
    )

    override fun highAtrVolatility(): String = s.i18nConsHighAtr
    override fun lowAtrVolatility(): String = s.i18nConsLowAtr
    override fun weakTrend(adx: String, minAdx: String): String = s.i18nConsWeakTrend(adx, minAdx)
    override fun highSignalConflict(ratio: String): String = s.i18nConsHighConflict(ratio)
    override fun moderateSignalConflict(ratio: String): String = s.i18nConsModerateConflict(ratio)
    override fun fewActiveSignals(active: Int, minActive: Int): String = s.i18nConsFewActive(active, minActive)
    override fun weakConviction(score: String): String = s.i18nConsWeakConviction(score)
    override fun whaleRetailDivergence(): String = s.i18nConsWhaleRetail
    override fun extremeFunding(): String = s.i18nConsExtremeFunding

    override fun narrativeReading(cell: Int, mirrored: Boolean): String =
        if (mirrored) s.i18nNarrReadingMirror[cell] else s.i18nNarrReading[cell]

    override fun narrativeBias(cell: Int, mirrored: Boolean): String =
        if (mirrored) s.i18nNarrBiasMirror[cell] else s.i18nNarrBias[cell]

    override fun flatPriceReading(): String = s.i18nNarrFlatReading

    override fun flatPriceBias(zState: String): String = when (zState) {
        "UP" -> s.i18nNarrFlatBiasUp
        "DOWN" -> s.i18nNarrFlatBiasDown
        else -> s.i18nNarrFlatBiasFlat
    }

    override fun multimodalExplanation(regime: String, confidence: Int, bias: String, reading: String, score: String): String =
        s.i18nNarrExplanation(regime, confidence, bias, reading, score)
}

private class DiveAlertMessages(private val s: DiveStrings) : AlertMessages {

    override fun priceAbove(symbol: String, threshold: Double, price: Double): String =
        s.i18nAlertPriceAbove(symbol, threshold, price)

    override fun priceBelow(symbol: String, threshold: Double, price: Double): String =
        s.i18nAlertPriceBelow(symbol, threshold, price)

    override fun verdict(symbol: String, signal: String, confidence: Int): String =
        s.i18nAlertVerdict(symbol, signal, confidence)

    override fun confidenceAbove(symbol: String, confidence: Int, threshold: Double): String =
        s.i18nAlertConfidenceAbove(symbol, confidence, threshold)

    override fun oiCollapse(symbol: String, spike: Double, threshold: Double): String =
        s.i18nAlertOiCollapse(symbol, spike, threshold)

    override fun oiExpansion(symbol: String, spike: Double, threshold: Double): String =
        s.i18nAlertOiExpansion(symbol, spike, threshold)

    override fun conditionJoiner(): String = s.i18nAlertJoiner
}
