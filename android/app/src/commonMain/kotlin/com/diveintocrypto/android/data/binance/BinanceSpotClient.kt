package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.domain.model.Candle
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * F1: Binance Spot REST client — kline history only.
 * Live signed orders land in F3.
 *
 * Error contract matches [BinanceFuturesClient]: non-2xx → [UpstreamHttpException];
 * rows with a missing required number are dropped, never zero-filled.
 */
class BinanceSpotClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: HttpClient = binanceHttpClient(),
) {

    suspend fun klines(symbol: String, interval: String = "1h", limit: Int = 300): List<Candle> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("api", "v3", "klines")
                parameters.append("symbol", symbol)
                parameters.append("interval", interval)
                parameters.append("limit", limit.toString())
            }
        }
        requireUpstreamOk("klines", response.status)
        val raw = response.bodyAsText()

        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr.map { entry ->
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
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.binance.com"
    }

    /**
     * Full 24h ticker payload for every spot symbol (`GET /api/v3/ticker/24hr`).
     * Same field shape as the futures variant — used by the live-ticker engine's
     * 60s REST refresh when the data source setting is SPOT (the `!miniTicker@arr`
     * socket covers the venue, but the 24h change% comes from REST).
     */
    suspend fun ticker24hAll(): List<Ticker24h> {
        val response = client.get(baseUrl) {
            url {
                appendPathSegments("api", "v3", "ticker", "24hr")
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
}
