package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class RocIndicatorTest {

    @Test
    fun `matches Python reference on BTCUSDT 1h x300 fixture`() {
        val candles = FixtureLoader.loadCandles()
        val cfg = IndicatorConfig(
            mapOf(
                "period" to 12.0,
                "strong_threshold" to 5.0,
                "weak_threshold" to 1.0,
            )
        )
        val expected = FixtureLoader.expectedFor("roc")
        val r = RocIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["roc"]!!, r.rawValues["roc"]!!, 0.5)
        assertEquals(expected.rawValues["roc_prev"]!!, r.rawValues["roc_prev"]!!, 0.5)
    }
}
