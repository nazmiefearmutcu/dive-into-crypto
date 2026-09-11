package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.data.binance.dto.WsKlineEnvelope
import com.diveintocrypto.android.data.binance.dto.WsMiniTickerEnvelope
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
import kotlinx.serialization.builtins.ListSerializer
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

    /**
     * Live kline stream that SURVIVES disconnects. Wraps [klineStream] in
     * [reconnectingFlow]: exponential backoff with jitter (1s→2s→4s…cap 30s,
     * reset on the first successful frame), reconnecting on both failures and
     * graceful server closes. Callers collect once — they no longer need their
     * own `while(true) { try { collect } catch { delay } }` restart loops, and
     * no tick is ever fabricated while the socket is down (ViewModels expose
     * explicit staleness state instead).
     */
    fun reconnectingKlineStream(
        symbol: String,
        interval: String,
        customBaseUrl: String? = null,
    ): Flow<KlineUpdate> = reconnectingFlow(upstream = {
        klineStream(symbol = symbol, interval = interval, customBaseUrl = customBaseUrl)
    })

    data class KlineUpdate(val candle: Candle, val isClosed: Boolean)

    /**
     * One parsed `!miniTicker@arr` element — the venue's last price for a
     * symbol plus its 24h OHLC window (real wire data, nothing derived).
     */
    data class MiniTicker(
        val symbol: String,
        val lastPrice: Double,
        val openPrice: Double,
        val highPrice: Double,
        val lowPrice: Double,
        val eventTime: Long,
    )

    /**
     * ALL-MARKET mini-ticker stream (`!miniTicker@arr`) as a cold Flow.
     *
     * ONE socket delivers a batch of every symbol's last price every ~1 second —
     * the efficient way to feed a watchlist / full-universe scanner (vs. one
     * socket per symbol). Each emission is one wire batch (a list, usually large).
     */
    fun miniTickerStream(customBaseUrl: String? = null): Flow<List<MiniTicker>> = callbackFlow {
        val activeBaseUrl = customBaseUrl ?: baseUrl
        // The array-stream lives under the same /ws/ path as the kline streams.
        val url = "$activeBaseUrl/ws/!miniTicker@arr"
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
                    val batchSerializer = ListSerializer(WsMiniTickerEnvelope.serializer())
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        val envelopes = runCatching {
                            json.decodeFromString(batchSerializer, text)
                        }.onFailure {
                            logError("DiveIntoCrypto", "WS miniTicker parse error: ${it.message}", it)
                        }.getOrNull() ?: continue
                        val batch = envelopes.mapNotNull { e ->
                            val last = e.close.toDoubleOrNull() ?: return@mapNotNull null
                            MiniTicker(
                                symbol = e.symbol,
                                lastPrice = last,
                                openPrice = e.open.toDoubleOrNull() ?: 0.0,
                                highPrice = e.high.toDoubleOrNull() ?: 0.0,
                                lowPrice = e.low.toDoubleOrNull() ?: 0.0,
                                eventTime = e.eventTime,
                            )
                        }
                        if (batch.isNotEmpty()) trySend(batch)
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

    /**
     * All-market mini-ticker stream that SURVIVES disconnects — same
     * [reconnectingFlow] backoff/jitter semantics as [reconnectingKlineStream].
     * No tick is fabricated while the socket is down; callers surface staleness.
     */
    fun reconnectingMiniTickerStream(customBaseUrl: String? = null): Flow<List<MiniTicker>> =
        reconnectingFlow(upstream = { miniTickerStream(customBaseUrl = customBaseUrl) })

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
