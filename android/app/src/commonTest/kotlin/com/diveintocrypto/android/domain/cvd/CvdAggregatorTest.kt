package com.diveintocrypto.android.domain.cvd

import com.diveintocrypto.android.data.binance.AggTrade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PURE CVD aggregation math: aggressor-side volumes, cumulative delta, per-minute
 * bucketing and the honest window trimming. No network, no clock.
 */
class CvdAggregatorTest {

    private fun trade(ts: Long, qty: Double, buyerMaker: Boolean, id: Long = ts) =
        AggTrade(aggTradeId = id, price = 100.0, quantity = qty, timestamp = ts, isBuyerMaker = buyerMaker)

    @Test
    fun `buy and sell volumes and cvd follow the aggressor flag`() {
        val now = 10 * 60_000L
        val trades = listOf(
            trade(now - 100, qty = 1.0, buyerMaker = false), // taker bought
            trade(now - 80, qty = 2.0, buyerMaker = false),  // taker bought
            trade(now - 60, qty = 0.5, buyerMaker = true),   // taker sold
        )
        val snap = CvdAggregator.aggregate("BTCUSDT", trades, nowMs = now)

        assertEquals(3.0, snap.buyVol, 1e-9)
        assertEquals(0.5, snap.sellVol, 1e-9)
        assertEquals(2.5, snap.cvd, 1e-9)
        assertEquals(3, snap.tradeCount)
        assertEquals(now - 100, snap.windowStartTs)
    }

    @Test
    fun `window trimming drops trades older than the window and newer than now`() {
        val now = 20 * 60_000L
        val trades = listOf(
            trade(now - CvdAggregator.WINDOW_MS - 1, 5.0, false), // too old → dropped
            trade(now - CvdAggregator.WINDOW_MS, 1.0, false),     // exactly at cutoff → kept
            trade(now, 2.0, true),
            trade(now + 1, 9.0, false),                           // future clock skew → dropped
        )
        val snap = CvdAggregator.aggregate("BTCUSDT", trades, nowMs = now)
        assertEquals(2, snap.tradeCount)
        assertEquals(1.0, snap.buyVol, 1e-9)
        assertEquals(2.0, snap.sellVol, 1e-9)
        assertEquals(-1.0, snap.cvd, 1e-9)
    }

    @Test
    fun `per-minute buckets are chronological with per-minute deltas`() {
        val t0 = 1_700_000_000_999L // minute M
        val t1 = 1_700_000_061_000L // a later minute
        val trades = listOf(
            trade(t0, qty = 1.0, false),
            trade(t0 + 10, qty = 0.5, true),
            trade(t1, qty = 2.0, false),
        )
        val snap = CvdAggregator.aggregate("XUSDT", trades, nowMs = t1 + 60_000)

        assertEquals(2, snap.buckets.size, "two distinct minutes")
        val first = snap.buckets[0]
        val second = snap.buckets[1]
        val expectedFirstBucket = t0 - (t0 % CvdAggregator.BUCKET_MS)
        val expectedSecondBucket = t1 - (t1 % CvdAggregator.BUCKET_MS)
        assertTrue(first.minuteTs < second.minuteTs, "chronological order")
        assertEquals(expectedFirstBucket, first.minuteTs)
        assertEquals(expectedSecondBucket, second.minuteTs)
        assertEquals(1.0, first.buyVol, 1e-9)
        assertEquals(0.5, first.sellVol, 1e-9)
        assertEquals(0.5, first.delta, 1e-9)
        assertEquals(2.0, second.buyVol, 1e-9)
        assertEquals(2.0, second.delta, 1e-9)
        // Headline CVD equals the bucket sum.
        assertEquals(
            snap.buckets.sumOf { it.delta },
            snap.cvd,
            1e-9,
        )
    }

    @Test
    fun `empty and out-of-window inputs give an honest empty snapshot`() {
        val snap = CvdAggregator.aggregate("XUSDT", emptyList(), nowMs = 1_000L)
        assertEquals(0.0, snap.cvd, 1e-9)
        assertEquals(0, snap.tradeCount)
        assertTrue(snap.buckets.isEmpty())
        assertEquals(null, snap.windowStartTs)
    }
}
