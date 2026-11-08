package com.diveintocrypto.android.ui.common

import com.diveintocrypto.android.data.binance.UpstreamHttpException
import com.diveintocrypto.android.data.binance.isForbidden
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.ui.i18n.EnStrings
import com.diveintocrypto.android.ui.i18n.TrStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Failure mapping for the Scanner and Signals screens: localized, worded text only.
 * No user-facing string may carry an HTTP status or a status-like number.
 */
class ScanFailuresTest {

    private val statusDigits = Regex("""\d{3}""")

    @Test
    fun regionBlockShowsLocalizedBodyWithoutRawStatus() {
        val e = UpstreamHttpException("/fapi/v1/klines", 451)
        assertEquals(TrStrings.errRegionBlockedBody, e.userFacingText(TrStrings))
        assertEquals(EnStrings.errRegionBlockedBody, e.userFacingText(EnStrings))
        for (s in listOf(TrStrings, EnStrings)) {
            val text = e.userFacingText(s)
            assertFalse(text.contains("HTTP"), "region text must not name HTTP: $text")
            assertFalse(statusDigits.containsMatchIn(text), "region text must not quote a code: $text")
        }
    }

    @Test
    fun regionBlockIsFoundThroughCauseChainAndOnlyFor451() {
        val wrapped = IllegalStateException("outer", UpstreamHttpException("/x", 451))
        assertTrue(wrapped.isRegionBlocked())
        assertEquals(EnStrings.errRegionBlockedBody, wrapped.userFacingText(EnStrings))
        assertFalse(UpstreamHttpException("/x", 403).isRegionBlocked())
    }

    @Test
    fun forbiddenIsItsOwnWordedStateNotARegionBlock() {
        val e = UpstreamHttpException("/fapi/v1/klines", 403)
        assertTrue(e.isForbidden())
        assertFalse(e.isRegionBlocked())
        assertFalse(e.isRateLimited())
        assertEquals(TrStrings.errForbiddenBody, e.userFacingText(TrStrings))
        assertEquals(EnStrings.errForbiddenBody, e.userFacingText(EnStrings))
        assertFalse(EnStrings.errForbiddenBody.contains("HTTP"))
        assertFalse(statusDigits.containsMatchIn(EnStrings.errForbiddenBody))
    }

    @Test
    fun rateLimitShowsWordedMessageAndStopsContinuousLoops() {
        val e = UpstreamHttpException("/fapi/v1/ticker/24hr", 429)
        assertTrue(e.isRateLimited())
        assertFalse(e.isRegionBlocked())
        assertEquals(TrStrings.scanRateLimited, e.userFacingText(TrStrings))
        assertEquals(EnStrings.scanRateLimited, e.userFacingText(EnStrings))
        assertTrue(UpstreamHttpException("/x", 418).isRateLimited())
        assertEquals(EnStrings.scanRateLimited, UpstreamHttpException("/x", 418).userFacingText(EnStrings))
        assertFalse(statusDigits.containsMatchIn(EnStrings.scanRateLimited))
    }

    @Test
    fun serverErrorShowsGenericWordedTextWithoutStatus() {
        val e = UpstreamHttpException("/fapi/v1/klines", 503)
        assertEquals(503, e.upstreamStatus())
        assertFalse(e.isRateLimited())
        assertEquals(EnStrings.errUpstreamGeneric, e.userFacingText(EnStrings))
        assertFalse(e.userFacingText(TrStrings).contains("HTTP"))
        assertFalse(statusDigits.containsMatchIn(e.userFacingText(TrStrings)))
    }

    @Test
    fun plainFailureMapsToNetworkTextAndNoStatus() {
        val e = IllegalStateException("ticker/24hr HTTP 500 raw text")
        assertNull(e.upstreamStatus())
        assertFalse(e.isRateLimited())
        assertEquals(TrStrings.errNetwork, e.userFacingText(TrStrings))
    }
}
