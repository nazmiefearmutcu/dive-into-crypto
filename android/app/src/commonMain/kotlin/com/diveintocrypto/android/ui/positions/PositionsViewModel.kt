package com.diveintocrypto.android.ui.positions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.platform.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * PositionsViewModel handles loading and live updates for market position data.
 * It combines parallel history REST calls on start, high-frequency 5-second polling
 * in the background, and a self-healing WebSocket live ticker for real-time price moves.
 *
 * NOTHING IS SYNTHESISED ("Nothing is synthesised" doctrine): when the WebSocket is
 * quiet the last REAL data is kept and the UI state is flagged via [PositionsUiState.isStale]
 * / [PositionsUiState.dataAgeMs]. The previous random price/OI/ratio fabrication
 * ("fallback ticker" + "simulated series") was deleted — never invent market data.
 */
class PositionsViewModel(private val container: AppContainer) : ViewModel() {

    private val lock = SynchronizedObject()
    private var cachedCandles = listOf<Candle>()

    private val _ui = MutableStateFlow(PositionsUiState())
    val ui: StateFlow<PositionsUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var pollingJob: Job? = null
    private var tickerJob: Job? = null
    private var stalenessJob: Job? = null
    private var flashResetJob: Job? = null

    init {
        viewModelScope.launch {
            container.activeSymbol.collect { symbol ->
                val currentPeriod = _ui.value.period
                _ui.update { it.copy(activeSymbol = symbol, isLoading = true, error = null) }
                restartJobs(symbol, currentPeriod)
            }
        }
    }

    fun selectSymbol(symbol: String) {
        if (symbol == _ui.value.activeSymbol) return
        container.activeSymbol.value = symbol
    }

    fun selectPeriod(period: String) {
        if (period == _ui.value.period) return
        val currentSymbol = _ui.value.activeSymbol
        _ui.update { it.copy(period = period, isLoading = true, error = null) }
        restartJobs(currentSymbol, period)
    }

    fun refresh() {
        val currentSymbol = _ui.value.activeSymbol
        val currentPeriod = _ui.value.period
        _ui.update { it.copy(isLoading = true, error = null) }
        restartJobs(currentSymbol, currentPeriod)
    }

