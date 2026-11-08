package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.logError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Binance USDT-M Futures REST client — kline history + 24h ticker universe.
 *
 * Port of the original Python reference implementation (ScannerService):
 *   - `_get_top_symbols_by_volume` → [universe24hSortedByVolume]
 *   - `MarketDataProvider.get_ohlcv` → [klines]
 *
 * Endpoint base is `fapi.binance.com` (not `api.binance.com`), matching the
 * original reference implementation's market. Kline JSON shape is identical to spot,
 * so the candle parser is shared in spirit with [BinanceSpotClient].
 *
 * Error contract: every non-2xx answer throws [UpstreamHttpException]. A row whose
 * REQUIRED numeric field is missing or malformed is dropped (or, for the single
 * premium-index snapshot, the call fails) — never zero-filled.
 */
class BinanceFuturesClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: HttpClient = binanceHttpClient(),
) {

    /**
     * Full 24h ticker payload for every USDT-M futures symbol. Used by the
     * leaderboard ("Leaders"/Performance) screen — it needs `priceChangePercent`,
     * `lastPrice`, and `quoteVolume`, not just the symbol string.
     */
    suspend fun ticker24hAll(): List<Ticker24h> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("fapi", "v1", "ticker", "24hr")
            }
        }
        requireUpstreamOk("ticker/24hr", response.status)
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            val sym = o.stringValue("symbol") ?: return@mapNotNull null
            Ticker24h(
                symbol = sym,
                lastPrice = o.finiteDouble("lastPrice") ?: return@mapNotNull null,
                priceChangePercent = o.finiteDouble("priceChangePercent") ?: return@mapNotNull null,
                quoteVolume = o.finiteDouble("quoteVolume") ?: return@mapNotNull null,
                highPrice = o.finiteDouble("highPrice") ?: return@mapNotNull null,
                lowPrice = o.finiteDouble("lowPrice") ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Open Interest history series. Binance public endpoint
     * `/futures/data/openInterestHist` — returns the open interest per
     * `period` bucket for the requested symbol over the requested range.
     */
    suspend fun openInterestHist(
        symbol: String,
        period: String = "1h",
        limit: Int = 30,
    ): List<OpenInterestPoint> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("futures", "data", "openInterestHist")
                parameters.append("symbol", symbol)
                parameters.append("period", period)
                parameters.append("limit", limit.toString())
            }
        }
        requireUpstreamOk("openInterestHist", response.status)
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            OpenInterestPoint(
                timestamp = o.longValue("timestamp") ?: return@mapNotNull null,
                sumOpenInterest = o.finiteDouble("sumOpenInterest") ?: return@mapNotNull null,
                sumOpenInterestValue = o.finiteDouble("sumOpenInterestValue") ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Top Traders Long/Short Ratio — Accounts. Binance public endpoint
     * `/futures/data/topLongShortAccountRatio` — what fraction of top traders'
     * accounts are net long vs short on this symbol.
     */
    suspend fun topLongShortAccountRatio(
        symbol: String,
        period: String = "1h",
        limit: Int = 30,
    ): List<LongShortRatioPoint> = fetchRatio("topLongShortAccountRatio", symbol, period, limit)

    /**
     * Top Traders Long/Short Ratio — Positions. Same shape as the accounts
     * variant but weighted by position size: gives a position-volume view of
     * top trader bias.
     */
    suspend fun topLongShortPositionRatio(
        symbol: String,
        period: String = "1h",
        limit: Int = 30,
    ): List<LongShortRatioPoint> = fetchRatio("topLongShortPositionRatio", symbol, period, limit)

    private suspend fun fetchRatio(
        endpoint: String,
        symbol: String,
        period: String,
        limit: Int,
    ): List<LongShortRatioPoint> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("futures", "data", endpoint)
                parameters.append("symbol", symbol)
                parameters.append("period", period)
                parameters.append("limit", limit.toString())
            }
        }
        requireUpstreamOk(endpoint, response.status)
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            LongShortRatioPoint(
                timestamp = o.longValue("timestamp") ?: return@mapNotNull null,
                longAccount = o.finiteDouble("longAccount") ?: return@mapNotNull null,
                shortAccount = o.finiteDouble("shortAccount") ?: return@mapNotNull null,
                longShortRatio = o.finiteDouble("longShortRatio") ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Taker Buy/Sell Volume Ratio — returns taker buy/sell volume and ratio.
     * Binance public endpoint `/futures/data/takerlongshortRatio`.
     */
    suspend fun takerLongShortRatio(
        symbol: String,
        period: String = "1h",
        limit: Int = 30,
    ): List<TakerLongShortRatioPoint> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("futures", "data", "takerlongshortRatio")
                parameters.append("symbol", symbol)
                parameters.append("period", period)
                parameters.append("limit", limit.toString())
            }
        }
        requireUpstreamOk("takerlongshortRatio", response.status)
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            TakerLongShortRatioPoint(
                timestamp = o.longValue("timestamp") ?: return@mapNotNull null,
                buySellRatio = o.finiteDouble("buySellRatio") ?: return@mapNotNull null,
                buyVol = o.finiteDouble("buyVol") ?: return@mapNotNull null,
                sellVol = o.finiteDouble("sellVol") ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Global Long/Short Ratio — Accounts. Binance public endpoint
     * `/futures/data/globalLongShortAccountRatio`.
     */
    suspend fun globalLongShortAccountRatio(
        symbol: String,
        period: String = "1h",
        limit: Int = 30,
    ): List<LongShortRatioPoint> = fetchRatio("globalLongShortAccountRatio", symbol, period, limit)

    /**
     * Funding Rate History — returns funding rate history.
     * Binance public endpoint `/fapi/v1/fundingRate`.
     */
    suspend fun fundingRate(
        symbol: String,
        limit: Int = 30,
    ): List<FundingRatePoint> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("fapi", "v1", "fundingRate")
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
            }
        }
        requireUpstreamOk("fundingRate", response.status)
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            FundingRatePoint(
                timestamp = o.longValue("fundingTime") ?: return@mapNotNull null,
                fundingRate = o.finiteDouble("fundingRate") ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Premium index for one symbol — current mark price, index price, the
     * (predicted) last funding rate and the next funding time.
     * Binance public endpoint `/fapi/v1/premiumIndex`.
     *
     * Throws if a required field is missing or malformed: a degraded payload must
     * surface as unavailable, never as a real-looking 0% funding rate or zero price.
     */
    suspend fun premiumIndex(symbol: String): PremiumIndexDto {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("fapi", "v1", "premiumIndex")
                parameters.append("symbol", symbol)
            }
        }
        requireUpstreamOk("premiumIndex", response.status)
        val raw = response.bodyAsText()
        val o = Json.parseToJsonElement(raw).jsonObject
        return PremiumIndexDto(
            symbol = o.stringValue("symbol") ?: symbol,
            markPrice = o.finiteDouble("markPrice") ?: missing("premiumIndex", "markPrice"),
            indexPrice = o.finiteDouble("indexPrice") ?: missing("premiumIndex", "indexPrice"),
            lastFundingRate = o.finiteDouble("lastFundingRate") ?: missing("premiumIndex", "lastFundingRate"),
            nextFundingTime = o.longValue("nextFundingTime") ?: missing("premiumIndex", "nextFundingTime"),
        )
    }

    /**
     * Funding interval in hours per symbol, from `/fapi/v1/fundingInfo`. Binance does
     * not settle every USD-M perpetual on the same cadence, so the APR math must
     * read this instead of assuming 8h. Symbols absent from the map use the venue
     * default (8h); malformed rows are skipped.
     */
    suspend fun fundingIntervalHours(): Map<String, Int> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("fapi", "v1", "fundingInfo")
            }
        }
        requireUpstreamOk("fundingInfo", response.status)
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            val sym = o.stringValue("symbol") ?: return@mapNotNull null
            val hours = (o["fundingIntervalHours"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                ?: return@mapNotNull null
            sym to hours
        }.toMap()
    }

    /**
     * Returns ALL USDT-M futures symbols sorted by 24h `quoteVolume` descending,
     * with the reference implementation's stablecoin skip set already removed. This is the universe
     * fed to the multi-symbol scanner.
     */
    suspend fun universe24hSortedByVolume(skip: Set<String> = SKIP_SYMBOLS): List<String> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("fapi", "v1", "ticker", "24hr")
            }
        }
        requireUpstreamOk("ticker/24hr", response.status)
        val raw = response.bodyAsText()
        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr
            .map { it.jsonObject }
            .mapNotNull { obj ->
                val sym = obj.stringValue("symbol") ?: return@mapNotNull null
                if (!sym.endsWith("USDT")) return@mapNotNull null
                if (sym in skip) return@mapNotNull null
                val vol = obj.finiteDouble("quoteVolume") ?: return@mapNotNull null
                sym to vol
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Kline endpoint — identical shape to spot but served from /fapi/v1/klines.
     * [startTimeMs] / [endTimeMs] (inclusive, epoch ms) bound the window when given.
     */
    suspend fun klines(
        symbol: String,
        interval: String = "1h",
        limit: Int = 300,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
    ): List<Candle> {
        logDebug("BinanceFuturesClient", "klines: executing request for $symbol $interval...")
        val raw = try {
            val response = client.get(baseUrl) {
                url {
                    appendPathSegments("fapi", "v1", "klines")
                    parameters.append("symbol", symbol)
                    parameters.append("interval", interval)
                    parameters.append("limit", limit.toString())
                    startTimeMs?.let { parameters.append("startTime", it.toString()) }
                    endTimeMs?.let { parameters.append("endTime", it.toString()) }
                }
            }
            logDebug("BinanceFuturesClient", "klines: request executed. code=${response.status.value}")
            requireUpstreamOk("klines", response.status)
            val bodyString = response.bodyAsText()
            logDebug("BinanceFuturesClient", "klines: body string read. length=${bodyString.length}")
            bodyString
        } catch (e: Throwable) {
            logError("BinanceFuturesClient", "klines: execution/reading failed", e)
            throw e
        }

        logDebug("BinanceFuturesClient", "klines: parsing JSON...")
        val arr = try {
            Json.parseToJsonElement(raw).jsonArray
        } catch (e: Throwable) {
            logError("BinanceFuturesClient", "klines: JSON parsing failed", e)
            throw e
        }

        logDebug("BinanceFuturesClient", "klines: mapping candles (count=${arr.size})...")
        return try {
            val list = arr.map { entry ->
                val cells = entry.jsonArray
                Candle(
                    openTime = cells[0].jsonPrimitive.longOrNull ?: error("bad open_time"),
                    open = (cells[1] as JsonPrimitive).contentOrNull!!.toDouble(),
                    high = (cells[2] as JsonPrimitive).contentOrNull!!.toDouble(),
                    low = (cells[3] as JsonPrimitive).contentOrNull!!.toDouble(),
                    close = (cells[4] as JsonPrimitive).contentOrNull!!.toDouble(),
                    volume = (cells[5] as JsonPrimitive).contentOrNull!!.toDouble(),
                    closeTime = cells[6].jsonPrimitive.longOrNull ?: error("bad close_time"),
                )
            }
            logDebug("BinanceFuturesClient", "klines: candles mapped successfully. count=${list.size}")
            list
        } catch (e: Throwable) {
            logError("BinanceFuturesClient", "klines: mapping candles failed", e)
            throw e
        }
    }

    /**
     * Aggregate trades — public endpoint `GET /fapi/v1/aggTrades`.
     *
     * `m` (isBuyerMaker) is the aggressor flag: `true` = the buyer was the maker,
     * i.e. the TAKER SOLD (sell-side volume); `false` = the taker bought.
     * This is what the rolling CVD is built from. `limit` is capped at 1000 by
     * the venue — for the most liquid symbols 1000 trades can span less than the
     * full 15-minute CVD window; the snapshot is then honest about the span it
     * actually covers (see [CvdAggregator]). A trade without a price, quantity,
     * timestamp or aggressor flag is dropped: it must not count as taker buying.
     */
    suspend fun aggTrades(symbol: String, limit: Int = 1000): List<AggTrade> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("fapi", "v1", "aggTrades")
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.coerceIn(1, 1000).toString())
            }
        }
        requireUpstreamOk("aggTrades", response.status)
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            AggTrade(
                aggTradeId = o.longValue("a") ?: return@mapNotNull null,
                price = o.finiteDouble("p") ?: return@mapNotNull null,
                quantity = o.finiteDouble("q") ?: return@mapNotNull null,
                timestamp = o.longValue("T") ?: return@mapNotNull null,
                isBuyerMaker = (o["m"] as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null,
            )
        }
    }

    private fun missing(endpoint: String, field: String): Nothing =
        throw IllegalStateException("$endpoint: missing or malformed '$field'")

    companion object {
        const val DEFAULT_BASE_URL = "https://fapi.binance.com"

        /**
         * Verbatim from the original Python reference implementation
         * (`SKIP_SYMBOLS`). Stablecoins + fiat-USD pairs are not tradable trends
         * — keep them out of the universe.
         */
        val SKIP_SYMBOLS: Set<String> = setOf(
            "USDCUSDT", "BUSDUSDT", "TUSDUSDT", "DAIUSDT", "FDUSDUSDT",
            "EURUSDT", "GBPUSDT",
        )
    }
}

