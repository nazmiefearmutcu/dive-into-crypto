package com.diveintocrypto.android.domain.alerts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a rule watches. */
@Serializable
enum class AlertKind {
    /** Consensus verdict on a scan cycle: LONG (BUY/STRONG_BUY) / SHORT (SELL/STRONG_SELL). */
    @SerialName("verdict") VERDICT,

    /** Verdict confidence above a threshold (0..100) on a scan cycle. */
    @SerialName("confidence_above") CONFIDENCE_ABOVE,

    /** Live price above a threshold (mini-ticker stream). */
    @SerialName("price_above") PRICE_ABOVE,

    /** Live price below a threshold (mini-ticker stream). */
    @SerialName("price_below") PRICE_BELOW,

    /** Open-interest spike over the lookback window, in percent (scan cycle). */
    @SerialName("oi_spike_pct") OI_SPIKE_PCT,
}

/**
 * A user-authored alert rule.
 *
 * @param direction for VERDICT rules: "ANY" | "LONG" | "SHORT". Ignored by other kinds.
 * @param threshold PRICE_*: absolute price. CONFIDENCE_ABOVE: 0..100. OI_SPIKE_PCT: percent.
 * @param oneShot when true the rule auto-disables after its first fire.
 * @param lastFiredTs null = never fired. Drives the re-fire coalescing window.
 */
@Serializable
data class AlertRule(
    val id: String,
    val symbol: String,
    val kind: AlertKind,
    val direction: String = DIRECTION_ANY,
    val threshold: Double = 0.0,
    val oneShot: Boolean = false,
    val enabled: Boolean = true,
    val createdTs: Long,
    val lastFiredTs: Long? = null,
) {
    companion object {
        const val DIRECTION_ANY = "ANY"
        const val DIRECTION_LONG = "LONG"
        const val DIRECTION_SHORT = "SHORT"
    }
}

/** One fired-alert event (in-app banner + persisted history ring). */
@Serializable
data class FiredAlert(
    val ruleId: String,
    val symbol: String,
    val kind: AlertKind,
    val message: String,
    val firedTs: Long,
)

/**
 * A consensus verdict snapshot for one symbol, taken at scan-cycle end.
 * [signal] is a [com.diveintocrypto.android.domain.model.Signal] name.
 */
data class AlertVerdict(
    val signal: String,
    val confidence: Int,
    val price: Double,
)

/** Symbol-keyed verdict snapshot passed to [AlertEvaluator] after a scan cycle. */
data class AlertScanEntry(
    val symbol: String,
    val verdict: AlertVerdict,
)

/**
 * Everything the evaluator needs for one pass. Missing entries simply mean
 * "no data for that symbol this pass" — the rule stays silent (honest).
 */
data class AlertInputs(
    /** Symbol → live last price (mini-ticker stream). */
    val prices: Map<String, Double> = emptyMap(),
    /** Symbol → scan-cycle verdict snapshot. */
    val verdicts: Map<String, AlertVerdict> = emptyMap(),
    /** Symbol → OI spike over the lookback window, in percent. */
    val oiSpikePct: Map<String, Double> = emptyMap(),
)
