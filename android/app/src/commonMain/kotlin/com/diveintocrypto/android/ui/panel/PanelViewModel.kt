package com.diveintocrypto.android.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.UpstreamHttpException
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.domain.consensus.Regime
import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.domain.overlay.Microstructure
import com.diveintocrypto.android.domain.overlay.MtfConfluence
import com.diveintocrypto.android.engine.appendLiveCandle
import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.i18n.DiveStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *
 * SYMBOL SWITCH SAFETY: the cached candles and REST series are only ever used for the
 * symbol/timeframe recorded in [loadedKey], and every write from a background job is
 * checked against the symbol the screen shows right now. A late result from the
 * previous symbol is therefore dropped instead of being drawn under the new title.
 */
class PanelViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(PanelUiState())
    val ui: StateFlow<PanelUiState> = _ui.asStateFlow()

    /** Serialises every access to the cached candles / REST series / throttles below. */
    private val stateMutex = Mutex()

    /** Symbol+timeframe whose candles and REST series are cached. Guarded by [stateMutex]. */
    private var loadedKey: Pair<String, String>? = null

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

    private var bootstrapJob: Job? = null
    private var multiTfJob: Job? = null
    private var tickerJob: Job? = null
    private var stalenessJob: Job? = null

    // ── CHART + CVD plumbing (additive; UI lane renders [PanelUiState] fields) ──
    private var cvdJob: Job? = null
    private var parityJob: Job? = null
    private var lastCvdFetchMs = 0L
    private var lastParityFetchMs = 0L

    /** Time of the last WS frame for the staleness watchdog (0 = none yet). */
    private val lastWsMessageTime = kotlinx.atomicfu.atomic(0L)

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                container.activeSymbol,
                container.activeTimeframe
            ) { symbol, timeframe ->
                symbol to timeframe
            }.collect { (symbol, timeframe) ->
                // Per-symbol state goes back to its defaults in the same atomic update that
                // changes the active symbol, so no previous symbol's value survives the switch.
                _ui.update { st ->
                    PanelUiState(
                        activeSymbol = symbol,
                        timeframe = timeframe,
                        isLoading = true,
                        allSymbols = st.allSymbols,
                        filteredSymbols = st.filteredSymbols,
                        searchQuery = st.searchQuery,
                        favorites = st.favorites,
                        isStale = st.isStale,
                        dataAgeMs = st.dataAgeMs,
                    )
                }
                restartJobs(symbol, timeframe)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val symbols = container.repository.futuresUniverse()
                _ui.update { st ->
                    st.copy(
                        allSymbols = symbols,
                        // Keep a search the user already typed while the universe was loading.
                        filteredSymbols = filterSymbols(symbols, st.searchQuery),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logError("PanelVM", "Failed to fetch futures universe", e)
            }
        }
    }

    private fun strings(): DiveStrings = container.language.value

    /** True while [symbol]/[timeframe] is the symbol/timeframe the screen currently shows. */
    private fun isShowing(symbol: String, timeframe: String): Boolean {
        val st = _ui.value
        return st.activeSymbol == symbol && st.timeframe == timeframe
    }

    /**
     * Applies [block] only while [symbol] (and [timeframe], when given) is still the one shown.
     * The check and the write happen in one atomic StateFlow update.
     */
    private fun updateFor(symbol: String, timeframe: String?, block: (PanelUiState) -> PanelUiState) {
        _ui.update { st ->
            if (st.activeSymbol == symbol && (timeframe == null || st.timeframe == timeframe)) block(st) else st
        }
    }

    private fun restartJobs(symbol: String, timeframe: String) {
        bootstrapJob?.cancel()
        multiTfJob?.cancel()
        tickerJob?.cancel()
        stalenessJob?.cancel()
        cvdJob?.cancel()
        parityJob?.cancel()

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

                    val cs = candlesDeferred.await()
                    logDebug("PanelVM", "bootstrapJob: candles fetched count=${cs.size}")
                    val oi = oiDeferred.await()
                    val accountRatio = accountRatioDeferred.await()
                    val positionRatio = positionRatioDeferred.await()
                    val taker = takerRatioDeferred.await()
                    val globalRatio = globalRatioDeferred.await()
                    val fundingRate = fundingRateDeferred.await()
                    ensureActive()

                    stateMutex.withLock {
                        // A newer symbol/timeframe is on screen: this result belongs to nobody now.
                        if (!isShowing(symbol, timeframe)) return@withLock
                        candles = cs
                        // Cache the REAL REST series for the throttled live recompute.
                        lastOi = oi
                        lastAcc = accountRatio
                        lastPos = positionRatio
                        lastTaker = taker
                        lastGlob = globalRatio
                        lastFunding = fundingRate
                        loadedKey = symbol to timeframe
                        // A successful bootstrap is the only thing that clears a load error.
                        updateFor(symbol, timeframe) { it.copy(errorMessage = null) }
                        recomputeMultimodal(symbol, timeframe)
                        refreshCvd(symbol, force = true)
                        refreshParity(symbol, force = true)
                    }
                }
            } catch (e: CancellationException) {
                logError("PanelVM", "bootstrapJob cancelled", e)
                throw e
            } catch (t: Throwable) {
                logError("PanelVM", "bootstrapJob error", t)
                val message = errorText(t, strings())
                updateFor(symbol, timeframe) { it.copy(isLoading = false, errorMessage = message) }
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
                    _ui.update { it.copy(isStale = false, dataAgeMs = 0L) }
                    try {
                        val now = nowMillis()
                        stateMutex.withLock {
                            // Ticks only feed the cache once THIS symbol's history is loaded;
                            // before that a single bar would be shown as a verdict.
                            val ready = loadedKey == (symbol to timeframe) && isShowing(symbol, timeframe)
                            val gap = ready && mergeTickCandle(
                                update.candle,
                                container.repository.getIntervalMs(timeframe),
                            )
                            if (gap) reloadAfterGap(symbol, timeframe)
                            updateFor(symbol, timeframe) { st ->
                                st.copy(
                                    currentPrice = update.candle.close,
                                    priceChangeDirection = priceDirection(st.currentPrice, update.candle.close, st.priceChangeDirection),
                                    lastUpdateMs = nowMillis(),
                                )
                            }
                            // THROTTLED LIVE RECOMPUTE: rerun indicators over the cached
                            // candles at most every [LIVE_RECOMPUTE_INTERVAL_MS], plus
                            // immediately on candle close. Recompose only when a value changed.
                            if (ready && !gap && (now - lastRecomputeMs >= LIVE_RECOMPUTE_INTERVAL_MS || update.isClosed)) {
                                lastRecomputeMs = now
                                recomputeMultimodal(symbol, timeframe)
                                // CVD rides the same throttle; the engine's 10s cache makes
                                // the actual REST cadence ≥10s per symbol.
                                refreshCvd(symbol)
                                // Parity blocks ride the same throttle (premiumIndex 15s cache,
                                // candles already engine-cached).
                                refreshParity(symbol)
                            }
                        }
                        // The 12-TF grid refreshes on candle close (grid cells only change
                        // when a TF's candle settles — keeps network chatter bounded).
                        if (update.isClosed) refreshMultiTf(symbol)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // One bad tick must not end the live feed for the session.
                        logError("PanelVM", "live tick processing failed", t)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // WS is optional — the REST snapshot already provided the last price.
                logError("PanelVM", "live ticker stopped", t)
            }
        }

        // Clock-only staleness watchdog (reads the wall clock; never fabricates data).
        stalenessJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1_000)
                val last = lastWsMessageTime.value
                if (last <= 0L) continue // no frame received yet in this session
                val age = nowMillis() - last
                val stale = age > STALE_AFTER_MS
                // The age changes every second, so this pushes once per second by design:
                // that keeps the "last update" and stale-chip ages live.
                _ui.update { st ->
                    if (st.isStale == stale && st.dataAgeMs == age) st else st.copy(isStale = stale, dataAgeMs = age)
                }
            }
        }
    }

    /**
     * Folds a live WS candle into the cached candle list (real ticks only). Guarded by [stateMutex].
     * Returns true, and appends nothing, when the candle opens more than one interval after the
     * cached tail: a missing bar must never be bridged (see [reloadAfterGap]).
     */
    private fun mergeTickCandle(candle: Candle, intervalMs: Long): Boolean {
        val last = candles.lastOrNull()
        if (last != null && intervalMs > 0L && candle.openTime - last.openTime > intervalMs) return true
        candles = appendLiveCandle(candles, candle, intervalMs).takeLast(300)
        return false
    }

    /**
     * A live tick opened after a missing bar: the cached series must not bridge it. The
     * symbol's history is reloaded from REST (same as a fresh symbol switch) and ticks are
     * ignored until that reload has landed. Called under [stateMutex].
     */
    private fun reloadAfterGap(symbol: String, timeframe: String) {
        loadedKey = null
        viewModelScope.launch {
            if (isShowing(symbol, timeframe)) restartJobs(symbol, timeframe)
        }
    }

    /**
     * Rolling CVD for the active symbol. Fire-and-forget on the VM scope; the engine caches
     * 10s per symbol (plus [CVD_FETCH_INTERVAL_MS] local guard) so the REST cadence stays
     * polite. On failure the headline fields keep the last real values for this symbol and
     * [PanelUiState.cvdUnavailable] flips true — nothing is zero-filled or fabricated.
     * Called under [stateMutex].
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
            updateFor(symbol, null) { st ->
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
     * Parity blocks (basis / funding lens / vol-cone / planning) for the active symbol.
     * Fire-and-forget on the VM scope; the engine's caches (premium 15s, funding + candles
     * 30s+) keep the REST cadence polite. On ANY failure the affected fields stay/become
     * null — nothing is zero-filled or fabricated. Called under [stateMutex].
     */
    private fun refreshParity(symbol: String, force: Boolean = false) {
        val now = nowMillis()
        if (!force && now - lastParityFetchMs < PARITY_FETCH_INTERVAL_MS) return
        lastParityFetchMs = now
        val atrPct = lastAtrPct
        val direction = lastDirection
        parityJob?.cancel()
        parityJob = viewModelScope.launch(Dispatchers.Default) {
            val bundle = try {
                container.repository.panelParity(symbol, atrPct, direction)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            updateFor(symbol, null) { st ->
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

    /** Cheap fingerprint to decide whether the chart series actually moved. */
    private fun chartChanged(st: PanelUiState, chart: ChartSeries): Boolean {
        val oldLast = st.chartCandles.lastOrNull()
        val newLast = chart.candles.lastOrNull()
        return st.chartCandles.size != chart.candles.size ||
            oldLast?.openTime != newLast?.openTime ||
            oldLast?.close != newLast?.close ||
            oldLast?.high != newLast?.high ||
            oldLast?.low != newLast?.low ||
            oldLast?.volume != newLast?.volume ||
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
                            if (cs.size < MIN_CANDLES_FOR_VERDICT) {
                                TfSignal(tf = tf, signal = "N/A", confidence = 0)
                            } else {
                                try {
                                    val indResults = container.indicators.map {
                                        val res = it.calculate(cs)
                                        res
                                    }
                                    val out = container.consensus.evaluate(
                                        indResults,
                                        com.diveintocrypto.android.ui.i18n.consensusLabelsFor(container.language.value),
                                    )
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
                updateFor(symbol, null) { it.copy(multiTf = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError("PanelVM", "multiTfJob: error", t)
            }
        }
    }

    /**
     * Reruns the multimodal consensus + overlays over the CACHED candles/series for
     * [symbol]/[timeframe]. Called only under [stateMutex] and only while [loadedKey] names
     * that symbol/timeframe.
     */
    private fun recomputeMultimodal(symbol: String, timeframe: String) {
        val oi = lastOi
        val accountRatio = lastAcc
        val positionRatio = lastPos
        val taker = lastTaker
        val globalRatio = lastGlob
        val fundingRate = lastFunding
        logDebug("PanelVM", "recomputeMultimodal: candles=${candles.size}, oi=${oi.size}, acc=${accountRatio.size}, pos=${positionRatio.size}, taker=${taker.size}, global=${globalRatio.size}, funding=${fundingRate.size}")
        val s = strings()
        if (candles.isEmpty()) {
            updateFor(symbol, timeframe) { it.copy(isLoading = false, errorMessage = s.panelNoCandles(symbol, timeframe)) }
            return
        }
        if (candles.size < MIN_CANDLES_FOR_VERDICT) {
            // Too little real history for an honest verdict: say so instead of computing one.
            updateFor(symbol, timeframe) {
                it.copy(isLoading = false, errorMessage = s.panelNotEnoughCandles(symbol, timeframe, candles.size))
            }
            return
        }
        // HONESTY: an endpoint with no history (for example a new listing without ratio data) must not
        // produce a verdict. The engine would fill the empty series with neutral placeholders and score
        // them, so the Panel says what is missing and computes nothing.
        if (oi.isEmpty() || accountRatio.isEmpty() || positionRatio.isEmpty() ||
            taker.isEmpty() || globalRatio.isEmpty() || fundingRate.isEmpty()
        ) {
            updateFor(symbol, timeframe) { it.copy(isLoading = false, errorMessage = s.panelNoSeriesData(symbol, timeframe)) }
            return
        }
        val consensusList = container.consensus.evaluateMultimodal(
            candles = candles,
            rawOi = oi,
            rawAcc = accountRatio,
            rawPos = positionRatio,
            rawGlobal = globalRatio,
            rawTaker = taker,
            rawFunding = fundingRate,
            labels = com.diveintocrypto.android.ui.i18n.consensusLabelsFor(container.language.value),
        )
        logDebug("PanelVM", "recomputeMultimodal: consensusList count=${consensusList.size}")
        val consensus = consensusList.lastOrNull()
        if (consensus == null) {
            logError("PanelVM", "recomputeMultimodal: consensusList is empty!")
            updateFor(symbol, timeframe) { it.copy(isLoading = false, errorMessage = s.panelConsensusFailed) }
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
        // MICROSTRUCTURE (domain/overlay/Microstructure.kt): directed bundle over the
        // REST series aligned to the candle timeline.
        val micro = Microstructure.evaluate(
            alignMicrostructure(
                candles = candles,
                oi = oi.map { it.timestamp to it.sumOpenInterestValue },
                pos = positionRatio.map { it.timestamp to it.longShortRatio },
                taker = taker.map { it.timestamp to it.buySellRatio },
                glob = globalRatio.map { it.timestamp to it.longShortRatio },
                funding = fundingRate.map { it.timestamp to it.fundingRate },
            )
        )
        // MTF-CONFLUENCE (domain/overlay/MtfConfluence.kt): agreement across the
        // 12-TF mini grid.
        val mtf = MtfConfluence.confluence(
            _ui.value.multiTf.map { MtfConfluence.TfVerdict(it.tf, it.signal, it.confidence) }
        )

        val action = PanelAction.of(consensus.finalSignal, consensus.shouldTrade)
        val lastClose = candles.last().close

        // CHART SERIES: computed FROM THE SAME cached candles via the indicator
        // engine's own math (Series.ewmAdjustFalse / rollingMean / rollingStd).
        // Recomputed on this (≤5s) throttle; pushed only when something moved.
        val chart = computeChartSeries(candles)

        // Emit ONLY when a displayed value actually changed (bounded recomposition).
        updateFor(symbol, timeframe) { st ->
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
            // NOTE: errorMessage is NOT cleared here — only a successful bootstrap clears it.
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
                lastUpdateMs = nowMillis(),
            )
        }
        logDebug("PanelVM", "recomputeMultimodal: UI state checked/updated. Signal=${consensus.finalSignal.name}")
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
        _ui.update { state ->
            state.copy(
                searchQuery = query,
                filteredSymbols = filterSymbols(state.allSymbols, query)
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

        /** Local guard for CVD REST fetches (the engine adds its own 10s cache). */
        const val CVD_FETCH_INTERVAL_MS = 10_000L

        /** Local guard for parity REST fetches (engine caches: premium 15s, candles 30s+). */
        const val PARITY_FETCH_INTERVAL_MS = 15_000L

        /** Fewest candles that give an honest verdict and TF cell (matches the 12-TF grid). */
        const val MIN_CANDLES_FOR_VERDICT = 50

        private fun maskWarmup(values: List<Double>, warmup: Int): List<Double?> =
            values.mapIndexed { i, v -> if (i < warmup) null else v }

        /** Symbol filter for the coin search (case-insensitive substring; empty = everything). */
        internal fun filterSymbols(all: List<String>, query: String): List<String> {
            val q = query.uppercase()
            return if (q.isEmpty()) all else all.filter { it.contains(q) }
        }

        /** Tick-to-tick direction for the status price colour; equal prices keep the previous direction. */
        internal fun priceDirection(previous: Double?, now: Double, keep: String): String = when {
            previous == null -> "NONE"
            now > previous -> "UP"
            now < previous -> "DOWN"
            else -> keep
        }

        /** Human-readable load error: region block, upstream HTTP status, or a generic network failure. */
        internal fun errorText(t: Throwable, s: DiveStrings): String {
            if (t.isRegionBlocked()) return s.errRegionBlockedBody
            var cause: Throwable? = t
            var depth = 0
            while (cause != null && depth < 8) {
                if (cause is UpstreamHttpException && cause.forbidden) return s.errForbiddenBody
                if (cause is UpstreamHttpException) return s.errUpstreamGeneric
                cause = cause.cause
                depth++
            }
            return s.errNetwork
        }

        /**
         * Aligns the REST series to the candle timeline (each candle takes the last point at or
         * before its openTime). Candles older than the newest series' first point are dropped so
         * every list stays index-aligned, as [Microstructure.Series] requires. Empty series stay empty.
         */
        internal fun alignMicrostructure(
            candles: List<Candle>,
            oi: List<Pair<Long, Double>>,
            pos: List<Pair<Long, Double>>,
            taker: List<Pair<Long, Double>>,
            glob: List<Pair<Long, Double>>,
            funding: List<Pair<Long, Double>>,
        ): Microstructure.Series {
            val sorted = listOf(oi, pos, taker, glob, funding).map { s -> s.sortedBy { it.first } }
            val startTime = sorted.filter { it.isNotEmpty() }.maxOfOrNull { it.first().first } ?: Long.MIN_VALUE
            val start = candles.indexOfFirst { it.openTime >= startTime }.let { if (it < 0) candles.size else it }
            val times = candles.subList(start, candles.size).map { it.openTime }

            fun fill(points: List<Pair<Long, Double>>): List<Double> {
                if (points.isEmpty()) return emptyList()
                val out = ArrayList<Double>(times.size)
                var p = -1
                for (t in times) {
                    while (p + 1 < points.size && points[p + 1].first <= t) p++
                    out.add(points[p.coerceAtLeast(0)].second)
                }
                return out
            }

            return Microstructure.Series(
                oi = fill(sorted[0]),
                price = candles.subList(start, candles.size).map { it.close },
                funding = fill(sorted[4]),
                taker = fill(sorted[2]),
                glob = fill(sorted[3]),
                pos = fill(sorted[1]),
            )
        }

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
