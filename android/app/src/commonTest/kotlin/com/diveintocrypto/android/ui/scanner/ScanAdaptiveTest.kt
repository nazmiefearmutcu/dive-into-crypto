package com.diveintocrypto.android.ui.scanner

import com.diveintocrypto.android.data.ScanUniverseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FULL-UNIVERSE MODE pure decision helpers: adaptive concurrency, the honest
 * ETA estimator, and the universe-mode label/limit mapping.
 */
class ScanAdaptiveTest {

    // ── adaptiveParallelism ───────────────────────────────────────────────────

    @Test
    fun `small universes keep the user's base parallelism`() {
        assertEquals(8, ScannerViewModel.adaptiveParallelism(8, 50))
        assertEquals(8, ScannerViewModel.adaptiveParallelism(8, 100))
    }

    @Test
    fun `universes over 100 symbols bump to at least 12`() {
        assertEquals(12, ScannerViewModel.adaptiveParallelism(8, 101))
        assertEquals(12, ScannerViewModel.adaptiveParallelism(8, 500))
        assertEquals(14, ScannerViewModel.adaptiveParallelism(14, 250))
    }

    @Test
    fun `parallelism is hard-capped at 16 and floored at 1`() {
        assertEquals(16, ScannerViewModel.adaptiveParallelism(24, 300), "cap 16")
        assertEquals(16, ScannerViewModel.adaptiveParallelism(24, 50), "cap applies regardless of size")
        assertEquals(1, ScannerViewModel.adaptiveParallelism(0, 50))
    }

    // ── estimateEtaSeconds ────────────────────────────────────────────────────

    @Test
    fun `eta scales linearly with rolling throughput`() {
        // 100 done in 60s → rate 1.667/s; 300 remaining → 180s.
        assertEquals(180L, ScannerViewModel.estimateEtaSeconds(100, 400, 60_000L))
        // 50 done in 10s → rate 5/s; 50 remaining → 10s.
        assertEquals(10L, ScannerViewModel.estimateEtaSeconds(50, 100, 10_000L))
    }

    @Test
    fun `eta is null until a rate exists and when nothing remains`() {
        assertNull(ScannerViewModel.estimateEtaSeconds(0, 400, 60_000L), "no completions → no rate")
        assertNull(ScannerViewModel.estimateEtaSeconds(100, 400, 0L), "no elapsed → no rate")
        assertNull(ScannerViewModel.estimateEtaSeconds(400, 400, 60_000L), "finished → null, not 0")
        assertNull(ScannerViewModel.estimateEtaSeconds(10, 0, 1_000L), "degenerate total")
        assertTrue(ScannerViewModel.estimateEtaSeconds(1, 400, 1_000L)!! > 0)
    }

    // ── ScanUniverseMode ──────────────────────────────────────────────────────

    @Test
    fun `universe mode labels map to phase-1 limits with ALL as null`() {
        assertEquals(20, ScanUniverseMode.TOP20.limit)
        assertEquals(50, ScanUniverseMode.TOP50.limit)
        assertEquals(100, ScanUniverseMode.TOP100.limit)
        assertEquals(250, ScanUniverseMode.TOP250.limit)
        assertEquals(null, ScanUniverseMode.ALL.limit)
    }

    @Test
    fun `fromLabel falls back to the TOP50 default for unknown labels`() {
        assertEquals(ScanUniverseMode.ALL, ScanUniverseMode.fromLabel("ALL"))
        assertEquals(ScanUniverseMode.TOP50, ScanUniverseMode.fromLabel("TOP50"))
        assertEquals(ScanUniverseMode.TOP50, ScanUniverseMode.fromLabel("nonsense"))
    }
}
