package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.domain.model.SignalDetail
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class RiskTest {

    private val config = RiskConfig()

    /** Build an [IndicatorResult] for tests. */
    private fun r(
        name: String,
        signal: Signal,
        rawValues: Map<String, Double?> = emptyMap(),
    ) = IndicatorResult(name = name, signal = signal, reason = "", rawValues = rawValues)

    /**
     * Build a [ScoreBreakdown] directly so tests don't depend on Scorer
     * computing exactly what we need for each scenario.
     */
    private fun breakdown(
        weightedScore: Double = 0.0,
        buy: Int = 0,
        sell: Int = 0,
        neutral: Int = 0,
        strongBuy: Int = 0,
        strongSell: Int = 0,
    ) = ScoreBreakdown(
        weightedScore = weightedScore,
        weightedSum = weightedScore,
        totalWeight = 1.0,
        buyCount = buy,
        sellCount = sell,
        neutralCount = neutral,
        strongBuyCount = strongBuy,
        strongSellCount = strongSell,
        activeSignals = buy + sell,
        totalSignals = buy + sell + neutral,
        signalDetails = emptyList<SignalDetail>(),
    )

    @Test
    fun `empty results with a clean breakdown yields LOW and risk_score 0`() {
        // No indicators trigger ATR / ADX paths. To stay at risk_score==0,
        // we feed the breakdown enough active signals and conviction so
        // sections (3), (4), (5) of risk.py also contribute nothing.
        val s = breakdown(weightedScore = 0.9, buy = 4, sell = 0)
        val out = RiskAssessor.assess(results = emptyList(), scoreBreakdown = s, config = config)

        assertEquals(RiskLevel.LOW, out.riskLevel)
        assertEquals(0.0, out.riskScore, 0.0)
        assertTrue(out.riskFactors.isEmpty(), "no risk factors expected")
        assertEquals(1.0, out.details["position_size_modifier"])
        assertEquals("LOW", out.details["risk_level"])
    }

    @Test
    fun `all-strong agreement is LOW risk`() {
        // 5 strong buys, no conflict, strong conviction, lots of active signals.
        val results = listOf(
            r("rsi", Signal.STRONG_BUY),
            r("macd", Signal.STRONG_BUY),
            r("bollinger", Signal.STRONG_BUY),
            r("ema_cross", Signal.STRONG_BUY),
            r("stochastic", Signal.STRONG_BUY),
        )
        val s = breakdown(weightedScore = 1.8, buy = 5, sell = 0, strongBuy = 5)
        val out = RiskAssessor.assess(results, s, config)

        assertEquals(RiskLevel.LOW, out.riskLevel)
        assertEquals(0.0, out.riskScore, 0.0)
        assertEquals(1.0, out.details["position_size_modifier"])
    }

    @Test
    fun `50-50 split triggers high conflict and is HIGH risk`() {
        // 3 buys vs 3 sells → minority/active = 3/6 = 0.5 > 0.3 (moderate) +1
        // … and we crank weightedScore to 0 to also trip "weak conviction" +1
        // … and active=6 >= minActive(4) so section 4 contributes 0.
        // To reach HIGH (>=5) we need at least one more contribution, so add
        // a high-volatility ATR filter (+3). Combined: 1 + 1 + 3 = 5 → HIGH.
        val results = listOf(
            r("rsi", Signal.BUY),
            r("macd", Signal.BUY),
            r("bollinger", Signal.BUY),
            r("ema_cross", Signal.SELL),
            r("stochastic", Signal.SELL),
            r("adx_di", Signal.SELL),
            r("atr_filter", Signal.NEUTRAL, mapOf("volatility" to 2.0)),
        )
        val s = breakdown(weightedScore = 0.0, buy = 3, sell = 3)
        val out = RiskAssessor.assess(results, s, config)

        assertEquals(RiskLevel.HIGH, out.riskLevel)
        assertTrue(out.riskScore >= 5.0, "score must be >=5")
        assertEquals(0.25, out.details["position_size_modifier"])
        assertTrue(
            out.riskFactors.any { it.contains("High ATR volatility") },
            "expected high-volatility factor",
        )
        assertTrue(
            out.riskFactors.any { it.contains("conflict") },
            "expected conflict factor",
        )
    }

    @Test
    fun `single high-volatility ATR flag pushes risk to MEDIUM or HIGH`() {
        // Otherwise quiet breakdown (no conflict, lots of conviction, lots of
        // active). Risk score should land at exactly 3 (HIGH ATR contribution)
        // → MEDIUM bucket. Position modifier 0.6.
        val results = listOf(
            r("atr_filter", Signal.NEUTRAL, mapOf("volatility" to 2.0)),
        )
        val s = breakdown(weightedScore = 1.5, buy = 5, sell = 0, strongBuy = 5)
        val out = RiskAssessor.assess(results, s, config)

        assertEquals(RiskLevel.MEDIUM, out.riskLevel)
        assertEquals(3.0, out.riskScore, 0.0)
        assertEquals(0.6, out.details["position_size_modifier"])
        assertTrue(out.riskFactors.contains("High ATR volatility"))
    }

    @Test
    fun `weak ADX trend adds risk and factor`() {
        // ADX of 10 < adxMin(15) → +2. No other risk paths trip
        // (strong wScore + enough active + no conflict).
        val results = listOf(
            r("adx_di", Signal.NEUTRAL, mapOf("adx" to 10.0)),
        )
        val s = breakdown(weightedScore = 1.0, buy = 4, sell = 0)
        val out = RiskAssessor.assess(results, s, config)

        assertEquals(RiskLevel.MEDIUM, out.riskLevel)
        assertEquals(2.0, out.riskScore, 0.0)
        assertTrue(
            out.riskFactors.any { it.startsWith("Weak trend (ADX=") },
            "expected ADX weak-trend factor",
        )
    }

    @Test
    fun `all neutral results degrade gracefully`() {
        // No indicators provide raw values for ATR/ADX paths. No active
        // signals, so section (3) is skipped — but section (4) +1 and
        // section (5) +1 fire on the empty breakdown. Total = 2 → MEDIUM.
        // Verifies the algorithm does not throw on a fully-neutral state.
        val results = listOf(
            r("rsi", Signal.NEUTRAL),
            r("macd", Signal.NEUTRAL),
            r("bollinger", Signal.NEUTRAL),
            r("ema_cross", Signal.NEUTRAL),
            r("stochastic", Signal.NEUTRAL),
        )
        val s = breakdown(weightedScore = 0.0, buy = 0, sell = 0, neutral = 5)
        val out = RiskAssessor.assess(results, s, config)

        assertEquals(RiskLevel.MEDIUM, out.riskLevel)
        assertEquals(2.0, out.riskScore, 0.0)
        assertNotNull(out.details["risk_factors"])
        assertEquals(2, out.riskFactors.size)
        assertTrue(
            out.riskFactors.any { it.startsWith("Few active signals") },
            "expected few-active factor",
        )
        assertTrue(
            out.riskFactors.any { it.startsWith("Weak conviction") },
            "expected weak-conviction factor",
        )
    }
}
