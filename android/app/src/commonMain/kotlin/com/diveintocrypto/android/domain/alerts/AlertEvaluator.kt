package com.diveintocrypto.android.domain.alerts

import kotlin.math.abs

/**
 * PURE rule evaluation (no clock reads, no state, no I/O — the clock is a
 * parameter). Given the current rules and the data available this pass, returns
 * the alerts that fire and the updated rules (lastFiredTs advanced; one-shot
 * rules disabled after firing).
 *
 * Coalescing: a rule never re-fires within [COALESCE_MS] of its last fire —
 * the scanner cycles and 1s ticker batches would otherwise spam the same
 * condition every pass.
 */
object AlertEvaluator {

    /** Minimum interval between two fires of the SAME rule (ms). */
    const val COALESCE_MS: Long = 60_000L

    data class Outcome(
        val fired: List<FiredAlert>,
        val rules: List<AlertRule>,
    )

    fun evaluate(
        rules: List<AlertRule>,
        inputs: AlertInputs,
        nowMs: Long,
        coalesceMs: Long = COALESCE_MS,
    ): Outcome {
        val fired = mutableListOf<FiredAlert>()
        val updatedRules = rules.map { rule ->
            if (!rule.enabled) return@map rule
            val last = rule.lastFiredTs
            if (last != null && nowMs - last < coalesceMs) return@map rule

            val message = messageFor(rule, inputs) ?: return@map rule

            fired += FiredAlert(
                ruleId = rule.id,
                symbol = rule.symbol,
                kind = rule.kind,
                message = message,
                firedTs = nowMs,
            )
            if (rule.oneShot) rule.copy(enabled = false, lastFiredTs = nowMs)
            else rule.copy(lastFiredTs = nowMs)
        }
        return Outcome(fired, updatedRules)
    }

    /** null = the rule's condition is not met (or no data this pass) → silent. */
    private fun messageFor(rule: AlertRule, inputs: AlertInputs): String? {
        return when (rule.kind) {
            AlertKind.PRICE_ABOVE -> {
                val price = inputs.prices[rule.symbol] ?: return null
                if (price > rule.threshold) {
                    "${rule.symbol} price above ${rule.threshold} (now $price)"
                } else null
            }
            AlertKind.PRICE_BELOW -> {
                val price = inputs.prices[rule.symbol] ?: return null
                if (price < rule.threshold) {
                    "${rule.symbol} price below ${rule.threshold} (now $price)"
                } else null
            }
            AlertKind.VERDICT -> {
                val v = inputs.verdicts[rule.symbol] ?: return null
                val dir = directionOf(v.signal) ?: return null // NEUTRAL → silent
                val wanted = when (rule.direction) {
                    AlertRule.DIRECTION_LONG -> 1
                    AlertRule.DIRECTION_SHORT -> -1
                    else -> 0 // ANY
                }
                val matches = wanted == 0 || wanted == dir
                if (matches) "${rule.symbol} verdict ${v.signal} (confidence ${v.confidence}%)"
                else null
            }
            AlertKind.CONFIDENCE_ABOVE -> {
                val v = inputs.verdicts[rule.symbol] ?: return null
                if (v.confidence > rule.threshold) {
                    "${rule.symbol} confidence ${v.confidence}% above ${rule.threshold}"
                } else null
            }
            AlertKind.OI_SPIKE_PCT -> {
                val spike = inputs.oiSpikePct[rule.symbol] ?: return null
                if (abs(spike) >= abs(rule.threshold) && spike > 0) {
                    "${rule.symbol} OI +${spike}% (threshold +${rule.threshold}%)"
                } else null
            }
        }
    }

    /** +1 for BUY/STRONG_BUY, −1 for SELL/STRONG_SELL, null for NEUTRAL/unknown. */
    fun directionOf(signal: String): Int? = when (signal) {
        "STRONG_BUY", "BUY" -> 1
        "STRONG_SELL", "SELL" -> -1
        else -> null
    }

    /**
     * Open-interest spike in percent over a series (oldest → newest), using the
     * first → last value. Returns null for degenerate series (honest silence):
     * fewer than two samples or a non-positive first value.
     */
    fun oiSpikePct(series: List<Double>): Double? {
        if (series.size < 2) return null
        val first = series.first()
        val last = series.last()
        if (first <= 0.0) return null
        return (last - first) / first * 100.0
    }
}
