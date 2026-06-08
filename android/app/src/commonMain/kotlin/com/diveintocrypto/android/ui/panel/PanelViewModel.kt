package com.diveintocrypto.android.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Paper-free ViewModel for the Panel screen.
 *
 *   - `bootstrap()`        → fetches the active symbol's 1h klines, runs the
 *                            indicators, and populates the UI state with the
 *                            last price + consensus result.
 *   - `bootstrapMultiTf()` → runs a separate kline + consensus for each of the
 *                            12 TFs, feeding the 12-cell mini grid.
 *   - `liveTicker()`       → subscribes to the spot WS klines stream; on each
 *                            tick it refreshes currentPrice (it does NOT recompute
 *                            the indicators — that would be wasteful at high frequency).
 */
class PanelViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(PanelUiState())
    val ui: StateFlow<PanelUiState> = _ui.asStateFlow()

    private var candles: List<Candle> = emptyList()

    private var bootstrapJob: kotlinx.coroutines.Job? = null
    private var multiTfJob: kotlinx.coroutines.Job? = null
    private var tickerJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                container.activeSymbol,
                container.activeTimeframe
            ) { symbol, timeframe ->
                symbol to timeframe
            }.collect { (symbol, timeframe) ->
                _ui.update {
                    it.copy(
                        activeSymbol = symbol,
                        timeframe = timeframe,
                        isLoading = true,
                        errorMessage = null
                    )
                }
                restartJobs(symbol, timeframe)
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val symbols = container.repository.futuresUniverse()
                _ui.update {
                    it.copy(
                        allSymbols = symbols,
                        filteredSymbols = symbols
                    )
                }
            } catch (e: Throwable) {
                logError("PanelVM", "Failed to fetch futures universe", e)
            }
        }
    }

    private fun restartJobs(symbol: String, timeframe: String) {
        bootstrapJob?.cancel()
        multiTfJob?.cancel()
        tickerJob?.cancel()

        bootstrapJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                logDebug("PanelVM", "bootstrapJob: starting for symbol=$symbol, timeframe=$timeframe")
                coroutineScope {
                    val limit = 300
                    val candlesDeferred = async { container.repository.futuresHistory(symbol, timeframe, limit = limit) }
                    val oiDeferred = async { container.repository.openInterestHist(symbol, timeframe, limit = limit) }
                    val accountRatioDeferred = async { container.repository.topLongShortAccountRatio(symbol, timeframe, limit = limit) }
                    val positionRatioDeferred = async { container.repository.topLongShortPositionRatio(symbol, timeframe, limit = limit) }
                    val takerRatioDeferred = async { container.repository.takerLongShortRatio(symbol, timeframe, limit = limit) }
                    val globalRatioDeferred = async { container.repository.globalLongShortAccountRatio(symbol, timeframe, limit = limit) }
                    val fundingRateDeferred = async { container.repository.fundingRate(symbol, limit = limit) }

                    logDebug("PanelVM", "bootstrapJob: awaiting deferreds...")
                    val cs = candlesDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: candles fetched count=${cs.size}")
                    val oi = oiDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: oi fetched count=${oi.size}")
                    val accountRatio = accountRatioDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: accountRatio fetched count=${accountRatio.size}")
                    val positionRatio = positionRatioDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: positionRatio fetched count=${positionRatio.size}")
                    val taker = takerRatioDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: taker fetched count=${taker.size}")
                    val globalRatio = globalRatioDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: globalRatio fetched count=${globalRatio.size}")
                    val fundingRate = fundingRateDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: fundingRate fetched count=${fundingRate.size}")

                    candles = cs
                    logDebug("PanelVM", "bootstrapJob: calling recomputeMultimodal")
                    recomputeMultimodal(oi, accountRatio, positionRatio, taker, globalRatio, fundingRate)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                logError("PanelVM", "bootstrapJob cancelled", e)
                throw e
            } catch (t: Throwable) {
                logError("PanelVM", "bootstrapJob error", t)
                _ui.update {
                    it.copy(isLoading = false, errorMessage = t.message ?: "network error")
                }
            }
        }

        multiTfJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val results = coroutineScope {
                    ALL_TIMEFRAMES.map { tf ->
                        async {
                            val cs = try {
                                container.repository.futuresHistory(symbol, tf, limit = 300)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                logError("PanelVM", "multiTfJob: futuresHistory failed for $tf", e)
                                emptyList()
                            }
                            if (cs.size < 50) {
                                TfSignal(tf = tf, signal = "N/A", confidence = 0)
                            } else {
                                try {
                                    val indResults = container.indicators.map {
                                        val res = it.calculate(cs)
                                        res
                                    }
                                    val out = container.consensus.evaluate(indResults)
                                    TfSignal(tf = tf, signal = out.finalSignal.name, confidence = out.confidence)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    logError("PanelVM", "multiTfJob: indicator calc failed for $tf", e)
                                    TfSignal(tf = tf, signal = "ERROR", confidence = 0)
                                }
                            }
                        }
                    }.awaitAll()
                }
                _ui.update { it.copy(multiTf = results) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError("PanelVM", "multiTfJob: error", t)
            }
        }

        tickerJob = viewModelScope.launch {
            try {
                container.repository.liveKlines(symbol, timeframe).collect { update ->
                    _ui.update {
                        it.copy(
                            currentPrice = update.candle.close,
                            lastUpdateMs = nowMillis(),
                        )
                    }
                }
            } catch (_: Throwable) {
                // WS is optional — the REST snapshot already provided the last price.
            }
        }
    }

    private fun recomputeMultimodal(
        oi: List<OpenInterestPoint>,
        accountRatio: List<LongShortRatioPoint>,
        positionRatio: List<LongShortRatioPoint>,
        taker: List<TakerLongShortRatioPoint>,
        globalRatio: List<LongShortRatioPoint>,
        fundingRate: List<FundingRatePoint>
    ) {
        logDebug("PanelVM", "recomputeMultimodal: candles=${candles.size}, oi=${oi.size}, acc=${accountRatio.size}, pos=${positionRatio.size}, taker=${taker.size}, global=${globalRatio.size}, funding=${fundingRate.size}")
        if (candles.isEmpty()) return
        val consensusList = container.consensus.evaluateMultimodal(
            candles = candles,
            rawOi = oi,
            rawAcc = accountRatio,
            rawPos = positionRatio,
            rawGlobal = globalRatio,
            rawTaker = taker,
            rawFunding = fundingRate
        )
        logDebug("PanelVM", "recomputeMultimodal: consensusList count=${consensusList.size}")
        val consensus = consensusList.lastOrNull()
        if (consensus == null) {
            logError("PanelVM", "recomputeMultimodal: consensusList is empty!")
            _ui.update { it.copy(isLoading = false, errorMessage = "Consensus could not be computed") }
            return
        }
        _ui.update {
            it.copy(
                currentPrice = candles.last().close,
                latestSignal = consensus.finalSignal.name,
                confidence = consensus.confidence,
                action = if (consensus.shouldTrade) "OPEN_${consensus.finalSignal.name}" else "HOLD",
                reason = consensus.reason,
                distBuy = consensus.buyCount,
                distSell = consensus.sellCount,
                distNeutral = consensus.neutralCount,
                isLoading = false,
                errorMessage = null,
                lastUpdateMs = nowMillis(),
            )
        }
        logDebug("PanelVM", "recomputeMultimodal: UI state updated. Signal=${consensus.finalSignal.name}, Reason=${consensus.reason}")
    }

    fun refresh() {
        val symbol = _ui.value.activeSymbol
        val timeframe = _ui.value.timeframe
        _ui.update {
            it.copy(
                isLoading = true,
                errorMessage = null
            )
        }
        restartJobs(symbol, timeframe)
    }

    fun setSearchQuery(query: String) {
        val uppercaseQuery = query.uppercase()
        _ui.update { state ->
            val filtered = if (uppercaseQuery.isEmpty()) {
                state.allSymbols
            } else {
                state.allSymbols.filter { it.contains(uppercaseQuery) }
            }
            state.copy(
                searchQuery = query,
                filteredSymbols = filtered
            )
        }
    }

    fun selectSymbol(symbol: String) {
        if (symbol == _ui.value.activeSymbol) return
        container.activeSymbol.value = symbol
        _ui.update { it.copy(searchQuery = "") }
    }
}
