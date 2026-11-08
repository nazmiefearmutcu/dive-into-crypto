package com.diveintocrypto.android.domain.evidence

import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EvidenceStore] ring-cap + round-trip + corrupt-line tolerance, and the pure
 * grader math on synthetic records. No network, no clock.
 */
class EvidenceStoreTest {

    private fun record(ts: Long, symbol: String = "BTCUSDT", verdict: String = "BUY") = VerdictRecord(
        ts = ts,
        symbol = symbol,
        verdict = verdict,
        confidence = 66,
        risk = "LOW",
        price = 42_000.0 + ts % 1000,
        dominantDir = 1,
    )

    @Test
    fun `append and readAll round-trips every field`() {
        val store = EvidenceStore(InMemoryKeyValueStore())
        val rec = VerdictRecord(
            ts = 1_700_000_000_000,
            symbol = "ETHUSDT",
            verdict = "STRONG_SELL",
            confidence = 81,
            risk = "HIGH",
            price = 2345.67,
            dominantDir = -1,
        )
        store.append(rec)
        assertEquals(listOf(rec), store.readAll())
    }

    @Test
    fun `coverage is optional - pre-coverage lines decode as null and partial runs keep their stamp`() {
        val kv = InMemoryKeyValueStore()
        val store = EvidenceStore(kv)
        // A line written before the field existed (no "coverage" key) still decodes.
        val legacyLine = """{"ts":1,"symbol":"BTCUSDT","verdict":"BUY","confidence":60,"risk":"N/A","price":100.0,"dominantDir":1}"""
        kv.putString(EvidenceStore.KEY, legacyLine)
        store.append(record(ts = 2L).copy(coverage = VerdictRecord.COVERAGE_ATTEMPTED))
        store.append(record(ts = 3L).copy(coverage = VerdictRecord.COVERAGE_OK))

        val all = store.readAll()
        assertEquals(3, all.size)
        assertNull(all[0].coverage)
        assertEquals(VerdictRecord.COVERAGE_ATTEMPTED, all[1].coverage)
        assertEquals(VerdictRecord.COVERAGE_OK, all[2].coverage)
    }

    @Test
    fun `appendAll respects the 2000-line ring cap keeping the newest`() {
        val store = EvidenceStore(InMemoryKeyValueStore())
        val total = EvidenceStore.RING_CAP + 50
        store.appendAll((0 until total).map { i -> record(ts = i.toLong()) })

        val all = store.readAll()
        assertEquals(EvidenceStore.RING_CAP, all.size)
        // The 50 OLDEST are gone; the newest survives.
        assertEquals(50L, all.first().ts)
        assertEquals((total - 1).toLong(), all.last().ts)
    }

    @Test
    fun `ringAppend pure helper keeps the newest cap lines`() {
        val lines = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "d"), EvidenceStore.ringAppend(lines, "d", 10))
        assertEquals(listOf("c", "d"), EvidenceStore.ringAppend(lines, "d", 2))
    }

    @Test
    fun `corrupt lines are skipped on read - failure tolerant`() {
        val kv = InMemoryKeyValueStore()
        val store = EvidenceStore(kv)
        // A blob containing one corrupt line and one valid record line.
        val raw = "NOT_JSON\n" +
            "{\"ts\":3,\"symbol\":\"XUSDT\",\"verdict\":\"BUY\",\"confidence\":50," +
            "\"risk\":\"LOW\",\"price\":1.0,\"dominantDir\":1}"
        kv.putString(EvidenceStore.KEY, raw)
        val out = store.readAll()
        assertEquals(1, out.size)
        assertEquals(3L, out.single().ts)
        assertTrue(out.single().symbol == "XUSDT")
    }

    @Test
    fun `last-graded cursor round-trips through the store`() {
        val store = EvidenceStore(InMemoryKeyValueStore())
        assertEquals(0L, store.lastGradedMs())
        store.setLastGradedMs(1_700_000_000_000)
        assertEquals(1_700_000_000_000, store.lastGradedMs())
    }
}

class EvidenceGraderTest {

    private fun sample(
        verdict: String = "BUY",
        confidence: Int = 60,
        dir: Int = 1,
        fwd: Double,
    ) = EvidenceGrader.GradedSample(verdict, confidence, dir, fwd)

    @Test
    fun `isHit follows the verdict direction with an honest flat band`() {
        assertTrue(EvidenceGrader.isHit(1, 0.5))
        assertFalse(EvidenceGrader.isHit(1, -0.5))
        assertTrue(EvidenceGrader.isHit(-1, -0.5))
        assertFalse(EvidenceGrader.isHit(-1, 0.5))
        assertFalse(EvidenceGrader.isHit(0, 10.0), "NEUTRAL never hits")
        assertFalse(EvidenceGrader.isHit(1, 0.005), "inside the flat band → not a hit")
        assertFalse(EvidenceGrader.isHit(-1, -0.005))
    }

