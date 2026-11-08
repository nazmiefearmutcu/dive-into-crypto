package com.diveintocrypto.android.ui.performance

import com.diveintocrypto.android.data.binance.UpstreamHttpException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Failure classification drives the localized message and whether a retry is offered. */
class MarketDataFailureTest {

    @Test
    fun typedRegionBlockIsRegionBlocked() {
        val failure = UpstreamHttpException("fapi/v1/ticker/24hr", 451).toMarketDataFailure()
        assertEquals(MarketDataFailure.RegionBlocked, failure)
    }

    @Test
    fun rateLimitIsUpstreamWithStatus() {
        val failure = UpstreamHttpException("fapi/v1/klines", 429).toMarketDataFailure()
        assertEquals(MarketDataFailure.Upstream(429), failure)
    }

    @Test
    fun plainMessageWithHttp451IsStillRegionBlocked() {
        // Clients that still throw a plain IllegalStateException keep the regional message.
        val failure = IllegalStateException("ticker/24hr HTTP 451").toMarketDataFailure()
        assertEquals(MarketDataFailure.RegionBlocked, failure)
    }

    @Test
    fun regionBlockIsFoundInTheCauseChain() {
        val failure = IllegalStateException("wrapped", UpstreamHttpException("klines", 451)).toMarketDataFailure()
        assertEquals(MarketDataFailure.RegionBlocked, failure)
    }

    @Test
    fun unknownFailureIsNetwork() {
        assertEquals(MarketDataFailure.Network, IllegalStateException("boom").toMarketDataFailure())
    }

    @Test
    fun http403IsForbiddenNotARegionBlock() {
        // A WAF / rate-limit 403 must not show the region-blocked state: it is a refusal with Retry.
        assertEquals(MarketDataFailure.Forbidden, UpstreamHttpException("fapi/v1/klines", 403).toMarketDataFailure())
        assertEquals(
            MarketDataFailure.Forbidden,
            IllegalStateException("wrapped", UpstreamHttpException("klines", 403)).toMarketDataFailure(),
        )
        assertEquals(MarketDataFailure.Forbidden, IllegalStateException("ticker/24hr HTTP 403").toMarketDataFailure())
    }
}
