package com.diveintocrypto.android.domain.math

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class SeriesTest {

    @Test
    fun `rollingMean returns nulls for incomplete windows`() {
        val out = Series.rollingMean(listOf(1.0, 2.0, 3.0, 4.0, 5.0), window = 3)
        assertNull(out[0])
        assertNull(out[1])
        assertEquals(2.0, out[2]!!, 1e-9)
        assertEquals(3.0, out[3]!!, 1e-9)
        assertEquals(4.0, out[4]!!, 1e-9)
    }

    @Test
    fun `rollingStd uses sample std with ddof=1`() {
        val out = Series.rollingStd(listOf(1.0, 2.0, 3.0, 4.0, 5.0), window = 3)
        assertEquals(1.0, out[4]!!, 1e-9)
        assertEquals(1.0, out[3]!!, 1e-9)
        assertEquals(1.0, out[2]!!, 1e-9)
        assertNull(out[1])
    }

    @Test
    fun `rollingMin returns smallest in window`() {
        val out = Series.rollingMin(listOf(5.0, 3.0, 4.0, 2.0, 1.0), window = 3)
        assertEquals(3.0, out[2]!!, 1e-9)
        assertEquals(2.0, out[3]!!, 1e-9)
        assertEquals(1.0, out[4]!!, 1e-9)
    }

    @Test
    fun `rollingMax returns largest in window`() {
        val out = Series.rollingMax(listOf(1.0, 3.0, 2.0, 5.0, 4.0), window = 3)
        assertEquals(3.0, out[2]!!, 1e-9)
        assertEquals(5.0, out[3]!!, 1e-9)
        assertEquals(5.0, out[4]!!, 1e-9)
    }

    @Test
    fun `ewmAdjustFalse matches pandas formula y_t = a x_t + (1-a) y_t-1 with y0 = x0`() {
        // span=3 -> alpha=2/4=0.5
        val out = Series.ewmAdjustFalse(listOf(1.0, 2.0, 3.0, 4.0), span = 3)
        assertEquals(1.0, out[0], 1e-9)
        assertEquals(1.5, out[1], 1e-9)
        assertEquals(2.25, out[2], 1e-9)
        assertEquals(3.125, out[3], 1e-9)
    }
}
