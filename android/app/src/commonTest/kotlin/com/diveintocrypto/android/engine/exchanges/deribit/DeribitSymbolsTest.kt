package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.OptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeribitSymbolsTest {
    @Test fun parsesCallSymbol() {
        val p = DeribitSymbols.parseOptionSymbol("BTC-27JUN25-100000-C")!!
        assertEquals("BTC", p.underlying)
        assertEquals(100000.0, p.strike)
        assertEquals(OptType.CALL, p.optType)
        assertEquals(1750982400L * 1_000_000_000L, p.expiryNs) // 2025-06-27 00:00 UTC
    }

    @Test fun parsesPutSingleDigitDay() {
        val p = DeribitSymbols.parseOptionSymbol("ETH-8JUN26-3000-P")!!
        assertEquals("ETH", p.underlying)
        assertEquals(3000.0, p.strike)
        assertEquals(OptType.PUT, p.optType)
        assertEquals(1780876800L * 1_000_000_000L, p.expiryNs) // 2026-06-08 00:00 UTC
    }

    @Test fun returnsNullForNonOption() {
        assertNull(DeribitSymbols.parseOptionSymbol("BTC-PERPETUAL"))
        assertNull(DeribitSymbols.parseOptionSymbol("BTC-27JUN25"))
    }
}
