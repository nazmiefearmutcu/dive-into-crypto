package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.test.Test
import kotlin.test.assertEquals

class ScorerTest {

    private fun r(name: String, signal: Signal) = IndicatorResult(name = name, signal = signal, reason = "")

    @Test
    fun `total signals counts zero-weight indicators like desktop len(results)`() {
        val results = listOf(
            r("rsi", Signal.BUY),
            r("macd", Signal.SELL),
            r("atr_filter", Signal.NEUTRAL),
        )
        val s = Scorer.compute(results, mapOf("rsi" to 1.0, "macd" to 1.0, "atr_filter" to 0.0))
        assertEquals(3, s.totalSignals)
        assertEquals(2, s.activeSignals)
    }

    @Test
    fun `weighted score is rounded to 4 decimals before thresholds`() {
        // (1 * 3.0 + 0 * 6.0) / 9.0 = 0.33333... -> 0.3333, as scorer.py round(x, 4)
        val results = listOf(r("rsi", Signal.BUY), r("macd", Signal.NEUTRAL))
        val s = Scorer.compute(results, mapOf("rsi" to 3.0, "macd" to 6.0))
        assertEquals(0.3333, s.weightedScore, 1e-12)
    }
}
