package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class SmaCrossIndicatorTest {

    private val candles = FixtureLoader.loadCandles()

    @Test
    fun `SMA cross matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "short_period" to 10.0,
            "long_period" to 50.0,
            "strong_divergence_pct" to 0.02,
        ))
        val expected = FixtureLoader.expectedFor("sma_cross")
        val r = SmaCrossIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.score, r.score)
        assertEquals(expected.rawValues["sma_short"]!!, r.rawValues["sma_short"]!!, 0.5)
        assertEquals(expected.rawValues["sma_long"]!!, r.rawValues["sma_long"]!!, 0.5)
        assertEquals(expected.rawValues["divergence_pct"]!!, r.rawValues["divergence_pct"]!!, 0.001)
    }
}
