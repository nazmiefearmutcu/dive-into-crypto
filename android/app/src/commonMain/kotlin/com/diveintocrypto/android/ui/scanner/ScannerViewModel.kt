package com.diveintocrypto.android.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.divergence.DivergenceAlignment
import com.diveintocrypto.android.domain.divergence.WhaleDivergence
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.beginBackgroundTask
import com.diveintocrypto.android.platform.endBackgroundTask
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/**
 * Multi-TF universe scanner — port of the original Python reference
 * implementation (`_run_auto_scan` and its companion `_process_multi_scan_results`).
 *
 * Algorithm (verbatim semantics from the Python source):
 *
 *   1. Pull the full Binance USDT-M futures universe sorted by 24h quoteVolume,
 *      stablecoins removed (see [com.diveintocrypto.android.data.binance.BinanceFuturesClient]).
 *
 *   2. **Phase 1** — scan the high-time-weight timeframes [1d, 12h, 8h] across EVERY
 *      symbol in the universe. After phase 1 completes, aggregate each symbol's
 *      `finalScore` total and keep the top [PHASE2_TOP_N] (50 by default).
 *
 *   3. **Phase 2** — scan the remaining 9 lower-time-weight timeframes
 *      [1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h] but ONLY across the phase-2
 *      survivors. This is the optimization that lets the original scanner scan
 *      12 timeframes in a few minutes instead of 578 × 12 = 6936 individual
 *      kline requests.
 *
 *   4. **Per-TF top15** — for each timeframe, sort results by
 *      `finalScore = confidence² × timeWeight / 100` desc and keep the top 15.
 *
 *   5. **Cross-rank** — for every symbol that appears in any TF's top15,
 *      compute `buy_nss = Σ finalScore where signal ∈ {BUY, STRONG_BUY}`,
 *      `sell_nss = Σ finalScore where signal ∈ {SELL, STRONG_SELL}`. Dominant
 *      direction is whichever is larger; `net_nss = max - min`. Sort symbols
 *      by `net_nss` desc.
 *
 *   6. **Fill in non-top15 TFs for the displayed top-N** — so every CoinResultCard
 *      shows 12 real per-TF cells (not 11 placeholders).
 */
class ScannerViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(ScannerUiState())
    val ui: StateFlow<ScannerUiState> = _ui.asStateFlow()

    /** Toggle CONTINUOUS (back-to-back) scanning. When on, a new scan starts as soon as one finishes. */
    fun setContinuous(on: Boolean) { _ui.update { it.copy(continuous = on) } }

    /** Table size (how many surviving coins to show): 5/10/15/20 or Int.MAX_VALUE = All.
     *  No re-scan needed — the screen takes this many from [ScannerUiState.survivors]. */
    fun setDisplaySize(n: Int) { _ui.update { it.copy(displaySize = n) } }

    /** Toggle the OPT-IN "Divergence Sort" lens (default OFF).
     *  Triggers ONLY a presentation re-sort — the SAME survivor pool is sorted by |divergenceScore|
     *  desc (surfacing the cross-sectional top-K validated by backtest). The signal/score/
     *  elimination/candidate-pool are UNCHANGED; when OFF, behavior is bit-for-bit identical to today. */
    fun setDivergenceSort(on: Boolean) { _ui.update { it.copy(divergenceSort = on) } }

    fun startDemoScan() {
        if (_ui.value.scanning) return
        // A new scan does NOT clear previous results — they stay on screen until new ones arrive.
        // (Only progress/error are reset; feed/survivors/eliminated are kept.)
        _ui.update {
            it.copy(
                scanning = true,
                stopRequested = false,
                cycle = 0,
                completedCount = 0,
                totalCount = 0,
                currentSymbol = null,
                currentPhase = ScanPhase.UNIVERSE,
                error = null,
                universeLoading = true,
            )
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var ok: Boolean
            do {
                // Best-effort: if the app goes to the background during a scan cycle,
                // request a short extra execution window from iOS (≈30s). No-op on Android.
                val bgToken = beginBackgroundTask("dive-scan")
                try {
                    ok = runOneScan()
                } finally {
                    endBackgroundTask(bgToken)
                }
                if (ok) _ui.update { it.copy(cycle = it.cycle + 1) }
            } while (ok && _ui.value.continuous && !_ui.value.stopRequested)
            _ui.update {
                it.copy(
                    scanning = false,
                    stopRequested = false,
                    currentPhase = ScanPhase.IDLE,
                    currentSymbol = null,
                )
            }
        }
    }

    fun stopDemoScan() {
        _ui.update { it.copy(stopRequested = true) }
    }

    /** Is the whale divergence OPPOSITE the indicator direction? (adverse = will be eliminated)
     *  Indicator BUY but divergence bearish (distribution) → adverse; Indicator SELL but divergence
     *  bullish (accumulation) → adverse. Sub-threshold divergence or no direction → not adverse. */
    private fun isAdverse(row: CrossRankingRow): Boolean {
        if (abs(row.divergenceScore) < DIVERGENCE_MIN_SHOWN) return false
        val dir = when (row.dominantDir) {
            Signal.BUY, Signal.STRONG_BUY -> 1
            Signal.SELL, Signal.STRONG_SELL -> -1
            else -> 0
        }
        if (dir == 0) return false
        return row.divergenceDirection == -dir
    }

    /** Fills missing (non-top15) TF cells for a displayed row from the full results. */
    private fun patchPerTf(row: CrossRankingRow, tfResults: Map<String, List<SymbolTfResult>>): CrossRankingRow {
        val patched = row.perTf.toMutableMap()
        for (tf in ALL_TFS) {
            if (tf in patched) continue
            val weight = TIME_WEIGHTS[tf] ?: 50
            val rr = tfResults[tf]?.firstOrNull { it.symbol == row.symbol }
            if (rr != null) {
                patched[tf] = TfSlotState(rr.signal, rr.confidence, weight, rr.finalScore, false)
            }
        }
        return row.copy(perTf = patched.toMap())
    }

    /** Runs one FULL universe scan. true = completed, false = stopped/error. */
    private suspend fun runOneScan(): Boolean {
        // Reset progress but keep previous results on screen (so continuous mode doesn't flicker).
        _ui.update {
            it.copy(
                completedCount = 0,
                totalCount = 0,
                currentSymbol = null,
                currentPhase = ScanPhase.UNIVERSE,
                universeLoading = true,
                error = null,
            )
        }
        run {
            val universe = try {
                container.repository.futuresUniverse()
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        universeLoading = false,
                        currentPhase = ScanPhase.IDLE,
                        error = "Failed to fetch symbol list: ${t.message ?: "network error"}",
                    )
                }
                return false
            }
            if (universe.isEmpty()) {
                _ui.update {
                    it.copy(
                        universeLoading = false,
                        currentPhase = ScanPhase.IDLE,
                        error = "Binance futures symbol list returned empty",
                    )
                }
                return false
            }

            val settings = container.settingsStore.getSettings()
            val totalTfScans = universe.size * PHASE1_TFS.size + settings.scanSurvivors * PHASE2_TFS.size
            _ui.update {
                it.copy(
                    universeLoading = false,
                    universeSize = universe.size,
                    totalCount = totalTfScans,
                    currentPhase = ScanPhase.PHASE1,
                )
            }

            val sem = Semaphore(settings.scanParallelism)
            val mutex = Mutex()
            val tfResults: MutableMap<String, MutableList<SymbolTfResult>> = mutableMapOf()
            var completed = 0

            suspend fun scanOne(symbol: String, tf: String) {
                if (_ui.value.stopRequested) return
                sem.withPermit {
                    if (_ui.value.stopRequested) return@withPermit
                    _ui.update { it.copy(currentSymbol = symbol) }
                    val candles = try {
                        container.repository.futuresHistory(symbol, tf, limit = 300)
                    } catch (_: Throwable) { emptyList() }

                    if (candles.size >= 50) {
                        val indicatorOuts = container.indicators.map { it.calculate(candles) }
                        val out = container.consensus.evaluate(indicatorOuts)
                        val price = candles.last().close
                        val weight = TIME_WEIGHTS[tf] ?: 50
                        val finalScore = (out.confidence.toDouble().let { it * it } * weight) / 100.0
                        // DISPLAY-ONLY: read the ATR% that the indicator layer ALREADY computed
                        // (atr_filter → atr_pct). No new computation; it never enters the score.
                        val atrPct = indicatorOuts
                            .firstOrNull { it.name == "atr_filter" }
                            ?.rawValues?.get("atr_pct")

                        mutex.withLock {
                            tfResults.getOrPut(tf) { mutableListOf() }.add(
                                SymbolTfResult(
                                    symbol = symbol,
                                    tf = tf,
                                    signal = out.finalSignal,
                                    confidence = out.confidence,
                                    price = price,
                                    timeWeight = weight,
                                    finalScore = finalScore,
                                    atrPct = atrPct,
                                ),
                            )
                        }
                    }
                    mutex.withLock {
                        completed += 1
                        val snap = completed
                        _ui.update { it.copy(completedCount = snap) }
                    }
                }
            }

            // ── Phase 1: all universe on [1d, 12h, 8h] ────────────────────────
            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
                for (tf in PHASE1_TFS) {
                    for (sym in universe) {
                        jobs.add(async { scanOne(sym, tf) })
                    }
                }
                jobs.awaitAll()
            }

            if (_ui.value.stopRequested) return false

            // ── Determine phase-2 survivors: top PHASE2_TOP_N by Σ phase-1 finalScore
            val p1Scores = mutableMapOf<String, Double>()
            for (tf in PHASE1_TFS) {
                for (r in tfResults[tf] ?: emptyList()) {
                    p1Scores[r.symbol] = (p1Scores[r.symbol] ?: 0.0) + r.finalScore
                }
            }
            val survivors = p1Scores.entries
                .sortedByDescending { it.value }
                .take(settings.scanSurvivors)
                .map { it.key }

            _ui.update { it.copy(currentPhase = ScanPhase.PHASE2) }

            // ── Phase 2: survivors only on remaining 9 TFs ───────────────────
            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
                for (tf in PHASE2_TFS) {
                    for (sym in survivors) {
                        jobs.add(async { scanOne(sym, tf) })
                    }
                }
                jobs.awaitAll()
            }

            if (_ui.value.stopRequested) return false

            _ui.update { it.copy(currentPhase = ScanPhase.FINALIZING) }

            // ── Per-TF top15 by finalScore desc ──────────────────────────────
            val tfTop15: Map<String, List<SymbolTfResult>> = ALL_TFS.associateWith { tf ->
                (tfResults[tf] ?: emptyList())
                    .sortedByDescending { it.finalScore }
                    .take(15)
            }

            // ── Cross-rank: union of symbols across all TF top15 lists ───────
            data class Stats(
                var count: Int = 0,
                var buyNss: Double = 0.0,
                var sellNss: Double = 0.0,
                var bestConf: Int = 0,
                var price: Double = 0.0,
                // DISPLAY-ONLY: the best-confidence TF's ATR% + the 1h TF's ATR% (preferred
                // when present — spec hint). Only feeds the RISK advisor row.
                var atrPctBest: Double? = null,
                var atrPct1h: Double? = null,
                val perTf: MutableMap<String, TfSlotState> = mutableMapOf(),
            )

            val symbolStats: MutableMap<String, Stats> = mutableMapOf()
            for (tf in ALL_TFS) {
                val weight = TIME_WEIGHTS[tf] ?: 50
                for (r in tfTop15[tf] ?: emptyList()) {
                    val s = symbolStats.getOrPut(r.symbol) { Stats() }
                    s.count += 1
                    when (r.signal) {
                        Signal.STRONG_BUY, Signal.BUY -> s.buyNss += r.finalScore
                        Signal.STRONG_SELL, Signal.SELL -> s.sellNss += r.finalScore
                        else -> Unit  // NEUTRAL contributes nothing
                    }
                    if (r.confidence > s.bestConf) {
                        s.bestConf = r.confidence
                        s.price = r.price
                        s.atrPctBest = r.atrPct
                    }
                    if (tf == "1h" && r.atrPct != null) s.atrPct1h = r.atrPct
                    s.perTf[tf] = TfSlotState(
                        signal = r.signal,
                        confidence = r.confidence,
                        timeWeight = weight,
                        finalScore = r.finalScore,
                        inTop15 = true,
                    )
                }
            }

            // Build rows + sort by net_nss desc
            val crossRows: List<CrossRankingRow> = symbolStats.entries
                .map { (sym, s) ->
                    val (dominantDir, netNss) = if (s.buyNss >= s.sellNss) {
                        Signal.BUY to (s.buyNss - s.sellNss)
                    } else {
                        Signal.SELL to (s.sellNss - s.buyNss)
                    }
                    CrossRankingRow(
                        symbol = sym,
                        dominantDir = dominantDir,
                        netNss = netNss,
                        countHit = s.count,
                        totalTfs = ALL_TFS.size,
                        perTf = s.perTf.toMap(),
                        price = s.price,
                        // Prefer 1h ATR% when present (spec hint); otherwise best-confidence TF ATR%.
                        atrPct = s.atrPct1h ?: s.atrPctBest,
                    )
                }
                .sortedByDescending { it.netNss }

            // ── Whale L/S divergence: compute for the candidate pool (first DIVERGENCE_CANDIDATES,
            //    by netNss); then blend the table order with netNss. Since L/S data is expensive,
            //    it is fetched only for the candidates, not the whole universe.
            // Candidate pool = top-N by netNss ∪ high-TF (1d/12h) leaders. High-TF leaders are the
            // symbols most likely to have experienced a price rally; even when their netNss is low,
            // divergence is computed → this closes the low-netNss blind spot (reviewer P1).
            val highTfLeaders = (tfTop15["1d"].orEmpty() + tfTop15["12h"].orEmpty())
                .map { it.symbol }.toSet()
            val candidateSymbols =
                crossRows.take(DIVERGENCE_CANDIDATES).map { it.symbol }.toSet() + highTfLeaders
            val candidates = crossRows.filter { it.symbol in candidateSymbols }
            // value = (result, usable-TF count) — for coverage visibility.
            val divMap = mutableMapOf<String, Pair<WhaleDivergence.SymbolResult, Int>>()
            // A SEPARATE semaphore for the divergence phase — we don't tie up the scan sem. (P0 fix:
            // 18 sequential network calls under a single permit serialized the phase.) All
            // candidate × TF requests share this gate → real bounded parallelism.
            val divGate = Semaphore(settings.scanParallelism)
            coroutineScope {
                candidates.map { row ->
                    async {
                        if (_ui.value.stopRequested) return@async
                        _ui.update { it.copy(currentSymbol = row.symbol) }
                        val res = computeDivergence(row.symbol, divGate)
                        mutex.withLock { divMap[row.symbol] = res }
                    }
                }.awaitAll()
            }
            val maxNet = crossRows.maxOfOrNull { it.netNss }?.takeIf { it > 0.0 } ?: 1.0
            // AIRTIGHT COVERAGE (reviewer P0): ranking/elimination/display is done ONLY over coins
            // whose whale divergence was COMPUTED (= candidates). Previously rankedRows came from
            // all crossRows; post-elimination backfill could push a lower-ranked coin whose
            // divergence was NEVER computed (score=0) into the visible top-5 → "the bot put it in
            // the top 5 without detecting it." Now every visible coin is guaranteed divergence-scanned.
            val rankedRows = candidates
                .map { row ->
                    val d = divMap[row.symbol]
                    row.copy(
                        divergenceScore = d?.first?.score ?: 0.0,
                        divergenceTf = d?.first?.bestTf,
                        divergenceRisePct = d?.first?.bestRisePct ?: 0.0,
                        divergenceWhaleDrop = d?.first?.bestWhaleDrop ?: 0.0,
                        // -1 = was not a candidate (never tried); 0 = tried but no whale data.
                        divergenceCoverage = d?.second ?: -1,
                        divergenceDirection = d?.first?.direction ?: 0,
                        divergencePatternDirection = d?.first?.patternDirection ?: 0,
                    )
                }
                .sortedByDescending { row ->
                    val netNorm = (row.netNss / maxNet).coerceIn(0.0, 1.0)
                    // DIRECTIONAL divergence: the signed score shifts ranking directly —
                    // bullish (+, accumulation) pushes up, bearish (−, distribution) pushes DOWN (negative score).
                    // Sub-threshold |score| is treated as noise.
                    val sig = if (abs(row.divergenceScore) >= DIVERGENCE_MIN_SHOWN)
                        (row.divergenceScore / 100.0).coerceIn(-1.0, 1.0) else 0.0
                    netNorm + DIVERGENCE_RANK_WEIGHT * sig
                }

            // ── ELIMINATION + BACKFILL: eliminate coins whose whale divergence runs OPPOSITE
            //    the indicator direction; fill the vacated slots with the next (lower-scoring)
            //    eligible coins (filter-then-take = automatic backfill). ───────────────
            val survivorsRaw = rankedRows.filterNot { isAdverse(it) }
            val eliminatedRows = rankedRows.filter { isAdverse(it) }
            // Fill the survivors' TF cells (first 24 for cost — table max 20).
            val survivorRows = survivorsRaw.mapIndexed { i, row ->
                if (i < 24) patchPerTf(row, tfResults) else row
            }
            val elimRows = eliminatedRows.map { patchPerTf(it, tfResults) }

            val size = _ui.value.displaySize
            val finalHotList: List<ScannerFeedItem> = survivorRows
                .take(size.coerceAtMost(survivorRows.size))
                .mapIndexed { i, row ->
                    ScannerFeedItem(
                        symbol = row.symbol,
                        rank = i + 1,
                        signal = row.dominantDir,
                        confidence = row.perTf.values.maxOfOrNull { it.confidence } ?: 0,
                        price = row.price,
                        riskLevel = riskFromCount(row.countHit, row.totalTfs),
                        error = null,
                    )
                }

            _ui.update {
                it.copy(
                    currentPhase = ScanPhase.IDLE,
                    currentSymbol = null,
                    feed = finalHotList,
                    hotList = finalHotList,
                    crossRanking = survivorRows,
                    survivors = survivorRows,
                    eliminated = elimRows,
                    lastScanAtMs = nowMillis(),
                )
            }
        }
        return true
    }

    fun selectTimeframe(tf: String) {
        _ui.update { it.copy(activeTimeframe = tf) }
    }

    private fun riskFromCount(countHit: Int, total: Int): String = when {
        countHit >= total - 1 -> "LOW"
        countHit >= total / 2 -> "MEDIUM"
        countHit > 0 -> "HIGH"
        else -> "N/A"
    }

    /**
     * Computes a symbol's whale L/S bearish divergence across the 9 supported TFs.
     * For each TF, price (close) + whale L/S (topLongShortPositionRatio) are fetched,
     * aligned to equal length from the tail, and fed to [WhaleDivergence.perTf];
     * then reduced to a single score with [WhaleDivergence.forSymbol].
     * A network error / insufficient data silently zeroes that TF (graceful degrade).
     */
    /** @return (divergence result, usable TF count 0..9). Coverage=0 →
     *  no whale data could be fetched (throttle/none); a score of 0 here is DISTINCT from "clean". */
    private suspend fun computeDivergence(
        symbol: String,
        gate: Semaphore,
    ): Pair<WhaleDivergence.SymbolResult, Int> = coroutineScope {
        val perTf = DIVERGENCE_TFS.map { tf ->
            async {
                if (_ui.value.stopRequested) return@async null
                gate.withPermit {
                    val candles = try {
                        container.repository.futuresHistory(symbol, tf, limit = 200)
                    } catch (_: Throwable) { emptyList() }
                    if (candles.size < 25) return@withPermit null
                    val ls = try {
                        container.repository.topLongShortPositionRatio(symbol, tf, limit = 200)
                    } catch (_: Throwable) { emptyList() }
                    if (ls.size < 25) return@withPermit null

                    // Timestamped alignment (pure [DivergenceAlignment]): matches whale L/S to the
                    // price candle by period bucket, forward-filling gaps. (Tail alignment would
                    // shift a single dropped 1d L/S bar by a FULL DAY.)
                    val periodMs = PERIOD_MS[tf] ?: return@withPermit null
                    val aligned = DivergenceAlignment.align(
                        priceTimes = candles.map { it.openTime },
                        priceVals = candles.map { it.close },
                        lsTimes = ls.map { it.timestamp },
                        lsVals = ls.map { it.longShortRatio },
                        periodMs = periodMs,
                    )
                    if (aligned.price.size < 25) return@withPermit null
                    // If more than half of the aligned window is forward-filled (sparse/gappy L/S,
                    // e.g. a short 1d L/S history spread over a long price window) → the peak
                    // flattens artificially, causing silent under-detection → skip this TF.
                    if (aligned.matched < aligned.price.size / 2) return@withPermit null
                    tf to WhaleDivergence.perTf(aligned.price, aligned.whale, TIME_WEIGHTS[tf] ?: 50)
                }
            }
        }.awaitAll().filterNotNull().toMap()
        WhaleDivergence.forSymbol(perTf) to perTf.size
    }

    companion object {
        const val MAX_PARALLEL = 8
        const val PHASE2_TOP_N = 50
        const val FINAL_TOP_N = 5

        /** Phase 1 covers the high-time-weight timeframes that filter the universe fast. */
        val PHASE1_TFS: List<String> = listOf("1d", "12h", "8h")

        /** Phase 2 covers the rest — only run against phase-1 survivors. */
        val PHASE2_TFS: List<String> = listOf(
            "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h",
        )

        /** Display order is chronological (lowest TF to highest). */
        val ALL_TFS: List<String> = listOf(
            "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d",
        )

        /** Backwards-compat alias used by the UI's ScannerUiState default. */
        val DEFAULT_TFS: List<String> = ALL_TFS

        /**
         * Verbatim from bot_service.py:106-110 (`self._ZAK`).
         * Higher timeframe = higher weight, mirroring the trader-intuition that
         * a 1d-confirmed signal should outweigh a 1m one.
         */
        val TIME_WEIGHTS: Map<String, Int> = mapOf(
            "1d" to 95, "12h" to 90, "8h" to 85, "6h" to 80, "4h" to 75,
            "2h" to 65, "1h" to 58, "30m" to 48, "15m" to 38, "5m" to 25,
            "3m" to 15, "1m" to 8,
        )

        // ── Whale L/S divergence (Bearish Whale Divergence) ──────────────
        /** Binance futures-data endpoints only support these 9 periods (no 1m, 3m, 8h).
         *  Divergence is computed only on these TFs; the others are 0. */
        val DIVERGENCE_TFS: List<String> = listOf(
            "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d",
        )
        /** Divergence is computed only for the top netNss candidates (rate-limit). This pool is
         *  ALSO the upper bound on all visible/eliminable coins (rankedRows = candidates),
         *  so it must comfortably cover the largest table size (20) + the elimination/backfill margin. */
        const val DIVERGENCE_CANDIDATES = 40
        /** Weight of divergence in the table ranking (0..1). Blended with netNss. */
        const val DIVERGENCE_RANK_WEIGHT = 0.35
        /** Divergence scores below this threshold are treated as "none" (noise filter). */
        const val DIVERGENCE_MIN_SHOWN = 5.0
        /** TF → period duration (ms). Used to align the price and whale-L/S series by timestamp
         *  (tail alignment would turn a single dropped bar at 1d into a full-day shift). */
        val PERIOD_MS: Map<String, Long> = mapOf(
            "1m" to 60_000L, "3m" to 180_000L, "5m" to 300_000L, "15m" to 900_000L,
            "30m" to 1_800_000L, "1h" to 3_600_000L, "2h" to 7_200_000L,
            "4h" to 14_400_000L, "6h" to 21_600_000L, "8h" to 28_800_000L,
            "12h" to 43_200_000L, "1d" to 86_400_000L,
        )
    }
}

