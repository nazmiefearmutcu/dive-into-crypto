package com.diveintocrypto.android.ui.alerts

import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertRule
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.panel.components.ChartMath

/**
 * PURE alert-UI helpers (no Compose, no clock, no I/O — unit-tested in
 * commonTest): Turkish kind/direction labels, fired-history line building,
 * threshold parsing/formatting/stepping, and the honest ETA / universe labels
 * used by the Scanner depth row. The screens stay dumb renderers.
 */
object AlertLabels {

    // ── Kind / direction labels (TR, matching the app's label style) ──

    fun kindLabel(kind: AlertKind): String = when (kind) {
        AlertKind.VERDICT -> "KARAR YÖNÜ"
        AlertKind.CONFIDENCE_ABOVE -> "GÜVEN ÜSTÜ"
        AlertKind.PRICE_ABOVE -> "FİYAT ÜSTÜ"
        AlertKind.PRICE_BELOW -> "FİYAT ALT"
        AlertKind.OI_SPIKE_PCT -> "OI ARTIŞI"
    }

    fun directionLabel(direction: String): String = when (direction) {
        AlertRule.DIRECTION_ANY -> "TÜMÜ"
        AlertRule.DIRECTION_LONG -> "LONG"
        AlertRule.DIRECTION_SHORT -> "SHORT"
        else -> direction
    }

    /**
     * One-line rule description for rule rows, fired history and the banner,
     * e.g. "LONG kararı" · "güven ≥ 87" · "fiyat ≥ 65000.00" · "OI ≥ +5%".
     * Reads the RULE config (not the English engine message) so history stays
     * readable even when the rule was deleted (null → kind label only).
     */
    fun ruleLineLabel(rule: AlertRule?): String {
        if (rule == null) return "kural silindi"
        return when (rule.kind) {
            AlertKind.VERDICT -> when (rule.direction) {
                AlertRule.DIRECTION_LONG -> "LONG kararı"
                AlertRule.DIRECTION_SHORT -> "SHORT kararı"
                else -> "karar · tümü"
            }
            AlertKind.CONFIDENCE_ABOVE -> "güven ≥ ${rule.threshold.format(0)}"
            AlertKind.PRICE_ABOVE -> "fiyat ≥ ${formatPrice(rule.threshold)}"
            AlertKind.PRICE_BELOW -> "fiyat ≤ ${formatPrice(rule.threshold)}"
            AlertKind.OI_SPIKE_PCT -> "OI ≥ +${rule.threshold.format(0)}%"
        }
    }

    /** Price with magnitude-adaptive decimals (no grouping — parser-friendly). */
    fun formatPrice(value: Double): String = value.format(ChartMath.priceDecimalsFor(value))

    // ── Threshold parsing / stepping (add-sheet) ──────────────────────

    /**
     * Parses the threshold text field: trims, accepts ',' or '.' as the decimal
     * separator, rejects blank/NaN/infinite/negative-free garbage (null).
     */
    fun parseThreshold(raw: String): Double? {
        val cleaned = raw.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val v = cleaned.toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return v
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
     * 1-point steps, price in magnitude-adaptive steps (0.0005 for a $0.003
     * coin up to 100 for a $20k coin).
     */
    fun priceStepFor(price: Double): Double = when {
        price <= 0.0 -> 1.0
        price < 0.01 -> 0.0005
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
    fun etaLabel(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "—"
        return when {
            seconds < 60 -> "~$seconds sn"
            seconds < 3600 -> "~${(seconds + 59) / 60} dk"
            else -> "~${(seconds + 3599) / 3600} sa"
        }
    }

    /** [ScanUniverseMode] label → display ("ALL" → "TÜMÜ"); unknown passes through. */
    fun universeLabel(mode: String): String = if (mode == "ALL") "TÜMÜ" else mode

    /** true for the full-universe mode (inline "~500 sembol · birkaç dk sürer" warning). */
    fun isFullUniverse(mode: String): Boolean = mode == "ALL"
}
