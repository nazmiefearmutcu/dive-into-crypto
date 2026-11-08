package com.diveintocrypto.android.ui.alerts

import com.diveintocrypto.android.domain.alerts.AlertCondition
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertRule
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.i18n.DiveStrings
import com.diveintocrypto.android.ui.i18n.TrStrings
import com.diveintocrypto.android.ui.panel.components.ChartMath
import com.diveintocrypto.android.ui.portfolio.AmbiguousNumber
import com.diveintocrypto.android.ui.portfolio.PortfolioNumbers
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * PURE alert-UI helpers (no Compose, no clock, no I/O — unit-tested in
 * commonTest): kind/direction labels, fired-history line building, threshold
 * parsing/formatting/stepping, and the honest ETA / universe labels used by the
 * Scanner depth row. The screens stay dumb renderers.
 *
 * Every user-visible label takes the active [DiveStrings] catalog as `s`. The
 * default is [TrStrings] so the pure unit tests keep their Turkish expectations;
 * Compose call sites pass `LocalDiveStrings.current` so EN mode renders English.
 */
object AlertLabels {

    // ── Kind / direction labels ──────────────────────────────────────

    fun kindLabel(kind: AlertKind, s: DiveStrings = TrStrings): String = when (kind) {
        AlertKind.VERDICT -> s.kindVerdict
        AlertKind.CONFIDENCE_ABOVE -> s.kindConfidenceAbove
        AlertKind.PRICE_ABOVE -> s.kindPriceAbove
        AlertKind.PRICE_BELOW -> s.kindPriceBelow
        AlertKind.OI_SPIKE_PCT -> s.kindOiSpike
    }

    fun directionLabel(direction: String, s: DiveStrings = TrStrings): String = when (direction) {
        AlertRule.DIRECTION_ANY -> s.dirAny
        AlertRule.DIRECTION_LONG -> s.dirLong
        AlertRule.DIRECTION_SHORT -> s.dirShort
        else -> direction
    }

    /**
     * One-line rule description for rule rows, fired history and the banner,
     * e.g. "LONG kararı" · "güven ≥ 87" · "fiyat ≥ 65000.00" · "OI ≥ +5%".
     * Reads the RULE config (not the engine message) so history stays readable
     * even when the rule was deleted (null → "kural silindi" / "rule deleted").
     */
    fun ruleLineLabel(rule: AlertRule?, s: DiveStrings = TrStrings): String {
        if (rule == null) return s.alertRuleDeleted
        // Every AND-ed condition is shown: the rule's mirror fields only hold the first one, so a
        // banner or history row for a multi-condition rule must list them all to be readable.
        return rule.effectiveConditions.joinToString(" + ") { conditionLineLabel(it, s) }
    }

    private fun conditionLineLabel(condition: AlertCondition, s: DiveStrings): String = when (condition.kind) {
        AlertKind.VERDICT -> when (condition.direction) {
            AlertRule.DIRECTION_LONG -> s.alertRuleVerdictLong
            AlertRule.DIRECTION_SHORT -> s.alertRuleVerdictShort
            else -> s.alertRuleVerdictAny
        }
        AlertKind.CONFIDENCE_ABOVE -> s.alertRuleConfidenceAbove(condition.threshold.format(0))
        AlertKind.PRICE_ABOVE -> s.alertRulePriceAbove(formatPrice(condition.threshold))
        AlertKind.PRICE_BELOW -> s.alertRulePriceBelow(formatPrice(condition.threshold))
        AlertKind.OI_SPIKE_PCT -> s.alertRuleOiSpike(condition.threshold.format(0))
    }

    /**
     * Price with magnitude-adaptive decimals (no grouping — parser-friendly).
     * Sub-cent prices keep six significant digits: a 2-decimal cap would turn
     * 0.0000124 into 0.000012 and silently move the stored threshold ~3%.
     */
    fun formatPrice(value: Double): String = value.format(priceDecimalsFor(value))