    @Test
    fun `confidence buckets partition 0-100`() {
        assertEquals("0-25", EvidenceGrader.confidenceBucket(0))
        assertEquals("0-25", EvidenceGrader.confidenceBucket(25))
        assertEquals("26-50", EvidenceGrader.confidenceBucket(26))
        assertEquals("26-50", EvidenceGrader.confidenceBucket(50))
        assertEquals("51-75", EvidenceGrader.confidenceBucket(51))
        assertEquals("51-75", EvidenceGrader.confidenceBucket(75))
        assertEquals("76-100", EvidenceGrader.confidenceBucket(76))
        assertEquals("76-100", EvidenceGrader.confidenceBucket(100))
    }

    @Test
    fun `median handles odd even and empty samples`() {
        assertEquals(3.0, EvidenceGrader.median(listOf(3.0))!!, 1e-9)
        assertEquals(2.5, EvidenceGrader.median(listOf(1.0, 2.0, 3.0, 4.0))!!, 1e-9)
        assertEquals(3.0, EvidenceGrader.median(listOf(9.0, 1.0, 3.0))!!, 1e-9, "unsorted input")
        assertNull(EvidenceGrader.median(emptyList()))
    }

    @Test
    fun `grade computes hit-rate and median per verdict bucket`() {
        val grade = EvidenceGrader.grade(
            listOf(
                sample(verdict = "BUY", dir = 1, fwd = 5.0),
                sample(verdict = "BUY", dir = 1, fwd = -1.0),
                sample(verdict = "BUY", dir = 1, fwd = 3.0),
                sample(verdict = "BUY", dir = 1, fwd = 3.0),
                sample(verdict = "BUY", dir = 1, fwd = -1.0),
                sample(verdict = "BUY", dir = 1, fwd = 3.0),
                sample(verdict = "SELL", dir = -1, fwd = -2.0),
                sample(verdict = "SELL", dir = -1, fwd = -2.0),
                sample(verdict = "SELL", dir = -1, fwd = -2.0),
                sample(verdict = "SELL", dir = -1, fwd = -2.0),
                sample(verdict = "SELL", dir = -1, fwd = -2.0),
            ),
        )
        assertEquals(11, grade.graded)
        val buy = grade.byVerdict.getValue("BUY")
        assertEquals(6, buy.samples)
        assertEquals(2.0 / 3.0, buy.hitRate!!, 1e-9)
        assertEquals(3.0, buy.medianReturnPct, 1e-9)
        val sell = grade.byVerdict.getValue("SELL")
        assertEquals(5, sell.samples)
        assertEquals(1.0, sell.hitRate!!, 1e-9)
    }

    @Test
    fun `grade computes per-confidence-band buckets`() {
        val grade = EvidenceGrader.grade(
            listOf(
                sample(confidence = 20, fwd = 4.0),
                sample(confidence = 20, fwd = -4.0),
                sample(confidence = 20, fwd = 4.0),
                sample(confidence = 20, fwd = -4.0),
                sample(confidence = 20, fwd = 4.0),
                sample(confidence = 20, fwd = -4.0),
                sample(confidence = 85, fwd = 1.0),
                sample(confidence = 85, fwd = 2.0),
                sample(confidence = 85, fwd = 3.0),
                sample(confidence = 85, fwd = 4.0),
                sample(confidence = 85, fwd = 5.0),
            ),
        )
        assertEquals(6, grade.byConfidence.getValue("0-25").samples)
        assertEquals(0.5, grade.byConfidence.getValue("0-25").hitRate!!, 1e-9)
        assertEquals(5, grade.byConfidence.getValue("76-100").samples)
        assertEquals(1.0, grade.byConfidence.getValue("76-100").hitRate!!, 1e-9)
    }

    private val h1 = 3_600_000L

    /** A "now" far past every candle in these fixtures, so every horizon candle is closed. */
    private val allClosed = 1_000L * 3_600_000L

