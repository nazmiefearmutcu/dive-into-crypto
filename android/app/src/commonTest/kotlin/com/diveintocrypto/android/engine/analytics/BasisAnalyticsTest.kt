package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisAnalyticsTest {
    private fun trade(ts: Long, px: Double, venue: Venue, raw: String) = Trade(
        venue = venue, symbol = "${venue.wire}:$raw", symbolRaw = raw,
        exchangeTs = ts, localTs = ts, price = px, amount = 1.0, side = Side.BUY)

    @Test fun spotFutureBasisAsofPriorMatch() {
        val futures = listOf(trade(100, 101.0, Venue.DERIBIT, "BTC-FUT"),
                             trade(200, 103.0, Venue.DERIBIT, "BTC-FUT"))
        val spot = listOf(trade(50, 100.0, Venue.BINANCE_SPOT, "BTCUSDT"),
                          trade(150, 102.0, Venue.BINANCE_SPOT, "BTCUSDT"))
        val rows = BasisAnalytics.spotFutureBasis(futures, spot)
        assertEquals(2, rows.size)
        assertEquals(1.0, rows[0].basis, 1e-12)
        assertEquals(0.01, rows[0].basisPct, 1e-12)
        assertEquals(1.0, rows[1].basis, 1e-12)
        assertEquals(1.0 / 102.0, rows[1].basisPct, 1e-12)
    }

    @Test fun spotFutureBasisAnnualized() {
        val futures = listOf(trade(0, 101.0, Venue.DERIBIT, "BTC-FUT"))
        val spot = listOf(trade(0, 100.0, Venue.BINANCE_SPOT, "BTCUSDT"))
        val expiry = (86_400L * 1_000_000_000L) * 365
        val rows = BasisAnalytics.spotFutureBasis(futures, spot, expiryNs = expiry)
        assertEquals(0.01, rows[0].annualizedPct!!, 1e-9)
    }

    @Test fun perpBasisMarkVsIndex() {
        val t = DerivativeTicker(venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL",
            symbolRaw = "BTC-PERPETUAL", exchangeTs = 10, localTs = 10,
            markPrice = 100.5, indexPrice = 100.0)
        val rows = BasisAnalytics.perpBasis(listOf(t))
        assertEquals(1, rows.size)
        assertEquals(0.5, rows[0].basis, 1e-12)
        assertEquals(0.005, rows[0].basisPct, 1e-12)
    }

    @Test fun perpBasisDropsNullOrZero() {
        val bad = DerivativeTicker(venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL",
            symbolRaw = "BTC-PERPETUAL", exchangeTs = 10, localTs = 10,
            markPrice = null, indexPrice = 100.0)
        assertEquals(0, BasisAnalytics.perpBasis(listOf(bad)).size)
    }
}
