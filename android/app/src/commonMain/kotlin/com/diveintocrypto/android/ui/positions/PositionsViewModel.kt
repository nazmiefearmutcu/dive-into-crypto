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
import kotlin.random.Random

/**
 * PositionsViewModel handles loading and live updates for market position data.
 * It combines parallel history REST calls on start, high-frequency 5-second polling
 * in the background, and WebSocket live tickers for real-time sub-second price moves
 * and simulated micro-fluctuations.
 */
class PositionsViewModel(private val container: AppContainer) : ViewModel() {

    private val lock = SynchronizedObject()
    private var cachedCandles = listOf<Candle>()

    private val _ui = MutableStateFlow(PositionsUiState())
    val ui: StateFlow<PositionsUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var pollingJob: Job? = null
    private var tickerJob: Job? = null
    private var fallbackTickerJob: Job? = null
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
        fallbackTickerJob?.cancel()
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
        fallbackTickerJob?.cancel()

        var lastWsMessageTime = 0L

        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    container.repository.liveKlines(symbol, period).collect { update ->
                        lastWsMessageTime = nowMillis()
                        processTick(update.candle.close, update.candle.openTime)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    delay(5000)
                }
            }
        }

        fallbackTickerJob = viewModelScope.launch(Dispatchers.Default) {
            delay(3000)
            while (true) {
                val now = nowMillis()
                if (now - lastWsMessageTime > 3000) {
                    val state = _ui.value
                    val lastPrice = state.closePrices.lastOrNull() ?: 0.0
                    if (lastPrice > 0.0) {
                        val durationMs = getPeriodDurationMs(period)
                        val openTime = (nowMillis() / durationMs) * durationMs
                        val simulatedPrice = lastPrice * (1.0 + Random.nextDouble(-0.0002, 0.0002))
                        processTick(simulatedPrice, openTime)
                    }
                }
                delay(1000)
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

        val state = _ui.value
        val currentOi = state.openInterest.toMutableList()
        val currentAcc = state.accountRatio.toMutableList()
        val currentPos = state.positionRatio.toMutableList()
        val currentTaker = state.takerRatio.toMutableList()
        val currentGlobal = state.globalRatio.toMutableList()
        val currentFunding = state.fundingRate.toMutableList()

        if (currentOi.isNotEmpty() && currentAcc.isNotEmpty() && currentPos.isNotEmpty() && currentTaker.isNotEmpty() && currentGlobal.isNotEmpty() && currentFunding.isNotEmpty()) {
            val lastOi = currentOi.last()
            val lastAcc = currentAcc.last()
            val lastPos = currentPos.last()
            val lastTaker = currentTaker.last()
            val lastGlobal = currentGlobal.last()
            val lastFunding = currentFunding.last()

            val simulatedOiVal = lastOi.sumOpenInterestValue * (1.0 + Random.nextDouble(-0.00005, 0.00005))
            val newOiPoint = lastOi.copy(timestamp = openTime, sumOpenInterestValue = simulatedOiVal)

            val accDelta = Random.nextDouble(-0.002, 0.002)
            val simulatedAccRatio = (lastAcc.longShortRatio + accDelta).coerceIn(0.1, 10.0)
            val accLongFraction = simulatedAccRatio / (simulatedAccRatio + 1.0)
            val newAccPoint = lastAcc.copy(
                timestamp = openTime,
                longShortRatio = simulatedAccRatio,
                longAccount = accLongFraction,
                shortAccount = 1.0 - accLongFraction
            )

            val posDelta = Random.nextDouble(-0.002, 0.002)
            val simulatedPosRatio = (lastPos.longShortRatio + posDelta).coerceIn(0.1, 10.0)
            val posLongFraction = simulatedPosRatio / (simulatedPosRatio + 1.0)
            val newPosPoint = lastPos.copy(
                timestamp = openTime,
                longShortRatio = simulatedPosRatio,
                longAccount = posLongFraction,
                shortAccount = 1.0 - posLongFraction
            )

            val globalDelta = Random.nextDouble(-0.002, 0.002)
            val simulatedGlobalRatio = (lastGlobal.longShortRatio + globalDelta).coerceIn(0.1, 10.0)
            val globalLongFraction = simulatedGlobalRatio / (simulatedGlobalRatio + 1.0)
            val newGlobalPoint = lastGlobal.copy(
                timestamp = openTime,
                longShortRatio = simulatedGlobalRatio,
                longAccount = globalLongFraction,
                shortAccount = 1.0 - globalLongFraction
            )

            val buyVolDelta = Random.nextDouble(-10.0, 10.0)
            val sellVolDelta = Random.nextDouble(-10.0, 10.0)
            val newBuyVol = (lastTaker.buyVol + buyVolDelta).coerceAtLeast(1.0)
            val newSellVol = (lastTaker.sellVol + sellVolDelta).coerceAtLeast(1.0)
            val simulatedTakerRatio = newBuyVol / newSellVol
            val newTakerPoint = lastTaker.copy(
                timestamp = openTime,
                buySellRatio = simulatedTakerRatio,
                buyVol = newBuyVol,
                sellVol = newSellVol
            )

            val newFundingPoint = lastFunding.copy(timestamp = openTime)

            if (isNewCandle) {
                currentOi.add(newOiPoint)
                while (currentOi.size > limit) currentOi.removeAt(0)

                currentAcc.add(newAccPoint)
                while (currentAcc.size > limit) currentAcc.removeAt(0)

                currentPos.add(newPosPoint)
                while (currentPos.size > limit) currentPos.removeAt(0)

                currentTaker.add(newTakerPoint)
                while (currentTaker.size > limit) currentTaker.removeAt(0)

                currentGlobal.add(newGlobalPoint)
                while (currentGlobal.size > limit) currentGlobal.removeAt(0)

                currentFunding.add(newFundingPoint)
                while (currentFunding.size > limit) currentFunding.removeAt(0)
            } else {
                currentOi[currentOi.lastIndex] = newOiPoint
                currentAcc[currentAcc.lastIndex] = newAccPoint
                currentPos[currentPos.lastIndex] = newPosPoint
                currentTaker[currentTaker.lastIndex] = newTakerPoint
                currentGlobal[currentGlobal.lastIndex] = newGlobalPoint
                currentFunding[currentFunding.lastIndex] = newFundingPoint
            }
        }

        val bias = calculateQuantBias(updatedCandles, currentOi, currentAcc, currentPos, currentTaker, currentGlobal, currentFunding)

        _ui.update {
            it.copy(
                closePrices = updatedCandles.map { c -> c.close },
                candles = updatedCandles,
                openInterest = currentOi,
                accountRatio = currentAcc,
                positionRatio = currentPos,
                takerRatio = currentTaker,
                globalRatio = currentGlobal,
                fundingRate = currentFunding,
                netTakerVolume = currentTaker.map { t -> t.buyVol - t.sellVol },
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
        fallbackTickerJob?.cancel()
        flashResetJob?.cancel()
        super.onCleared()
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
) {
    val periods: List<String> = listOf("5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d")
}
