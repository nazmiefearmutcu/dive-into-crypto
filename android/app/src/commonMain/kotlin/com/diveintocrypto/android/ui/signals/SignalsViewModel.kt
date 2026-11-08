package com.diveintocrypto.android.ui.signals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.SignalDetail
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.common.userFacingText
import com.diveintocrypto.android.ui.i18n.DiveStrings
import com.diveintocrypto.android.ui.panel.ALL_TIMEFRAMES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for SignalsScreen — multi-timeframe version.
 * Loads history for all 12 timeframes in parallel when active symbol changes,
 * computes consensus outcome and indicator vote breakdown for all of them,
 * and maintains a live ticker stream subscription for the active timeframe.
 *
 * Threading: every job-handle mutation (symbol load, ticker restart, staleness watchdog)
 * runs on the main dispatcher; only the network fetches and the indicator maths hop to
 * [Dispatchers.Default]. Candle state is guarded by [mapLock].
 */
class SignalsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(SignalsUiState())
    val ui: StateFlow<SignalsUiState> = _ui.asStateFlow()

    private val candlesMap = mutableMapOf<String, List<Candle>>()
    private val mapLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** Symbol whose candles are loaded (set when a load starts). Ticks for any other symbol are
     *  dropped. Guarded by [mapLock]. */
    private var loadedSymbol: String? = null

    private var symbolJobs: Job? = null
    private var tickerJob: Job? = null
    private var stalenessJob: Job? = null

    private fun strings(): DiveStrings = container.language.value

    init {
        // Collect active symbol changes.
        // On new symbol: trigger parallel load of all 12 timeframes.
        viewModelScope.launch {
            container.activeSymbol.collect { symbol ->
                _ui.update {
                    it.copy(
                        activeSymbol = symbol,
                        isLoading = true,
                        errorMessage = null
                    )
                }
                restartSymbolJobs(symbol)
            }
        }

        // Collect active timeframe changes.
        // On new timeframe: cancel previous ticker and start new one.
        viewModelScope.launch {
            container.activeTimeframe.collect { tf ->
                _ui.update {
                    it.copy(
                        timeframe = tf
                    )
                }
                restartTickerJob(tf)
            }
        }
    }

    fun refresh() {
        val currentSymbol = _ui.value.activeSymbol
        _ui.update { it.copy(isLoading = true, errorMessage = null) }
        restartSymbolJobs(currentSymbol)
    }

    /** One timeframe's history load: the candles, or the failure that prevented them. */
    private class TfLoad(val tf: String, val candles: List<Candle>, val failure: Throwable?)

    private suspend fun loadTf(symbol: String, tf: String): TfLoad = try {
        TfLoad(tf, container.repository.futuresHistory(symbol, tf, limit = 300), null)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        TfLoad(tf, emptyList(), t)
    }

    private fun restartSymbolJobs(symbol: String) {
        symbolJobs?.cancel()
        tickerJob?.cancel()
        stalenessJob?.cancel()
        // Switching symbol drops the previous symbol's candles, grid and live stream, so
        // nothing of it stays on screen (was: BTC values kept under the ETH selection).
        // A pull-to-refresh of the SAME symbol keeps the grid on screen while it reloads.
        val switched = kotlinx.atomicfu.locks.synchronized(mapLock) {
            val differs = loadedSymbol != symbol
            if (differs) candlesMap.clear()
            loadedSymbol = symbol
            differs
        }
        if (switched) {
            _ui.update {
                it.copy(
                    tfSignals = emptyMap(),
                    isStale = true,
                    dataAgeMs = null,
                    failedTfs = emptySet(),
                    regionBlocked = false,
                )
            }
        }
        symbolJobs = viewModelScope.launch {
            try {
                // Fetch historical candles for all 12 timeframes in parallel.
                val loads = withContext(Dispatchers.Default) {
                    coroutineScope {
                        ALL_TIMEFRAMES.map { tf -> async { loadTf(symbol, tf) } }.awaitAll()
                    }
                }

                kotlinx.atomicfu.locks.synchronized(mapLock) {
                    loads.forEach { candlesMap[it.tf] = it.candles }
                }

                recomputeAll()

                // HONESTY: failed timeframes are counted and shown, never rendered as a quiet N/A.
                val s = strings()
                val failures = loads.filter { it.failure != null }
                val allFailed = failures.size == loads.size
                val firstFailure = failures.firstOrNull()?.failure
                _ui.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (allFailed) firstFailure?.userFacingText(s) else null,
                        regionBlocked = allFailed && firstFailure?.isRegionBlocked() == true,
                        failedTfs = failures.map { f -> f.tf }.toSet(),
                    )
                }

                // Automatically restart/synchronize the live stream for the active timeframe
                restartTickerJob(container.activeTimeframe.value)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = t.userFacingText(strings()),
                    )
                }
            }
        }
    }

    private fun restartTickerJob(timeframe: String) {
        tickerJob?.cancel()
        stalenessJob?.cancel()
        // Subscribe only once the current symbol has been loaded: its candles are then the base
        // the live bars merge into. Before that there is nothing consistent to stream into.
        val symbol = kotlinx.atomicfu.locks.synchronized(mapLock) { loadedSymbol } ?: return

        var lastWsMessageTime = 0L

        // The repository stream is self-healing (BinanceWsClient.reconnectingKlineStream:
        // exponential backoff + jitter, reset on first frame), so a single collect is enough.
        // NO synthetic ticks: when the socket is quiet the data simply stops being refreshed
        // and the staleness watchdog below flags it — nothing is fabricated.
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                container.repository.liveKlines(symbol, timeframe).collect { update ->
                    lastWsMessageTime = nowMillis()
                    _ui.update { it.copy(isStale = false, dataAgeMs = 0L) }
                    try {
                        processTick(symbol, timeframe, update.candle)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // One bad tick must not kill the live stream; the feed itself is still alive.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // HONESTY: the stream failed terminally — flag it stale instead of crashing the app.
                _ui.update { it.copy(isStale = true) }
            }
        }

        // Clock-only staleness watchdog. It ONLY reads the wall clock — it never
        // touches market data. UI renders a STALE chip from [SignalsUiState.isStale]
        // / [SignalsUiState.dataAgeMs]; ticks are NOT invented while quiet.
        stalenessJob = viewModelScope.launch(Dispatchers.Default) {
            var lastAgePush = 0L
            while (true) {
                delay(1_000)
                val last = lastWsMessageTime
                val now = nowMillis()
                val age = if (last > 0L) now - last else null
                val stale = age == null || age > STALE_AFTER_MS
                _ui.update {
                    // Push immediately on staleness transitions; refresh the age
                    // reading at most every STALE_AGE_PUSH_MS to bound recomposition.
                    val dueAgePush = now - lastAgePush >= STALE_AGE_PUSH_MS
                    if (it.isStale == stale && !dueAgePush && it.dataAgeMs == age) {
                        it
                    } else {
                        if (dueAgePush || it.isStale != stale) lastAgePush = now
                        it.copy(isStale = stale, dataAgeMs = age)
                    }
                }
            }
        }
    }

    /** Merges one live bar into the loaded candles and recomputes that timeframe.
     *  Returns silently for a tick whose symbol is no longer the loaded one. */
    private fun processTick(symbol: String, timeframe: String, candle: Candle) {
        val newCandles: List<Candle>? = kotlinx.atomicfu.locks.synchronized(mapLock) {
            if (loadedSymbol != symbol) {
                null
            } else {
                val cs = candlesMap[timeframe] ?: emptyList()
                val lastCandle = cs.lastOrNull()
                val merged = when {
                    lastCandle == null -> listOf(candle)
                    candle.openTime == lastCandle.openTime -> cs.dropLast(1) + candle
                    candle.openTime > lastCandle.openTime -> (cs + candle).takeLast(300)
                    else -> cs
                }
                candlesMap[timeframe] = merged
                merged
            }
        }
        if (newCandles == null) return

        // Recompute only this timeframe to avoid unnecessary CPU load
        val updatedState = computeTfSignalState(timeframe, newCandles)
        // Publish only while this symbol is still the loaded one. The check runs inside the update and
        // reads loadedSymbol under mapLock; restartSymbolJobs sets loadedSymbol before it clears the grid,
        // so a stale tick either lands before that clear or is skipped here.
        _ui.update {
            if (kotlinx.atomicfu.locks.synchronized(mapLock) { loadedSymbol } != symbol) {
                it
            } else {
                it.copy(
                    tfSignals = it.tfSignals + (timeframe to updatedState),
                    lastUpdateMs = nowMillis()
                )
            }
        }
    }

    /** Recomputes all 12 timeframes off the main thread, then publishes them. */
    private suspend fun recomputeAll() {
        val snapshot: Map<String, List<Candle>> = kotlinx.atomicfu.locks.synchronized(mapLock) {
            ALL_TIMEFRAMES.associateWith { tf -> candlesMap[tf] ?: emptyList() }
        }
        val newTfSignals = withContext(Dispatchers.Default) {
            ALL_TIMEFRAMES.associateWith { tf -> computeTfSignalState(tf, snapshot.getValue(tf)) }
        }
        _ui.update {
            it.copy(
                tfSignals = newTfSignals,
                lastUpdateMs = nowMillis()
            )
        }
    }

    private fun computeTfSignalState(tf: String, cs: List<Candle>): TfSignalState {
        val s = strings()
        val now = nowMillis()
        if (cs.isEmpty()) return TfSignalState(timeframe = tf, reason = s.sigNoDataTimeframe, computedAtMs = now)
        // Same minimum-history gate as the scanner: a verdict from a handful of bars is not shown.
        if (cs.size < MIN_HISTORY_CANDLES) {
            return TfSignalState(
                timeframe = tf,
                reason = s.sigInsufficientHistory(cs.size, MIN_HISTORY_CANDLES),
                computedAtMs = now,
            )
        }

        val results: List<IndicatorResult> = container.indicators.map { it.calculate(cs) }
        val consensus = container.consensus.evaluate(
            results,
            com.diveintocrypto.android.ui.i18n.consensusLabelsFor(container.language.value),
        )

        val rows = if (results.isNotEmpty()) {
            results.map { r ->
                val detail = consensus.signalDetails.firstOrNull { it.name == r.name }
                IndicatorVoteRow(
                    name = r.name,
                    signal = r.signal.name,
                    score = r.signal.score,
                    weight = detail?.weight ?: 0.0,
                    weightedScore = detail?.weightedScore ?: 0.0,
                    reason = r.reason,
                )
            }
        } else emptyList()

        val riskLevel = when {
            consensus.confidence >= 70 -> "HIGH"
            consensus.confidence >= 40 -> "MEDIUM"
            consensus.confidence > 0 -> "LOW"
            else -> "N/A"
        }
        // Non-executional threshold status (no OPEN_*/HOLD tokens): the screen maps these to
        // "above threshold · upward bias" / "above threshold · downward bias" / "observe only".
        val action = when {
            !consensus.shouldTrade -> ACTION_BELOW_THRESHOLD
            consensus.finalSignal.score > 0 -> ACTION_ABOVE_BULL
            consensus.finalSignal.score < 0 -> ACTION_ABOVE_BEAR
            else -> ACTION_BELOW_THRESHOLD
        }

        return TfSignalState(
            timeframe = tf,
            finalSignal = consensus.finalSignal.name,
            confidence = consensus.confidence,
            weightedScore = consensus.weightedScore,
            riskLevel = riskLevel,
            action = action,
            shouldTrade = consensus.shouldTrade,
            reason = consensus.reason,
            buyCount = consensus.buyCount,
            sellCount = consensus.sellCount,
            neutralCount = consensus.neutralCount,
            votes = rows,
            signalDetails = consensus.signalDetails,
            computedAtMs = now,
        )
    }

    override fun onCleared() {
        symbolJobs?.cancel()
        tickerJob?.cancel()
        stalenessJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** WS-quiet threshold after which the UI state is flagged stale (nothing is fabricated). */
        const val STALE_AFTER_MS = 3_000L

        /** Minimum interval between staleness-age state pushes (bounds recomposition). */
        const val STALE_AGE_PUSH_MS = 5_000L

        /** Candles needed before a timeframe verdict is shown (mirrors the scanner's gate). */
        const val MIN_HISTORY_CANDLES = 50

        /** [TfSignalState.action] tokens — threshold status, never an execution command. */
        const val ACTION_ABOVE_BULL = "ABOVE_BULL"
        const val ACTION_ABOVE_BEAR = "ABOVE_BEAR"
        const val ACTION_BELOW_THRESHOLD = "BELOW"
    }
}

