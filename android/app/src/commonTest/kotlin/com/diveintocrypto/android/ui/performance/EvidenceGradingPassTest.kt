package com.diveintocrypto.android.ui.performance

import com.diveintocrypto.android.domain.evidence.VerdictRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The evidence grading pass is keyed by (ts, symbol, horizon) — not by a timestamp
 * cursor — so a failed or capped symbol can never hide records from other symbols.
 */
class EvidenceGradingPassTest {

    private fun rec(ts: Long, symbol: String, verdict: String = "BUY") = VerdictRecord(
        ts = ts,
        symbol = symbol,
        verdict = verdict,
        confidence = 60,
        risk = "LOW",
        price = 100.0,
        dominantDir = 1,
    )

    @Test
    fun failedSymbolStaysPendingAndDoesNotHideOtherRecords() {
        // Scan A (ts=100) failed to fetch candles; scan B (ts=200) was graded fine.
        val eligible = listOf(rec(100, "A"), rec(200, "B"))
        val settled = mapOf(EvidenceGradingPass.keyOf(rec(200, "B"), 4) to 1.2)

        assertEquals(listOf("A"), EvidenceGradingPass.symbolsToFetch(eligible, settled, 4, 40))
        assertEquals(1, EvidenceGradingPass.gradedSamples(eligible, settled, 4).size)
        assertEquals(1, EvidenceGradingPass.pendingCount(eligible, settled, 4))
        assertEquals(200L, EvidenceGradingPass.newestGradedTs(eligible, settled, 4))
    }

    @Test
    fun recordsSharingOneScanTimestampAreNotLostToTheSymbolCap() {
        // Three symbols from the same scan share one timestamp; the pass may fetch only 2.
        val eligible = listOf(rec(100, "A"), rec(100, "B"), rec(100, "C"))

        val firstPass = EvidenceGradingPass.symbolsToFetch(eligible, emptyMap(), 4, 2)
        assertEquals(listOf("C", "B"), firstPass)
        val settled = firstPass.associate { sym -> EvidenceGradingPass.keyOf(rec(100, sym), 4) to 0.5 }

        // The next pass still reaches A, and the statistics cover all three once it is settled.
        assertEquals(listOf("A"), EvidenceGradingPass.symbolsToFetch(eligible, settled, 4, 2))
        assertEquals(2, EvidenceGradingPass.gradedSamples(eligible, settled, 4).size)
        assertEquals(1, EvidenceGradingPass.pendingCount(eligible, settled, 4))
    }

    @Test
    fun uncoverableRecordIsSettledNotPending() {
        val eligible = listOf(rec(100, "OLD"))
        val settled = mapOf(EvidenceGradingPass.keyOf(rec(100, "OLD"), 4) to null)

        assertEquals(0, EvidenceGradingPass.pendingCount(eligible, settled, 4))
        assertTrue(EvidenceGradingPass.symbolsToFetch(eligible, settled, 4, 40).isEmpty())
        assertEquals(0, EvidenceGradingPass.gradedSamples(eligible, settled, 4).size)
    }

    @Test
    fun outcomesAreKeyedByHorizon() {
        val eligible = listOf(rec(100, "A"))
        val settled = mapOf(EvidenceGradingPass.keyOf(rec(100, "A"), 1) to 0.3)

        assertEquals(1, EvidenceGradingPass.pendingCount(eligible, settled, 4))
        assertEquals(0, EvidenceGradingPass.pendingCount(eligible, settled, 1))
    }

    @Test
    fun strongVerdictsFoldIntoBuyAndSell() {
        val eligible = listOf(
            rec(100, "A", "STRONG_BUY"),
            rec(100, "B", "STRONG_SELL"),
            rec(100, "C", "NEUTRAL"),
        )
        val settled = eligible.associate { EvidenceGradingPass.keyOf(it, 4) to 1.0 }

        val verdicts = EvidenceGradingPass.gradedSamples(eligible, settled, 4).map { it.verdict }
        assertEquals(listOf("BUY", "SELL", "NEUTRAL"), verdicts)
    }
}
