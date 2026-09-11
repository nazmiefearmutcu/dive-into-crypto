package com.diveintocrypto.android.ui.panel.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic tests for the honesty-chip UI helper (lane B): the human age label
 * rendered inside the "GECİKME" staleness chip. No Compose, no network.
 */
class StaleChipLabelTest {

    @Test
    fun nullAgeRendersZeroSeconds() {
        assertEquals("0 sn önce", staleAgeLabel(null))
    }

    @Test
    fun subMinuteRendersSeconds() {
        assertEquals("0 sn önce", staleAgeLabel(0L))
        assertEquals("12 sn önce", staleAgeLabel(12_345L))
        assertEquals("59 sn önce", staleAgeLabel(59_999L))
    }

    @Test
    fun oneMinuteAndBeyondRendersMinutes() {
        assertEquals("1 dk önce", staleAgeLabel(60_000L))
        assertEquals("3 dk önce", staleAgeLabel(199_999L))
        assertEquals("61 dk önce", staleAgeLabel(3_660_000L))
    }

    @Test
    fun negativeAgeIsClampedToZero() {
        assertEquals("0 sn önce", staleAgeLabel(-5_000L))
    }
}