data class IndicatorVoteRow(
    val name: String,
    val signal: String,
    val score: Int,
    val weight: Double,
    val weightedScore: Double,
    val reason: String,
)

data class TfSignalState(
    val timeframe: String,
    val finalSignal: String = "N/A",
    val confidence: Int = 0,
    val weightedScore: Double = 0.0,
    val riskLevel: String = "N/A",
    /** Threshold-status token: see [SignalsViewModel.ACTION_ABOVE_BULL] and siblings. */
    val action: String = SignalsViewModel.ACTION_BELOW_THRESHOLD,
    val shouldTrade: Boolean = false,
    val reason: String = "",
    val buyCount: Int = 0,
    val sellCount: Int = 0,
    val neutralCount: Int = 0,
    val votes: List<IndicatorVoteRow> = emptyList(),
    val signalDetails: List<SignalDetail> = emptyList(),
    /** Wall-clock time this state was computed; 0 = never. Shown as "as of" on inactive timeframes. */
    val computedAtMs: Long = 0L,
)

data class SignalsUiState(
    val activeSymbol: String = "BTCUSDT",
    val timeframe: String = "1h",
    val tfSignals: Map<String, TfSignalState> = emptyMap(),
    val lastUpdateMs: Long? = null,
    val isLoading: Boolean = true,
    /** Localized failure text when the whole load failed (null = loaded, possibly partially). */
    val errorMessage: String? = null,
    /** True when the failed load was a regional block (HTTP 451): no retry loop, dedicated text. */
    val regionBlocked: Boolean = false,
    /** Timeframes whose history could not be loaded; shown as a partial-data note. */
    val failedTfs: Set<String> = emptySet(),
    /**
     * HONESTY FIELDS (replaces the deleted random-tick fallback):
     * true once no WebSocket frame has arrived for more than [SignalsViewModel.STALE_AFTER_MS]
     * (or none ever arrived on this session). The displayed candles are then the last REAL
     * data — nothing is extrapolated.
     */
    val isStale: Boolean = false,
    /** ms since the last WS frame; null = no frame received yet in this session. */
    val dataAgeMs: Long? = null,
)
