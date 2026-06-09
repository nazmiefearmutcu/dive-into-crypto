package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlackScholesTest {
    @Test fun normCdfParity() {
        assertEquals(0.5, BlackScholes.normCdf(0.0), 1e-12)
        assertEquals(0.8413447460685429, BlackScholes.normCdf(1.0), 1e-6)
        assertEquals(0.3989422804014327, BlackScholes.normPdf(0.0), 1e-12)
    }

    @Test fun priceParityAtmCallPut() {
        val c = BlackScholes.price(100.0, 100.0, 0.5, 0.6, OptType.CALL)
        val p = BlackScholes.price(100.0, 100.0, 0.5, 0.6, OptType.PUT)
        assertEquals(16.799597142736353, c, 1e-3)
        assertEquals(16.799597142736353, p, 1e-3)
    }

    @Test fun greeksParity() {
        val g = BlackScholes.greeks(100.0, 100.0, 0.5, 0.6, OptType.CALL)
        assertEquals(0.5839979857136818, g.delta, 1e-5)
        assertEquals(0.009193951055423533, g.gamma, 1e-7)
        assertEquals(27.5818531662706, g.vega, 1e-3)
        assertEquals(-16.54911189976236, g.theta, 1e-3)
        assertEquals(-8.399798571368176, g.rho, 1e-3)
        val gp = BlackScholes.greeks(100.0, 100.0, 0.5, 0.6, OptType.PUT)
        assertEquals(-0.4160020142863183, gp.delta, 1e-5)
    }

    @Test fun impliedVolRecovers() {
        val price = 16.799597142736353
        val iv = BlackScholes.impliedVol(price, 100.0, 100.0, 0.5, OptType.CALL)
        assertNotNull(iv)
        assertEquals(0.6, iv!!, 1e-4)
        val p2 = BlackScholes.price(110.0, 100.0, 0.25, 0.8, OptType.CALL)
        assertEquals(22.108139117843983, p2, 1e-3)
        val iv2 = BlackScholes.impliedVol(p2, 110.0, 100.0, 0.25, OptType.CALL)
        assertNotNull(iv2)
        assertEquals(0.8, iv2!!, 1e-4)
    }

    @Test fun expiredAndBounds() {
        assertEquals(10.0, BlackScholes.price(100.0, 90.0, -1.0, 0.5, OptType.CALL), 1e-12)
        val g = BlackScholes.greeks(100.0, 90.0, -1.0, 0.5, OptType.CALL)
        assertEquals(0.0, g.delta); assertEquals(0.0, g.gamma); assertEquals(0.0, g.vega)
        assertNull(BlackScholes.impliedVol(5.0, 100.0, 90.0, -1.0, OptType.CALL))
        assertNull(BlackScholes.impliedVol(0.0001, 100.0, 90.0, 0.5, OptType.CALL))
    }
}
