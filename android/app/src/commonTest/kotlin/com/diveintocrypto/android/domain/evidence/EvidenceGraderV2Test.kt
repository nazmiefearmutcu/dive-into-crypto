package com.diveintocrypto.android.domain.evidence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v2 grader math on hand-computed fixtures: Wilson 95% intervals + gates,
 * ECE calibration bins, Brier score + skill vs the base rate, rolling windows.
 * PURE — no clock, no storage, no network.
 */
class EvidenceGraderV2Test {

    private fun sample(
        verdict: String = "BUY",
        confidence: Int = 60,
        dir: Int = 1,
        fwd: Double,
        ts: Long = 0L,
    ) = EvidenceGrader.GradedSample(verdict, confidence, dir, fwd, ts)

    // ── Wilson interval (known values) ────────────────────────────────────────

    @Test
    fun `wilson interval at p05 n10 matches the hand-computed value`() {
        val (lo, hi) = EvidenceGrader.wilsonInterval(5, 10)!!
        assertEquals(0.2366, lo, 1e-3)
        assertEquals(0.7634, hi, 1e-3)
    }

    @Test
    fun `wilson interval clamps to 0 and 1 at the extremes`() {
        val (loHi, hiHi) = EvidenceGrader.wilsonInterval(5, 5)!!   // 5/5
        assertEquals(0.5655, loHi, 1e-3)
        assertEquals(1.0, hiHi, 1e-9)
        val (loLo, hiLo) = EvidenceGrader.wilsonInterval(0, 5)!!   // 0/5
        assertEquals(0.0, loLo, 1e-9)
        assertEquals(0.4345, hiLo, 1e-3)
    }

    @Test
    fun `wilson interval is degenerate-null for empty buckets`() {
        assertNull(EvidenceGrader.wilsonInterval(0, 0))
    }

    @Test
    fun `interval always brackets the raw hit rate`() {
        for (n in 5..40) {
            val (lo, hi) = EvidenceGrader.wilsonInterval(n / 2, n)!!
            val p = n / 2.0 / n
            assertTrue(lo <= p && p <= hi, "n=$n")
        }
    }

    // ── Gates ─────────────────────────────────────────────────────────────────

    @Test
    fun `gate below 5 samples the hit rate is honestly null`() {
        val stats = EvidenceGrader.stats((0 until 4).map { sample(fwd = 5.0) })
        assertEquals(4, stats.samples)
        assertNull(stats.hitRate)
        assertNull(stats.hitRateLo)
        assertNull(stats.hitRateHi)
        assertTrue(stats.gated)
    }

    @Test
    fun `gate 5 to 19 samples keeps the rate but flags it gated`() {
        val stats = EvidenceGrader.stats((0 until 19).map { sample(fwd = 5.0) })
        assertEquals(1.0, stats.hitRate!!, 1e-9)
        assertNotNull(stats.hitRateLo)
        assertNotNull(stats.hitRateHi)
        assertTrue(stats.gated)
    }

    @Test
    fun `gate at 20 samples the interval is un-gated`() {
        val stats = EvidenceGrader.stats((0 until 20).map { sample(fwd = 5.0) })
        assertFalse(stats.gated)
        assertEquals(20, stats.samples)
    }

    @Test
    fun `empty bucket is gated with no median lie`() {
        val stats = EvidenceGrader.stats(emptyList())
        assertEquals(0, stats.samples)
        assertNull(stats.hitRate)
        assertTrue(stats.gated)
        assertEquals(0.0, stats.medianReturnPct, 1e-9)
    }

    @Test
    fun `grade propagates intervals and gates into the buckets`() {
        val grade = EvidenceGrader.grade(
            (0 until 25).map { sample(verdict = "BUY", fwd = if (it % 2 == 0) 5.0 else -5.0) } +
                (0 until 3).map { sample(verdict = "SELL", fwd = -5.0) },
        )
        val buy = grade.byVerdict.getValue("BUY")
        assertEquals(0.52, buy.hitRate!!, 1e-9, "13 of 25 alternating samples hit")
        assertFalse(buy.gated)
        val sell = grade.byVerdict.getValue("SELL")
        assertNull(sell.hitRate, "3-sample bucket gated to null")
        assertTrue(sell.gated)
    }

    // ── ECE ───────────────────────────────────────────────────────────────────