/** Full row of `/fapi/v1/ticker/24hr` projected onto the fields the UI uses. */
data class Ticker24h(
    val symbol: String,
    val lastPrice: Double,
    val priceChangePercent: Double,
    val quoteVolume: Double,
    val highPrice: Double,
    val lowPrice: Double,
)

/**
 * One aggregate trade from `/fapi/v1/aggTrades`. `isBuyerMaker=true` means the
 * taker SOLD (aggressor on the bid side); `false` means the taker bought.
 */
data class AggTrade(
    val aggTradeId: Long,
    val price: Double,
    val quantity: Double,
    val timestamp: Long,
    val isBuyerMaker: Boolean,
)

/** One bucket of /futures/data/openInterestHist. */
data class OpenInterestPoint(
    val timestamp: Long,
    /** OI in base-asset units (contracts). */
    val sumOpenInterest: Double,
    /** OI in USD terms — used to compare across symbols. */
    val sumOpenInterestValue: Double,
)

/** One bucket of /futures/data/topLongShort{Account,Position}Ratio. */
data class LongShortRatioPoint(
    val timestamp: Long,
    val longAccount: Double,   // 0..1 fraction
    val shortAccount: Double,  // 0..1 fraction
    val longShortRatio: Double,
)

/** One bucket of /futures/data/takerlongshortRatio. */
data class TakerLongShortRatioPoint(
    val timestamp: Long,
    val buySellRatio: Double,
    val buyVol: Double,
    val sellVol: Double,
)

/** One bucket of /fapi/v1/fundingRate. */
data class FundingRatePoint(
    val timestamp: Long,
    val fundingRate: Double,
)

/**
 * One row of `/fapi/v1/premiumIndex` (single-symbol response).
 * [lastFundingRate] is the venue's CURRENT (predicted) funding rate for the
 * next settlement — the last SETTLED rate comes from /fapi/v1/fundingRate.
 * Every field is required: the client throws rather than substituting a value.
 */
data class PremiumIndexDto(
    val symbol: String,
    val markPrice: Double,
    val indexPrice: Double,
    val lastFundingRate: Double,
    val nextFundingTime: Long,
)
