package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals

class ResampleTest {
    private fun tr(ts: Long, px: Double, amt: Double, side: Side = Side.BUY) = Trade(
        venue = Venue.BINANCE_USDM, symbol = "binance-usdm:BTCUSDT", symbolRaw = "BTCUSDT",
        exchangeTs = ts, localTs = ts, price = px, amount = amt, side = side)

    @Test fun ohlcvBucketsByInterval() {
        val interval = 1_000L
        val trades = listOf(
            tr(0, 10.0, 1.0, Side.BUY), tr(500, 12.0, 2.0, Side.SELL), tr(999, 11.0, 1.0, Side.BUY),
            tr(1000, 20.0, 1.0, Side.BUY),
        )
        val bars = Resample.resampleOhlcv(trades, interval)
        assertEquals(2, bars.size)
        val b0 = bars[0]
        assertEquals(0L, b0.exchangeTs)
        assertEquals(10.0, b0.open); assertEquals(12.0, b0.high)
        assertEquals(10.0, b0.low); assertEquals(11.0, b0.close)
        assertEquals(4.0, b0.volume, 1e-12)
        assertEquals(2.0, b0.buyVolume, 1e-12); assertEquals(2.0, b0.sellVolume, 1e-12)
        assertEquals(3, b0.numTrades)
    }

    @Test fun metricsVwap() {
        val interval = 1_000L
        val rows = Resample.resampleMetrics(listOf(tr(0, 10.0, 2.0), tr(100, 20.0, 2.0)), interval)
        assertEquals(1, rows.size)
        assertEquals(15.0, rows[0].vwap, 1e-12)
        assertEquals(60.0, rows[0].dollarVolume, 1e-12)
        assertEquals(2, rows[0].tradeCount)
    }
}
