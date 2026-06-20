package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import com.diveintocrypto.android.engine.schema.OptionsChain
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VolSurfaceTest {
    private fun opt(strike: Double, expiry: Long, type: OptType, ts: Long,
                    markIv: Double? = null, up: Double? = 100.0) = OptionsChain(
        venue = Venue.DERIBIT, symbol = "deribit:BTC-x", symbolRaw = "BTC-x",
        exchangeTs = ts, localTs = ts, underlying = "BTC", underlyingPrice = up,
        strike = strike, expiry = expiry, optType = type, markIv = markIv)

    private val E1 = 1_000_000_000_000_000L

    @Test fun surfaceUsesMarkIvAndLatestSnapshot() {
        val chain = listOf(
            opt(100.0, E1, OptType.CALL, ts = 10, markIv = 0.60),
            opt(100.0, E1, OptType.CALL, ts = 20, markIv = 0.65),
            opt(120.0, E1, OptType.PUT,  ts = 15, markIv = 0.70),
        )
        val surf = VolSurface.ivSurface(chain, atNs = 100)
        assertEquals(2, surf.size)
        val call = surf.first { it.strike == 100.0 }
        assertEquals(0.65, call.iv!!, 1e-12)
        assertEquals("mark_iv", call.source)
        assertEquals(1.0, call.moneyness, 1e-12)
    }

    @Test fun surfaceSnapshotExcludesFutureRows() {
        val chain = listOf(opt(100.0, E1, OptType.CALL, ts = 200, markIv = 0.6))
        assertTrue(VolSurface.ivSurface(chain, atNs = 100).isEmpty())
    }

    @Test fun skewComputesDeltaAndRrBf() {
        val chain = listOf(
            opt(90.0,  E1, OptType.PUT,  ts = 10, markIv = 0.70),
            opt(100.0, E1, OptType.CALL, ts = 10, markIv = 0.60),
            opt(110.0, E1, OptType.CALL, ts = 10, markIv = 0.65),
        )
        val skew = VolSurface.volSkew(chain, expiryNs = E1, atNs = 100L)
        assertEquals(3, skew.size)
        assertTrue(skew.all { it.delta != null })
        val (rr, bf) = VolSurface.riskReversalButterfly(skew, targetDelta = 0.25)
        assertNotNull(rr); assertNotNull(bf)
    }

    @Test fun termStructureAtmPerExpiry() {
        val E2 = E1 * 2
        val chain = listOf(
            opt(100.0, E1, OptType.CALL, ts = 10, markIv = 0.60),
            opt(105.0, E1, OptType.CALL, ts = 10, markIv = 0.62),
            opt(100.0, E2, OptType.CALL, ts = 10, markIv = 0.55),
        )
        val ts = VolSurface.termStructure(chain, atNs = 100)
        assertEquals(listOf(E1, E2), ts.map { it.expiry })
        assertEquals(100.0, ts[0].atmStrike, 1e-12)
        assertEquals(0.60, ts[0].atmIv!!, 1e-12)
    }
}
