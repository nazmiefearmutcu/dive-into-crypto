package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class AdxDiIndicatorTest {

    @Test
    fun `matches Python reference on BTCUSDT 1h x300 fixture`() {
        val candles = FixtureLoader.loadCandles()
        val cfg = IndicatorConfig(
            mapOf(
                "period" to 14.0,
                "strong_trend" to 25.0,
                "weak_trend" to 15.0,
            )
        )
        val expected = FixtureLoader.expectedFor("adx_di")
        val r = AdxDiIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["adx"]!!, r.rawValues["adx"]!!, 0.5)
        assertEquals(expected.rawValues["plus_di"]!!, r.rawValues["plus_di"]!!, 0.5)
        assertEquals(expected.rawValues["minus_di"]!!, r.rawValues["minus_di"]!!, 0.5)
    }
}
