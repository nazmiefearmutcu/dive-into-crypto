package com.diveintocrypto.android.domain.divergence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins down the [DivergenceAlignment] contract: same-bucket 1:1 matching, gap
 * forward-fill, skipping candles before the L/S starts, the period-START alignment
 * assumption (Binance stamps both the kline openTime and the futures-data timestamp
 * at the start of the period), and safe edge cases.
 */
class DivergenceAlignmentTest {

    private val P = 3_600_000L // 1h period (ms)

    @Test
    fun coBucketAligns1To1() {
        val times = (0L until 30L).map { it * P }
        val prices = times.indices.map { 100.0 + it }
        val lsVals = times.indices.map { 1.5 + it * 0.01 }
        val r = DivergenceAlignment.align(times, prices, times, lsVals, P)
        assertEquals(30, r.price.size)
        assertEquals(30, r.matched)
        assertEquals(prices, r.price)
        assertEquals(lsVals, r.whale)
    }

    @Test
    fun forwardFillsMissingBuckets() {
        val times = (0L until 10L).map { it * P }
        val prices = times.indices.map { 100.0 + it }
        // L/S buckets 3,4,5 are missing
        val lsKept = listOf(0, 1, 2, 6, 7, 8, 9)
        val lsTimes = lsKept.map { it * P }
        val lsVals = lsKept.map { 2.0 + it * 0.1 }
        val r = DivergenceAlignment.align(times, prices, lsTimes, lsVals, P)
        assertEquals(10, r.price.size)  // all candles kept (L/S starts at bucket 0)
        assertEquals(7, r.matched)      // 7 real matches
        val b2 = 2.0 + 2 * 0.1          // bucket-2 value should carry forward
        assertEquals(b2, r.whale[3], 1e-9)
        assertEquals(b2, r.whale[5], 1e-9)
    }

    @Test
    fun skipsCandlesBeforeLsStarts() {
        val times = (0L until 10L).map { it * P }
        val prices = times.indices.map { 100.0 + it }
        val lsKept = (4..9).toList()     // L/S only from bucket 4 onward
        val lsTimes = lsKept.map { it * P }
        val lsVals = lsKept.map { 1.0 }
        val r = DivergenceAlignment.align(times, prices, lsTimes, lsVals, P)
        assertEquals(6, r.price.size)    // candles 0-3 were skipped
        assertEquals(6, r.matched)
        assertEquals(prices.subList(4, 10), r.price)
    }

    @Test
    fun periodStartAssumptionPinned() {
        // Binance stamps at the period START → falls into the same bucket as the kline openTime.
        // If it were period-END (shifted by +P), the buckets would shift by 1 and matching would drop.
        val times = (0L until 30L).map { it * P }
        val prices = times.indices.map { 100.0 + it }
        val lsVals = times.indices.map { 1.5 }
        val good = DivergenceAlignment.align(times, prices, times, lsVals, P)
        val shifted = DivergenceAlignment.align(times, prices, times.map { it + P }, lsVals, P)
        assertEquals(30, good.matched, "period-START aligned → full match")
        assertTrue(shifted.matched < good.matched, "a period-END shift reduces matching")
    }

    @Test
    fun zeroPeriodAndEmptyInputsAreSafe() {
        assertEquals(0, DivergenceAlignment.align(listOf(0L), listOf(1.0), listOf(0L), listOf(1.0), 0L).matched)
        assertTrue(DivergenceAlignment.align(emptyList(), emptyList(), emptyList(), emptyList(), P).price.isEmpty())
        // empty L/S → no candle matches, all are skipped
        val times = (0L until 5L).map { it * P }
        val r = DivergenceAlignment.align(times, times.map { 1.0 }, emptyList(), emptyList(), P)
        assertEquals(0, r.price.size)
        assertEquals(0, r.matched)
    }

    @Test
    fun trailingLsGapStopsCarryingTheStaleValue() {
        // 200 candles, L/S feed stops after bucket 175 (24 periods before the end).
        val times = (0L until 200L).map { it * P }
        val prices = times.indices.map { 100.0 + it }
        val lsKept = (0..175).toList()
        val lsTimes = lsKept.map { it * P }
        val lsVals = lsKept.map { 1.0 + it * 0.01 }
        val r = DivergenceAlignment.align(times, prices, lsTimes, lsVals, P)
        assertEquals(176, r.matched)
        // Buckets 176..178 are within MAX_FORWARD_FILL_PERIODS (3) of bucket 175; 179+ are dropped.
        assertEquals(179, r.price.size)
        assertEquals(prices[178], r.price.last(), 1e-9)
        assertEquals(lsVals.last(), r.whale.last(), 1e-9)
    }

    @Test
    fun maxForwardFillIsConfigurable() {
        val times = (0L until 10L).map { it * P }
        val prices = times.indices.map { 100.0 + it }
        val lsKept = listOf(0, 1)               // last real bucket = 1
        val lsTimes = lsKept.map { it * P }
        val lsVals = lsKept.map { 2.0 }
        // Buckets 2..4 are within 3 periods of bucket 1 (default); a bound of 1 keeps only bucket 2.
        assertEquals(5, DivergenceAlignment.align(times, prices, lsTimes, lsVals, P).price.size)
        assertEquals(3, DivergenceAlignment.align(times, prices, lsTimes, lsVals, P, maxForwardFillPeriods = 1L).price.size)
    }
}
