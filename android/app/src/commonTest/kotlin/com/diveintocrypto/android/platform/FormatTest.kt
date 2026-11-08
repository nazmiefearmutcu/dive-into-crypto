package com.diveintocrypto.android.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the HALF_UP tie behaviour of [Double.format] (printf / String.format parity).
 * kotlin.math.round is ties-to-even, so the exact-tie cases below would fail against it.
 * Only exactly-representable binary ties are used (0.125, 2.5, 0.5, 1.5).
 */
class FormatTest {

    @Test
    fun exactTiesRoundHalfUp() {
        assertEquals("0.13", 0.125.format(2))
        assertEquals("3", 2.5.format(0))
        assertEquals("2", 1.5.format(0))
        assertEquals("1", 0.5.format(0))
    }

    @Test
    fun tiesKeepSignAndPlusFlag() {
        assertEquals("-0.13", (-0.125).format(2))
        assertEquals("+0.13", 0.125.format(2, plus = true))
    }

    @Test
    fun nonTiesRoundNormally() {
        assertEquals("12.34", 12.341.format(2))
        assertEquals("3.20", 3.2.format(2))
        assertEquals("0.12", 0.1249.format(2))
        assertEquals("0.00", 0.001.format(2))
    }

    @Test
    fun zeroNeverRendersNegative() {
        assertEquals("0.00", (-0.001).format(2))
        assertEquals("0", (-0.4).format(0))
    }

    @Test
    fun groupingAndNonFinite() {
        assertEquals("1,234.56", 1234.56.format(2, grouped = true))
        assertEquals("NaN", Double.NaN.format(2))
        assertEquals("Infinity", Double.POSITIVE_INFINITY.format(2))
    }
}