/** A single (symbol, TF) analysis result. */
data class SymbolTfResult(
    val symbol: String,
    val tf: String,
    val signal: Signal,
    val confidence: Int,
    val price: Double,
    val timeWeight: Int,
    val finalScore: Double,
    /** ATR(14) as a price-% from the indicator layer (`atr_filter` rawValue `atr_pct`).
     *  DISPLAY-ONLY: only feeds the RISK advisor row — it does NOT affect score/elimination/
     *  ranking (finalScore is confidence²×timeWeight; this field never enters that). */
    val atrPct: Double? = null,
)

/** A row in the scanner feed/hot list (style.css:661-688). */
data class ScannerFeedItem(
    val symbol: String,
    val rank: Int,
    val signal: Signal,
    val confidence: Int,
    val price: Double,
    val riskLevel: String,
    val error: String?,
)

/** Per-TF state inside one cross-ranking row. */
data class TfSlotState(
    val signal: Signal,
    val confidence: Int,
    val timeWeight: Int = 50,
    val finalScore: Double = 0.0,
    val inTop15: Boolean = false,
)

/** A row in the cross-timeframe ranking (style.css:976-1047). */
data class CrossRankingRow(
    val symbol: String,
    val dominantDir: Signal,
    val netNss: Double,
    val countHit: Int,
    val totalTfs: Int,
    val perTf: Map<String, TfSlotState>,
    val price: Double,
    /** ATR(14) price-% (from the indicator layer, 1h preferred). DISPLAY-ONLY: only feeds
     *  the RISK advisor row; it never enters score/elimination/ranking. null = no data. */
    val atrPct: Double? = null,
    /** Whale L/S bearish divergence interest score (0..100). 0 = none. */
    val divergenceScore: Double = 0.0,
    /** The strongest timeframe that produced the divergence (e.g. "1d"), or null. */
    val divergenceTf: String? = null,
    /** Price rise ratio at the strongest TF (0..1). Shown in the UI as "Price +X%". */
    val divergenceRisePct: Double = 0.0,
    /** Raw whale drop ratio at the strongest TF (0..1). Shown in the UI as "Whale −Y%". */
    val divergenceWhaleDrop: Double = 0.0,
    /** Number of usable whale-L/S TFs: -1 = was not a candidate, 0 = no data, 1..9. */
    val divergenceCoverage: Int = -1,
    /** Divergence PREDICTIVE/RANKING direction: -1 expect down, +1 expect up, 0 none. */
    val divergenceDirection: Int = 0,
    /** Divergence DESCRIPTIVE raw whale movement: -1 distribution (sell), +1 accumulation (buy), 0 none.
     *  The label/color/arrow is driven by this (NOT sign(score)); independent of the contrarian flip. */
    val divergencePatternDirection: Int = 0,
)