    private fun priceDecimalsFor(value: Double): Int {
        if (value > 0.0 && value < 0.01) {
            val magnitude = floor(log10(value)).toInt() // 0.0000124 -> -5
            return (-magnitude + 5).coerceIn(ChartMath.priceDecimalsFor(value), 12)
        }
        return ChartMath.priceDecimalsFor(value)
    }

    // ── Threshold parsing / stepping (add-sheet) ──────────────────────

    /**
     * True for locale-ambiguous grouped input such as "65,000", "65.000" or "1,234,567"
     * (the shared rule in [AmbiguousNumber]). Read as a decimal it would be 65.0 and could fire a
     * BTC price rule on the first tick, so it is rejected instead of guessed.
     */
    fun isAmbiguousThousands(raw: String): Boolean = AmbiguousNumber.matches(raw)

    /**
     * Parses the threshold text field through the SHARED parser ([PortfolioNumbers.parseUserNumber]),
     * the same one the position sheet uses: trims, accepts ',' or '.' as the decimal separator, and
     * returns null for blank, NaN, infinite and ambiguous grouped input.
     */
    fun parseThreshold(raw: String): Double? = PortfolioNumbers.parseUserNumber(raw)

    /**
     * Display text for a stepped threshold, by kind: price kinds use price
     * precision; integer kinds (confidence, OI %) show whole numbers, so a
     * "+" tap on 60 reads "65", not "65.00".
     */
    fun formatThreshold(kind: AlertKind, value: Double): String = when (kind) {
        AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW -> formatPrice(value)
        else -> if (value == floor(value)) value.format(0) else value.format(2)
    }

    /** Default threshold text when the user switches kind in the add-sheet. */
    fun defaultThresholdText(kind: AlertKind, livePrice: Double?): String = when (kind) {
        AlertKind.VERDICT -> "0"
        AlertKind.CONFIDENCE_ABOVE -> "60"
        AlertKind.OI_SPIKE_PCT -> "5"
        AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW ->
            livePrice?.let { formatPrice(it) } ?: "0"
    }

    /**
     * Stepper increment per kind: confidence moves in 5-point steps, OI in
     * 1-point steps, price in magnitude-adaptive steps. Sub-cent prices step by
     * 1/100 of their own magnitude (0.00001 → 1e-7, 0.003 → 1e-5), so the step
     * stays a fixed fraction of the price for micro-priced symbols.
     */
    fun priceStepFor(price: Double): Double = when {
        price <= 0.0 -> 1.0
        price < 0.01 -> 10.0.pow(floor(log10(price)).toInt() - 2)
        price < 0.1 -> 0.005
        price < 1.0 -> 0.05
        price < 10.0 -> 0.5
        price < 100.0 -> 1.0
        price < 1000.0 -> 10.0
        price < 10000.0 -> 50.0
        else -> 100.0
    }

    fun thresholdStepFor(kind: AlertKind, current: Double): Double = when (kind) {
        AlertKind.VERDICT -> 1.0
        AlertKind.CONFIDENCE_ABOVE -> 5.0
        AlertKind.OI_SPIKE_PCT -> 1.0
        AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW -> priceStepFor(current)
    }

    /** Clamps a threshold into the kind's valid band (confidence 0..100 etc.). */
    fun clampThreshold(kind: AlertKind, value: Double): Double = when (kind) {
        AlertKind.VERDICT -> 0.0
        AlertKind.CONFIDENCE_ABOVE -> value.coerceIn(0.0, 100.0)
        AlertKind.OI_SPIKE_PCT -> value.coerceIn(0.0, 1000.0)
        AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW -> value.coerceAtLeast(0.0)
    }

    /** A rule needs a positive threshold for every kind except VERDICT. */
    fun thresholdRequired(kind: AlertKind): Boolean = kind != AlertKind.VERDICT

    // ── Scanner depth row (honest ETA / universe labels) ─────────────

    /** "—" when null (not enough samples yet — never a fake 0). */
    fun etaLabel(seconds: Long?, s: DiveStrings = TrStrings): String {
        if (seconds == null || seconds < 0) return "—"
        return when {
            seconds < 60 -> s.alertEtaSeconds(seconds)
            seconds < 3600 -> s.alertEtaMinutes((seconds + 59) / 60)
            else -> s.alertEtaHours((seconds + 3599) / 3600)
        }
    }

