package com.diveintocrypto.android.engine

import com.diveintocrypto.android.domain.model.Candle
import kotlin.test.Test
import kotlin.test.assertEquals

/** The live candle series must never bridge a gap left by a dropped socket. */
class CandleGapTest {
    private val m = 60_000L

    private fun bar(openTime: Long, close: Double = 1.0) = Candle(
        openTime = openTime,
        open = 1.0,
        high = 1.0,
        low = 1.0,
        close = close,
        volume = 1.0,
        closeTime = openTime + m - 1,
    )

    @Test
    fun `contiguous live bars are appended`() {
        val out = appendLiveCandle(listOf(bar(0L), bar(m)), bar(2 * m), m)
        assertEquals(listOf(0L, m, 2 * m), out.map { it.openTime })
    }

    @Test
    fun `the in-progress bar is replaced, not duplicated`() {
        val out = appendLiveCandle(listOf(bar(0L), bar(m, close = 1.0)), bar(m, close = 2.0), m)
        assertEquals(listOf(0L, m), out.map { it.openTime })
        assertEquals(2.0, out.last().close)
    }

    @Test
    fun `a gap restarts the series from the new bar instead of bridging it`() {
        // Bar 1 opened while the socket was down; the first frame after reconnect is bar 3.
        val out = appendLiveCandle(listOf(bar(0L), bar(m)), bar(3 * m), m)
        assertEquals(listOf(3 * m), out.map { it.openTime })
    }

    @Test
    fun `an older bar is ignored`() {
        val current = listOf(bar(0L), bar(m))
        assertEquals(listOf(0L, m), appendLiveCandle(current, bar(0L), m).map { it.openTime })
    }

    @Test
    fun `an empty series starts with the first bar`() {
        assertEquals(listOf(5 * m), appendLiveCandle(emptyList(), bar(5 * m), m).map { it.openTime })
    }
}
