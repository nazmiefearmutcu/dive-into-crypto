package com.diveintocrypto.android.platform

import kotlin.math.abs
import kotlin.math.floor

/**
 * Multiplatform replacement for `String.format(Locale.US, "%[,].Nf", value)`.
 *
 * Returns the numeric portion only (call sites prepend "$" etc. as before).
 *   - `decimals` → fixed fractional digits, e.g. 2 → "12.30"
 *   - `grouped`  → US thousands separator on the integer part, e.g. "1,234.56"
 *   - `plus`     → force a leading "+" on non-negative values (printf "%+.Nf")
 *
 * Decimal point is always '.', matching Locale.US. Exact ties round HALF_UP
 * (away from zero for the magnitude), as printf/String.format do — e.g.
 * `0.125.format(2)` → "0.13" and `2.5.format(0)` → "3". Kotlin's `round` is
 * ties-to-even, so it is deliberately not used here.
 */
fun Double.format(decimals: Int, grouped: Boolean = false, plus: Boolean = false): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "Infinity" else "-Infinity"

    val neg = this < 0.0
    val absValue = abs(this)

    var factor = 1L
    repeat(decimals) { factor *= 10 }

    // HALF_UP on the magnitude: floor(x + 0.5). Double.toLong() saturates on overflow.
    val scaled = floor(absValue * factor + 0.5).toLong()
    val intUnits = scaled / factor
    val fracUnits = scaled % factor

    val intStr = if (grouped) groupThousands(intUnits) else intUnits.toString()

    return buildString {
        if (neg && scaled != 0L) append('-') else if (plus) append('+')
        append(intStr)
        if (decimals > 0) {
            append('.')
            append(fracUnits.toString().padStart(decimals, '0'))
        }
    }
}

private fun groupThousands(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    return buildString {
        val rem = s.length % 3
        var i = 0
        if (rem > 0) {
            append(s, 0, rem)
            i = rem
            if (i < s.length) append(',')
        }
        while (i < s.length) {
            append(s, i, i + 3)
            i += 3
            if (i < s.length) append(',')
        }
    }
}