    @Test
    fun `ece is zero for a perfectly calibrated fixture`() {
        // Conf 80 hits and misses in exactly 80/20 proportion → bin gap 0;
        // conf 20 in a 20/80 proportion → gap 0.
        val samples = (0 until 8).map { sample(confidence = 80, fwd = 5.0) } +
            (0 until 2).map { sample(confidence = 80, fwd = -5.0) } +
            (0 until 2).map { sample(confidence = 20, fwd = 5.0) } +
            (0 until 8).map { sample(confidence = 20, fwd = -5.0) }
        val ece = EvidenceGrader.ece(samples)
        assertEquals(0.0, ece.ece!!, 1e-9)
        val bin8 = ece.bins[8]
        assertEquals(10, bin8.samples)
        assertEquals(0.8, bin8.predicted!!, 1e-9)
        assertEquals(0.8, bin8.observed!!, 1e-9)
    }

    @Test
    fun `ece matches the hand-computed over-confident fixture`() {
        // 5 confident hits (bin 80s: predicted 0.8, observed 1.0 → gap 0.2)
        // 5 unconfident misses (bin 20s: predicted 0.2, observed 0.0 → gap 0.2)
        val samples = (0 until 5).map { sample(confidence = 80, fwd = 5.0) } +
            (0 until 5).map { sample(confidence = 20, fwd = -5.0) }
        val ece = EvidenceGrader.ece(samples)
        assertEquals(0.2, ece.ece!!, 1e-9)
        assertEquals(2, ece.bins.count { it.samples > 0 }, "only two bins populated")
    }

    @Test
    fun `ece on empty input is honestly null with the full empty bin table`() {
        val ece = EvidenceGrader.ece(emptyList())
        assertNull(ece.ece)
        assertEquals(EvidenceGrader.ECE_BINS, ece.bins.size)
        assertTrue(ece.bins.all { it.samples == 0 })
    }

    // ── Brier ─────────────────────────────────────────────────────────────────

    @Test
    fun `brier and skill match the hand-computed fixture`() {
        val samples = listOf(
            sample(confidence = 80, fwd = 5.0),   // p=0.8, o=1 → 0.04
            sample(confidence = 20, fwd = -5.0),  // p=0.2, o=0 → 0.04
            sample(confidence = 50, fwd = 5.0),   // p=0.5, o=1 → 0.25
            sample(confidence = 50, fwd = -5.0),  // p=0.5, o=0 → 0.25
        )
        val b = EvidenceGrader.brier(samples)!!
        assertEquals(0.145, b.brier, 1e-9)
        assertEquals(0.5, b.baseRate, 1e-9)
        assertEquals(0.25, b.brierBase, 1e-9)
        assertEquals(0.42, b.skill!!, 1e-9)
    }

    @Test
    fun `perfect probabilistic forecasts get brier zero and full skill`() {
        // Confident-correct hits AND confident-correct misses: every stated
        // probability is exactly right → Brier 0, skill 1.
        val samples = (0 until 5).map { sample(confidence = 100, fwd = 5.0) } +
            (0 until 5).map { sample(confidence = 0, fwd = -5.0) }
        val b = EvidenceGrader.brier(samples)!!
        assertEquals(0.0, b.brier, 1e-9)
        assertEquals(0.5, b.baseRate, 1e-9)
        assertEquals(1.0, b.skill!!, 1e-9)
    }

    @Test
    fun `degenerate all-same-outcome fixture has null skill - honest`() {
        val samples = (0 until 10).map { sample(confidence = 50, fwd = 5.0) } // all hits
        val b = EvidenceGrader.brier(samples)!!
        assertEquals(0.0, b.brierBase, 1e-9)
        assertNull(b.skill)
    }

    @Test
    fun `brier on empty input is null`() {
        assertNull(EvidenceGrader.brier(emptyList()))
    }

    // ── Rolling windows ───────────────────────────────────────────────────────

    @Test
    fun `rolling windows bucket samples by age honestly`() {
        val day = EvidenceGrader.DAY_MS
        val now = 100 * day
        val samples = listOf(
            sample(fwd = 5.0, ts = now - 1 * day),   // inside 7d
            sample(fwd = 5.0, ts = now - 3 * day),   // inside 7d
            sample(fwd = 5.0, ts = now - 10 * day),  // inside 30d only
            sample(fwd = -5.0, ts = now - 300 * day), // all only
        )
        val windows = EvidenceGrader.gradeWindows(samples, now)
        assertEquals(3, windows.size)
        assertEquals(2, windows.getValue(EvidenceGrader.WINDOW_7D).graded)
        assertEquals(3, windows.getValue(EvidenceGrader.WINDOW_30D).graded)
        assertEquals(4, windows.getValue(EvidenceGrader.WINDOW_ALL).graded)
        // The 2-sample 7d BUY bucket is gated → hitRate honestly null.
        assertNull(windows.getValue(EvidenceGrader.WINDOW_7D).byVerdict.getValue("BUY").hitRate)
    }
}
