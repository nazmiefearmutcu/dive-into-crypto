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
    // Bounded LRU: an app-scoped singleton scanning ~500 symbols × 12 TFs used to
    // grow this map without bound; it is now capped (access-ordered, evicts LRU key).
    private val candleCache = LruCache<String, List<Candle>>(CANDLE_CACHE_MAX_KEYS)

    private val restCacheLock = SynchronizedObject()
    private val openInterestCache = mutableMapOf<String, CachedData<OpenInterestPoint>>()
    private val topLongShortAccountCache = mutableMapOf<String, CachedData<LongShortRatioPoint>>()
    private val topLongShortPositionCache = mutableMapOf<String, CachedData<LongShortRatioPoint>>()
    private val globalLongShortAccountCache = mutableMapOf<String, CachedData<LongShortRatioPoint>>()
    private val takerLongShortCache = mutableMapOf<String, CachedData<TakerLongShortRatioPoint>>()
    private val fundingRateCache = mutableMapOf<String, CachedData<FundingRatePoint>>()
    // CVD snapshots (nullable → a FAILED fetch is cached too, briefly, so an
    // unavailable endpoint is surfaced honestly without hammering it).
    private val cvdCache = mutableMapOf<String, CachedSingle<com.diveintocrypto.android.domain.cvd.CvdSnapshot?>>()
    // Premium-index snapshots (nullable → failures cached briefly too).
    private val premiumCache = mutableMapOf<String, CachedSingle<com.diveintocrypto.android.data.binance.PremiumIndexDto?>>()

    private data class CachedData<T>(val data: List<T>, val timestamp: Long)

    /** Single-value cache entry (CVD snapshots — nullable: failures cached briefly too). */
    private data class CachedSingle<T>(val data: T, val timestamp: Long)

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
            val currentList = candleCache.get(key) ?: emptyList()
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
            candleCache.put(key, newList.takeLast(1000))
        }
    }

    private fun mergeLists(listA: List<Candle>, listB: List<Candle>): List<Candle> {
        val map = (listA + listB).associateBy { it.openTime }
        return map.values.sortedBy { it.openTime }
    }

    suspend fun history(symbol: String, interval: String, limit: Int = 300): List<Candle> {
        val key = "SPOT:$symbol:$interval"
        val cached = synchronized(candleCacheLock) { candleCache.get(key) }
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
            val current = candleCache.get(key) ?: emptyList()
            val merged = mergeLists(fetched, current).takeLast(1000)
            candleCache.put(key, merged)
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
        // Reconnecting stream: backoff+jitter lives in BinanceWsClient, so the
        // ViewModels no longer need their own restart loops.
        return binance.wsClient().reconnectingKlineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
            .onEach { update ->
                updateCache(key, update.candle)
            }
    }

    suspend fun futuresHistory(symbol: String, interval: String, limit: Int = 300): List<Candle> {
        val key = "FUTURES:$symbol:$interval"
        val cached = synchronized(candleCacheLock) { candleCache.get(key) }
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
            val current = candleCache.get(key) ?: emptyList()
            val merged = mergeLists(fetched, current).takeLast(1000)
            candleCache.put(key, merged)
            merged.takeLast(limit)
        }
        return mergedPruned
    }

    suspend fun futuresUniverse(): List<String> = binance.futuresClient().universe24hSortedByVolume()
    suspend fun ticker24hAll(): List<Ticker24h> = binance.futuresClient().ticker24hAll()

    /**
     * Rolling CVD over the trailing ~15 minutes for one symbol, from the public
     * aggTrades REST endpoint (cached 10s per symbol). HONEST UNAVAILABILITY:
     * returns `null` on any fetch/parse failure — the caller must surface that
     * instead of showing a stale/zero CVD as if it were live.
     */
    suspend fun cvdSnapshot(symbol: String): com.diveintocrypto.android.domain.cvd.CvdSnapshot? {
        synchronized(restCacheLock) {
            cvdCache[symbol]?.let { cached ->
                if (nowMillis() - cached.timestamp < CVD_CACHE_MS) return cached.data
            }
        }
        val snapshot: com.diveintocrypto.android.domain.cvd.CvdSnapshot? = try {
            val now = nowMillis()
            val trades = binance.futuresClient().aggTrades(symbol, CVD_AGG_TRADE_LIMIT)
            com.diveintocrypto.android.domain.cvd.CvdAggregator.aggregate(symbol, trades, now)
        } catch (t: Throwable) {
            null // honest unavailability — also cached briefly so we don't hammer
        }
        synchronized(restCacheLock) {
            cvdCache[symbol] = CachedSingle(snapshot, nowMillis())
        }
        return snapshot
    }

    /** Connector access for app-scoped services (live-ticker engine) in the same module. */
    internal fun binanceConnector(): BinanceConnector = binance

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

    // ── Market-data parity additions (0.3.0): basis block / funding lens /
    //    vol-cone envelope / planning strip. Every consumer field is
    //    nullable-honest: a failed fetch NEVER becomes a fabricated zero. ──────

    /**
     * Cached (15s, failures cached too) `/fapi/v1/premiumIndex` snapshot.
     * null while unavailable — callers must surface that, not zero-fill.
     */
    suspend fun premiumIndex(symbol: String): com.diveintocrypto.android.data.binance.PremiumIndexDto? {
        synchronized(restCacheLock) {
            premiumCache[symbol]?.let { cached ->
                if (nowMillis() - cached.timestamp < PREMIUM_CACHE_MS) return cached.data
            }
        }
        val dto: com.diveintocrypto.android.data.binance.PremiumIndexDto? = try {
            binance.futuresClient().premiumIndex(symbol)
        } catch (t: Throwable) {
            null // honest unavailability — cached briefly so we don't hammer
        }
        synchronized(restCacheLock) {
            premiumCache[symbol] = CachedSingle(dto, nowMillis())
        }
        return dto
    }

    /**
     * One-shot additive parity bundle for the Panel screen:
     * basis block + funding lens (premiumIndex + funding history), vol-cone
     * envelope (cached 1h futures candles) and the ATR%-planning strip (pure,
     * from the caller's already-computed atrPct + consensus direction).
     */
    suspend fun panelParity(
        symbol: String,
        atrPct: Double?,
        direction: String?,
    ): ParityBundle {
        val premium = premiumIndex(symbol)
        val settled = runCatching {
            fundingRate(symbol, limit = 2).lastOrNull()?.fundingRate ?: Double.NaN
        }.getOrDefault(Double.NaN)

        val basis = premium?.let {
            com.diveintocrypto.android.engine.analytics.BasisAnalytics
                .basisBlock(it.markPrice, it.indexPrice, it.lastFundingRate)
        }
        val lens = premium?.let {
            com.diveintocrypto.android.engine.analytics.FundingAnalytics
                .fundingLens(it.lastFundingRate, settled, it.nextFundingTime, nowMillis())
        }
        val cone = runCatching {
            val candles = futuresHistory(symbol, "1h", limit = 200)
            com.diveintocrypto.android.engine.analytics.VolCone.fromCloses(candles.map { it.close })
        }.getOrNull()
        val planning = if (atrPct != null && direction != null && (premium?.markPrice ?: 0.0) > 0.0) {
            com.diveintocrypto.android.engine.analytics.PlanningStrip.build(
                entry = premium!!.markPrice,
                atrPct = atrPct,
                direction = direction,
            )
        } else null
        return ParityBundle(basis, lens, cone, planning)
    }

    /** Live canonical OHLCV for the active symbol/interval (futures WS). */
    fun liveOhlcv(symbol: String, interval: String): Flow<OHLCV> {        val settings = settingsStore.getSettings()
        val wsDataSource = settings.wsDataSource
        val wsUrl = if (wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        val key = "$wsDataSource:$symbol:$interval"
        return binance.wsClient().reconnectingKlineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
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

    companion object {
        /**
         * Upper bound on distinct symbol:timeframe keys kept in the candle cache
         * (each entry holds ≤1000 candles). Bounds the singleton's memory footprint
         * regardless of universe size.
         */
        const val CANDLE_CACHE_MAX_KEYS = 96

        /** CVD snapshot cache TTL per symbol (spec: 10s). */
        const val CVD_CACHE_MS = 10_000L

        /** aggTrades page size for the CVD snapshot (venue max = 1000). */
        const val CVD_AGG_TRADE_LIMIT = 1000

        /** Premium-index cache TTL per symbol (parity additions, 0.3.0). */
        const val PREMIUM_CACHE_MS = 15_000L
    }
}

/**
 * The additive Panel parity bundle (0.3.0). Every field is nullable-honest:
 * null = the underlying fetch or math could not be done (no data fabricated).
 *
 * @param basisBlock perp basis in bps + annualised predicted funding
 * @param fundingLens predicted vs settled funding + APR + seconds to settlement
 * @param cone 1h-σ volatility cone with the 24h/48h envelope
 * @param planning ATR%-based SL/TP/envelope strip for the consensus direction
 */
data class ParityBundle(
    val basisBlock: com.diveintocrypto.android.engine.analytics.BasisAnalytics.BasisBlock?,
    val fundingLens: com.diveintocrypto.android.engine.analytics.FundingAnalytics.FundingLens?,
    val cone: com.diveintocrypto.android.engine.analytics.VolCone.ConeEnv?,
    val planning: com.diveintocrypto.android.engine.analytics.PlanningStrip.Plan?,
)
