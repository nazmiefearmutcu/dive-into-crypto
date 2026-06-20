package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals

class BinanceCanonicalTest {
    @Test fun candleToOhlcvUsesUsdmVenueAndNsTimestamps() {
        val c = Candle(openTime = 1_700_000_000_000L, open = 1.0, high = 2.0, low = 0.5,
            close = 1.5, volume = 10.0, closeTime = 1_700_000_059_999L)
        val o = c.toOhlcv("BTCUSDT", interval = "1m", localTsMs = 1_700_000_060_000L)
        assertEquals(Venue.BINANCE_USDM, o.venue)
        assertEquals("binance-usdm:BTCUSDT", o.symbol)
        assertEquals("1m", o.interval)
        assertEquals(1_700_000_000_000L * 1_000_000, o.exchangeTs)   // ms→ns
        assertEquals(1_700_000_060_000L * 1_000_000, o.localTs)
        assertEquals(1.5, o.close)
    }

    @Test fun openInterestPointToCanonical() {
        val p = OpenInterestPoint(timestamp = 1_700_000_000_000L, sumOpenInterest = 1234.0, sumOpenInterestValue = 9.9e7)
        val oi = p.toOpenInterest("BTCUSDT", localTsMs = 1_700_000_001_000L)
        assertEquals(1234.0, oi.openInterest)
        assertEquals(9.9e7, oi.openInterestValue)
        assertEquals(1_700_000_000_000L * 1_000_000, oi.exchangeTs)
    }

    @Test fun fundingPointToCanonical() {
        val p = FundingRatePoint(timestamp = 1_700_000_000_000L, fundingRate = -0.00012)
        val f = p.toFunding("BTCUSDT", localTsMs = 1_700_000_001_000L)
        assertEquals(-0.00012, f.fundingRate)
        assertEquals(8, f.intervalHours)
    }
}