    private fun restartJobs(symbol: String, period: String) {
        loadJob?.cancel()
        pollingJob?.cancel()
        tickerJob?.cancel()
        stalenessJob?.cancel()
        flashResetJob?.cancel()

        synchronized(lock) {
            cachedCandles = emptyList()
        }

        loadJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                fetchInitialData(symbol, period)

                // Start live updates
                startPolling(symbol, period)
                startTicker(symbol, period)
            } catch (t: Throwable) {
                _ui.update { it.copy(isLoading = false, error = t.message ?: "Network error") }
            }
        }
    }

    private suspend fun fetchInitialData(symbol: String, period: String) {
        coroutineScope {
            val limit = container.settingsStore.getSettings().chartCandleCount
            val oiDeferred = async { container.repository.openInterestHist(symbol, period, limit = limit) }
            val accountRatioDeferred = async { container.repository.topLongShortAccountRatio(symbol, period, limit = limit) }
            val positionRatioDeferred = async { container.repository.topLongShortPositionRatio(symbol, period, limit = limit) }
            val takerRatioDeferred = async { container.repository.takerLongShortRatio(symbol, period, limit = limit) }
            val globalRatioDeferred = async { container.repository.globalLongShortAccountRatio(symbol, period, limit = limit) }
            val fundingRateDeferred = async { container.repository.fundingRate(symbol, limit = limit) }
            val candlesDeferred = async { container.repository.futuresHistory(symbol, period, limit = limit) }

            val oi = oiDeferred.await()
            val accountRatio = accountRatioDeferred.await()
            val positionRatio = positionRatioDeferred.await()
            val taker = takerRatioDeferred.await()
            val globalRatio = globalRatioDeferred.await()
            val fundingRate = fundingRateDeferred.await()
            val candles = candlesDeferred.await()

            synchronized(lock) {
                cachedCandles = candles
            }
            val aligned = alignData(candles, oi, accountRatio, positionRatio, taker, globalRatio, fundingRate)
            val bias = calculateQuantBias(candles, aligned.oi, aligned.acc, aligned.pos, aligned.taker, aligned.global, aligned.funding)

            _ui.update {
                it.copy(
                    openInterest = aligned.oi,
                    accountRatio = aligned.acc,
                    positionRatio = aligned.pos,
                    takerRatio = aligned.taker,
                    globalRatio = aligned.global,
                    fundingRate = aligned.funding,
                    netTakerVolume = aligned.taker.map { t -> t.buyVol - t.sellVol },
                    quantBias = bias,
                    closePrices = candles.map { c -> c.close },
                    candles = candles,
                    isLoading = false,
                    error = null,
                    lastUpdateMs = nowMillis(),
                )
            }
        }
    }

    private fun startPolling(symbol: String, period: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    delay(5000)
                    val limit = container.settingsStore.getSettings().chartCandleCount
                    coroutineScope {
                        val oiDeferred = async { container.repository.openInterestHist(symbol, period, limit = limit) }
                        val accountRatioDeferred = async { container.repository.topLongShortAccountRatio(symbol, period, limit = limit) }
                        val positionRatioDeferred = async { container.repository.topLongShortPositionRatio(symbol, period, limit = limit) }
                        val takerRatioDeferred = async { container.repository.takerLongShortRatio(symbol, period, limit = limit) }
                        val globalRatioDeferred = async { container.repository.globalLongShortAccountRatio(symbol, period, limit = limit) }
                        val fundingRateDeferred = async { container.repository.fundingRate(symbol, limit = limit) }
                        val candlesDeferred = async { container.repository.futuresHistory(symbol, period, limit = limit) }

                        val oi = oiDeferred.await()
                        val accountRatio = accountRatioDeferred.await()
                        val positionRatio = positionRatioDeferred.await()
                        val taker = takerRatioDeferred.await()
                        val globalRatio = globalRatioDeferred.await()
                        val fundingRate = fundingRateDeferred.await()
                        val candles = candlesDeferred.await()

                        val merged = synchronized(lock) {
                            val m = mergeCandles(candles, cachedCandles, limit)
                            cachedCandles = m
                            m
                        }
                        val aligned = alignData(merged, oi, accountRatio, positionRatio, taker, globalRatio, fundingRate, _ui.value)
                        val bias = calculateQuantBias(merged, aligned.oi, aligned.acc, aligned.pos, aligned.taker, aligned.global, aligned.funding)

                        _ui.update {
                            it.copy(
                                openInterest = aligned.oi,
                                accountRatio = aligned.acc,
                                positionRatio = aligned.pos,
                                takerRatio = aligned.taker,
                                globalRatio = aligned.global,
                                fundingRate = aligned.funding,
                                netTakerVolume = aligned.taker.map { t -> t.buyVol - t.sellVol },
                                quantBias = bias,
                                closePrices = merged.map { c -> c.close },
                                candles = merged,
                                error = null,
                                lastUpdateMs = nowMillis(),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    delay(5000)
                }
            }
        }
    }

    private fun startTicker(symbol: String, period: String) {
        tickerJob?.cancel()
        stalenessJob?.cancel()

        var lastWsMessageTime = 0L

        // The repository stream is self-healing (BinanceWsClient.reconnectingKlineStream),
        // so one collect suffices. NO synthetic prices: when the socket is quiet the last
        // REAL data stays on screen and the staleness watchdog flags it.
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                container.repository.liveKlines(symbol, period).collect { update ->
                    lastWsMessageTime = nowMillis()
                    _ui.update { it.copy(isStale = false, dataAgeMs = 0L) }
                    processTick(update.candle.close, update.candle.openTime)
                }
            } catch (e: CancellationException) {
                throw e
            }
        }

        // Clock-only staleness watchdog — reads the wall clock, never fabricates data.
        // UI renders a STALE chip from [PositionsUiState.isStale] / [PositionsUiState.dataAgeMs].
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

    private fun processTick(newPrice: Double, openTime: Long) {
        val direction: String
        val isNewCandle: Boolean
        val updatedCandles: List<Candle>
        val limit = container.settingsStore.getSettings().chartCandleCount

        synchronized(lock) {
            val currentCandles = cachedCandles.toMutableList()
            if (currentCandles.isEmpty()) {
                logDebug("PositionsVM", "processTick: cachedCandles is empty, skipping tick price=$newPrice")
                return
            }

            val lastCandle = currentCandles.last()
            isNewCandle = openTime > lastCandle.openTime

            direction = when {
                newPrice > lastCandle.close -> "UP"
                newPrice < lastCandle.close -> "DOWN"
                else -> "NONE"
            }

            if (isNewCandle) {
                logDebug("PositionsVM", "processTick: NEW CANDLE! openTime=$openTime, lastCandleOpenTime=${lastCandle.openTime}, price=$newPrice")
                val newCandle = Candle(
                    openTime = openTime,
                    open = lastCandle.close,
                    high = maxOf(lastCandle.close, newPrice),
                    low = minOf(lastCandle.close, newPrice),
                    close = newPrice,
                    volume = 0.0,
                    closeTime = openTime + getPeriodDurationMs(_ui.value.period) - 1
                )
                currentCandles.add(newCandle)
                while (currentCandles.size > limit) {
                    currentCandles.removeAt(0)
                }
            } else {
                logDebug("PositionsVM", "processTick: updating current candle openTime=$openTime, price=$newPrice")
                val updatedLastCandle = lastCandle.copy(
                    close = newPrice,
                    high = maxOf(lastCandle.high, newPrice),
                    low = minOf(lastCandle.low, newPrice)
                )
                currentCandles[currentCandles.lastIndex] = updatedLastCandle
            }

            cachedCandles = currentCandles
            updatedCandles = currentCandles
        }

        // HONESTY: only the CANDLES follow the live WebSocket price (real ticks).
        // The OI / L/S / taker / funding series are NEVER extrapolated or simulated —
        // they refresh solely from the REST polling loop; while the REST refresh is
        // in flight the displayed series keep their last REAL points and the staleness
        // fields in the UI state say so. (The previous per-tick random series
        // fabrication was removed — "Nothing is synthesised".)
        val state = _ui.value
        val bias = calculateQuantBias(
            updatedCandles,
            state.openInterest,
            state.accountRatio,
            state.positionRatio,
            state.takerRatio,
            state.globalRatio,
            state.fundingRate,
        )

        _ui.update {
            it.copy(
                closePrices = updatedCandles.map { c -> c.close },
                candles = updatedCandles,
                quantBias = bias,
                priceChangeDirection = direction,
                lastUpdateMs = nowMillis()
            )
        }

        // Reset flash color after 300ms
        flashResetJob?.cancel()
        flashResetJob = viewModelScope.launch {
            delay(300)
            _ui.update {
                if (it.priceChangeDirection == direction) {
                    it.copy(priceChangeDirection = "NONE")
                } else {
                    it
                }
            }
        }
    }

    private fun mergeCandles(serverCandles: List<Candle>, localCandles: List<Candle>, limit: Int): List<Candle> {
        if (localCandles.isEmpty()) return serverCandles
        val lastServerOpenTime = serverCandles.lastOrNull()?.openTime ?: 0L
        val newerLocalCandles = localCandles.filter { it.openTime > lastServerOpenTime }

        val lastServerCandle = serverCandles.lastOrNull()
        val matchingLocalCandle = localCandles.find { it.openTime == lastServerOpenTime }

        val updatedServerCandles = if (lastServerCandle != null && matchingLocalCandle != null) {
            val mergedLast = lastServerCandle.copy(
                close = matchingLocalCandle.close,
                high = maxOf(lastServerCandle.high, matchingLocalCandle.high),
                low = minOf(lastServerCandle.low, matchingLocalCandle.low)
            )
            serverCandles.dropLast(1) + mergedLast
        } else {
            serverCandles
        }

        return (updatedServerCandles + newerLocalCandles).takeLast(limit)
    }

    private fun alignData(
        candles: List<Candle>,
        rawOi: List<OpenInterestPoint>,
        rawAcc: List<LongShortRatioPoint>,
        rawPos: List<LongShortRatioPoint>,
        rawTaker: List<TakerLongShortRatioPoint>,
        rawGlobal: List<LongShortRatioPoint>,
        rawFunding: List<FundingRatePoint>,
        currentState: PositionsUiState? = null
    ): AlignedData {
        val alignedOi = ArrayList<OpenInterestPoint>(candles.size)
        val alignedAcc = ArrayList<LongShortRatioPoint>(candles.size)
        val alignedPos = ArrayList<LongShortRatioPoint>(candles.size)
        val alignedTaker = ArrayList<TakerLongShortRatioPoint>(candles.size)
        val alignedGlobal = ArrayList<LongShortRatioPoint>(candles.size)
        val alignedFunding = ArrayList<FundingRatePoint>(candles.size)

        var oiIdx = 0
        var accIdx = 0
        var posIdx = 0
        var takerIdx = 0
        var globalIdx = 0
        var fundingIdx = 0

        var existingOiIdx = 0
        var existingAccIdx = 0
        var existingPosIdx = 0
        var existingTakerIdx = 0
        var existingGlobalIdx = 0
        var existingFundingIdx = 0

        val existingOi = currentState?.openInterest ?: emptyList()
        val existingAcc = currentState?.accountRatio ?: emptyList()
        val existingPos = currentState?.positionRatio ?: emptyList()
        val existingTaker = currentState?.takerRatio ?: emptyList()
        val existingGlobal = currentState?.globalRatio ?: emptyList()
        val existingFunding = currentState?.fundingRate ?: emptyList()

        for (candle in candles) {
            val t = candle.openTime

            // 1. Open Interest
            while (oiIdx < rawOi.size && rawOi[oiIdx].timestamp < t) {
                oiIdx++
            }
            val exactRawOi = if (oiIdx < rawOi.size && rawOi[oiIdx].timestamp == t) rawOi[oiIdx] else null

            val oiPoint = if (exactRawOi != null) {
                exactRawOi
            } else {
                while (existingOiIdx < existingOi.size && existingOi[existingOiIdx].timestamp < t) {
                    existingOiIdx++
                }
                val exactExistingOi = if (existingOiIdx < existingOi.size && existingOi[existingOiIdx].timestamp == t) existingOi[existingOiIdx] else null
                
                if (exactExistingOi != null) {
                    exactExistingOi
                } else {
                    val p = if (rawOi.isEmpty()) {
                        OpenInterestPoint(t, 0.0, 0.0)
                    } else if (oiIdx == rawOi.size) {
                        rawOi.last()
                    } else if (oiIdx == 0) {
                        rawOi.first()
                    } else {
                        val next = rawOi[oiIdx]
                        val prev = rawOi[oiIdx - 1]
                        if (abs(next.timestamp - t) < abs(prev.timestamp - t)) next else prev
                    }
                    p.copy(timestamp = t)
                }
            }
            alignedOi.add(oiPoint)

            // 2. Account Ratio
            while (accIdx < rawAcc.size && rawAcc[accIdx].timestamp < t) {
                accIdx++
            }
            val exactRawAcc = if (accIdx < rawAcc.size && rawAcc[accIdx].timestamp == t) rawAcc[accIdx] else null

            val accPoint = if (exactRawAcc != null) {
                exactRawAcc
            } else {
                while (existingAccIdx < existingAcc.size && existingAcc[existingAccIdx].timestamp < t) {
                    existingAccIdx++
                }
                val exactExistingAcc = if (existingAccIdx < existingAcc.size && existingAcc[existingAccIdx].timestamp == t) existingAcc[existingAccIdx] else null
                
                if (exactExistingAcc != null) {
                    exactExistingAcc
                } else {
                    val p = if (rawAcc.isEmpty()) {
                        LongShortRatioPoint(t, 0.5, 0.5, 1.0)
                    } else if (accIdx == rawAcc.size) {
                        rawAcc.last()
                    } else if (accIdx == 0) {
                        rawAcc.first()
                    } else {
                        val next = rawAcc[accIdx]
                        val prev = rawAcc[accIdx - 1]
                        if (abs(next.timestamp - t) < abs(prev.timestamp - t)) next else prev
                    }
                    p.copy(timestamp = t)
                }
            }
            alignedAcc.add(accPoint)

            // 3. Position Ratio
            while (posIdx < rawPos.size && rawPos[posIdx].timestamp < t) {
                posIdx++
            }
            val exactRawPos = if (posIdx < rawPos.size && rawPos[posIdx].timestamp == t) rawPos[posIdx] else null

            val posPoint = if (exactRawPos != null) {
                exactRawPos
            } else {
                while (existingPosIdx < existingPos.size && existingPos[existingPosIdx].timestamp < t) {
                    existingPosIdx++
                }
                val exactExistingPos = if (existingPosIdx < existingPos.size && existingPos[existingPosIdx].timestamp == t) existingPos[existingPosIdx] else null
                
                if (exactExistingPos != null) {
                    exactExistingPos
                } else {
                    val p = if (rawPos.isEmpty()) {
                        LongShortRatioPoint(t, 0.5, 0.5, 1.0)
                    } else if (posIdx == rawPos.size) {
                        rawPos.last()
                    } else if (posIdx == 0) {
                        rawPos.first()
                    } else {
                        val next = rawPos[posIdx]
                        val prev = rawPos[posIdx - 1]
                        if (abs(next.timestamp - t) < abs(prev.timestamp - t)) next else prev
                    }
                    p.copy(timestamp = t)
                }
            }
            alignedPos.add(posPoint)

            // 4. Taker Ratio
            while (takerIdx < rawTaker.size && rawTaker[takerIdx].timestamp < t) {
                takerIdx++
            }
            val exactRawTaker = if (takerIdx < rawTaker.size && rawTaker[takerIdx].timestamp == t) rawTaker[takerIdx] else null

            val takerPoint = if (exactRawTaker != null) {
                exactRawTaker
            } else {
                while (existingTakerIdx < existingTaker.size && existingTaker[existingTakerIdx].timestamp < t) {
                    existingTakerIdx++
                }
                val exactExistingTaker = if (existingTakerIdx < existingTaker.size && existingTaker[existingTakerIdx].timestamp == t) existingTaker[existingTakerIdx] else null
                
                if (exactExistingTaker != null) {
                    exactExistingTaker
                } else {
                    val p = if (rawTaker.isEmpty()) {
                        TakerLongShortRatioPoint(t, 1.0, 0.0, 0.0)
                    } else if (takerIdx == rawTaker.size) {
                        rawTaker.last()
                    } else if (takerIdx == 0) {
                        rawTaker.first()
                    } else {
                        val next = rawTaker[takerIdx]
                        val prev = rawTaker[takerIdx - 1]
                        if (abs(next.timestamp - t) < abs(prev.timestamp - t)) next else prev
                    }
                    p.copy(timestamp = t)
                }
            }
            alignedTaker.add(takerPoint)

            // 5. Global Ratio
            while (globalIdx < rawGlobal.size && rawGlobal[globalIdx].timestamp < t) {
                globalIdx++
            }
            val exactRawGlobal = if (globalIdx < rawGlobal.size && rawGlobal[globalIdx].timestamp == t) rawGlobal[globalIdx] else null

            val globalPoint = if (exactRawGlobal != null) {
                exactRawGlobal
            } else {
                while (existingGlobalIdx < existingGlobal.size && existingGlobal[existingGlobalIdx].timestamp < t) {
                    existingGlobalIdx++
                }
                val exactExistingGlobal = if (existingGlobalIdx < existingGlobal.size && existingGlobal[existingGlobalIdx].timestamp == t) existingGlobal[existingGlobalIdx] else null
                
                if (exactExistingGlobal != null) {
                    exactExistingGlobal
                } else {
                    val p = if (rawGlobal.isEmpty()) {
                        LongShortRatioPoint(t, 0.5, 0.5, 1.0)
                    } else if (globalIdx == rawGlobal.size) {
                        rawGlobal.last()
                    } else if (globalIdx == 0) {
                        rawGlobal.first()
                    } else {
                        val next = rawGlobal[globalIdx]
                        val prev = rawGlobal[globalIdx - 1]
                        if (abs(next.timestamp - t) < abs(prev.timestamp - t)) next else prev
                    }
                    p.copy(timestamp = t)
                }
            }
            alignedGlobal.add(globalPoint)

            // 6. Funding Rate
            while (existingFundingIdx < existingFunding.size && existingFunding[existingFundingIdx].timestamp < t) {
                existingFundingIdx++
            }
            val exactExistingFunding = if (existingFundingIdx < existingFunding.size && existingFunding[existingFundingIdx].timestamp == t) existingFunding[existingFundingIdx] else null

            val fundingPoint = if (exactExistingFunding != null) {
                exactExistingFunding
            } else {
                while (fundingIdx < rawFunding.size && rawFunding[fundingIdx].timestamp <= t) {
                    fundingIdx++
                }
                val p = if (rawFunding.isEmpty()) {
                    FundingRatePoint(t, 0.0)
                } else if (fundingIdx > 0) {
                    rawFunding[fundingIdx - 1]
                } else {
                    rawFunding[0]
                }
                p.copy(timestamp = t)
            }
            alignedFunding.add(fundingPoint)
        }

        return AlignedData(alignedOi, alignedAcc, alignedPos, alignedTaker, alignedGlobal, alignedFunding)
    }

    private fun calculateQuantBias(
        candles: List<Candle>,
        oiList: List<OpenInterestPoint>,
        accList: List<LongShortRatioPoint>,
        posList: List<LongShortRatioPoint>,
        takerList: List<TakerLongShortRatioPoint>,
        globalList: List<LongShortRatioPoint>,
        fundingList: List<FundingRatePoint>
    ): List<Double> {
        val outputs = container.consensus.evaluateMultimodal(
            candles = candles,
            rawOi = oiList,
            rawAcc = accList,
            rawPos = posList,
            rawGlobal = globalList,
            rawTaker = takerList,
            rawFunding = fundingList
        )
        return outputs.map { it.weightedScore }
    }

    private fun getPeriodDurationMs(period: String): Long = when (period) {
        "5m" -> 5 * 60 * 1000L
        "15m" -> 15 * 60 * 1000L
        "30m" -> 30 * 60 * 1000L
        "1h" -> 1 * 60 * 60 * 1000L
        "2h" -> 2 * 60 * 60 * 1000L
        "4h" -> 4 * 60 * 60 * 1000L
        "6h" -> 6 * 60 * 60 * 1000L
        "12h" -> 12 * 60 * 60 * 1000L
        "1d" -> 24 * 60 * 60 * 1000L
        else -> 1 * 60 * 60 * 1000L
    }

    override fun onCleared() {
        loadJob?.cancel()
        pollingJob?.cancel()
        tickerJob?.cancel()
        stalenessJob?.cancel()
        flashResetJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** WS-quiet threshold after which the UI state is flagged stale (nothing is fabricated). */
        const val STALE_AFTER_MS = 3_000L

        /** Minimum interval between staleness-age state pushes (bounds recomposition). */
        const val STALE_AGE_PUSH_MS = 5_000L
    }
}

