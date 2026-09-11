package com.diveintocrypto.android.ui.scanner

import com.diveintocrypto.android.domain.model.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the scanner pipeline helpers (no network, no VM state):
 * cross-rank aggregation + MTF-confluence annotation, adverse-divergence
 * elimination/backfill partition, per-TF cell backfill, and the canonical
 * risk-from-agreement mapping.
 */
class ScannerLogicTest {

    private fun tfResult(
        symbol: String,
        tf: String,
        signal: Signal,
        confidence: Int,
        finalScore: Double,
        regime: String = "MIXED",
    ) = SymbolTfResult(
        symbol = symbol,
        tf = tf,
        signal = signal,
        confidence = confidence,
        price = 100.0,
        timeWeight = ScannerViewModel.TIME_WEIGHTS[tf] ?: 50,
        finalScore = finalScore,
        regime = regime,
    )

    private fun row(
        symbol: String,
        dominantDir: Signal = Signal.BUY,
        netNss: Double = 0.0,
        countHit: Int = 12,
        divergenceScore: Double = 0.0,
        divergenceDirection: Int = 0,
    ) = CrossRankingRow(
        symbol = symbol,
        dominantDir = dominantDir,
        netNss = netNss,
        countHit = countHit,
        totalTfs = 12,
        perTf = emptyMap(),
        price = 100.0,
        divergenceScore = divergenceScore,
        divergenceDirection = divergenceDirection,
    )

    // ── riskFromAgreement (canonical; ScannerScreen's private copy must match) ──

    @Test
    fun `riskFromAgreement pins the LOW MEDIUM HIGH N-A bands`() {
        assertEquals("LOW", ScannerViewModel.riskFromAgreement(11, 12))
        assertEquals("LOW", ScannerViewModel.riskFromAgreement(12, 12))
        assertEquals("MEDIUM", ScannerViewModel.riskFromAgreement(6, 12))
        assertEquals("HIGH", ScannerViewModel.riskFromAgreement(1, 12))
        assertEquals("N/A", ScannerViewModel.riskFromAgreement(0, 12))
    }

    // ── crossRankRows ─────────────────────────────────────────────────────────

    @Test
    fun `crossRank aggregates nss sorts desc and annotates regime`() {
        val tfTop15 = mapOf(
            "1d" to listOf(
                tfResult("AAA", "1d", Signal.STRONG_BUY, confidence = 80, finalScore = 60.0, regime = "TREND"),
                tfResult("BBB", "1d", Signal.NEUTRAL, confidence = 10, finalScore = 1.0, regime = "RANGE"),
            ),
            "1h" to listOf(
                tfResult("AAA", "1h", Signal.BUY, confidence = 70, finalScore = 30.0),
                tfResult("BBB", "1h", Signal.SELL, confidence = 90, finalScore = 50.0, regime = "RANGE"),
            ),
        )
        val rows = ScannerViewModel.crossRankRows(tfTop15)

        // AAA: buyNss=60+30=90, sellNss=0 → netNss 90. BBB: sellNss=50 → netNss 50.
        assertEquals(listOf("AAA", "BBB"), rows.map { it.symbol })
        val aaa = rows[0]
        assertEquals(Signal.BUY, aaa.dominantDir)
        assertEquals(90.0, aaa.netNss)
        assertEquals(2, aaa.countHit)
        assertEquals(12, aaa.totalTfs)
        // Regime annotation comes from the best-confidence TF (1d, conf 80 → TREND).
        assertEquals("TREND", aaa.regime)
        assertEquals("RANGE", rows[1].regime)
        // per-TF slots from the top15 are marked inTop15.
        assertTrue(aaa.perTf.getValue("1d").inTop15)
        assertTrue(aaa.perTf.getValue("1h").inTop15)
    }

