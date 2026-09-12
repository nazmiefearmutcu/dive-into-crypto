package com.diveintocrypto.android.engine.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.3.0 parity additions — hand-computed fixtures. All PURE: no clock, no I/O.
 */
class ParityMathTest {

    // ── Basis block ───────────────────────────────────────────────────────────

    @Test
    fun `basisBlock computes bps and annualised funding`() {
        val block = BasisAnalytics.basisBlock(markPrice = 101.0, indexPrice = 100.0, lastFundingRate = 0.0001)!!
        assertEquals(100.0, block.basisBps, 1e-9)
        // 0.01% per 8h × 3 × 365 = 10.95%/yr.
        assertEquals(10.95, block.annFundingPct, 1e-9)
    }

    @Test
    fun `basisBlock rejects non-positive mark or index - honest null`() {
        assertNull(BasisAnalytics.basisBlock(0.0, 100.0, 0.0001))
        assertNull(BasisAnalytics.basisBlock(101.0, -1.0, 0.0001))
    }

    // ── Funding lens ──────────────────────────────────────────────────────────

    @Test
    fun `fundingLens computes predicted-vs-settled apr and seconds-to-funding`() {
        val now = 1_700_000_000_000L
        val lens = FundingAnalytics.fundingLens(
            predictedRate = 0.0001,
            lastSettledRate = 0.0002,
            nextFundingMs = now + 600_000,
            nowMs = now,
        )!!
        assertEquals(0.01, lens.predictedRatePct, 1e-12)
        assertEquals(0.02, lens.lastSettledRatePct, 1e-12)
        // Simple annualisation: 0.0001 × (8760/8) × 100 = 10.95%/yr.
        assertEquals(10.95, lens.aprPct, 1e-9)
        assertEquals(600L, lens.secondsToFunding)
    }

    @Test
    fun `fundingLens without a next-funding time has null seconds - honest`() {
        val lens = FundingAnalytics.fundingLens(0.0001, 0.0002, nextFundingMs = 0, nowMs = 0)!!
        assertNull(lens.secondsToFunding)
    }

    @Test
    fun `fundingLens rejects non-finite rates`() {
        assertNull(FundingAnalytics.fundingLens(Double.NaN, 0.0, 1, 0))
        assertNull(FundingAnalytics.fundingLens(0.0, Double.POSITIVE_INFINITY, 1, 0))
    }

    // ── Vol cone (desktop structure.py::vol_cone parity — log-normal envelope) ──

    /** Builds a close series from a LOG-return sequence (start 100): c_k = c_{k−1}·e^r. */
    private fun closesFromLogReturns(returns: List<Double>): List<Double> {
        val out = mutableListOf(100.0)
        for (r in returns) out += out.last() * kotlin.math.exp(r)
        return out
    }

    @Test
    fun `vol cone hand case - 1 percent sigma log returns give the log-normal envelope`() {
        // 30 alternating log returns ±a whose SAMPLE σ (n−1 denominator) is
        // EXACTLY 0.01: mean 0, var = 30a²/29 → a = 0.01·√(29/30).
        val a = 0.01 * kotlin.math.sqrt(29.0 / 30.0)
        val rets = (0 until 30).map { if (it % 2 == 0) a else -a }
        val cone = VolCone.fromCloses(closesFromLogReturns(rets))!!
        assertEquals(1.0, cone.sigma1hPct, 1e-9)
        // σ = 0.01, h = 24 → env ≈ ±(e^(0.01·√24) − 1) ≈ +5.02% / −4.78%.
        assertEquals(kotlin.math.exp(0.01 * kotlin.math.sqrt(24.0)) - 1.0, cone.env24hUpPct / 100.0, 1e-12)
        assertEquals(kotlin.math.exp(-0.01 * kotlin.math.sqrt(24.0)) - 1.0, cone.env24hDownPct / 100.0, 1e-12)
        assertEquals(kotlin.math.exp(0.01 * kotlin.math.sqrt(48.0)) - 1.0, cone.env48hUpPct / 100.0, 1e-12)
        assertEquals(kotlin.math.exp(-0.01 * kotlin.math.sqrt(48.0)) - 1.0, cone.env48hDownPct / 100.0, 1e-12)
        // Sanity band from the hand computation: up ≈ +5.02%.
        assertTrue(cone.env24hUpPct > 5.0 && cone.env24hUpPct < 5.05)
        assertTrue(cone.env24hDownPct < -4.7 && cone.env24hDownPct > -4.85)
        // Chart convenience magnitudes: down expressed as a positive percent.
        assertEquals(cone.env24hUpPct, cone.envHighPct, 1e-12)
        assertEquals(-cone.env24hDownPct, cone.envLowPct, 1e-12)
        // √t projections stay available for the chart σ label.
        assertEquals(0.01 * kotlin.math.sqrt(24.0) * 100.0, cone.vol24hPct, 1e-9)
        assertEquals(0.01 * kotlin.math.sqrt(48.0) * 100.0, cone.vol48hPct, 1e-9)
    }