    // ── v2: cooldown chips + condition summary ───────────────────────────

    /**
     * One cooldown chip preset. [coalesceMs] is the rule's re-fire window;
     * [oneShot] chips ignore it ("once" = auto-disable after first fire).
     * [label] is the TR default; render with [cooldownChipLabel] for the active language.
     */
    data class CooldownChip(val label: String, val coalesceMs: Long, val oneShot: Boolean)

    /** The 4 cooldown chips: 1 DK / 15 DK / 1 SA / TEK ATIŞ (engine constants). */
    val COOLDOWN_CHIPS: List<CooldownChip> = listOf(
        CooldownChip("1 DK", AlertRule.COALESCE_1M, oneShot = false),
        CooldownChip("15 DK", AlertRule.COALESCE_15M, oneShot = false),
        CooldownChip("1 SA", AlertRule.COALESCE_1H, oneShot = false),
        CooldownChip("TEK ATIŞ", coalesceMs = 0L, oneShot = true),
    )

    /** Localized chip label for the active catalog. */
    fun cooldownChipLabel(chip: CooldownChip, s: DiveStrings = TrStrings): String =
        s.cooldownChipLabel(chip.oneShot, chip.coalesceMs)

    /** The chip matching a rule's cooldown config (default → the 1 DK chip). */
    fun selectedCooldownChip(oneShot: Boolean, coalesceMs: Long): CooldownChip =
        COOLDOWN_CHIPS.firstOrNull { chip ->
            chip.oneShot == oneShot && (oneShot || chip.coalesceMs == coalesceMs)
        } ?: COOLDOWN_CHIPS.first()

    /** Cooldown display for a rule: oneShot wins; unmatched windows render honestly ("N dk"). */
    fun cooldownLabel(oneShot: Boolean, coalesceMs: Long, s: DiveStrings = TrStrings): String = when {
        oneShot -> s.cooldownChipLabel(oneShot = true, coalesceMs = 0L)
        coalesceMs == AlertRule.COALESCE_1M ||
            coalesceMs == AlertRule.COALESCE_15M ||
            coalesceMs == AlertRule.COALESCE_1H -> s.cooldownChipLabel(oneShot = false, coalesceMs = coalesceMs)
        coalesceMs > 0 -> s.alertCooldownMinutes(coalesceMs / 60_000)
        else -> s.alertCooldownDefault
    }

    /**
     * One-line v2 summary for rule cards, e.g.
     * "2 koşul · KARAR YÖNÜ(LONG)+GÜVEN ÜSTÜ · 15 DK". v1 rules (empty
     * [AlertRule.conditions]) fold into exactly ONE condition — migration is
     * display-only and automatic.
     */
    fun conditionSummaryLabel(rule: AlertRule, s: DiveStrings = TrStrings): String {
        val conds = rule.effectiveConditions
        val kinds = conds.map { c ->
            val base = kindLabel(c.kind, s)
            if (c.kind == AlertKind.VERDICT && c.direction != AlertRule.DIRECTION_ANY) {
                "$base(${directionLabel(c.direction, s)})"
            } else base
        }
        val shown = if (kinds.size > 2) {
            kinds.take(2).joinToString("+") + "+${kinds.size - 2}"
        } else kinds.joinToString("+")
        return s.alertConditionSummary(conds.size, shown, cooldownLabel(rule.oneShot, rule.coalesceMs, s))
    }

    /** [ScanUniverseMode] label → display ("ALL" → "TÜMÜ"/"ALL"); unknown passes through. */
    fun universeLabel(mode: String, s: DiveStrings = TrStrings): String =
        if (mode == "ALL") s.alertUniverseAll else mode

    /** true for the full-universe mode (inline "~500 sembol · birkaç dk sürer" warning). */
    fun isFullUniverse(mode: String): Boolean = mode == "ALL"
}
