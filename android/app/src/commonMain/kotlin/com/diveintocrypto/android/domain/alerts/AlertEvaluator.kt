package com.diveintocrypto.android.domain.alerts

/**
 * PURE rule evaluation (no clock reads, no state, no I/O — the clock is a
 * parameter). Given the current rules and the data available this pass, returns
 * the alerts that fire and the updated rules (lastFiredTs advanced; one-shot
 * rules disabled after firing).
 *
 * Coalescing (v2): a rule never re-fires within its OWN [AlertRule.coalesceMs]
 * window (0 → the engine-wide [COALESCE_MS] default) of its last fire — the
 * scanner cycles and 1s ticker batches would otherwise spam the same condition
 * every pass.
 *
 * AND semantics (v2): every condition in [AlertRule.effectiveConditions] must be
 * satisfiable from THIS pass's data for the rule to fire. Any missing data for
 * any condition = honest silence for the whole rule.
 */
object AlertEvaluator {

    /** Minimum interval between two fires of the SAME rule (ms) — engine default. */
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
            // v2: the rule's own window wins; 0 = fall back to the engine default.
            val window = if (rule.coalesceMs > 0) rule.coalesceMs else coalesceMs
            if (last != null && nowMs - last < window) return@map rule

            val conditions = rule.effectiveConditions
            if (conditions.isEmpty()) return@map rule

            val metMessages = conditions.map { conditionMessage(it, rule.symbol, inputs) }
            if (metMessages.any { it == null }) return@map rule // AND: any unknown → silent

            fired += FiredAlert(
                ruleId = rule.id,
                symbol = rule.symbol,
                kind = rule.kind,
                message = metMessages.filterNotNull().joinToString(" AND "),
                firedTs = nowMs,
            )
            if (rule.oneShot) rule.copy(enabled = false, lastFiredTs = nowMs)
            else rule.copy(lastFiredTs = nowMs)
        }
        return Outcome(fired, updatedRules)
    }

    /** null = the condition is not met (or no data this pass) → silent. */
    fun conditionMessage(condition: AlertCondition, symbol: String, inputs: AlertInputs): String? {
        return when (condition.kind) {
            AlertKind.PRICE_ABOVE -> {
                val price = inputs.prices[symbol] ?: return null
                if (price > condition.threshold) {
                    "$symbol price above ${condition.threshold} (now $price)"
                } else null
            }
            AlertKind.PRICE_BELOW -> {
                val price = inputs.prices[symbol] ?: return null
                if (price < condition.threshold) {
                    "$symbol price below ${condition.threshold} (now $price)"
                } else null
            }
            AlertKind.VERDICT -> {
                val v = inputs.verdicts[symbol] ?: return null
                val dir = directionOf(v.signal) ?: return null // NEUTRAL → silent
                val wanted = when (condition.direction) {
                    AlertRule.DIRECTION_LONG -> 1
                    AlertRule.DIRECTION_SHORT -> -1
                    else -> 0 // ANY
                }
                val matches = wanted == 0 || wanted == dir
                if (matches) "$symbol verdict ${v.signal} (confidence ${v.confidence}%)"
                else null
            }
            AlertKind.CONFIDENCE_ABOVE -> {
                val v = inputs.verdicts[symbol] ?: return null
                if (v.confidence > condition.threshold) {
                    "$symbol confidence ${v.confidence}% above ${condition.threshold}"
                } else null
            }
            AlertKind.OI_SPIKE_PCT -> {
                // SIGNED threshold semantics (the spike input itself is signed
                // first→last %): positive threshold = OI EXPANSION spike
                // (fires when spike >= threshold); negative threshold = OI
                // COLLAPSE (fires when spike <= threshold, e.g. −10 → −12%
                // fires, −5% stays silent); threshold 0 = legacy positive-only
                // watch — any expansion at all.
                val spike = inputs.oiSpikePct[symbol] ?: return null
                val threshold = condition.threshold
                val met = when {
                    threshold > 0 -> spike >= threshold
                    threshold < 0 -> spike <= threshold
                    else -> spike > 0
                }
                if (!met) return null
                if (threshold < 0) "$symbol OI $spike% (threshold $threshold%)"
                else "$symbol OI +$spike% (threshold +$threshold%)"
            }
        }
    }

    /** null = the rule's condition set is not met (or no data this pass) → silent. */
    @Deprecated(
        message = "v2 evaluates AND groups; kept for one-transition for v1 callers.",
        replaceWith = ReplaceWith("AlertEvaluator.evaluate(...)"),
    )
    fun messageFor(rule: AlertRule, inputs: AlertInputs): String? {
        val conditions = rule.effectiveConditions
        if (conditions.isEmpty()) return null
        val met = conditions.map { conditionMessage(it, rule.symbol, inputs) }
        return if (met.any { it == null }) null else met.filterNotNull().joinToString(" AND ")
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