    @Test
    fun `vol cone percentile ranks the last 24h move in the trailing windows - else omitted`() {
        // 53 returns → exactly 30 overlapping 24h windows. 25 leading +B
        // returns then zeros: window |Σr| = (25−i)·B for i ≤ 24 and the LAST
        // five windows are exactly 0 → the last move ranks 5/30 → 16.7.
        val rets = List(25) { 0.01 } + List(28) { 0.0 }
        val cone = VolCone.fromCloses(closesFromLogReturns(rets))!!
        assertEquals(16.7, cone.percentile!!, 1e-9)

        // 30 returns → only 7 windows < CONE_MIN_WINDOWS(30) → omitted.
        val short = VolCone.fromCloses(
            closesFromLogReturns((0 until 30).map { if (it % 2 == 0) 0.01 else -0.01 }),
        )!!
        assertNull(short.percentile)
    }

    @Test
    fun `vol cone honest gates - too short, zero sigma null and bad prices skipped`() {
        // 29 log returns < MIN_RETURNS(30) → honest null.
        assertNull(VolCone.fromCloses(closesFromLogReturns(List(29) { 0.01 })))
        // Zero σ (all returns 0) → null — desktop returns None for σ ≤ 0 too.
        assertNull(VolCone.fromCloses(closesFromLogReturns(List(30) { 0.0 })))
        // Desktop filters non-positive closes PAIR-wise instead of rejecting
        // the series: a leading garbage price is skipped, the rest computes.
        // σ of 30 raw alternating ±0.01 log returns (sample, n−1) = 0.01·√(30/29).
        val rets = (0 until 30).map { if (it % 2 == 0) 0.01 else -0.01 }
        val withGarbage = listOf(0.0) + closesFromLogReturns(rets)
        assertEquals(0.01 * kotlin.math.sqrt(30.0 / 29.0) * 100.0, VolCone.fromCloses(withGarbage)!!.sigma1hPct, 1e-9)
    }

    // ── Planning strip ────────────────────────────────────────────────────────

    @Test
    fun `planning strip long at 2pct ATR with defaults`() {
        val plan = PlanningStrip.build(entry = 100.0, atrPct = 2.0, direction = "LONG")!!
        assertEquals(97.0, plan.slPrice, 1e-9)   // 100 × (1 − 1.5·2%)
        assertEquals(106.0, plan.tpPrice, 1e-9)  // 100 × (1 + 3.0·2%)
        assertEquals(96.0, plan.envLow, 1e-9)    // 100 × (1 − 2.0·2%)
        assertEquals(104.0, plan.envHigh, 1e-9)  // 100 × (1 + 2.0·2%)
        assertEquals(2.0, plan.rr, 1e-9)
    }

    @Test
    fun `planning strip short mirrors stop and target around entry`() {
        val plan = PlanningStrip.build(entry = 100.0, atrPct = 2.0, direction = "SHORT")!!
        assertEquals(103.0, plan.slPrice, 1e-9)
        assertEquals(94.0, plan.tpPrice, 1e-9)
        assertEquals(96.0, plan.envLow, 1e-9)
        assertEquals(104.0, plan.envHigh, 1e-9)
    }

    @Test
    fun `planning strip honors custom multiples and rejects invalid input`() {
        val plan = PlanningStrip.build(entry = 50.0, atrPct = 1.0, direction = "long", slMult = 1.0, tpMult = 4.0, envMult = 3.0)!!
        assertEquals(49.5, plan.slPrice, 1e-9)
        assertEquals(52.0, plan.tpPrice, 1e-9)
        assertEquals(48.5, plan.envLow, 1e-9)
        assertEquals(51.5, plan.envHigh, 1e-9)
        assertEquals(4.0, plan.rr, 1e-9)

        assertNull(PlanningStrip.build(0.0, 2.0, "LONG"))
        assertNull(PlanningStrip.build(100.0, -1.0, "LONG"))
        assertNull(PlanningStrip.build(100.0, 2.0, "NEUTRAL"))
        assertTrue(PlanningStrip.build(100.0, 0.0, "LONG") != null, "zero ATR is a valid (tight) plan")
    }
}
