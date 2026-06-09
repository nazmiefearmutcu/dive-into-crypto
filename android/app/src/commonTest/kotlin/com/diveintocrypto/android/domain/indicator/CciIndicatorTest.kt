package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class CciIndicatorTest {

    @Test
    fun `matches Python reference on BTCUSDT 1h x300 fixture`() {
        val candles = FixtureLoader.loadCandles()
        val cfg = IndicatorConfig(
            mapOf(
                "period" to 20.0,
                "buy" to -100.0,
                "strong_buy" to -200.0,
                "sell" to 100.0,
                "strong_sell" to 200.0,
            )
        )
        val expected = FixtureLoader.expectedFor("cci")
        val r = CciIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["cci"]!!, r.rawValues["cci"]!!, 0.5)
    }
}
