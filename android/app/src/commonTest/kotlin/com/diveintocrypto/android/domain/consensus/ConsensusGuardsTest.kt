package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression guards for the consensus parity fixes (desktop engine is the reference). */
class ConsensusGuardsTest {

    private fun r(name: String, signal: Signal, raw: Map<String, Double?> = emptyMap()) =
        IndicatorResult(name = name, signal = signal, reason = "", rawValues = raw)

    /** 3 STRONG_BUY vs 3 STRONG_SELL: minority/active = 0.5, the largest value the ratio can take. */
    private fun evenSplit() = listOf(
        r("macd", Signal.STRONG_BUY),
        r("ichimoku", Signal.STRONG_BUY),
        r("supertrend", Signal.STRONG_BUY),
        r("rsi", Signal.STRONG_SELL),
        r("cci", Signal.STRONG_SELL),
        r("mfi", Signal.STRONG_SELL),
    )

    @Test
    fun `shipped conflict thresholds keep the guard reachable`() {
        assertTrue(ConsensusConfig().conflictRatioThreshold < 0.5)
        assertTrue(RiskConfig().conflictRatioThreshold < 0.5)
    }

    @Test
    fun `even split is forced NEUTRAL, not tradable, and reports high conflict`() {
        val out = ConsensusEngine().evaluate(evenSplit())
        assertEquals(Signal.NEUTRAL, out.finalSignal)
        assertFalse(out.shouldTrade)
        assertTrue(out.riskFactors.any { it.startsWith("High signal conflict") })
    }

    @Test
    fun `regime overlay does not change the canonical verdict`() {
        // ADX=18 used to scale oscillators x1.5 and trend x0.5, flipping this to SELL.
        // Canonical desktop arithmetic: -0.9 / 10.0 = -0.09 -> NEUTRAL.
        val results = listOf(
            r("adx_di", Signal.NEUTRAL, mapOf("adx" to 18.0)),
            r("macd", Signal.BUY),
            r("ema_cross", Signal.BUY),
            r("rsi", Signal.SELL),
            r("stochastic", Signal.SELL),
            r("williams_r", Signal.SELL),
            r("cci", Signal.SELL),
        )
        val out = ConsensusEngine().evaluate(results)
        assertEquals(Signal.NEUTRAL, out.finalSignal)
        assertEquals(-0.09, out.weightedScore, 1e-9)
    }

    @Test
    fun `default weights equal the desktop reference table`() {
        assertEquals(60, DEFAULT_FULL_WEIGHTS.size)
        assertEquals(ALL_INDICATOR_WEIGHTS, DEFAULT_FULL_WEIGHTS)
        for ((name, weight) in DEFAULT_F2_WEIGHTS) {
            assertEquals(ALL_INDICATOR_WEIGHTS.getValue(name), weight, name)
        }
    }

    @Test
    fun `atr squeeze factor needs a real squeeze ratio`() {
        val base = Scorer.compute(
            listOf(r("rsi", Signal.BUY), r("macd", Signal.BUY), r("cci", Signal.BUY), r("mfi", Signal.BUY)),
            emptyMap(),
        )
        val normal = listOf(r("atr_filter", Signal.NEUTRAL, mapOf("volatility" to 0.0, "atr_ratio" to 0.6)))
        val squeeze = listOf(r("atr_filter", Signal.NEUTRAL, mapOf("volatility" to 0.0, "atr_ratio" to 0.3)))
        assertFalse(RiskAssessor.assess(normal, base, RiskConfig()).riskFactors.any { it.contains("squeeze") })
        assertTrue(RiskAssessor.assess(squeeze, base, RiskConfig()).riskFactors.any { it.contains("squeeze") })
    }

    @Test
    fun `english labels keep the historical reason wording`() {
        val line = EnglishConsensusLabels.reasonLine(
            signal = "BUY",
            confidence = 40,
            risk = "LOW",
            buyCount = 2,
            sellCount = 1,
            neutralCount = 2,
            weightedScore = "0.432",
            risks = listOf("a", "b"),
        )
        assertEquals("Signal=BUY Conf=40% Risk=LOW Buy=2/Sell=1/Neut=2 WScore=0.432 Risks: a; b", line)
    }
}
