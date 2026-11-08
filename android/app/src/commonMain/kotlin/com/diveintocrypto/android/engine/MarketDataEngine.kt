package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.data.binance.isRegionBlocked
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
import com.diveintocrypto.android.engine.schema.Venue
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.atomicfu.locks.SynchronizedObject
import com.diveintocrypto.android.platform.synchronized

/**
 * Single on-device market-data source — the Crocodile-KMP engine façade (Kotlin mirror of
 * Crocodile's crypto engine, formerly Crypcodile).
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
    // Per-symbol funding interval (hours) from /fapi/v1/fundingInfo: one call covers the whole
    // venue, so it is cached as a single map. Guarded by [restCacheLock].
    private var fundingIntervalCache: Pair<Long, Map<String, Int>>? = null

    /** Anything stored in a TTL cache: the write time decides expiry. */
    private interface Timestamped {
        val timestamp: Long
    }

    private data class CachedData<T>(val data: List<T>, override val timestamp: Long) : Timestamped

    /** Single-value cache entry (CVD snapshots — nullable: failures cached briefly too). */
    private data class CachedSingle<T>(val data: T, override val timestamp: Long) : Timestamped

    internal fun getIntervalMs(interval: String): Long {
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

    /**
     * Folds one live candle into the series cached under [key].
     *
     * A candle that opens MORE than one interval after the cached tail means the socket
     * dropped across at least one bar. Bridging that gap would serve a series with missing
     * bars to every indicator, so the series is restarted from this bar instead: the next
     * history() call then sees fewer than `limit` bars and refetches the full range.
     */
    private fun updateCache(key: String, candle: Candle, intervalMs: Long) {
        synchronized(candleCacheLock) {
            val currentList = candleCache.get(key) ?: emptyList()
            candleCache.put(key, appendLiveCandle(currentList, candle, intervalMs).takeLast(1000))
        }
    }

    /** Merges two series by openTime; on an equal openTime the entry from [listB] wins. */
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
            // REST (fetched) is the authoritative snapshot: it must win an openTime tie against
            // a cached in-progress tick that may be older while the socket was down.
            val merged = mergeLists(current, fetched).takeLast(1000)
            candleCache.put(key, merged)
            merged.takeLast(limit)
        }
        return mergedPruned
    }

    /**
     * Ends a live kline stream on a regional block (HTTP 451): the stream is not retried, so it
     * completes after logging the cause. Any other failure still propagates to the collector.
     * Collectors see the completion as the end of live data and mark their prices stale.
     */
    private fun <T> Flow<T>.completeOnRegionBlock(): Flow<T> = catch { e ->
        if (e is CancellationException) throw e
        if (!e.isRegionBlocked()) throw e
        logError("MarketDataEngine", "kline stream stopped: region blocked", e)
    }

    fun liveKlines(symbol: String, interval: String): Flow<BinanceWsClient.KlineUpdate> {
        val settings = settingsStore.getSettings()
        val wsDataSource = settings.wsDataSource
        val wsUrl = if (wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        val key = "$wsDataSource:$symbol:$interval"
        val intervalMs = getIntervalMs(interval)
        // Reconnecting stream: backoff+jitter lives in BinanceWsClient, so the
        // ViewModels no longer need their own restart loops.
        return binance.wsClient().reconnectingKlineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
            .onEach { update ->
                updateCache(key, update.candle, intervalMs)
            }
            .completeOnRegionBlock()
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
            // REST (fetched) wins an openTime tie — see history().
            val merged = mergeLists(current, fetched).takeLast(1000)
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
     * instead of showing a stale/zero CVD as if it were live. A CANCELLED call
     * rethrows and caches nothing: cancellation is not a failed fetch.
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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null // honest unavailability — also cached briefly so we don't hammer
        }
        synchronized(restCacheLock) {
            cvdCache.putPruned(symbol, CachedSingle(snapshot, nowMillis()), CVD_CACHE_MS)
        }
        return snapshot
    }

    /** Connector access for app-scoped services (live-ticker engine) in the same module. */
    internal fun binanceConnector(): BinanceConnector = binance

    suspend fun openInterestHist(symbol: String, period: String = "1h", limit: Int = 30): List<OpenInterestPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = openInterestCache[key]
            if (cached != null && nowMillis() - cached.timestamp < REST_CACHE_TTL_MS) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().openInterestHist(symbol, period, limit)
        synchronized(restCacheLock) {
            openInterestCache.putPruned(key, CachedData(fetched, nowMillis()), REST_CACHE_TTL_MS)
        }
        return fetched
    }

    suspend fun topLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = topLongShortAccountCache[key]
            if (cached != null && nowMillis() - cached.timestamp < REST_CACHE_TTL_MS) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().topLongShortAccountRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            topLongShortAccountCache.putPruned(key, CachedData(fetched, nowMillis()), REST_CACHE_TTL_MS)
        }
        return fetched
    }

    suspend fun topLongShortPositionRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = topLongShortPositionCache[key]
            if (cached != null && nowMillis() - cached.timestamp < REST_CACHE_TTL_MS) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().topLongShortPositionRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            topLongShortPositionCache.putPruned(key, CachedData(fetched, nowMillis()), REST_CACHE_TTL_MS)
        }
        return fetched
    }

    suspend fun globalLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = globalLongShortAccountCache[key]
            if (cached != null && nowMillis() - cached.timestamp < REST_CACHE_TTL_MS) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().globalLongShortAccountRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            globalLongShortAccountCache.putPruned(key, CachedData(fetched, nowMillis()), REST_CACHE_TTL_MS)
        }
        return fetched
    }

    suspend fun takerLongShortRatio(symbol: String, period: String = "1h", limit: Int = 30): List<TakerLongShortRatioPoint> {
        val key = "$symbol:$period:$limit"
        synchronized(restCacheLock) {
            val cached = takerLongShortCache[key]
            if (cached != null && nowMillis() - cached.timestamp < REST_CACHE_TTL_MS) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().takerLongShortRatio(symbol, period, limit)
        synchronized(restCacheLock) {
            takerLongShortCache.putPruned(key, CachedData(fetched, nowMillis()), REST_CACHE_TTL_MS)
        }
        return fetched
    }

    suspend fun fundingRate(symbol: String, limit: Int = 30): List<FundingRatePoint> {
        val key = "$symbol:$limit"
        synchronized(restCacheLock) {
            val cached = fundingRateCache[key]
            if (cached != null && nowMillis() - cached.timestamp < REST_CACHE_TTL_MS) {
                return cached.data
            }
        }
        val fetched = binance.futuresClient().fundingRate(symbol, limit)
        synchronized(restCacheLock) {
            fundingRateCache.putPruned(key, CachedData(fetched, nowMillis()), REST_CACHE_TTL_MS)
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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null // honest unavailability — cached briefly so we don't hammer
        }
        synchronized(restCacheLock) {
            premiumCache.putPruned(symbol, CachedSingle(dto, nowMillis()), PREMIUM_CACHE_MS)
        }
        return dto
    }

    /**
     * Funding interval in hours for [symbol] (from `/fapi/v1/fundingInfo`, cached 1h).
     * When the venue cannot be asked, the USD-M default of 8h is used for that call only and
     * nothing is cached, so the next call retries.
     */
    private suspend fun fundingIntervalHours(symbol: String): Int {
        synchronized(restCacheLock) {
            fundingIntervalCache?.let { (ts, bysymbol) ->
                if (nowMillis() - ts < FUNDING_INFO_TTL_MS) {
                    return bysymbol[symbol] ?: DEFAULT_FUNDING_INTERVAL_HOURS
                }
            }
        }
        val fetched = runCatchingUnlessCancelled { binance.futuresClient().fundingIntervalHours() }.getOrNull()
            ?: return DEFAULT_FUNDING_INTERVAL_HOURS
        synchronized(restCacheLock) {
            fundingIntervalCache = nowMillis() to fetched
        }
        return fetched[symbol] ?: DEFAULT_FUNDING_INTERVAL_HOURS
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
        // Settled history is independent of the premium snapshot: a failure here blanks only
        // the settled field of the lens (null), never the whole lens.
        val settled: Double? = runCatchingUnlessCancelled {
            fundingRate(symbol, limit = 2).lastOrNull()?.fundingRate
        }.getOrNull()
        val intervalHours = fundingIntervalHours(symbol)

        val basis = premium?.let {
            com.diveintocrypto.android.engine.analytics.BasisAnalytics
                .basisBlock(it.markPrice, it.indexPrice, it.lastFundingRate, intervalHours)
        }
        val lens = premium?.let {
            com.diveintocrypto.android.engine.analytics.FundingAnalytics
                .fundingLens(it.lastFundingRate, settled, it.nextFundingTime, nowMillis(), intervalHours)
        }
        val cone = runCatchingUnlessCancelled {
            val candles = futuresHistory(symbol, "1h", limit = 200)
            com.diveintocrypto.android.engine.analytics.VolCone.fromCloses(candles.map { it.close })
        }.getOrNull()
        val planning = if (atrPct != null && direction != null && premium != null && premium.markPrice > 0.0) {
            com.diveintocrypto.android.engine.analytics.PlanningStrip.build(
                entry = premium.markPrice,
                atrPct = atrPct,
                direction = direction,
            )
        } else null
        return ParityBundle(basis, lens, cone, planning)
    }

    /** Live canonical OHLCV for the active symbol/interval (futures WS, or spot when the setting says so). */
    fun liveOhlcv(symbol: String, interval: String): Flow<OHLCV> {
        val settings = settingsStore.getSettings()
        val wsDataSource = settings.wsDataSource
        val wsUrl = if (wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        val venue = if (wsDataSource == "SPOT") Venue.BINANCE_SPOT else Venue.BINANCE_USDM
        val key = "$wsDataSource:$symbol:$interval"
        val intervalMs = getIntervalMs(interval)
        return binance.wsClient().reconnectingKlineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
            .onEach { update ->
                updateCache(key, update.candle, intervalMs)
            }
            .completeOnRegionBlock()
            .map { it.candle.toOhlcv(symbol, interval, nowMillis(), venue) }
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

    /**
     * Stores [value] under [key] and drops every entry whose TTL has lapsed. Before this the
     * per-symbol REST maps only grew: an expired key was overwritten only when requested again.
     * Call while holding [restCacheLock].
     */
    private fun <V : Timestamped> MutableMap<String, V>.putPruned(key: String, value: V, ttlMs: Long) {
        val now = value.timestamp
        entries.removeAll { now - it.value.timestamp >= ttlMs }
        this[key] = value
    }

    /** [runCatching] that lets cancellation propagate instead of folding it into a failure. */
    private inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    companion object {
        /**
         * Upper bound on distinct symbol:timeframe keys kept in the candle cache
         * (each entry holds ≤1000 candles). Bounds the singleton's memory footprint
         * regardless of universe size.
         */
        const val CANDLE_CACHE_MAX_KEYS = 96

        /** TTL of the per-symbol REST caches (open interest, long/short, taker, funding). */
        const val REST_CACHE_TTL_MS = 30_000L

        /** CVD snapshot cache TTL per symbol (spec: 10s). */
        const val CVD_CACHE_MS = 10_000L

        /** aggTrades page size for the CVD snapshot (venue max = 1000). */
        const val CVD_AGG_TRADE_LIMIT = 1000

        /** Premium-index cache TTL per symbol (parity additions, 0.3.0). */
        const val PREMIUM_CACHE_MS = 15_000L

        /** How long the venue's per-symbol funding intervals are reused. */
        const val FUNDING_INFO_TTL_MS = 60 * 60 * 1000L

        /** USD-M default funding interval, used when the venue's value is not known. */
        const val DEFAULT_FUNDING_INTERVAL_HOURS = 8
    }
}

/**
 * PURE: folds one live candle into a cached series (ascending openTime).
 *
 * Same openTime → the in-progress bar is replaced. Newer openTime → appended, UNLESS it opens
 * more than one [intervalMs] after the tail: the socket dropped across at least one bar, and
 * bridging that would serve a series with missing bars to every indicator. The series then
 * restarts from this bar, so the next history() call sees fewer than `limit` bars and refetches
 * the full range. Older openTime → ignored.
 */
internal fun appendLiveCandle(current: List<Candle>, candle: Candle, intervalMs: Long): List<Candle> {
    val lastCandle = current.lastOrNull() ?: return listOf(candle)
    return when {
        candle.openTime == lastCandle.openTime -> current.dropLast(1) + candle
        candle.openTime > lastCandle.openTime -> {
            if (intervalMs > 0L && candle.openTime - lastCandle.openTime > intervalMs) {
                listOf(candle)
            } else {
                current + candle
            }
        }
        else -> current
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
