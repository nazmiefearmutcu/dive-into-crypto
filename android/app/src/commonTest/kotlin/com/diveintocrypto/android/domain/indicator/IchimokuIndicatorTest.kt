package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.assertEquals
import kotlin.test.Test

class IchimokuIndicatorTest {

    private val candles = FixtureLoader.loadCandles()

    @Test
    fun `Ichimoku matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "tenkan_period" to 9.0,
            "kijun_period" to 26.0,
            "senkou_b_period" to 52.0,
        ))
        val expected = FixtureLoader.expectedFor("ichimoku")
        val r = IchimokuIndicator(cfg).calculate(candles)

        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.score, r.score)
        assertEquals(expected.rawValues["tenkan"]!!, r.rawValues["tenkan"]!!, 0.5)
        assertEquals(expected.rawValues["kijun"]!!, r.rawValues["kijun"]!!, 0.5)
        assertEquals(expected.rawValues["senkou_a"]!!, r.rawValues["senkou_a"]!!, 0.5)
        assertEquals(expected.rawValues["senkou_b"]!!, r.rawValues["senkou_b"]!!, 0.5)
        assertEquals(expected.rawValues["cloud_top"]!!, r.rawValues["cloud_top"]!!, 0.5)
        assertEquals(expected.rawValues["cloud_bottom"]!!, r.rawValues["cloud_bottom"]!!, 0.5)
    }
}
