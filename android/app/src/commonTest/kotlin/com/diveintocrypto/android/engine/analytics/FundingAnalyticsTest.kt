package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.Funding
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FundingAnalyticsTest {
    private fun f(ts: Long, rate: Double, ih: Int = 8) = Funding(
        venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL", symbolRaw = "BTC-PERPETUAL",
        exchangeTs = ts, localTs = ts, fundingRate = rate, fundingTs = ts, intervalHours = ih)

    @Test fun ppyAndApr() {
        assertEquals(1095.0, FundingAnalytics.periodsPerYear(8), 1e-12)
        assertEquals(0.1095, FundingAnalytics.aprFromRate(0.0001, 8), 1e-12)
    }

    @Test fun fundingAprRowsSortedWithCumulative() {
        val rows = FundingAnalytics.fundingApr(listOf(f(30, 0.0002), f(10, 0.0001), f(20, -0.0001)))
        assertEquals(listOf(10L, 20L, 30L), rows.map { it.fundingTs })
        assertEquals(0.0001 * 1095.0, rows[0].apr, 1e-12)
        assertEquals(0.0001, rows[0].cumulativeFunding, 1e-12)
        assertEquals(0.0001 + -0.0001, rows[1].cumulativeFunding, 1e-12)
        assertEquals(0.0001 + -0.0001 + 0.0002, rows[2].cumulativeFunding, 1e-12)
    }

    @Test fun summary() {
        val s = FundingAnalytics.fundingSummary(listOf(f(10, 0.0001), f(20, 0.0003)))
        assertEquals(2, s!!.nEvents)
        assertEquals(0.0002, s.meanRate, 1e-12)
        assertEquals(0.0004, s.totalFunding, 1e-12)
    }

    @Test fun emptyReturnsNullSummary() {
        assertTrue(FundingAnalytics.fundingApr(emptyList()).isEmpty())
        assertEquals(null, FundingAnalytics.fundingSummary(emptyList()))
    }
}
