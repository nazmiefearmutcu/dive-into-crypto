package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class MfiIndicatorTest {

    @Test
    fun `matches Python reference on BTCUSDT 1h x300 fixture`() {
        val candles = FixtureLoader.loadCandles()
        val cfg = IndicatorConfig(
            mapOf(
                "period" to 14.0,
                "strong_buy" to 20.0,
                "buy" to 30.0,
                "sell" to 70.0,
                "strong_sell" to 80.0,
            )
        )
        val expected = FixtureLoader.expectedFor("mfi")
        val r = MfiIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["mfi"]!!, r.rawValues["mfi"]!!, 0.5)
    }
}
