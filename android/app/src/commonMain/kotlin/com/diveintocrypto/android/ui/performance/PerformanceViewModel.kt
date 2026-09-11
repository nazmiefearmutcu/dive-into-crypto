package com.diveintocrypto.android.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.domain.evidence.EvidenceBucketStats
import com.diveintocrypto.android.domain.evidence.EvidenceGrader
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
 * per verdict bucket and confidence band. Grading runs inside viewModelScope
 * ([refreshEvidence] is public + cancellable), is capped at [GRADE_SYMBOL_CAP]
 * symbols per pass and resumable via the persisted last-graded timestamp.
 */
class PerformanceViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(PerformanceUiState())
    val ui: StateFlow<PerformanceUiState> = _ui.asStateFlow()

    private var evidenceJob: kotlinx.coroutines.Job? = null

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

    private fun load() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
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
        } catch (t: Throwable) {
            _ui.update { it.copy(isLoading = false, error = t.message ?: "network error") }
        }
    }

    // ── VERDICT EVIDENCE (Android self-grading) ─────────────────────────────

    /**
     * Selects the grading horizon (hours: 1 / 4 / 24 supported by the UI).
     * Changing the horizon invalidates the resume cursor so the next pass
     * re-grades from the archive start under the new horizon.
     */
    fun setEvidenceHorizon(hours: Long) {
        _ui.update { it.copy(evidence = it.evidence.copy(horizonHours = hours)) }
        container.evidenceStore.setLastGradedMs(0L)
        refreshEvidence()
    }

    /**
     * Runs one grading pass (cancellable — a new call cancels the previous one).
     * Records older than `now − horizon` with `ts > lastGradedMs` are graded:
     * for each record the forward return is taken from the engine's (cached)
     * 1h klines — newest close vs. the close of the candle covering the record.
     * Bounded to [GRADE_SYMBOL_CAP] distinct symbols per pass; anything left
     * over keeps the state honestly `stale` and is picked up next pass.
     */
    fun refreshEvidence() {
        evidenceJob?.cancel()
        evidenceJob = viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(evidence = it.evidence.copy(isGrading = true, error = null)) }
            try {
                val store = container.evidenceStore
                val records = store.readAll()
                val horizonHours = _ui.value.evidence.horizonHours
                val horizonMs = horizonHours * 3_600_000L
                val cutoff = nowMillis() - horizonMs
                val lastGraded = store.lastGradedMs()

                val pending = records.filter { it.ts <= cutoff && it.ts > lastGraded }
                if (pending.isEmpty()) {
                    _ui.update {
                        it.copy(
                            evidence = it.evidence.copy(
                                archived = records.size,
                                stale = false,
                                isGrading = false,
                                lastGradedMs = lastGraded.takeIf { lg -> lg > 0L },
                            ),
                        )
                    }
                    return@launch
                }

                // Cap symbols per pass (spec: 40); the rest stay stale → next pass.
                val symbols = pending.map { it.symbol }.distinct().take(GRADE_SYMBOL_CAP)
                val bySymbol = pending.filter { it.symbol in symbols }.groupBy { it.symbol }

                val graded = mutableListOf<Pair<com.diveintocrypto.android.domain.evidence.VerdictRecord, Double>>()
                val gate = Semaphore(GRADE_PARALLELISM)
                coroutineScope {
                    bySymbol.keys.map { sym ->
                        async {
                            gate.withPermit {
                                val candles = try {
                                    container.repository.futuresHistory(sym, "1h", limit = 48)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    emptyList()
                                }
                                if (candles.isEmpty()) return@withPermit
                                val openTimes = candles.map { it.openTime }
                                val closes = candles.map { it.close }
                                for (rec in bySymbol[sym].orEmpty()) {
                                    val fwd = EvidenceGrader.forwardReturnPct(openTimes, closes, rec.ts)
                                        ?: continue
                                    graded += rec to fwd
                                }
                            }
                        }
                    }.awaitAll()
                }

                val grade = EvidenceGrader.grade(
                    graded.map { (rec, fwd) ->
                        EvidenceGrader.GradedSample(
                            verdict = rec.verdict,
                            confidence = rec.confidence,
                            dominantDir = rec.dominantDir,
                            forwardReturnPct = fwd,
                        )
                    },
                )

                // Resume cursor = newest graded record (only successfully graded
                // ones advance it — failed symbols stay stale and are retried).
                val maxGradedTs = graded.maxOfOrNull { it.first.ts } ?: lastGraded
                val newCursor = maxOf(lastGraded, maxGradedTs)
                store.setLastGradedMs(newCursor)
                val stillStale = records.any { it.ts <= cutoff && it.ts > newCursor }

                _ui.update {
                    it.copy(
                        evidence = EvidenceState(
                            archived = records.size,
                            graded = grade.graded,
                            byVerdict = grade.byVerdict,
                            byConfidence = grade.byConfidence,
                            stale = stillStale,
                            isGrading = false,
                            horizonHours = horizonHours,
                            lastGradedMs = newCursor.takeIf { c -> c > 0L },
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(evidence = it.evidence.copy(isGrading = false, error = t.message ?: "grading failed"))
                }
            }
        }
    }

    companion object {
        const val TOP_N = 10

        /** Distinct symbols graded per pass (spec cap: 40). */
        const val GRADE_SYMBOL_CAP = 40

        /** Concurrent kline fetches during grading (engine caches absorb repeats). */
        const val GRADE_PARALLELISM = 4
    }
}

/**
 * Verdict-evidence self-audit state.
 *
 * @param archived total records in the JSONL ring
 * @param graded records graded in the latest pass
 * @param byVerdict hit-rate/median-forward-return per verdict (BUY/SELL/…)
 * @param byConfidence same per confidence band (0-25 / 26-50 / 51-75 / 76-100)
 * @param stale true when archived records older than the horizon are NOT yet
 *              graded (capped pass or failed fetches) — honest lag signal
 */
data class EvidenceState(
    val archived: Int = 0,
    val graded: Int = 0,
    val byVerdict: Map<String, EvidenceBucketStats> = emptyMap(),
    val byConfidence: Map<String, EvidenceBucketStats> = emptyMap(),
    val stale: Boolean = false,
    val isGrading: Boolean = false,
    val horizonHours: Long = 4,
    val lastGradedMs: Long? = null,
    val error: String? = null,
)

data class PerformanceUiState(
    val totalSymbols: Int = 0,
    val gainers: List<Ticker24h> = emptyList(),
    val losers: List<Ticker24h> = emptyList(),
    val byVolume: List<Ticker24h> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastUpdateMs: Long? = null,
    /** ADDITIVE: verdict-evidence self-grading state (domain/evidence). */
    val evidence: EvidenceState = EvidenceState(),
)
