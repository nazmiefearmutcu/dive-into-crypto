package com.diveintocrypto.android.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.domain.evidence.EvidenceBucketStats
import com.diveintocrypto.android.domain.evidence.EvidenceGrade
import com.diveintocrypto.android.domain.evidence.EvidenceGrader
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/**
 * 24h market leaderboard — replaced the paper PnL history.
 *
 * Uses the ALL-ticker payload returned by `/fapi/v1/ticker/24hr`
 * (BinanceFuturesClient.ticker24hAll). Stablecoins ([SKIP_SYMBOLS]) are dropped,
 * USDT-paired symbols are kept. Then:
 *   - Gainers: `priceChangePercent` DESC top N
 *   - Losers: `priceChangePercent` ASC top N
 *   - Highest volume: `quoteVolume` DESC top N
 *
 * PLUS verdict-evidence self-grading (domain/evidence): the archived scanner
 * verdicts are graded against forward klines — hit-rate + median forward return
 * per verdict bucket and confidence band. Every statistic is rebuilt over the WHOLE
 * eligible archive on every pass (cumulative), never over the last pass alone.
 * Candle fetches are capped at [GRADE_SYMBOL_CAP] symbols per pass; records still
 * without an outcome keep the state honestly `stale` and are retried next pass.
 */
class PerformanceViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(PerformanceUiState())
    val ui: StateFlow<PerformanceUiState> = _ui.asStateFlow()

    private var evidenceJob: Job? = null

    /**
     * Final grading outcomes for this ViewModel's lifetime. A non-null value is a graded
     * forward return; a null value marks a record that no fetched candle history can
     * ever cover. Records whose fetch failed or whose horizon candle is not reached yet
     * are absent and retried. Only touched while holding [gradeLock].
     */
    private val settled = HashMap<GradeKey, Double?>()
    private val gradeLock = Mutex()

    init {
        // Watchlist rows read live prices from the all-market ticker engine
        // (one socket for every symbol); idempotent, app-scoped.
        container.liveTickerEngine.ensureStarted()
        load()
        refreshEvidence()
    }

    fun refresh() {
        _ui.update { it.copy(isLoading = true, error = null) }
        load()
    }

    private fun load() = viewModelScope.launch(Dispatchers.Default) {
        try {
            val all = container.repository.ticker24hAll()
                .filter { it.symbol.endsWith("USDT") && it.symbol !in BinanceFuturesClient.SKIP_SYMBOLS }

            val gainers = all.sortedByDescending { it.priceChangePercent }.take(TOP_N)
            val losers = all.sortedBy { it.priceChangePercent }.take(TOP_N)
            val byVolume = all.sortedByDescending { it.quoteVolume }.take(TOP_N)

            _ui.update {
                it.copy(
                    totalSymbols = all.size,
                    gainers = gainers,
                    losers = losers,
                    byVolume = byVolume,
                    isLoading = false,
                    error = null,
                    lastUpdateMs = nowMillis(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(isLoading = false, error = t.toMarketDataFailure()) }
        }
    }

    // ── VERDICT EVIDENCE (Android self-grading) ─────────────────────────────

    /**
     * Selects the grading horizon (hours: 1 / 4 / 24 supported by the UI). Outcomes are
     * keyed by horizon, so the statistics shown always belong to the selected horizon.
     */
    fun setEvidenceHorizon(hours: Long) {
        _ui.update { it.copy(evidence = it.evidence.copy(horizonHours = hours)) }
        refreshEvidence()
    }

    /**
     * Runs one grading pass (cancellable — a new call cancels the previous one).
     * Records older than `now − horizon` are eligible. For each record the forward
     * return runs from the verdict-time price to the close of the 1h candle covering
     * `ts + horizon` (see EvidenceGrader). Candles are fetched only for symbols with
     * unsettled records, capped at [GRADE_SYMBOL_CAP] per pass.
     */
    fun refreshEvidence() {
        evidenceJob?.cancel()
        evidenceJob = viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(evidence = it.evidence.copy(isGrading = true, error = null)) }
            try {
                gradeLock.withLock { runGradingPass() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(evidence = it.evidence.copy(isGrading = false, error = t.toMarketDataFailure()))
                }
            }
        }
    }

    /** One grading pass. Runs under [gradeLock]. */
    private suspend fun runGradingPass() {
        val store = container.evidenceStore
        val records = store.readAll()
        val horizonHours = _ui.value.evidence.horizonHours
        val horizonMs = horizonHours * 3_600_000L
        val cutoff = nowMillis() - horizonMs
        val eligible = records.filter { it.ts <= cutoff }

        // Forget outcomes of records that have been evicted from the archive ring.
        val live = eligible.mapTo(HashSet()) { EvidenceGradingPass.keyOf(it, horizonHours) }
        settled.keys.removeAll { it.horizonHours == horizonHours && it !in live }

        // Fetch only what is still unsettled, newest symbols first, capped per pass.
        val symbols = EvidenceGradingPass.symbolsToFetch(eligible, settled, horizonHours, GRADE_SYMBOL_CAP)
        if (symbols.isNotEmpty()) {
            val unsettledBySymbol = eligible
                .filter { it.symbol in symbols && EvidenceGradingPass.keyOf(it, horizonHours) !in settled }
                .groupBy { it.symbol }
            val gate = Semaphore(GRADE_PARALLELISM)
            // Each fetch returns its own result map; the shared cache is written only after
            // every fetch has finished, so no two coroutines ever mutate one collection.
            val results: List<Map<GradeKey, Double?>?> = coroutineScope {
                symbols.map { sym ->
                    async {
                        gate.withPermit {
                            val unsettled = unsettledBySymbol[sym].orEmpty()
                            // Enough 1h candles to reach the horizon candle of the OLDEST unsettled record.
                            val oldestTs = unsettled.minOfOrNull { it.ts } ?: nowMillis()
                            val candlesNeeded = ((nowMillis() - oldestTs) / 3_600_000L + 3L)
                                .coerceIn(48L, 1000L).toInt()
                            val candles = try {
                                container.repository.futuresHistory(sym, "1h", limit = candlesNeeded)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                // Fetch failed: no outcome is recorded, so these records are retried next pass.
                                null
                            }
                            if (candles.isNullOrEmpty()) {
                                null
                            } else {
                                val openTimes = candles.map { it.openTime }
                                val closes = candles.map { it.close }
                                val windowStart = openTimes.first()
                                unsettled.mapNotNull { rec ->
                                    val key = EvidenceGradingPass.keyOf(rec, horizonHours)
                                    val forward = EvidenceGrader.forwardReturnPct(
                                        openTimes, closes, rec.ts, rec.price, horizonMs, nowMillis(),
                                    )
                                    val entry: Pair<GradeKey, Double?>? = when {
                                        forward != null -> key to forward
                                        // Older than the fetched history, or archived without a usable
                                        // verdict-time price: no candle or base can ever grade it.
                                        rec.ts < windowStart || !(rec.price > 0.0) -> key to null
                                        // Horizon candle not closed yet: stays pending, retried next pass.
                                        else -> null
                                    }
                                    entry
                                }.toMap()
                            }
                        }
                    }
                }.awaitAll()
            }
            results.forEach { result -> if (result != null) settled.putAll(result) }
        }

        // Cumulative statistics over the whole eligible archive, not just this pass.
        val samples = EvidenceGradingPass.gradedSamples(eligible, settled, horizonHours)
        val grade = EvidenceGrader.grade(samples)
        val ece = EvidenceGrader.ece(samples)
        val brier = EvidenceGrader.brier(samples)
        val windows = EvidenceGrader.gradeWindows(samples, nowMillis())
        val newest = EvidenceGradingPass.newestGradedTs(eligible, settled, horizonHours)
        val pending = EvidenceGradingPass.pendingCount(eligible, settled, horizonHours)
        store.setLastGradedMs(newest ?: 0L)

        _ui.update {
            it.copy(
                evidence = EvidenceState(
                    archived = records.size,
                    graded = grade.graded,
                    byVerdict = grade.byVerdict,
                    byConfidence = grade.byConfidence,
                    ece = ece.ece,
                    eceBins = ece.bins,
                    brier = brier?.brier,
                    brierSkill = brier?.skill,
                    windows = windows,
                    stale = pending > 0,
                    isGrading = false,
                    horizonHours = horizonHours,
                    lastGradedMs = newest,
                    error = null,
                ),
            )
        }
    }

    companion object {
        const val TOP_N = 10

        /** Distinct symbols whose candles are fetched per pass (spec cap: 40). */
        const val GRADE_SYMBOL_CAP = 40

        /** Concurrent kline fetches during grading (engine caches absorb repeats). */
        const val GRADE_PARALLELISM = 4
    }
}

