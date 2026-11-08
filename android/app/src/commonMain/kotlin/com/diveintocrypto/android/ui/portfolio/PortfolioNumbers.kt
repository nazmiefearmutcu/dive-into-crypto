package com.diveintocrypto.android.ui.portfolio

import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * THE shared number parser for user-typed amounts (PURE — unit-tested).
 *
 * Every typed number in the app goes through here: position entry price and size in this
 * file, and alert thresholds, where AlertLabels delegates to [PortfolioNumbers.parseUserNumber].
 *
 *   - [PortfolioNumbers.parseUserNumber] returns the value, or null for empty, malformed,
 *     NaN, infinite or ambiguous input.
 *   - [AmbiguousNumber.matches] tells "rejected as ambiguous" apart from "malformed", so the
 *     UI can show the thousands-separator hint instead of a generic one.
 *
 * Accepted: plain decimals with '.' or ',' ("1.5", "1,5", "0.125"), and Turkish grouping with
 * a decimal comma ("65.000,5" = 65000.5). Leading-zero forms such as "0.001" are not grouping.
 *
 * Rejected as AMBIGUOUS rather than guessed: a leading digit 1-9 followed by digit groups of
 * exactly three with no decimal comma ("65.000", "1,234", "1.000.000", "145.123"), because the
 * same text means 65 in one locale and 65000 in another. A silent guess would change the
 * entry price 1000x without any hint.
 *
 * App-generated text ([PortfolioNumbers.editableText], used for the live-price fill and the
 * edit prefill) never produces an ambiguous shape: a value that would print as "145.123" is
 * written as "145.1230", which reads back to the same double.
 */
internal object PortfolioNumbers {

    /** Parses a user-typed number; null for empty, ambiguous, malformed, NaN or infinite input. */
    fun parseUserNumber(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || AmbiguousNumber.matches(trimmed)) return null
        val cleaned = if (TR_GROUPED_DECIMAL.matches(trimmed)) {
            trimmed.replace(".", "").replace(',', '.')
        } else {
            trimmed.replace(',', '.')
        }
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return value
    }

    /** True for grouped input whose meaning depends on the locale. Same rule as [AmbiguousNumber.matches]. */
    fun isAmbiguousGrouping(raw: String): Boolean = AmbiguousNumber.matches(raw)

    /**
     * Edit-sheet prefill and live-price fill, with enough digits that the stored value survives
     * a round trip (a fixed 2-5 decimal prefill rounded 0.0000123 to 0.00001 on every save).
     * Trailing zeros are dropped; "." is the decimal separator, like [parseUserNumber] accepts.
     * Precision is bounded so the scaled value always fits in a Long (large values lose
     * fractional digits instead of saturating, e.g. 5e11 prints as "500000000000").
     */
    fun editableText(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.format(0)
        val magnitude = abs(value)
        val decimals = when {
            magnitude == 0.0 -> 0
            magnitude >= 1.0 -> (17 - floor(log10(magnitude)).toInt()).coerceIn(0, 8)
            else -> (floor(-log10(magnitude)).toInt() + 10).coerceIn(8, 12)
        }
        val text = value.format(decimals)
        val plain = if (decimals > 0) text.trimEnd('0').trimEnd('.') else text
        // A dotted three-digit tail reads as grouping; the trailing zero keeps the same value unambiguous.
        return if (AmbiguousNumber.matches(plain)) "${plain}0" else plain
    }

    private val TR_GROUPED_DECIMAL = Regex("""[1-9]\d{0,2}(\.\d{3})+,\d+""")
}

/**
 * Marker for locale-ambiguous grouped input: a leading digit 1-9, then groups of exactly three
 * digits joined by '.' or ',' with no decimal comma ("65.000", "1,234"). [PortfolioNumbers.parseUserNumber]
 * rejects these; callers check this marker to show the thousands-separator hint.
 */
internal object AmbiguousNumber {
    private val DOT_GROUPED = Regex("""[1-9]\d{0,2}(\.\d{3})+""")
    private val COMMA_GROUPED = Regex("""[1-9]\d{0,2}(,\d{3})+""")

    fun matches(raw: String): Boolean {
        val trimmed = raw.trim()
        return DOT_GROUPED.matches(trimmed) || COMMA_GROUPED.matches(trimmed)
    }
}
