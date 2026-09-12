package com.diveintocrypto.android.domain.alerts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a rule (or one AND-condition inside a rule) watches. */
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

    /**
     * Open-interest spike over the lookback window, in percent (scan cycle).
     * SIGNED threshold: > 0 watches OI expansion spikes (spike >= threshold);
     * < 0 watches OI collapses (spike <= threshold); 0 = any expansion.
     */
    @SerialName("oi_spike_pct") OI_SPIKE_PCT,
}

/**
 * ONE alert condition (v2): kind + direction + threshold. A rule fires only when
 * EVERY one of its conditions is satisfiable from the current pass data
 * (AND semantics). Missing data for any condition = honest silence.
 */
@Serializable
data class AlertCondition(
    val kind: AlertKind,
    val direction: String = AlertRule.DIRECTION_ANY,
    val threshold: Double = 0.0,
)

/**
 * A user-authored alert rule (v2).
 *
 * v1 compatibility: the single-condition fields ([kind]/[direction]/[threshold])
 * are KEPT and always mirror [conditions].firstOrNull() (or the primary condition
 * when the list is empty). UI rows and the fired-alert banner read them directly.
 *
 * @param direction for VERDICT conditions: "ANY" | "LONG" | "SHORT". Ignored by other kinds.
 * @param threshold PRICE_*: absolute price. CONFIDENCE_ABOVE: 0..100. OI_SPIKE_PCT: percent.
 * @param oneShot when true the rule auto-disables after its first fire.
 * @param lastFiredTs null = never fired. Drives the re-fire coalescing window.
 * @param conditions AND-ed condition list (v2). Empty = derive the single primary
 *        condition from kind/direction/threshold (the v1 shape).
 * @param coalesceMs per-rule re-fire window in ms (0 = engine default). UI chips:
 *        1m/15m/1h map to [COALESCE_1M]/[COALESCE_15M]/[COALESCE_1H]; "once" → [oneShot].
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
    val conditions: List<AlertCondition> = emptyList(),
    val coalesceMs: Long = COALESCE_DEFAULT_MS,
) {
    /** The AND-ed condition list this rule evaluates against (v1 shape folded in). */
    val effectiveConditions: List<AlertCondition>
        get() = if (conditions.isNotEmpty()) conditions
        else listOf(AlertCondition(kind, direction, threshold))

    companion object {
        const val DIRECTION_ANY = "ANY"
        const val DIRECTION_LONG = "LONG"
        const val DIRECTION_SHORT = "SHORT"

        /** Default minimum interval between two fires of the SAME rule (ms). */
        const val COALESCE_DEFAULT_MS: Long = 60_000L

        /** Coalesce chip presets (UI lane renders the labels). */
        const val COALESCE_1M: Long = 60_000L
        const val COALESCE_15M: Long = 15 * 60_000L
        const val COALESCE_1H: Long = 60 * 60_000L
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
