package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class AtrFilterIndicatorTest {

    private val candles = FixtureLoader.loadCandles()

    @Test
    fun `ATR filter matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "period" to 14.0,
            "high_volatility_multiplier" to 2.0,
        ))
        val expected = FixtureLoader.expectedFor("atr_filter")
        val r = AtrFilterIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.score, r.score)
        assertEquals(expected.rawValues["atr"]!!, r.rawValues["atr"]!!, 0.5)
        assertEquals(expected.rawValues["atr_pct"]!!, r.rawValues["atr_pct"]!!, 0.5)
        assertEquals(expected.rawValues["atr_ratio"]!!, r.rawValues["atr_ratio"]!!, 0.5)
    }
}