    @Test
    fun `crossRank annotates MTF confluence gate`() {
        val allBuy = mapOf(
            "1d" to listOf(tfResult("AAA", "1d", Signal.STRONG_BUY, 80, 60.0)),
            "4h" to listOf(tfResult("AAA", "4h", Signal.BUY, 70, 30.0)),
            "1h" to listOf(tfResult("AAA", "1h", Signal.BUY, 70, 30.0)),
        )
        val mtf = ScannerViewModel.crossRankRows(allBuy).single()
        assertEquals(1, mtf.mtfDirection)
        assertTrue(mtf.mtfGate, "higher-TF stack agrees → gate on")
        assertTrue(mtf.mtfScore > 0)
        assertEquals("STRONG", mtf.mtfLabel)

        // One symbol conflicting across HTFs (BUY on 1h, SELL on 4h) → htfAgree 0.5 < 0.6 → gate off.
        val split = mapOf(
            "4h" to listOf(tfResult("BBB", "4h", Signal.SELL, 70, 30.0)),
            "1h" to listOf(tfResult("BBB", "1h", Signal.BUY, 70, 30.0)),
        )
        val conflict = ScannerViewModel.crossRankRows(split).single()
        assertFalse(conflict.mtfGate, "split higher-TF stack → gate off")
    }

    @Test
    fun `crossRank empty input yields empty output`() {
        assertTrue(ScannerViewModel.crossRankRows(emptyMap()).isEmpty())
    }

    // ── eliminateAdverse ──────────────────────────────────────────────────────

    @Test
    fun `eliminateAdverse removes opposite-direction divergence and preserves order`() {
        val clean = row("CLEAN", dominantDir = Signal.BUY, divergenceScore = 0.0)
        val adverse = row(
            "ADVERSE", dominantDir = Signal.BUY,
            divergenceScore = 25.0, divergenceDirection = -1, // BUY but whale distributing → adverse
        )
        val aligned = row(
            "ALIGNED", dominantDir = Signal.SELL,
            divergenceScore = 25.0, divergenceDirection = -1, // SELL with whale distributing → aligned
        )
        val subThreshold = row(
            "NOISE", dominantDir = Signal.BUY,
            divergenceScore = 3.0, divergenceDirection = -1, // below DIVERGENCE_MIN_SHOWN → kept
        )
        val (survivors, eliminated) = ScannerViewModel.eliminateAdverse(listOf(clean, adverse, aligned, subThreshold))

        assertEquals(listOf("CLEAN", "ALIGNED", "NOISE"), survivors.map { it.symbol })
        assertEquals(listOf("ADVERSE"), eliminated.map { it.symbol })
    }

    @Test
    fun `eliminateAdverse empty input`() {
        val (s, e) = ScannerViewModel.eliminateAdverse(emptyList())
        assertTrue(s.isEmpty() && e.isEmpty())
    }

    // ── patchPerTfRow ─────────────────────────────────────────────────────────

    @Test
    fun `patchPerTfRow backfills missing TF cells from the full results`() {
        val partial = row("AAA").copy(
            perTf = mapOf("1d" to TfSlotState(Signal.BUY, 80, 95, 60.0, true)),
        )
        val tfResults = mapOf(
            "1m" to listOf(tfResult("AAA", "1m", Signal.SELL, 55, 20.0)),
            "1h" to listOf(tfResult("OTHER", "1h", Signal.NEUTRAL, 10, 1.0)),
        )
        val patched = ScannerViewModel.patchPerTfRow(partial, tfResults)

        assertEquals(Signal.BUY, patched.perTf.getValue("1d").signal)   // untouched, stays top15
        assertTrue(patched.perTf.getValue("1d").inTop15)
        val backfilled = patched.perTf["1m"]
        assertEquals(Signal.SELL, backfilled?.signal)
        assertEquals(false, backfilled?.inTop15)
        assertEquals(8, backfilled?.timeWeight) // 1m time weight
        // "1h" has no result for AAA → no cell fabricated.
        assertEquals(null, patched.perTf["1h"])
    }
}
