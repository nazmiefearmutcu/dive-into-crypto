package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class PsarIndicatorTest {

    private val candles = FixtureLoader.loadCandles()

    @Test
    fun `PSAR matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "af_start" to 0.02,
            "af_increment" to 0.02,
            "af_max" to 0.20,
        ))
        val expected = FixtureLoader.expectedFor("psar")
        val r = PsarIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.score, r.score)
        assertEquals(expected.rawValues["psar"]!!, r.rawValues["psar"]!!, 0.5)
        assertEquals(expected.rawValues["distance_pct"]!!, r.rawValues["distance_pct"]!!, 0.5)
    }
}
