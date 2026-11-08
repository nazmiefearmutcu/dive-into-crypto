package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.OptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeribitSymbolsTest {
    @Test fun parsesCallSymbol() {
        val p = DeribitSymbols.parseOptionSymbol("BTC-27JUN25-100000-C")!!
        assertEquals("BTC", p.underlying)
        assertEquals(100000.0, p.strike)
        assertEquals(OptType.CALL, p.optType)
        assertEquals(1751011200L * 1_000_000_000L, p.expiryNs) // 2025-06-27 08:00 UTC (Deribit expiry hour)
    }

    @Test fun parsesPutSingleDigitDay() {
        val p = DeribitSymbols.parseOptionSymbol("ETH-8JUN26-3000-P")!!
        assertEquals("ETH", p.underlying)
        assertEquals(3000.0, p.strike)
        assertEquals(OptType.PUT, p.optType)
        assertEquals(1780905600L * 1_000_000_000L, p.expiryNs) // 2026-06-08 08:00 UTC (Deribit expiry hour)
    }

    @Test fun optionIsStillAliveTwoHoursBeforeSettlement() {
        // 2025-06-27 06:00 UTC (1751004000) is two hours before the 08:00 UTC expiry, so it must be
        // strictly before expiryNs. A midnight expiry would make it negative time-to-expiry.
        val p = DeribitSymbols.parseOptionSymbol("BTC-27JUN25-100000-C")!!
        assertTrue(p.expiryNs > 1751004000L * 1_000_000_000L)
    }

    @Test fun returnsNullForNonOption() {
        assertNull(DeribitSymbols.parseOptionSymbol("BTC-PERPETUAL"))
        assertNull(DeribitSymbols.parseOptionSymbol("BTC-27JUN25"))
    }
}
