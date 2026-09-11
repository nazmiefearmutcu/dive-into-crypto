package com.diveintocrypto.android.ui.panel.components

import com.diveintocrypto.android.domain.cvd.CvdBucket
import com.diveintocrypto.android.domain.model.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the Panel candlestick chart's scale helpers (UI lane):
 * price-range union (candles ∪ Bollinger band) with padding, pixel mapping
 * monotonicity/bounds, axis-decimal selection, price-magnitude decimals,
 * volume/delta maxima and axis formatting. No Compose, no clock, no network.
 */
class ChartMathTest {

    private fun candle(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double = 10.0,
    ) = Candle(
        openTime = 0L,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        closeTime = 0L,
    )

    // ── chartPriceRange ──────────────────────────────────────────────

    @Test
    fun `price range spans candle highs and lows padded symmetrically`() {
        val range = ChartMath.chartPriceRange(
            candles = listOf(
                candle(open = 100.0, high = 110.0, low = 95.0, close = 105.0),
                candle(open = 105.0, high = 120.0, low = 90.0, close = 92.0),
            ),
        )
        // 6% pad on both ends of the [90, 120] raw span.
        assertEquals(90.0 - (30.0 * 0.06), range.min, 1e-9)
        assertEquals(120.0 + (30.0 * 0.06), range.max, 1e-9)
    }

    @Test
    fun `price range includes bollinger bands that pierce beyond the candles`() {
        val upper = listOf<Double?>(null, null, 130.0) // warm-up nulls skipped
        val lower = listOf<Double?>(null, null, 85.0)
        val range = ChartMath.chartPriceRange(
            candles = listOf(
                candle(open = 100.0, high = 110.0, low = 95.0, close = 105.0),
                candle(open = 105.0, high = 120.0, low = 90.0, close = 92.0),
                candle(open = 92.0, high = 115.0, low = 91.0, close = 108.0),
            ),
            bbUpper = upper,
            bbLower = lower,
        )
        assertTrue(range.max > 130.0, "max must cover the band ($range)")
        assertTrue(range.min < 85.0, "min must cover the band ($range)")
    }

    @Test
    fun `empty candles degrade to the honest 0 to 1 fallback`() {
        val range = ChartMath.chartPriceRange(emptyList())
        assertEquals(0.0, range.min)
        assertEquals(1.0, range.max)
    }

    @Test
    fun `flat series pads one percent around the single value`() {
        val range = ChartMath.chartPriceRange(
            candles = listOf(candle(open = 100.0, high = 100.0, low = 100.0, close = 100.0)),
        )
        assertEquals(99.0, range.min, 1e-9)
        assertEquals(101.0, range.max, 1e-9)
    }

    // ── scaleY ───────────────────────────────────────────────────────

    @Test
    fun `scaleY maps max to top and min to bottom`() {
        val range = ChartMath.ChartRange(0.0, 100.0)
        assertEquals(10f, ChartMath.scaleY(100.0, range, topPx = 10f, bottomPx = 110f))
        assertEquals(110f, ChartMath.scaleY(0.0, range, topPx = 10f, bottomPx = 110f))
    }

    @Test
    fun `scaleY is monotonic decreasing in pixels as the value increases and clamped`() {
        val range = ChartMath.ChartRange(50.0, 60.0)
        // top = range.max → value ↑ means pixel y ↓ (y=0 at the top of the plot).
        var prev = Float.POSITIVE_INFINITY
        for (v in listOf(50.0, 55.0, 58.0, 60.0)) {
            val y = ChartMath.scaleY(v, range, topPx = 0f, bottomPx = 200f)
            assertTrue(y < prev, "y($v)=$y must be < previous $prev (value ↑ → pixel y ↓)")
            assertTrue(y in 0f..200f)
            prev = y
        }
        // Endpoints: value=max → top edge, value=min → bottom edge.
        assertEquals(0f, ChartMath.scaleY(60.0, range, 0f, 200f))
        assertEquals(200f, ChartMath.scaleY(50.0, range, 0f, 200f))
        // Out-of-domain values clamp, never escape the plot area.
        assertEquals(200f, ChartMath.scaleY(0.0, range, 0f, 200f))
        assertEquals(0f, ChartMath.scaleY(100.0, range, 0f, 200f))
    }

    @Test
    fun `degenerate zero-width range maps to the middle`() {
        val y = ChartMath.scaleY(42.0, ChartMath.ChartRange(10.0, 10.0), topPx = 0f, bottomPx = 100f)
        assertEquals(50f, y)
    }

    // ── axisDecimals / priceDecimalsFor ──────────────────────────────

    @Test
    fun `axis decimals follow the viewport width`() {
        assertEquals(6, ChartMath.axisDecimals(ChartMath.ChartRange(0.0, 0.0005)))
        assertEquals(4, ChartMath.axisDecimals(ChartMath.ChartRange(1.0, 1.05)))
        assertEquals(2, ChartMath.axisDecimals(ChartMath.ChartRange(10.0, 50.0)))
        assertEquals(0, ChartMath.axisDecimals(ChartMath.ChartRange(60000.0, 62500.0)))
        assertEquals(2, ChartMath.axisDecimals(ChartMath.ChartRange(0.0, 0.0))) // degenerate
    }

    @Test
    fun `price decimals follow the magnitude of the value`() {
        assertEquals(6, ChartMath.priceDecimalsFor(0.003))
        assertEquals(4, ChartMath.priceDecimalsFor(0.5))
        assertEquals(3, ChartMath.priceDecimalsFor(5.0))
        assertEquals(2, ChartMath.priceDecimalsFor(65432.10))
        assertEquals(2, ChartMath.priceDecimalsFor(0.0)) // guard
    }

    // ── maxima + axis formatting ─────────────────────────────────────

    @Test
    fun `maxVolume ignores non-finite and picks the largest`() {
        val max = ChartMath.maxVolume(
            listOf(
                candle(open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 12.5),
                candle(open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = Double.NaN),
                candle(open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 40.0),
            ),
        )
        assertEquals(40.0, max)
        assertEquals(0.0, ChartMath.maxVolume(emptyList()))
    }

    @Test
    fun `maxAbsDelta spans positive and negative cvd buckets`() {
        val max = ChartMath.maxAbsDelta(
            listOf(
                CvdBucket(minuteTs = 0L, buyVol = 10.0, sellVol = 2.0, delta = 8.0),
                CvdBucket(minuteTs = 1L, buyVol = 1.0, sellVol = 9.0, delta = -8.0),
                CvdBucket(minuteTs = 2L, buyVol = 1.0, sellVol = 1.0, delta = 0.0),
            ),
        )
        assertEquals(8.0, max)
        assertEquals(0.0, ChartMath.maxAbsDelta(emptyList()))
    }

    @Test
    fun `formatAxisPrice renders the requested decimals`() {
        assertEquals("65432", ChartMath.formatAxisPrice(65432.1, 0))
        assertEquals("1.2346", ChartMath.formatAxisPrice(1.23456, 4))
        assertEquals("0.000123", ChartMath.formatAxisPrice(0.0001234, 6))
    }
}
