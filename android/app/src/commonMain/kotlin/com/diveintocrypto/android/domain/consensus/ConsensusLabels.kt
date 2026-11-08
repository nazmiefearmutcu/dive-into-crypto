package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.ui.i18n.EnStrings

/**
 * Human-readable text produced by the consensus domain. The domain itself stays
 * language-neutral: a UI layer may pass a translated implementation, while the
 * default [EnglishConsensusLabels] reproduces the desktop reference wording
 * exactly. Numbers arrive pre-formatted so each implementation controls how they
 * are presented.
 */
interface ConsensusLabels {

    /** Reason line shown under the verdict. [risks] is already capped by the caller. */
    fun reasonLine(
        signal: String,
        confidence: Int,
        risk: String,
        buyCount: Int,
        sellCount: Int,
        neutralCount: Int,
        weightedScore: String,
        risks: List<String>,
    ): String

    fun highAtrVolatility(): String
    fun lowAtrVolatility(): String
    fun weakTrend(adx: String, minAdx: String): String
    fun highSignalConflict(ratio: String): String
    fun moderateSignalConflict(ratio: String): String
    fun fewActiveSignals(active: Int, minActive: Int): String
    fun weakConviction(score: String): String

    /** Multimodal overlay risk factors. */
    fun whaleRetailDivergence(): String
    fun extremeFunding(): String

    /**
     * Multimodal narrative for one cell of the 27-cell table in [ConsensusEngine] (index = oi*9 +
     * yState*3 + zState). [mirrored] is true when the price is rising, which selects the
     * bullish-perspective text.
     */
    fun narrativeReading(cell: Int, mirrored: Boolean): String
    fun narrativeBias(cell: Int, mirrored: Boolean): String

    /** Reading for a flat price (no polarity). */
    fun flatPriceReading(): String

    /** Bias for a flat price; [zState] is the crowd state "UP", "DOWN" or anything else for flat. */
    fun flatPriceBias(zState: String): String

    /** Multimodal explanation line, with the regime name and field labels localized. */
    fun multimodalExplanation(regime: String, confidence: Int, bias: String, reading: String, score: String): String
}

/** English wording, identical to the historical output and the desktop reference. */
object EnglishConsensusLabels : ConsensusLabels {

    override fun reasonLine(
        signal: String,
        confidence: Int,
        risk: String,
        buyCount: Int,
        sellCount: Int,
        neutralCount: Int,
        weightedScore: String,
        risks: List<String>,
    ): String {
        val base = "Signal=$signal Conf=${confidence}% Risk=$risk " +
            "Buy=$buyCount/Sell=$sellCount/Neut=$neutralCount WScore=$weightedScore"
        return if (risks.isNotEmpty()) "$base Risks: ${risks.joinToString("; ")}" else base
    }

    override fun highAtrVolatility(): String = "High ATR volatility"
    override fun lowAtrVolatility(): String = "Low ATR volatility (squeeze potential)"
    override fun weakTrend(adx: String, minAdx: String): String = "Weak trend (ADX=$adx < $minAdx)"
    override fun highSignalConflict(ratio: String): String = "High signal conflict (ratio=$ratio)"
    override fun moderateSignalConflict(ratio: String): String = "Moderate signal conflict (ratio=$ratio)"
    override fun fewActiveSignals(active: Int, minActive: Int): String = "Few active signals ($active < $minActive)"
    override fun weakConviction(score: String): String = "Weak conviction (score=$score)"
    override fun whaleRetailDivergence(): String = "Whale-Retail positioning divergence"
    override fun extremeFunding(): String = "Extreme carry/funding rate"

    override fun narrativeReading(cell: Int, mirrored: Boolean): String =
        if (mirrored) EnStrings.i18nNarrReadingMirror[cell] else EnStrings.i18nNarrReading[cell]

    override fun narrativeBias(cell: Int, mirrored: Boolean): String =
        if (mirrored) EnStrings.i18nNarrBiasMirror[cell] else EnStrings.i18nNarrBias[cell]

    override fun flatPriceReading(): String = EnStrings.i18nNarrFlatReading

    override fun flatPriceBias(zState: String): String = when (zState) {
        "UP" -> EnStrings.i18nNarrFlatBiasUp
        "DOWN" -> EnStrings.i18nNarrFlatBiasDown
        else -> EnStrings.i18nNarrFlatBiasFlat
    }

    override fun multimodalExplanation(regime: String, confidence: Int, bias: String, reading: String, score: String): String =
        EnStrings.i18nNarrExplanation(regime, confidence, bias, reading, score)
}
