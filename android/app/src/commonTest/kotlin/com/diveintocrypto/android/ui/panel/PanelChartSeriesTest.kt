package com.diveintocrypto.android.ui.panel

import com.diveintocrypto.android.data.binance.UpstreamHttpException
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.ui.i18n.EnStrings
import com.diveintocrypto.android.ui.i18n.TrStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chart-series plumbing for PanelUiState: EMA(20)/EMA(50)/Bollinger(20,2) are
 * computed over the SAME cached candles via the indicator engine's Series math —
 * warm-up positions must be honest nulls, never fabricated zeros.
 */
class PanelChartSeriesTest {

    private fun candles(n: Int, start: Double = 100.0): List<Candle> =
        (0 until n).map { i ->
            val close = start + i // monotonically rising
            Candle(
                openTime = i * 3_600_000L,
                open = close - 1,
                high = close + 1,
                low = close - 2,
                close = close,
                volume = 10.0,
                closeTime = (i + 1) * 3_600_000L - 1,
            )
        }

    @Test
    fun `ema20 ema50 and bollinger warm-ups are null and then defined`() {
        val series = PanelViewModel.computeChartSeries(candles(60))

        assertEquals(60, series.ema20.size)
        assertEquals(60, series.ema50.size)
        // EMA(20): first 19 null, index 19 defined.
        for (i in 0 until 19) assertNull(series.ema20[i], "EMA20 warm-up $i must be null")
        assertTrue(series.ema20[19] != null)
        // EMA(50): first 49 null.
        for (i in 0 until 49) assertNull(series.ema50[i], "EMA50 warm-up $i must be null")
        assertTrue(series.ema50[49] != null)
        // Bollinger(20): first 19 null, then both bands defined with upper > lower.
        assertNull(series.bbUpper[0])
        assertNull(series.bbLower[18])
        val u = series.bbUpper[19]!!
        val l = series.bbLower[19]!!
        assertTrue(u > l, "upper band must sit above the lower band")
    }

    @Test
    fun `bollinger bands hug the mean on a flat series`() {
        // Constant closes → zero std → both bands collapse onto the close.
        val n = 40
        val flat = (0 until n).map { i ->
            Candle(
                openTime = i * 3_600_000L,
                open = 100.0,
                high = 100.0,
                low = 100.0,
                close = 100.0,
                volume = 10.0,
                closeTime = (i + 1) * 3_600_000L - 1,
            )
        }
        val series = PanelViewModel.computeChartSeries(flat)
        val last = n - 1
        assertEquals(100.0, series.bbUpper[last]!!, 1e-6)
        assertEquals(100.0, series.bbLower[last]!!, 1e-6)
    }

    @Test
    fun `short series never fabricate indicator values`() {
        val series = PanelViewModel.computeChartSeries(candles(10))
        assertEquals(10, series.ema20.size)
        assertTrue(series.ema20.all { it == null }, "EMA20 undefined with <20 candles")
        assertTrue(series.ema50.all { it == null })
        assertTrue(series.bbUpper.all { it == null })
        assertTrue(series.bbLower.all { it == null })
    }

    @Test
    fun `empty candle list yields empty series`() {
        val series = PanelViewModel.computeChartSeries(emptyList())
        assertTrue(series.candles.isEmpty())
        assertTrue(series.ema20.isEmpty() && series.ema50.isEmpty())
        assertTrue(series.bbUpper.isEmpty() && series.bbLower.isEmpty())
    }

    @Test
    fun `microstructure series are forward-filled onto candle times and stay index-aligned`() {
        val cs = candles(5) // openTime = i * 1h
        val h = 3_600_000L
        val funding = listOf(0L to 0.1, 2 * h to 0.2) // settlement lands on candle 2
        val oi = listOf(0L to 1.0, h to 1.5, 2 * h to 2.0, 3 * h to 2.5, 4 * h to 3.0)
        val series = PanelViewModel.alignMicrostructure(
            cs, oi = oi, pos = oi, taker = oi, glob = oi, funding = funding,
        )
        assertEquals(5, series.price.size)
        assertEquals(5, series.oi.size)
        assertEquals(5, series.funding.size)
        assertEquals(listOf(0.1, 0.1, 0.2, 0.2, 0.2), series.funding)
    }

    @Test
    fun `candles older than the newest first series point are dropped to keep alignment`() {
        val cs = candles(5)
        val h = 3_600_000L
        val oi = listOf(2 * h to 2.0, 3 * h to 2.5, 4 * h to 3.0)
        val series = PanelViewModel.alignMicrostructure(
            cs, oi = oi, pos = emptyList(), taker = emptyList(), glob = emptyList(), funding = emptyList(),
        )
        assertEquals(3, series.price.size)
        assertEquals(listOf(2.0, 2.5, 3.0), series.oi)
        assertTrue(series.funding.isEmpty())
    }

    @Test
    fun `coin search keeps a query typed before the universe loaded`() {
        val all = listOf("BTCUSDT", "SOLUSDT", "ETHUSDT")
        assertEquals(listOf("SOLUSDT"), PanelViewModel.filterSymbols(all, "sol"))
        assertEquals(all, PanelViewModel.filterSymbols(all, ""))
    }

    @Test
    fun `price direction follows the tick and keeps the last direction on equal prices`() {
        assertEquals("UP", PanelViewModel.priceDirection(100.0, 101.0, "NONE"))
        assertEquals("DOWN", PanelViewModel.priceDirection(100.0, 99.0, "UP"))
        assertEquals("UP", PanelViewModel.priceDirection(100.0, 100.0, "UP"))
        assertEquals("NONE", PanelViewModel.priceDirection(null, 100.0, "DOWN"))
    }

    @Test
    fun `load errors map to region, upstream status or network messages`() {
        assertEquals(
            TrStrings.errRegionBlockedBody,
            PanelViewModel.errorText(UpstreamHttpException("ticker/24hr", 451), TrStrings),
        )
        assertEquals(
            EnStrings.errUpstreamGeneric,
            PanelViewModel.errorText(UpstreamHttpException("klines", 500), EnStrings),
        )
        assertEquals(
            EnStrings.errNetwork,
            PanelViewModel.errorText(IllegalStateException("boom"), EnStrings),
        )
    }
}
