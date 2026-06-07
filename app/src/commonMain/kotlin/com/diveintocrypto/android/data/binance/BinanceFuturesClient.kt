package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.logError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        if (!response.status.isSuccess()) throw IllegalStateException("ticker/24hr HTTP ${response.status.value}")
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            val sym = o["symbol"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Ticker24h(
                symbol = sym,
                lastPrice = o["lastPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                priceChangePercent = o["priceChangePercent"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                quoteVolume = o["quoteVolume"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                highPrice = o["highPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                lowPrice = o["lowPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
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
        if (!response.status.isSuccess()) throw IllegalStateException("openInterestHist HTTP ${response.status.value}")
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            OpenInterestPoint(
                timestamp = o["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                sumOpenInterest = o["sumOpenInterest"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                sumOpenInterestValue = o["sumOpenInterestValue"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
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
        if (!response.status.isSuccess()) throw IllegalStateException("$endpoint HTTP ${response.status.value}")
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            LongShortRatioPoint(
                timestamp = o["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                longAccount = o["longAccount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                shortAccount = o["shortAccount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                longShortRatio = o["longShortRatio"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
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
        if (!response.status.isSuccess()) throw IllegalStateException("takerlongshortRatio HTTP ${response.status.value}")
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            TakerLongShortRatioPoint(
                timestamp = o["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                buySellRatio = o["buySellRatio"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                buyVol = o["buyVol"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                sellVol = o["sellVol"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
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
        if (!response.status.isSuccess()) throw IllegalStateException("fundingRate HTTP ${response.status.value}")
        val raw = response.bodyAsText()
        return Json.parseToJsonElement(raw).jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            FundingRatePoint(
                timestamp = o["fundingTime"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                fundingRate = o["fundingRate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            )
        }
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
        if (!response.status.isSuccess()) throw IllegalStateException("ticker/24hr HTTP ${response.status.value}")
        val raw = response.bodyAsText()
        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr
            .map { it.jsonObject }
            .mapNotNull { obj ->
                val sym = obj["symbol"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!sym.endsWith("USDT")) return@mapNotNull null
                if (sym in skip) return@mapNotNull null
                val vol = obj["quoteVolume"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                sym to vol
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Kline endpoint — identical shape to spot but served from /fapi/v1/klines. */
    suspend fun klines(symbol: String, interval: String = "1h", limit: Int = 300): List<Candle> {
        logDebug("BinanceFuturesClient", "klines: executing request for $symbol $interval...")
        val raw = try {
            val response = client.get(baseUrl) {
                url {
                    appendPathSegments("fapi", "v1", "klines")
                    parameters.append("symbol", symbol)
                    parameters.append("interval", interval)
                    parameters.append("limit", limit.toString())
                }
            }
            logDebug("BinanceFuturesClient", "klines: request executed. code=${response.status.value}")
            if (!response.status.isSuccess()) throw IllegalStateException("klines HTTP ${response.status.value}")
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
