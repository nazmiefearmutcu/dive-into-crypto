package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.data.binance.NetworkLog
import com.diveintocrypto.android.data.binance.binanceHttpClient
import com.diveintocrypto.android.engine.exchanges.Connector
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.Record
import com.diveintocrypto.android.engine.schema.Venue
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.Url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Deribit public WS connector — port of `crypcodile/exchanges/deribit/connector.py`.
 * Streams canonical records from `wss://www.deribit.com/ws/api/v2` via JSON-RPC
 * `public/subscribe`. Reconnects with exponential backoff; isolates per-frame parse errors.
 */
class DeribitConnector(
    private val httpClient: HttpClient = binanceHttpClient(),
    private val wsUrl: String = WS_URL,
) : Connector {
    override val venue: Venue = Venue.DERIBIT

    private val json = Json { ignoreUnknownKeys = true }

    /** Map canonical channels → Deribit wire channels (deduped, sorted). */
    fun buildChannels(symbols: Set<String>, channels: Set<Channel>): List<String> {
        val out = sortedSetOf<String>()
        for (sym in symbols) for (ch in channels) {
            val wire = when (ch) {
                Channel.TRADE, Channel.LIQUIDATION -> "trades.$sym.$WS_INTERVAL"
                Channel.BOOK_DELTA, Channel.BOOK_SNAPSHOT -> "book.$sym.$WS_INTERVAL"
                Channel.DERIVATIVE_TICKER, Channel.OPTIONS_CHAIN, Channel.FUNDING, Channel.OPEN_INTEREST ->
                    "ticker.$sym.$WS_INTERVAL"
                else -> null
            }
            if (wire != null) out.add(wire)
        }
        return out.toList()
    }

    /** JSON-RPC 2.0 public/subscribe frame for the given wire channels. */
    fun subscribeFrame(wireChannels: List<String>): String {
        val chans = wireChannels.joinToString(",") { "\"$it\"" }
        return """{"jsonrpc":"2.0","method":"public/subscribe","params":{"channels":[$chans]}}"""
    }

    /** Parse one WS text frame → records, swallowing malformed frames. */
    fun handleFrameText(text: String, localTs: Long): List<Record> = runCatching {
        DeribitNormalize.normalizeMessage(json.parseToJsonElement(text).jsonObject, localTs)
    }.getOrElse {
        logError("Deribit", "frame parse error: ${it.message}", it)
        emptyList()
    }

    override fun stream(channels: Set<Channel>, symbols: Set<String>): Flow<Record> = callbackFlow {
        val wire = buildChannels(symbols, channels)
        if (wire.isEmpty()) { close(); return@callbackFlow }
        val host = Url(wsUrl).host
        var backoffMs = 1_000L
        val job = launch {
            while (isActive) {
                val opened = nowMillis()
                try {
                    httpClient.webSocket(wsUrl) {
                        NetworkLog.recordWs(host = host, path = "/ws/api/v2", status = 101, durationMs = 0)
                        send(subscribeFrame(wire))
                        backoffMs = 1_000L
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            for (r in handleFrameText(frame.readText(), nowMillis())) trySend(r)
                        }
                    }
                    NetworkLog.recordWs(host, "/ws/api/v2", 1000, nowMillis() - opened, "Closed")
                } catch (t: Throwable) {
                    NetworkLog.recordWs(host, "/ws/api/v2", -1, nowMillis() - opened, t.message ?: "error")
                }
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
        awaitClose { job.cancel() }
    }

    /** Deribit has no plain REST record backfill wired in this milestone. */
    override suspend fun backfill(
        channel: Channel,
        symbolRaw: String,
        startNs: Long,
        endNs: Long,
        limit: Int,
    ): List<Record> = emptyList()

    companion object {
        const val WS_URL = "wss://www.deribit.com/ws/api/v2"
        const val WS_INTERVAL = "100ms"
    }
}
