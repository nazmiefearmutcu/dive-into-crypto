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

    @Test
    fun `forwardReturnPct uses the candle covering the record and rejects non-covering data`() {
        val opens = listOf(0L, 3_600_000L, 7_200_000L)
        val closes = listOf(100.0, 110.0, 121.0)
        // Record inside candle 0 → base 100, last 121 → +21%.
        assertEquals(21.0, EvidenceGrader.forwardReturnPct(opens, closes, 1_000L)!!, 1e-9)
        // Record inside candle 1 → base 110 → +10%.
        assertEquals(10.0, EvidenceGrader.forwardReturnPct(opens, closes, 3_600_001L)!!, 1e-9)
        // Record before the first candle → honestly ungradable.
        assertNull(EvidenceGrader.forwardReturnPct(opens, closes, -5L))
        assertNull(EvidenceGrader.forwardReturnPct(emptyList(), emptyList(), 1L))
    }
}