/** Coarse status for the progress UI. */
enum class ScanPhase { IDLE, UNIVERSE, PHASE1, PHASE2, FINALIZING }

data class ScannerUiState(
    val timeframes: List<String> = ScannerViewModel.DEFAULT_TFS,
    val activeTimeframe: String = "15m",
    val feed: List<ScannerFeedItem> = emptyList(),
    val hotList: List<ScannerFeedItem> = emptyList(),
    val crossRanking: List<CrossRankingRow> = emptyList(),
    /** Survivors after elimination (coins with adverse whale divergence were removed, backfilled). */
    val survivors: List<CrossRankingRow> = emptyList(),
    /** Coins eliminated because their whale divergence ran opposite the indicator. */
    val eliminated: List<CrossRankingRow> = emptyList(),
    /** Whether CONTINUOUS (back-to-back) scanning is on. */
    val continuous: Boolean = false,
    /** Table size: number of survivors to show (5/10/15/20 or Int.MAX_VALUE=All). */
    val displaySize: Int = 5,
    /** OPT-IN presentation lens: when ON, the visible survivors are sorted by |divergenceScore| desc
     *  (sub-threshold rows kept at the bottom in default order). OFF (default) =
     *  today's netNss + 0.35×div ordering. PRESENTATION only; score/elimination are UNCHANGED. */
    val divergenceSort: Boolean = false,
    /** Number of completed scan cycles (continuous mode). */
    val cycle: Int = 0,
    val scanning: Boolean = false,
    val stopRequested: Boolean = false,
    val currentSymbol: String? = null,
    val currentPhase: ScanPhase = ScanPhase.IDLE,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    /** Universe size (full Binance USDT-M futures set after stablecoin filter). */
    val universeSize: Int = 0,
    val universeLoading: Boolean = false,
    val lastScanAtMs: Long? = null,
    val error: String? = null,
)
