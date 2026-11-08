package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.Funding
import com.diveintocrypto.android.engine.schema.OptType
import com.diveintocrypto.android.engine.schema.OptionsChain
import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the engine analytics fixes (funding, basis, weekly bars, expired options). */
class AnalyticsHonestyTest {

    private fun funding(ts: Long, rate: Double) = Funding(
        venue = Venue.BINANCE_USDM, symbol = "binance-usdm:BTCUSDT", symbolRaw = "BTCUSDT",
        exchangeTs = ts, localTs = ts, fundingRate = rate, fundingTs = ts, intervalHours = 8,
    )

    @Test
    fun `a settlement re-emitted after a reconnect is counted once`() {
        val rows = FundingAnalytics.fundingApr(listOf(funding(1_000L, 0.0001), funding(1_000L, 0.0001), funding(2_000L, 0.0002)))
        assertEquals(2, rows.size)
        assertEquals(0.0003, rows.last().cumulativeFunding, 1e-12)
    }

    @Test
    fun `seconds to funding is null once the settlement is in the past`() {
        val lens = FundingAnalytics.fundingLens(0.0001, 0.0002, nextFundingMs = 1_000L, nowMs = 5_000L)!!
        assertNull(lens.secondsToFunding)
    }

    @Test
    fun `funding APR uses the symbol's own settlement interval`() {
        val eightHour = FundingAnalytics.fundingLens(0.0001, 0.0001, nextFundingMs = 0, nowMs = 0, intervalHours = 8)!!
        val fourHour = FundingAnalytics.fundingLens(0.0001, 0.0001, nextFundingMs = 0, nowMs = 0, intervalHours = 4)!!
        assertEquals(eightHour.aprPct * 2, fourHour.aprPct, 1e-9)
    }

    @Test
    fun `basis block annualises with the symbol's own settlement interval`() {
        val eightHour = BasisAnalytics.basisBlock(101.0, 100.0, 0.0001, intervalHours = 8)!!
        val fourHour = BasisAnalytics.basisBlock(101.0, 100.0, 0.0001, intervalHours = 4)!!
        assertEquals(eightHour.annFundingPct * 2, fourHour.annFundingPct, 1e-9)
    }

    private val week = 7L * 86_400L * 1_000_000_000L
    private val day = 86_400L * 1_000_000_000L
    /** 2024-01-01 00:00 UTC, a Monday. */
    private val mondayNs = 1_704_067_200_000L * 1_000_000L

    private fun trade(ns: Long, price: Double) = Trade(
        venue = Venue.BINANCE_USDM, symbol = "binance-usdm:BTCUSDT", symbolRaw = "BTCUSDT",
        exchangeTs = ns, localTs = ns, price = price, amount = 1.0, side = Side.BUY,
    )

    @Test
    fun `weekly bars start on Monday`() {
        val wednesdayNoon = mondayNs + 2 * day + day / 2
        val bars = Resample.resampleOhlcv(listOf(trade(wednesdayNoon, 100.0)), week, "1w")
        assertEquals(mondayNs, bars.single().exchangeTs)
    }

    @Test
    fun `a Sunday trade belongs to the previous Monday's weekly bar`() {
        val sundayLate = mondayNs - 1
        val bars = Resample.resampleOhlcv(listOf(trade(sundayLate, 100.0)), week, "1w")
        assertEquals(mondayNs - week, bars.single().exchangeTs)
    }

    @Test
    fun `daily bars stay aligned to the epoch`() {
        val bars = Resample.resampleOhlcv(listOf(trade(day + 5, 1.0)), day, "1d")
        assertEquals(day, bars.single().exchangeTs)
    }

    private fun option(strike: Double, expiry: Long, ts: Long, markIv: Double) = OptionsChain(
        venue = Venue.DERIBIT, symbol = "deribit:BTC-x", symbolRaw = "BTC-x",
        exchangeTs = ts, localTs = ts, underlying = "BTC", underlyingPrice = 100.0,
        strike = strike, expiry = expiry, optType = OptType.CALL, markIv = markIv,
    )

    @Test
    fun `an expired expiry is not part of the surface or the term structure`() {
        val atNs = 1_000L
        val expired = 500L   // expiry already passed at atNs
        val live = 5_000L
        val chain = listOf(
            option(100.0, expired, ts = 10L, markIv = 0.8),
            option(100.0, live, ts = 10L, markIv = 0.6),
        )
        val surface = VolSurface.ivSurface(chain, atNs)
        assertTrue(surface.all { it.expiry == live }, "expired rows must not reach the surface")
        val term = VolSurface.termStructure(chain, atNs)
        assertTrue(term.all { it.expiry == live && it.daysToExpiry > 0.0 }, "no negative days to expiry: $term")
    }
}
