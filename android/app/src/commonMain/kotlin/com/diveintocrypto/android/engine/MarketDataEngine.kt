package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
import com.diveintocrypto.android.engine.exchanges.binance.toOhlcv
import com.diveintocrypto.android.engine.exchanges.deribit.DeribitConnector
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.OptionsChain
import com.diveintocrypto.android.engine.schema.Record
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.atomicfu.locks.SynchronizedObject
import com.diveintocrypto.android.platform.synchronized

/**
 * Single on-device market-data source — the Crypcodile-KMP engine façade.
 *
 * Exposes the exact method surface the screens already use (so existing
 * ViewModels are untouched) PLUS canonical [OHLCV] flows that deep-data
 * features build on. Backed by [BinanceConnector] in M1; the Deribit connector
 * and cross-channel canonical streams arrive in later milestones.
 */
class MarketDataEngine(
    private val binance: BinanceConnector,
    private val settingsStore: SettingsStore,
    val deribit: DeribitConnector = DeribitConnector(),
) {
    private val candleCacheLock = SynchronizedObject()
    private val candleCache = mutableMapOf<String, List<Candle>>()

    private val restCacheLock = SynchronizedObject()
    private val openInterestCache = mutableMapOf<String, CachedData<OpenInterestPoint>>()
    private val topLongShortAccountCache = mutableMapOf<String, CachedData<LongShortRatioPoint>>()
    private val topLongShortPositionCache = mutableMapOf<String, CachedData<LongShortRatioPoint>>()
    private val globalLongShortAccountCache = mutableMapOf<String, CachedData<LongShortRatioPoint>>()
    private val takerLongShortCache = mutableMapOf<String, CachedData<TakerLongShortRatioPoint>>()
    private val fundingRateCache = mutableMapOf<String, CachedData<FundingRatePoint>>()

    private data class CachedData<T>(val data: List<T>, val timestamp: Long)

    private fun getIntervalMs(interval: String): Long {
        val number = interval.takeWhile { it.isDigit() }.toLongOrNull() ?: 1L
        val unit = interval.dropWhile { it.isDigit() }
        val multiplier = when (unit) {
            "s" -> 1000L
            "m" -> 60 * 1000L
            "h" -> 60 * 60 * 1000L
            "d" -> 24 * 60 * 60 * 1000L
            "w" -> 7 * 24 * 60 * 60 * 1000L
            "M" -> 30 * 24 * 60 * 60 * 1000L
            else -> 60 * 1000L
        }
        return number * multiplier
    }

    private fun updateCache(key: String, candle: Candle) {
        synchronized(candleCacheLock) {
            val currentList = candleCache[key] ?: emptyList()
            val lastCandle = currentList.lastOrNull()
            val newList = when {
                lastCandle == null -> listOf(candle)
                candle.openTime == lastCandle.openTime -> {
                    currentList.dropLast(1) + candle
                }
                candle.openTime > lastCandle.openTime -> {
                    currentList + candle
                }
                else -> {
                    currentList
                }
            }
            candleCache[key] = newList.takeLast(1000)
        }
    }

    private fun mergeLists(listA: List<Candle>, listB: List<Candle>): List<Candle> {
        val map = (listA + listB).associateBy { it.openTime }
        return map.values.sortedBy { it.openTime }
    }

    suspend fun history(symbol: String, interval: String, limit: Int = 300): List<Candle> {
        val key = "SPOT:$symbol:$interval"
        val cached = synchronized(candleCacheLock) { candleCache[key] }
        if (cached != null && cached.size >= limit) {
            val lastCandle = cached.lastOrNull()
            if (lastCandle != null) {
                val intervalMs = getIntervalMs(interval)
                val age = nowMillis() - lastCandle.openTime
                if (age < 2 * intervalMs) {
                    return cached.takeLast(limit)
                }
            }
        }
        val fetched = binance.spotClient().klines(symbol = symbol, interval = interval, limit = limit)
        val mergedPruned = synchronized(candleCacheLock) {
            val current = candleCache[key] ?: emptyList()
            val merged = mergeLists(fetched, current).takeLast(1000)
            candleCache[key] = merged
            merged.takeLast(limit)
        }
        return mergedPruned
    }

    fun liveKlines(symbol: String, interval: String): Flow<BinanceWsClient.KlineUpdate> {
        val settings = settingsStore.getSettings()
        val wsDataSource = settings.wsDataSource
        val wsUrl = if (wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        val key = "$wsDataSource:$symbol:$interval"
        return binance.wsClient().klineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
            .onEach { update ->
                updateCache(key, update.candle)
            }
    }

    suspend fun futuresHistory(symbol: String, interval: String, limit: Int = 300): List<Candle> {
        val key = "FUTURES:$symbol:$interval"
        val cached = synchronized(candleCacheLock) { candleCache[key] }
        if (cached != null && cached.size >= limit) {
            val lastCandle = cached.lastOrNull()
            if (lastCandle != null) {
                val intervalMs = getIntervalMs(interval)
                val age = nowMillis() - lastCandle.openTime
                if (age < 2 * intervalMs) {
                    return cached.takeLast(limit)
                }
            }
        }
        val fetched = binance.futuresClient().klines(symbol = symbol, interval = interval, limit = limit)
        val mergedPruned = synchronized(candleCacheLock) {
            val current = candleCache[key] ?: emptyList()
            val merged = mergeLists(fetched, current).takeLast(1000)
            candleCache[key] = merged
            merged.takeLast(limit)
        }
        return mergedPruned
    }

    suspend fun futuresUniverse(): List<String> = binance.futuresClient().universe24hSortedByVolume()
    suspend fun ticker24hAll(): List<Ticker24h> = binance.futuresClient().ticker24hAll()

    suspend fun openInterestHist(symbol: String, period: String = "1h", limit: Int = 30): List<OpenInterestPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = openInterestCache[key]
            if (cached != null && nowMillis() - cached.timestamp < 30000) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().openInterestHist(symbol, period, limit)
        synchronized(restCacheLock) {
            openInterestCache[key] = CachedData(fetched, nowMillis())
        }
        return fetched
    }

    suspend fun topLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = topLongShortAccountCache[key]
            if (cached != null && nowMillis() - cached.timestamp < 30000) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().topLongShortAccountRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            topLongShortAccountCache[key] = CachedData(fetched, nowMillis())
        }
        return fetched
    }

    suspend fun topLongShortPositionRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = topLongShortPositionCache[key]
            if (cached != null && nowMillis() - cached.timestamp < 30000) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().topLongShortPositionRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            topLongShortPositionCache[key] = CachedData(fetched, nowMillis())
        }
        return fetched
    }

    suspend fun globalLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = globalLongShortAccountCache[key]
            if (cached != null && nowMillis() - cached.timestamp < 30000) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().globalLongShortAccountRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            globalLongShortAccountCache[key] = CachedData(fetched, nowMillis())
        }
        return fetched
    }

    suspend fun takerLongShortRatio(symbol: String, period: String = "1h", limit: Int = 30): List<TakerLongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = takerLongShortCache[key]
            if (cached != null && nowMillis() - cached.timestamp < 30000) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().takerLongShortRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            takerLongShortCache[key] = CachedData(fetched, nowMillis())
        }
        return fetched
    }

    suspend fun fundingRate(symbol: String, limit: Int = 30): List<FundingRatePoint> {
        val key = "$symbol:$limit"
        synchronized(restCacheLock) {
            val cached = fundingRateCache[key]
            if (cached != null && nowMillis() - cached.timestamp < 30000) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().fundingRate(symbol, limit)
        synchronized(restCacheLock) {
            fundingRateCache[key] = CachedData(fetched, nowMillis())
        }
        return fetched
    }

    /** Live canonical OHLCV for the active symbol/interval (futures WS). */
    fun liveOhlcv(symbol: String, interval: String): Flow<OHLCV> {
        val settings = settingsStore.getSettings()
        val wsDataSource = settings.wsDataSource
        val wsUrl = if (wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        val key = "$wsDataSource:$symbol:$interval"
        return binance.wsClient().klineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
            .onEach { update ->
                updateCache(key, update.candle)
            }
            .map { it.candle.toOhlcv(symbol, interval, nowMillis()) }
    }

    // ── Deribit deep-data surface ──
    /** Raw canonical Deribit record stream for the given channels/symbols. */
    fun deribitRecords(channels: Set<Channel>, symbols: Set<String>): Flow<Record> =
        deribit.stream(channels, symbols)

    /** Live option-chain ticks (OptionsChain) for the given option instrument symbols. */
    fun optionChainStream(symbols: Set<String>): Flow<OptionsChain> =
        deribit.stream(setOf(Channel.OPTIONS_CHAIN), symbols).filterIsInstance<OptionsChain>()

    /** Live derivative tickers (perp/future) for the given symbols. */
    fun derivativeTickerStream(symbols: Set<String>): Flow<DerivativeTicker> =
        deribit.stream(setOf(Channel.DERIVATIVE_TICKER), symbols).filterIsInstance<DerivativeTicker>()
}