    @Test
    fun `forwardReturnPct runs from the verdict price to the horizon candle close`() {
        val opens = listOf(0L, h1, 2 * h1)
        val closes = listOf(100.0, 110.0, 121.0)
        // Record at 1s, base = verdict price 100, horizon 1h → target 1h+1s sits in candle 1 → close 110 → +10%.
        assertEquals(10.0, EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, 100.0, h1, allClosed)!!, 1e-9)
        // Same record, 2h horizon → target 2h+1s sits in candle 2 → close 121 → +21%.
        assertEquals(21.0, EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, 100.0, 2 * h1, allClosed)!!, 1e-9)
    }

    @Test
    fun `forwardReturnPct never uses a candle close before the record as the base`() {
        // Candle 0 closes at 105 (after the record) — the old base would have been 105.
        val opens = listOf(0L, h1)
        val closes = listOf(105.0, 103.0)
        // Verdict price 100 at ts 1000, horizon 1h → candle 1 close 103 → +3% (not -1.9%).
        assertEquals(3.0, EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, 100.0, h1, allClosed)!!, 1e-9)
    }

    @Test
    fun `forwardReturnPct is pending until the horizon candle exists and rejects bad base`() {
        val opens = listOf(0L, h1, 2 * h1)
        val closes = listOf(100.0, 110.0, 121.0)
        // Horizon 3h → target 3h+1s is past the newest candle's window → not gradable yet.
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, 100.0, 3 * h1, allClosed))
        // Target before the first candle → not gradable.
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes, -10 * h1, 100.0, h1, allClosed))
        // Degenerate base or empty / mismatched inputs → null.
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, 0.0, h1, allClosed))
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, Double.NaN, h1, allClosed))
        assertNull(EvidenceGrader.forwardReturnPct(emptyList(), emptyList(), 1L, 100.0, h1, allClosed))
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes.dropLast(1), 1_000L, 100.0, h1, allClosed))
    }

    @Test
    fun `a horizon candle that is still forming is pending, not a final close`() {
        val opens = listOf(0L, h1)
        val closes = listOf(100.0, 110.0)
        // Target h1+1s sits in candle 1, which opens at h1 and closes at 2h1. At now = h1+10s the
        // candle is still open, so its provisional close (110) must not be graded.
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, 100.0, h1, h1 + 10_000L))
        // Once the candle has closed (now = 2h1) the same record grades to +10%.
        assertEquals(10.0, EvidenceGrader.forwardReturnPct(opens, closes, 1_000L, 100.0, h1, 2 * h1)!!, 1e-9)
    }

    @Test
    fun `the candle containing the verdict timestamp is never the horizon candle`() {
        val opens = listOf(0L, h1, 2 * h1)
        val closes = listOf(500.0, 110.0, 121.0)
        // Verdict at 1h+5min sits in candle 1 (open h1). A short horizon of 10 min targets 1h+15min,
        // which is still inside that same candle. Its close (110) is partly before the verdict,
        // so it must not be used; the record stays pending instead.
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes, h1 + 300_000L, 100.0, 600_000L, allClosed))
    }

    @Test
    fun `a NEUTRAL-only bucket has no hit rate instead of a structural zero`() {
        val neutral = (0 until 6).map { EvidenceGrader.GradedSample("NEUTRAL", 40, 0, 1.0) }
        val stats = EvidenceGrader.stats(neutral)
        assertEquals(6, stats.samples)
        assertNull(stats.hitRate, "NEUTRAL makes no directional claim")
        assertNull(stats.hitRateLo)
        assertNull(stats.hitRateHi)
        assertEquals(1.0, stats.medianReturnPct, 1e-9)
    }

    @Test
    fun `NEUTRAL samples are left out of the directional hit rate, not counted as misses`() {
        // 6 directional (4 hits, 2 misses) + 4 NEUTRAL: the rate is 4 of 6, not 4 of 10.
        val mixed = listOf(
            EvidenceGrader.GradedSample("BUY", 80, 1, 1.0),
            EvidenceGrader.GradedSample("BUY", 80, 1, 1.0),
            EvidenceGrader.GradedSample("BUY", 80, 1, 1.0),
            EvidenceGrader.GradedSample("BUY", 80, 1, 1.0),
            EvidenceGrader.GradedSample("BUY", 80, 1, -1.0),
            EvidenceGrader.GradedSample("BUY", 80, 1, -1.0),
        ) + (0 until 4).map { EvidenceGrader.GradedSample("NEUTRAL", 80, 0, 1.0) }
        val stats = EvidenceGrader.stats(mixed)
        assertEquals(10, stats.samples)
        assertEquals(4.0 / 6.0, stats.hitRate!!, 1e-9)
        assertTrue(stats.gated, "6 directional samples is below the ungated floor of 20")
    }

    @Test
    fun `a mixed bucket with too few directional samples shows no rate`() {
        // 3 directional + 5 NEUTRAL: below MIN_BUCKET_N directional samples, so no rate.
        val mixed = (0 until 3).map { EvidenceGrader.GradedSample("BUY", 80, 1, 1.0) } +
            (0 until 5).map { EvidenceGrader.GradedSample("NEUTRAL", 80, 0, 1.0) }
        val stats = EvidenceGrader.stats(mixed)
        assertEquals(8, stats.samples)
        assertNull(stats.hitRate)
    }
}
