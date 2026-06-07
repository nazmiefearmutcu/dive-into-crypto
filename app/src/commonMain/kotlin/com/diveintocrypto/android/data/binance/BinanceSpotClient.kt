package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.domain.model.Candle
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * F1: Binance Spot REST client — kline history only.
 * Live signed orders land in F3.
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
        if (!response.status.isSuccess()) throw IllegalStateException("klines HTTP ${response.status.value}")
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
}