/**
 * Verdict-evidence self-audit state (v2).
 *
 * @param archived total records in the JSONL ring
 * @param graded records with a final outcome in the cumulative statistics
 * @param byVerdict hit-rate/median-forward-return per verdict (BUY/SELL/…);
 *                  v2 buckets carry Wilson 95% bounds + the small-sample gate
 * @param byConfidence same per confidence band (0-25 / 26-50 / 51-75 / 76-100)
 * @param ece Expected Calibration Error over fixed 10-bin confidence bands; null = no graded samples
 * @param eceBins the 10-bin reliability table behind [ece] (empty bins included)
 * @param brier Brier score of stated confidences vs 0/1 outcomes; null = no samples
 * @param brierSkill Brier Skill Score vs the constant base-rate forecast (>0 = skill); null when degenerate
 * @param windows rolling-window grades keyed "7d"/"30d"/"all"
 * @param stale true when archived records older than the horizon still have NO outcome
 *              (failed fetches, horizon not reached, or capped pass) — honest lag signal
 * @param error classified failure of the last grading pass; null when it completed
 */
data class EvidenceState(
    val archived: Int = 0,
    val graded: Int = 0,
    val byVerdict: Map<String, EvidenceBucketStats> = emptyMap(),
    val byConfidence: Map<String, EvidenceBucketStats> = emptyMap(),
    val ece: Double? = null,
    val eceBins: List<EvidenceGrader.CalibrationBin> = emptyList(),
    val brier: Double? = null,
    val brierSkill: Double? = null,
    val windows: Map<String, EvidenceGrade> = emptyMap(),
    val stale: Boolean = false,
    val isGrading: Boolean = false,
    val horizonHours: Long = 4,
    val lastGradedMs: Long? = null,
    val error: MarketDataFailure? = null,
)

data class PerformanceUiState(
    val totalSymbols: Int = 0,
    val gainers: List<Ticker24h> = emptyList(),
    val losers: List<Ticker24h> = emptyList(),
    val byVolume: List<Ticker24h> = emptyList(),
    val isLoading: Boolean = true,
    val error: MarketDataFailure? = null,
    val lastUpdateMs: Long? = null,
    /** ADDITIVE: verdict-evidence self-grading state (domain/evidence). */
    val evidence: EvidenceState = EvidenceState(),
)
