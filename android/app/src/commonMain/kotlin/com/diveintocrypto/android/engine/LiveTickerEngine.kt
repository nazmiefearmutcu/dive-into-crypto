package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.platform.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ONE live last-price per symbol for the whole venue.
 *
 *   - WS: a single `!miniTicker@arr` socket (all symbols, ~1s batches) wrapped in
 *     the self-healing [reconnectingFlow] — a watchlist of 500 symbols costs ONE
 *     connection. Stream price is merged OVER the REST price (fresher wins).
 *   - REST: `/ticker/24hr` (all) every 60s supplies the 24h change% (the mini
 *     ticker wire payload carries no change% — we never derive/fake it).
 *   - Source setting respected: FUTURES → fstream + fapi, SPOT → stream.binance + api.
 *
 * HONESTY: nothing is synthesized. While the socket is down the last REAL values
 * stay in the map and [isStale] / [dataAgeMs] say so.
 */
class LiveTickerEngine(
    private val futures: BinanceFuturesClient,
    private val spot: BinanceSpotClient,
    private val ws: BinanceWsClient,
    private val settingsStore: SettingsStore,
) {
    /** Latest real quote for one symbol. [changePercent] is null until the first REST refresh. */
    data class LiveTicker(
        val symbol: String,
        val price: Double,
        val changePercent: Double?,
        val ts: Long,
        /** "WS" = mini-ticker stream price, "REST" = ticker/24hr snapshot. */
        val source: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startLock = SynchronizedObject()

    private val _tickers = MutableStateFlow<Map<String, LiveTicker>>(emptyMap())
    val tickers: StateFlow<Map<String, LiveTicker>> = _tickers.asStateFlow()

    private val _isStale = MutableStateFlow(true)
    val isStale: StateFlow<Boolean> = _isStale.asStateFlow()

    private val _dataAgeMs = MutableStateFlow<Long?>(null)
    val dataAgeMs: StateFlow<Long?> = _dataAgeMs.asStateFlow()

    @Volatile private var started = false
    @Volatile private var lastStreamTickMs = 0L

    /** Idempotent — the engine is app-scoped; first caller wins. */
    fun ensureStarted() {
        synchronized(startLock) {
            if (started) return
            started = true
        }
        startStream()
        startRestRefresh()
        startStalenessWatchdog()
    }

    private fun wsBaseUrl(): String =
        if (settingsStore.getSettings().wsDataSource == "SPOT") SPOT_WS_URL else FUTURES_WS_URL

    private fun startStream() {
        scope.launch {
            ws.reconnectingMiniTickerStream(customBaseUrl = wsBaseUrl()).collect { batch ->
                lastStreamTickMs = nowMillis()
                _tickers.update { mergeStream(it, batch, nowMillis()) }
            }
        }
    }

    private fun startRestRefresh() {
        scope.launch {
            while (true) {
                try {
                    val useSpot = settingsStore.getSettings().wsDataSource == "SPOT"
                    val rest = if (useSpot) spot.ticker24hAll() else futures.ticker24hAll()
                    _tickers.update { mergeRest(it, rest, nowMillis()) }
                } catch (t: Throwable) {
                    // Honest: keep the last real values; retry on the next cycle.
                    logError("LiveTickerEngine", "24h ticker refresh failed: ${t.message}", t)
                }
                delay(REST_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun startStalenessWatchdog() {
        scope.launch {
            var lastAgePush = 0L
            while (true) {
                delay(1_000)
                val now = nowMillis()
                val last = lastStreamTickMs
                val age = if (last > 0L) now - last else null
                val stale = age == null || age > STALE_AFTER_MS
                _isStale.value = stale
                _dataAgeMs.value = age
                lastAgePush = now // age is pushed every second (single consumer, cheap)
            }
        }
    }

    companion object {
        const val FUTURES_WS_URL = "wss://fstream.binance.com"
        const val SPOT_WS_URL = "wss://stream.binance.com:9443"
        const val REST_REFRESH_INTERVAL_MS = 60_000L
        const val STALE_AFTER_MS = 10_000L

        /**
         * PURE: fold one wire batch over the current map. A stream tick refreshes
         * the price but PRESERVES the REST-sourced 24h change% (the mini ticker
         * wire payload has no change% — it must not be invented).
         */
        fun mergeStream(
            existing: Map<String, LiveTicker>,
            batch: List<BinanceWsClient.MiniTicker>,
            nowMs: Long,
        ): Map<String, LiveTicker> {
            if (batch.isEmpty()) return existing
            val out = existing.toMutableMap()
            for (t in batch) {
                val cur = out[t.symbol]
                out[t.symbol] = if (cur == null) {
                    LiveTicker(t.symbol, t.lastPrice, changePercent = null, ts = nowMs, source = "WS")
                } else {
                    cur.copy(price = t.lastPrice, ts = nowMs, source = "WS")
                }
            }
            return out
        }

        /**
         * PURE: fold the 60s REST all-ticker snapshot over the current map.
         * REST never clobbers a fresher WS price — it only supplies the 24h
         * change% (and the initial price for symbols the stream hasn't ticked yet).
         */
        fun mergeRest(
            existing: Map<String, LiveTicker>,
            rest: List<Ticker24h>,
            nowMs: Long,
        ): Map<String, LiveTicker> {
            if (rest.isEmpty()) return existing
            val out = existing.toMutableMap()
            for (r in rest) {
                val cur = out[r.symbol]
                out[r.symbol] = when {
                    cur == null -> LiveTicker(r.symbol, r.lastPrice, r.priceChangePercent, nowMs, "REST")
                    cur.source == "WS" -> cur.copy(changePercent = r.priceChangePercent)
                    else -> cur.copy(price = r.lastPrice, changePercent = r.priceChangePercent, ts = nowMs, source = "REST")
                }
            }
            return out
        }
    }
}
