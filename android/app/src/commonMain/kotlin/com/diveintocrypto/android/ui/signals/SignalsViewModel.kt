package com.diveintocrypto.android.ui.signals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.SignalDetail
import com.diveintocrypto.android.platform.nowMillis
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

/**
 * ViewModel for SignalsScreen — multi-timeframe version.
 * Loads history for all 12 timeframes in parallel when active symbol changes,
 * computes consensus outcome and indicator vote breakdown for all of them,
 * and maintains a live ticker stream subscription for the active timeframe.
 */
class SignalsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(SignalsUiState())
    val ui: StateFlow<SignalsUiState> = _ui.asStateFlow()

    private val candlesMap = mutableMapOf<String, List<Candle>>()
    private val mapLock = kotlinx.atomicfu.locks.SynchronizedObject()

    private var symbolJobs: Job? = null
    private var tickerJob: Job? = null
    private var stalenessJob: Job? = null

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

    private fun restartSymbolJobs(symbol: String) {
        symbolJobs?.cancel()
        symbolJobs = viewModelScope.launch(Dispatchers.Default) {
            try {
                // Fetch historical candles for all 12 timeframes in parallel
                val results = coroutineScope {
                    ALL_TIMEFRAMES.map { tf ->
                        async {
                            val cs = try {
                                container.repository.futuresHistory(symbol, tf, limit = 300)
                            } catch (_: Throwable) {
                                emptyList()
                            }
                            tf to cs
                        }
                    }.awaitAll()
                }

                results.forEach { (tf, cs) ->
                    kotlinx.atomicfu.locks.synchronized(mapLock) { candlesMap[tf] = cs }
                }

                recomputeAll()

                // Automatically restart/synchronize the live stream for the active timeframe
                restartTickerJob(container.activeTimeframe.value)
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = t.message ?: "Network error: failed to fetch data"
                    )
                }
            }
        }
    }

    private fun restartTickerJob(timeframe: String) {
        tickerJob?.cancel()
        stalenessJob?.cancel()
        val symbol = container.activeSymbol.value

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
                    processTick(timeframe, update.candle)
                }
            } catch (e: CancellationException) {
                throw e
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

    private fun processTick(timeframe: String, candle: Candle) {
        val cs = kotlinx.atomicfu.locks.synchronized(mapLock) { candlesMap[timeframe] } ?: emptyList()
        val lastCandle = cs.lastOrNull()
        val newCandles = when {
            lastCandle == null -> listOf(candle)
            candle.openTime == lastCandle.openTime -> {
                cs.dropLast(1) + candle
            }
            candle.openTime > lastCandle.openTime -> {
                (cs + candle).takeLast(300)
            }
            else -> cs
        }
        kotlinx.atomicfu.locks.synchronized(mapLock) { candlesMap[timeframe] = newCandles }

        // Recompute only this timeframe to avoid unnecessary CPU load
        val updatedState = computeTfSignalState(timeframe, newCandles)
        _ui.update {
            it.copy(
                tfSignals = it.tfSignals + (timeframe to updatedState),
                lastUpdateMs = nowMillis()
            )
        }
    }

    private fun recomputeAll() {
        val newTfSignals = ALL_TIMEFRAMES.associateWith { tf ->
            val cs = kotlinx.atomicfu.locks.synchronized(mapLock) { candlesMap[tf] } ?: emptyList()
            computeTfSignalState(tf, cs)
        }
        _ui.update {
            it.copy(
                tfSignals = newTfSignals,
                isLoading = false,
                lastUpdateMs = nowMillis()
            )
        }
    }

    private fun computeTfSignalState(tf: String, cs: List<Candle>): TfSignalState {
        if (cs.isEmpty()) return TfSignalState(timeframe = tf)

        val results: List<IndicatorResult> = container.indicators.map { it.calculate(cs) }
        val consensus = container.consensus.evaluate(results)

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
        val action = if (consensus.shouldTrade) "OPEN_${consensus.finalSignal.name}" else "HOLD"

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
    val action: String = "N/A",
    val shouldTrade: Boolean = false,
    val reason: String = "",
    val buyCount: Int = 0,
    val sellCount: Int = 0,
    val neutralCount: Int = 0,
    val votes: List<IndicatorVoteRow> = emptyList(),
    val signalDetails: List<SignalDetail> = emptyList(),
)

data class SignalsUiState(
    val activeSymbol: String = "BTCUSDT",
    val timeframe: String = "1h",
    val tfSignals: Map<String, TfSignalState> = emptyMap(),
    val lastUpdateMs: Long? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
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
