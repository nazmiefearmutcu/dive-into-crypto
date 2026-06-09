package com.diveintocrypto.android.domain.indicator

import kotlin.math.roundToLong

/**
 * Multiplatform shim for the `Math.round(...)` calls used across the indicator
 * package — `java.lang.Math` is JVM-only. Living in the same package lets every
 * indicator keep calling `Math.round(x)` unchanged (no import, no edit).
 *
 * `Math.round(Double)` returns a Long with ties rounded toward positive infinity,
 * identical to java.lang.Math.round; NaN maps to 0 to match Java's behaviour
 * (kotlin's roundToLong() would otherwise throw).
 */
internal object Math {
    fun round(value: Double): Long = if (value.isNaN()) 0L else value.roundToLong()
}
