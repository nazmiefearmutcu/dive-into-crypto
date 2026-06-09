package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.data.binance.dto.WsKlineEnvelope
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.Url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class BinanceWsClient(
    private val baseUrl: String = DEFAULT_WS_URL,
    private val httpClient: HttpClient = binanceHttpClient(),
) {

    /**
     * Live kline stream as a cold Flow. Each emission is the latest kline
     * tick (in-progress updates every ~1s + a final isClosed=true on
     * candle close). Caller maps to Candle.
     */
    fun klineStream(symbol: String, interval: String, customBaseUrl: String? = null): Flow<KlineUpdate> = callbackFlow {
        val activeBaseUrl = customBaseUrl ?: baseUrl
        val url = "$activeBaseUrl/ws/${symbol.lowercase()}@kline_$interval"
        val parsedUrl = Url(url)
        var openedAt = 0L
        val job = launch {
            try {
                httpClient.webSocket(url) {
                    openedAt = nowMillis()
                    NetworkLog.recordWs(
                        host = parsedUrl.host,
                        path = parsedUrl.encodedPath,
                        status = 101,
                        durationMs = 0
                    )
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        logDebug("DiveIntoCrypto", "WS onMessage raw: $text")
                        val envelope = runCatching {
                            json.decodeFromString(WsKlineEnvelope.serializer(), text)
                        }.onFailure {
                            logError("DiveIntoCrypto", "WS JSON Parse Error: ${it.message}", it)
                        }.getOrNull() ?: continue
                        trySend(KlineUpdate(envelope.toCandle(), envelope.kline.isClosed))
                    }
                    val duration = if (openedAt > 0) nowMillis() - openedAt else 0
                    NetworkLog.recordWs(
                        host = parsedUrl.host,
                        path = parsedUrl.encodedPath,
                        status = 1000,
                        durationMs = duration,
                        error = "Closed"
                    )
                    close()
                }
            } catch (t: Throwable) {
                val duration = if (openedAt > 0) nowMillis() - openedAt else 0
                NetworkLog.recordWs(
                    host = parsedUrl.host,
                    path = parsedUrl.encodedPath,
                    status = -1,
                    durationMs = duration,
                    error = t.message ?: (t::class.simpleName ?: "Unknown")
                )
                close(t)
            }
        }
        awaitClose { job.cancel() }
    }

    data class KlineUpdate(val candle: Candle, val isClosed: Boolean)

    companion object {
        const val DEFAULT_WS_URL = "wss://fstream.binance.com"

        private val json = Json { ignoreUnknownKeys = true }

        private fun WsKlineEnvelope.toCandle(): Candle = Candle(
            openTime = kline.openTime,
            open = kline.open.toDouble(),
            high = kline.high.toDouble(),
            low = kline.low.toDouble(),
            close = kline.close.toDouble(),
            volume = kline.volume.toDouble(),
            closeTime = kline.closeTime,
        )
    }
}
