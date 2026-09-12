package com.diveintocrypto.android.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.domain.consensus.Regime
import com.diveintocrypto.android.domain.cvd.CvdSnapshot
import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.overlay.Microstructure
import com.diveintocrypto.android.domain.overlay.MtfConfluence
import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * Paper-free ViewModel for the Panel screen.
 *
 *   - `bootstrap()`        → fetches the active symbol's 1h klines + REST series,
 *                            runs the multimodal consensus, and populates the UI
 *                            state with the last price + verdict (+ overlays).
 *   - `bootstrapMultiTf()` → runs a separate kline + consensus for each of the
 *                            12 TFs, feeding the 12-cell mini grid.
 *   - `liveTicker()`       → subscribes to the (self-healing) WS klines stream.
 *                            Every tick updates currentPrice; the VERDICT is
 *                            recomputed at most every [LIVE_RECOMPUTE_INTERVAL_MS]
 *                            (and immediately on candle close) over the cached
 *                            candles/series. State is re-emitted only when a
 *                            value actually changed.
 *
 * NOTHING IS SYNTHESISED: no random-tick fallback exists; when the WS is quiet the
 * last REAL data stays and [PanelUiState.isStale] / [PanelUiState.dataAgeMs] say so.
 */
class PanelViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(PanelUiState())
    val ui: StateFlow<PanelUiState> = _ui.asStateFlow()

    private var candles: List<Candle> = emptyList()

    // Last REAL REST series — reused by the throttled live recompute (never fabricated).
    private var lastOi: List<OpenInterestPoint> = emptyList()
    private var lastAcc: List<LongShortRatioPoint> = emptyList()
    private var lastPos: List<LongShortRatioPoint> = emptyList()
    private var lastTaker: List<TakerLongShortRatioPoint> = emptyList()
    private var lastGlob: List<LongShortRatioPoint> = emptyList()
    private var lastFunding: List<FundingRatePoint> = emptyList()

    // Last REAL verdict context — feeds the pure parity math (planning strip).
    private var lastAtrPct: Double? = null
    private var lastDirection: String? = null

    private var bootstrapJob: kotlinx.coroutines.Job? = null
    private var multiTfJob: kotlinx.coroutines.Job? = null
    private var tickerJob: kotlinx.coroutines.Job? = null
    private var stalenessJob: kotlinx.coroutines.Job? = null
    private var parityJob: kotlinx.coroutines.Job? = null

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
        stalenessJob?.cancel()

        bootstrapJob = viewModelScope.launch(Dispatchers.Default) {
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
                    // Cache the REAL REST series for the throttled live recompute.
                    lastOi = oi
                    lastAcc = accountRatio
                    lastPos = positionRatio
                    lastTaker = taker
                    lastGlob = globalRatio
                    lastFunding = fundingRate
                    logDebug("PanelVM", "bootstrapJob: calling recomputeMultimodal")
                    recomputeMultimodal(oi, accountRatio, positionRatio, taker, globalRatio, fundingRate)
                    refreshCvd(symbol, force = true)
                    refreshParity(symbol, force = true)
                }
            } catch (e: CancellationException) {
                logError("PanelVM", "bootstrapJob cancelled", e)
                throw e
            } catch (t: Throwable) {
                logError("PanelVM", "bootstrapJob error", t)
                _ui.update {
                    it.copy(isLoading = false, errorMessage = t.message ?: "network error")
                }
            }
        }

        refreshMultiTf(symbol)

        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            var lastRecomputeMs = 0L
            try {
                // Self-healing stream (BinanceWsClient.reconnectingKlineStream) — a single
                // collect survives disconnects. NO synthetic ticks: quiet periods are
                // surfaced via the staleness fields instead.
                container.repository.liveKlines(symbol, timeframe).collect { update ->
                    lastWsMessageTime.value = nowMillis()
                    mergeTickCandle(update.candle)
                    _ui.update {
                        it.copy(
                            currentPrice = update.candle.close,
                            lastUpdateMs = nowMillis(),
                            isStale = false,
                            dataAgeMs = 0L,
                        )
                    }
                    val now = nowMillis()
                    // THROTTLED LIVE RECOMPUTE: rerun indicators over the cached candles
                    // at most every [LIVE_RECOMPUTE_INTERVAL_MS], plus immediately on
                    // candle close. Recompose only when a value actually changed.
                    if (now - lastRecomputeMs >= LIVE_RECOMPUTE_INTERVAL_MS || update.isClosed) {
                        lastRecomputeMs = now
                        recomputeVerdictFromCache()
                        // CVD rides the same throttle; the engine's 10s cache makes
                        // the actual REST cadence ≥10s per symbol.
                        refreshCvd(symbol)
                        // Parity blocks ride the same throttle (premiumIndex 15s cache,
                        // candles already engine-cached).
                        refreshParity(symbol)
                    }
                    // The 12-TF grid refreshes on candle close (grid cells only change
                    // when a TF's candle settles — keeps network chatter bounded).
                    if (update.isClosed) refreshMultiTf(symbol)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // WS is optional — the REST snapshot already provided the last price.
            }
        }

        // Clock-only staleness watchdog (reads the wall clock; never fabricates data).
        stalenessJob = viewModelScope.launch(Dispatchers.Default) {
            var lastAgePush = 0L
            while (true) {
                delay(1_000)
                val last = lastWsMessageTime.value
                if (last <= 0L) continue // no frame received yet in this session
                val now = nowMillis()
                val age = now - last
                val stale = age > STALE_AFTER_MS
                _ui.update {
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

    /** Time of the last WS frame for the staleness watchdog (0 = none yet). */
    private val lastWsMessageTime = kotlinx.atomicfu.atomic(0L)

    /** Folds a live WS candle into the cached candle list (real ticks only). */
    private fun mergeTickCandle(candle: Candle) {
        val last = candles.lastOrNull()
        candles = when {
            last == null -> listOf(candle)
            candle.openTime == last.openTime -> candles.dropLast(1) + candle
            candle.openTime > last.openTime -> (candles + candle).takeLast(300)
            else -> candles
        }
    }

    /** Reruns the multimodal consensus + overlays over the CACHED candles/series. */
    private fun recomputeVerdictFromCache() {
        recomputeMultimodal(lastOi, lastAcc, lastPos, lastTaker, lastGlob, lastFunding)
    }

    // ── CHART + CVD plumbing (additive; UI lane renders [PanelUiState] fields) ──

    private var cvdJob: kotlinx.coroutines.Job? = null
    private var lastCvdFetchMs = 0L
    private var lastParityFetchMs = 0L

    /**
     * Rolling CVD for the active symbol. Fire-and-forget on the VM scope; the
     * engine caches 10s per symbol (plus [CVD_FETCH_INTERVAL_MS] local guard) so
     * the REST cadence stays polite. On failure the headline fields keep the
     * last real values and [PanelUiState.cvdUnavailable] flips true — nothing
     * is zero-filled or fabricated.
     */
    private fun refreshCvd(symbol: String, force: Boolean = false) {
        val now = nowMillis()
        if (!force && now - lastCvdFetchMs < CVD_FETCH_INTERVAL_MS) return
        lastCvdFetchMs = now
        cvdJob?.cancel()
        cvdJob = viewModelScope.launch(Dispatchers.Default) {
            val snap = try {
                container.repository.cvdSnapshot(symbol)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            _ui.update { st ->
                if (snap == null) {
                    if (st.cvdUnavailable) st else st.copy(cvdUnavailable = true)
                } else st.copy(
                    cvd = snap.cvd,
                    cvdBuyVol = snap.buyVol,
                    cvdSellVol = snap.sellVol,
                    deltaSeries = snap.buckets,
                    cvdUnavailable = false,
                )
            }
        }
    }

    /**
     * Parity blocks (basis / funding lens / vol-cone / planning) for the active
     * symbol. Fire-and-forget on the VM scope; the engine's caches (premium 15s,
     * funding + candles 30s+) keep the REST cadence polite. On ANY failure the
     * affected fields stay/become null — nothing is zero-filled or fabricated.
     */
    private fun refreshParity(symbol: String, force: Boolean = false) {
        val now = nowMillis()
        if (!force && now - lastParityFetchMs < PARITY_FETCH_INTERVAL_MS) return
        lastParityFetchMs = now
        parityJob?.cancel()
        parityJob = viewModelScope.launch(Dispatchers.Default) {
            val bundle = try {
                container.repository.panelParity(symbol, lastAtrPct, lastDirection)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            _ui.update { st ->
                if (bundle == null) {
                    // Full unavailability — only flip fields that had values.
                    if (st.basisBlock == null && st.fundingLens == null && st.cone == null && st.planning == null) st
                    else st.copy(basisBlock = null, fundingLens = null, cone = null, planning = null)
                } else st.copy(
                    basisBlock = bundle.basisBlock,
                    fundingLens = bundle.fundingLens,
                    cone = bundle.cone,
                    planning = bundle.planning,
                )
            }
        }
    }

    /** Cheap fingerprint to decide whether the chart series actually moved. */    private fun chartChanged(st: PanelUiState, chart: ChartSeries): Boolean {
        val oldLast = st.chartCandles.lastOrNull()
        val newLast = chart.candles.lastOrNull()
        return st.chartCandles.size != chart.candles.size ||
            oldLast?.openTime != newLast?.openTime ||
            oldLast?.close != newLast?.close ||
            st.ema20.size != chart.ema20.size ||
            st.ema20.lastOrNull() != chart.ema20.lastOrNull() ||
            st.bbUpper.size != chart.bbUpper.size ||
            st.bbUpper.lastOrNull() != chart.bbUpper.lastOrNull()
    }

    /** Runs the 12-TF mini grid over freshly (cache-aware) fetched klines. */
    private fun refreshMultiTf(symbol: String) {
        multiTfJob?.cancel()
        multiTfJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val results = coroutineScope {
                    ALL_TIMEFRAMES.map { tf ->
                        async {
                            val cs = try {
                                container.repository.futuresHistory(symbol, tf, limit = 300)
                            } catch (e: CancellationException) {
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
                                } catch (e: CancellationException) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError("PanelVM", "multiTfJob: error", t)
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

        // ── STRATEGY OVERLAY ANNOTATIONS (README's "3 overlays") — ADDITIVE:
        //    they are surfaced next to the verdict and NEVER change it. ──────────
        // REGIME (domain/consensus/Regime.kt): label + adaptively-weighted
        // observational score from the already-computed ADX/Choppiness raws.
        val indResults = container.indicators.map { it.calculate(candles) }
        // Capture (DISPLAY-ONLY) the ATR% the indicator layer ALREADY computed and
        // the consensus direction — inputs for the pure parity planning strip.
        lastAtrPct = indResults.firstOrNull { it.name == "atr_filter" }?.rawValues?.get("atr_pct")
        lastDirection = when (consensus.finalSignal) {
            Signal.STRONG_BUY, Signal.BUY -> "LONG"
            Signal.STRONG_SELL, Signal.SELL -> "SHORT"
            else -> null // NEUTRAL gets no plan (honest)
        }
        val regimeEval = Regime.evaluate(indResults, container.settingsStore.getSettings().weights)
        // MICROSTRUCTURE (domain/overlay/Microstructure.kt): directed bundle over
        // the REAL aligned series (OI/price/funding/taker/global/whale L-S).
        val micro = Microstructure.evaluate(
            Microstructure.Series(
                oi = oi.map { it.sumOpenInterestValue },
                price = candles.map { it.close },
                funding = fundingRate.map { it.fundingRate },
                taker = taker.map { it.buySellRatio },
                glob = globalRatio.map { it.longShortRatio },
                pos = positionRatio.map { it.longShortRatio },
            )
        )
        // MTF-CONFLUENCE (domain/overlay/MtfConfluence.kt): agreement across the
        // 12-TF mini grid.
        val mtf = MtfConfluence.confluence(
            _ui.value.multiTf.map { MtfConfluence.TfVerdict(it.tf, it.signal, it.confidence) }
        )

        val action = if (consensus.shouldTrade) "OPEN_${consensus.finalSignal.name}" else "HOLD"
        val lastClose = candles.last().close

        // CHART SERIES: computed FROM THE SAME cached candles via the indicator
        // engine's own math (Series.ewmAdjustFalse / rollingMean / rollingStd).
        // Recomputed on this (≤5s) throttle; pushed only when something moved.
        val chart = computeChartSeries(candles)

        // Emit ONLY when a displayed value actually changed (bounded recomposition).
        _ui.update { st ->
            val changed = st.isLoading ||
                st.currentPrice != lastClose ||
                st.latestSignal != consensus.finalSignal.name ||
                st.confidence != consensus.confidence ||
                st.action != action ||
                st.reason != consensus.reason ||
                st.distBuy != consensus.buyCount ||
                st.distSell != consensus.sellCount ||
                st.distNeutral != consensus.neutralCount ||
                st.regime != regimeEval.regime ||
                st.regimeAdaptiveScore != regimeEval.adaptiveScore ||
                st.mtfScore != mtf.score ||
                st.mtfDirection != mtf.direction ||
                st.mtfGate != mtf.gate ||
                st.mtfLabel != mtf.label ||
                st.microScore != micro.score ||
                st.microDirection != micro.direction ||
                st.microLabel != micro.label ||
                st.microActive != micro.active ||
                chartChanged(st, chart)
            if (!changed) st else st.copy(
                currentPrice = lastClose,
                latestSignal = consensus.finalSignal.name,
                confidence = consensus.confidence,
                action = action,
                reason = consensus.reason,
                distBuy = consensus.buyCount,
                distSell = consensus.sellCount,
                distNeutral = consensus.neutralCount,
                regime = regimeEval.regime,
                regimeAdaptiveScore = regimeEval.adaptiveScore,
                mtfScore = mtf.score,
                mtfDirection = mtf.direction,
                mtfGate = mtf.gate,
                mtfLabel = mtf.label,
                microScore = micro.score,
                microDirection = micro.direction,
                microLabel = micro.label,
                microActive = micro.active,
                chartCandles = chart.candles,
                ema20 = chart.ema20,
                ema50 = chart.ema50,
                bbUpper = chart.bbUpper,
                bbLower = chart.bbLower,
                isLoading = false,
                errorMessage = null,
                lastUpdateMs = nowMillis(),
            )
        }
        logDebug("PanelVM", "recomputeMultimodal: UI state checked/updated. Signal=${consensus.finalSignal.name}, Reason=${consensus.reason}")
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

    override fun onCleared() {
        bootstrapJob?.cancel()
        multiTfJob?.cancel()
        tickerJob?.cancel()
        stalenessJob?.cancel()
        cvdJob?.cancel()
        parityJob?.cancel()
        super.onCleared()
    }

    /** Chart-ready overlay series over one candle list (PURE, unit-testable). */
    internal data class ChartSeries(
        val candles: List<Candle>,
        val ema20: List<Double?>,
        val ema50: List<Double?>,
        val bbUpper: List<Double?>,
        val bbLower: List<Double?>,
    )

    companion object {
        /** Cadence of the live verdict recompute over cached candles (≥5s per spec). */
        const val LIVE_RECOMPUTE_INTERVAL_MS = 5_000L

        /** WS-quiet threshold after which the UI state is flagged stale (nothing is fabricated). */
        const val STALE_AFTER_MS = 3_000L

        /** Minimum interval between staleness-age state pushes (bounds recomposition). */
        const val STALE_AGE_PUSH_MS = 5_000L

        /** Local guard for CVD REST fetches (the engine adds its own 10s cache). */
        const val CVD_FETCH_INTERVAL_MS = 10_000L

        /** Local guard for parity REST fetches (engine caches: premium 15s, candles 30s+). */
        const val PARITY_FETCH_INTERVAL_MS = 15_000L

        private fun maskWarmup(values: List<Double>, warmup: Int): List<Double?> =
            values.mapIndexed { i, v -> if (i < warmup) null else v }

        /**
         * Computes EMA(20), EMA(50) and Bollinger(20,2) bands over [candles]
         * using the indicator engine's own Series math (NO duplicated formulas).
         * Warm-up positions are null (honest: the series simply is not defined
         * there yet). When a period is not yet covered, the whole series is null.
         */
        internal fun computeChartSeries(candles: List<Candle>): ChartSeries {
            val closes = candles.map { it.close }
            if (closes.isEmpty()) {
                return ChartSeries(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }
            val ema20 = if (closes.size >= 20) maskWarmup(Series.ewmAdjustFalse(closes, 20), 19)
            else List(closes.size) { null }
            val ema50 = if (closes.size >= 50) maskWarmup(Series.ewmAdjustFalse(closes, 50), 49)
            else List(closes.size) { null }

            val bbUpper: List<Double?>
            val bbLower: List<Double?>
            if (closes.size >= 20) {
                val mean = Series.rollingMean(closes, 20)
                val std = Series.rollingStd(closes, 20)
                bbUpper = List(closes.size) { i ->
                    val m = mean[i]; val s = std[i]
                    if (m != null && s != null) m + 2.0 * s else null
                }
                bbLower = List(closes.size) { i ->
                    val m = mean[i]; val s = std[i]
                    if (m != null && s != null) m - 2.0 * s else null
                }
            } else {
                bbUpper = List(closes.size) { null }
                bbLower = List(closes.size) { null }
            }
            return ChartSeries(candles, ema20, ema50, bbUpper, bbLower)
        }
    }
}
