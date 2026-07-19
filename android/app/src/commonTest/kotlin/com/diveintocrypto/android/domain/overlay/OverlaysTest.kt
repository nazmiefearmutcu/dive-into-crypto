package com.diveintocrypto.android.domain.overlay

import com.diveintocrypto.android.domain.consensus.Regime
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kotlin mirror of the Python overlay tests (test_strategies.py + test_microstructure.py). */
class OverlaysTest {

    private fun res(name: String, signal: Signal, raw: Map<String, Double?> = emptyMap()) =
        IndicatorResult(name = name, signal = signal, reason = "", rawValues = raw)

    // ── Regime ──────────────────────────────────────────────────────────────
    @Test fun regimeTrendDetected() {
        val results = listOf(
            res("adx_di", Signal.BUY, mapOf("adx" to 32.0)),
            res("choppiness", Signal.BUY, mapOf("chop" to 30.0)),
        )
        assertEquals("TREND", Regime.detect(results).regime)
    }

    @Test fun regimeRangeDetected() {
        val results = listOf(
            res("adx_di", Signal.NEUTRAL, mapOf("adx" to 15.0)),
            res("choppiness", Signal.NEUTRAL, mapOf("chop" to 70.0)),
        )
        assertEquals("RANGE", Regime.detect(results).regime)
    }

    @Test fun regimeMixedWhenNoData() {
        assertEquals("MIXED", Regime.detect(listOf(res("rsi", Signal.BUY))).regime)
    }

    @Test fun adaptiveWeightsBoostAndDamp() {
        val base = mapOf("macd" to 2.0, "rsi" to 1.5, "atr_filter" to 0.0)
        val w = Regime.adaptiveWeights(base, "TREND")
        assertTrue(w.getValue("macd") > 2.0)        // trend family boosted
        assertTrue(w.getValue("rsi") < 1.5)         // range family damped
        assertEquals(0.0, w.getValue("atr_filter")) // pure filter untouched
    }

    @Test fun regimeEvaluateShape() {
        val results = listOf(
            res("adx_di", Signal.BUY, mapOf("adx" to 32.0)),
            res("macd", Signal.BUY), res("rsi", Signal.SELL),
        )
        val out = Regime.evaluate(results, mapOf("macd" to 2.0, "rsi" to 1.5))
        assertEquals("TREND", out.regime)
    }

    // ── MTF confluence ──────────────────────────────────────────────────────
    @Test fun mtfBullGate() {
        val m = listOf("1h", "4h", "1d").map { MtfConfluence.TfVerdict(it, "BUY", 80) }
        val c = MtfConfluence.confluence(m)
        assertEquals(1, c.direction)
        assertTrue(c.gate)
        assertTrue(c.score > 0)
    }

    @Test fun mtfSplitNoGate() {
        val m = listOf(
            MtfConfluence.TfVerdict("1h", "BUY", 70),
            MtfConfluence.TfVerdict("4h", "SELL", 70),
            MtfConfluence.TfVerdict("1d", "NEUTRAL", 0),
        )
        assertFalse(MtfConfluence.confluence(m).gate)
    }

    @Test fun mtfEmptyIsNeutral() {
        val c = MtfConfluence.confluence(emptyList())
        assertEquals(0, c.direction)
        assertFalse(c.gate)
        assertEquals(0.0, c.score)
    }

    // ── Microstructure ──────────────────────────────────────────────────────
    private fun bullishSeries() = Microstructure.Series(
        price = List(48) { 100.0 + it * 0.5 },
        oi = List(48) { 1000.0 + it * 15 },
        taker = List(48) { 1.15 + (it % 3) * 0.01 },
        funding = List(48) { -0.0002 - (it % 4) * 1e-5 },
        glob = List(48) { 1.0 + (it % 5) * 0.01 },
        pos = List(48) { 1.6 + (it % 3) * 0.02 },
    )

    private fun bearishSeries() = Microstructure.Series(
        price = List(48) { 100.0 + it * 0.5 },
        oi = List(48) { 1800.0 - it * 15 },
        taker = List(48) { 0.85 },
        funding = List(47) { 0.0001 } + listOf(0.0025),
        glob = List(47) { 1.2 } + listOf(3.4),
        pos = List(48) { 1.0 },
    )

    @Test fun microBullishBundlePositive() {
        val out = Microstructure.evaluate(bullishSeries())
        assertTrue(out.label == "BUY" || out.label == "STRONG_BUY")
        assertEquals(1, out.direction)
        assertTrue(out.score > 0)
        assertTrue(out.active >= 4)
    }

    @Test fun microBearishBundleNegative() {
        val out = Microstructure.evaluate(bearishSeries())
        assertEquals(-1, out.direction)
        assertTrue(out.score < 0)
        assertTrue(out.label == "SELL" || out.label == "STRONG_SELL")
    }

    @Test fun microDisabledShortCircuits() {
        val out = Microstructure.evaluate(bullishSeries(), enabled = false)
        assertEquals("OFF", out.label)
        assertEquals(0, out.active)
        assertEquals(0.0, out.score)
    }

    @Test fun microEmptySeriesNeutralNotCrash() {
        val out = Microstructure.evaluate(Microstructure.Series())
        assertEquals(0, out.direction)
        assertEquals(0, out.active)
        assertEquals(0.0, out.score)
    }

    @Test fun microPartialSeriesUsesAvailable() {
        val out = Microstructure.evaluate(
            Microstructure.Series(funding = List(20) { 0.0001 } + listOf(0.003))
        )
        assertEquals(1, out.active)
        assertTrue(out.score <= 0)
    }

    @Test fun microScoreBounded() {
        val out = Microstructure.evaluate(bullishSeries())
        assertTrue(out.score in -100.0..100.0)
        out.signals.forEach { assertTrue(it.score in -1.0..1.0) }
    }
}
