package com.diveintocrypto.android.ui.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared typed-number parsing (ambiguity is rejected, never guessed) and edit-sheet precision. */
class PortfolioNumbersTest {

    @Test
    fun plainDecimalsAcceptDotAndComma() {
        assertEquals(1.5, PortfolioNumbers.parseUserNumber("1.5"))
        assertEquals(1.5, PortfolioNumbers.parseUserNumber("1,5"))
        assertEquals(12.5, PortfolioNumbers.parseUserNumber(" 12,5 "))
    }

    @Test
    fun groupedThousandsWithoutDecimalCommaAreRejected() {
        // "65.000" is 65 in one locale and 65000 in another: refusing it protects the P&L.
        assertTrue(AmbiguousNumber.matches("65.000"))
        assertTrue(PortfolioNumbers.isAmbiguousGrouping("65.000"))
        assertNull(PortfolioNumbers.parseUserNumber("65.000"))
        assertNull(PortfolioNumbers.parseUserNumber("1,234"))
        assertNull(PortfolioNumbers.parseUserNumber("1.000.000"))
        assertNull(PortfolioNumbers.parseUserNumber("145.123"))
    }

    @Test
    fun ambiguityMarkerIsDistinctFromMalformedInput() {
        assertTrue(AmbiguousNumber.matches("1,234"))
        assertFalse(AmbiguousNumber.matches("1.2.3"))
        assertFalse(AmbiguousNumber.matches("abc"))
        assertFalse(AmbiguousNumber.matches("65.000,5"))
        assertFalse(AmbiguousNumber.matches("0.001"))
    }

    @Test
    fun turkishGroupingWithDecimalCommaIsParsed() {
        assertEquals(65000.5, PortfolioNumbers.parseUserNumber("65.000,5"))
    }

    @Test
    fun smallPricesWithThreeDecimalsAreNotTakenAsGrouping() {
        assertEquals(0.001, PortfolioNumbers.parseUserNumber("0.001"))
        assertEquals(0.125, PortfolioNumbers.parseUserNumber("0,125"))
    }

    @Test
    fun emptyAndMalformedInputIsNull() {
        assertNull(PortfolioNumbers.parseUserNumber(""))
        assertNull(PortfolioNumbers.parseUserNumber("abc"))
        assertNull(PortfolioNumbers.parseUserNumber("1.2.3"))
        assertNull(PortfolioNumbers.parseUserNumber("NaN"))
        assertNull(PortfolioNumbers.parseUserNumber("Infinity"))
    }

    @Test
    fun editableTextKeepsSmallPricesExact() {
        val value = 0.0000123
        assertEquals(value, PortfolioNumbers.parseUserNumber(PortfolioNumbers.editableText(value)))
    }

    @Test
    fun editableTextKeepsFullPrecisionAndDropsTrailingZeros() {
        assertEquals("65000", PortfolioNumbers.editableText(65000.0))
        assertEquals("1.5", PortfolioNumbers.editableText(1.5))
        assertEquals("0", PortfolioNumbers.editableText(0.0))
        val value = 1.23456789
        assertEquals(value, PortfolioNumbers.parseUserNumber(PortfolioNumbers.editableText(value)))
    }

    @Test
    fun appGeneratedTextIsAlwaysAcceptedByTheParser() {
        // A three-decimal value in 1..999 would print as "145.123", which the typed-input rule
        // rejects. The prefill pads it instead, so Save stays enabled and the value is unchanged.
        for (value in listOf(145.123, 123.456, 1.5, 1.123, 999.999, 0.125)) {
            val text = PortfolioNumbers.editableText(value)
            assertFalse(AmbiguousNumber.matches(text), "text=$text")
            assertEquals(value, PortfolioNumbers.parseUserNumber(text), "text=$text")
        }
        assertEquals("145.1230", PortfolioNumbers.editableText(145.123))
        assertEquals("123.4560", PortfolioNumbers.editableText(123.456))
    }

    @Test
    fun largeValuesKeepTheirMagnitudeInsteadOfSaturating() {
        // 8 decimals would scale 5e11 past Long.MAX and print "92233720368.54775807".
        assertEquals("500000000000", PortfolioNumbers.editableText(5e11))
        assertEquals("1000000000000000000", PortfolioNumbers.editableText(1e18))
        assertEquals(5e11, PortfolioNumbers.parseUserNumber(PortfolioNumbers.editableText(5e11)))
    }
}
