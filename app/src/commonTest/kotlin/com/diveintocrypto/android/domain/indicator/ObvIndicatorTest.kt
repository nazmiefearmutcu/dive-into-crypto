package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class ObvIndicatorTest {

    private val candles = FixtureLoader.loadCandles()

    @Test
    fun `OBV matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "sma_period" to 20.0,
            "divergence_lookback" to 10.0,
        ))
        val expected = FixtureLoader.expectedFor("obv")
        val r = ObvIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.score, r.score)
        assertEquals(expected.rawValues["obv"]!!, r.rawValues["obv"]!!, 1.0)
        assertEquals(expected.rawValues["obv_sma"]!!, r.rawValues["obv_sma"]!!, 1.0)
        assertEquals(expected.rawValues["price_change_pct"]!!, r.rawValues["price_change_pct"]!!, 0.5)
    }
}
