package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class WilliamsRIndicatorTest {

    @Test
    fun `matches Python reference on BTCUSDT 1h x300 fixture`() {
        val candles = FixtureLoader.loadCandles()
        val cfg = IndicatorConfig(
            mapOf(
                "period" to 14.0,
                "oversold" to -80.0,
                "overbought" to -20.0,
            )
        )
        val expected = FixtureLoader.expectedFor("williams_r")
        val r = WilliamsRIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["williams_r"]!!, r.rawValues["williams_r"]!!, 0.5)
    }
}