private data class AlignedData(
    val oi: List<OpenInterestPoint>,
    val acc: List<LongShortRatioPoint>,
    val pos: List<LongShortRatioPoint>,
    val taker: List<TakerLongShortRatioPoint>,
    val global: List<LongShortRatioPoint>,
    val funding: List<FundingRatePoint>
)

data class PositionsUiState(
    val activeSymbol: String = "BTCUSDT",
    val period: String = "1h",
    val openInterest: List<OpenInterestPoint> = emptyList(),
    val accountRatio: List<LongShortRatioPoint> = emptyList(),
    val positionRatio: List<LongShortRatioPoint> = emptyList(),
    val takerRatio: List<TakerLongShortRatioPoint> = emptyList(),
    val globalRatio: List<LongShortRatioPoint> = emptyList(),
    val netTakerVolume: List<Double> = emptyList(),
    val fundingRate: List<FundingRatePoint> = emptyList(),
    val quantBias: List<Double> = emptyList(),
    val closePrices: List<Double> = emptyList(),
    val candles: List<Candle> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastUpdateMs: Long? = null,
    val priceChangeDirection: String = "NONE",
    /**
     * HONESTY FIELDS (replaces the deleted random price/series fabrication):
     * true once no WebSocket frame has arrived for more than [PositionsViewModel.STALE_AFTER_MS]
     * (or none ever arrived on this session). The OI/L/S/taker/funding series keep their
     * last REAL REST points — nothing is extrapolated between refreshes.
     */
    val isStale: Boolean = false,
    /** ms since the last WS frame; null = no frame received yet in this session. */
    val dataAgeMs: Long? = null,
) {
    val periods: List<String> = listOf("5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d")
}
