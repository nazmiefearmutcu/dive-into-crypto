package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.data.binance.NetworkLog
import com.diveintocrypto.android.data.binance.asUpstreamIfHandshake
import com.diveintocrypto.android.data.binance.binanceHttpClient
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.engine.exchanges.Connector
import com.diveintocrypto.android.engine.schema.BookDelta
import com.diveintocrypto.android.engine.schema.BookSnapshot
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Deribit public WS connector — port of `crocodile/crypto/exchanges/deribit/connector.py`.
 * Streams canonical records from `wss://www.deribit.com/ws/api/v2` via JSON-RPC
 * `public/subscribe`. Reconnects with exponential backoff; isolates per-frame parse errors.
 *
 * Records are emitted with `localTs` in NANOSECONDS (the canonical contract), and a
 * `book.*` delta that does not chain onto the last change_id forces a resubscribe.
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

    /**
     * Parse one WS text frame → records, swallowing malformed frames.
     * [localTs] is the receive time in NANOSECONDS, like every canonical timestamp.
     */
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
                val bookGate = DeribitBookGate() // per connection: every subscription starts with a snapshot
                try {
                    httpClient.webSocket(wsUrl) {
                        NetworkLog.recordWs(host = host, path = "/ws/api/v2", status = 101, durationMs = 0)
                        send(subscribeFrame(wire))
                        var gotFrame = false
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            if (!gotFrame) {
                                // Only a frame from a live server resets the backoff. A socket that
                                // is accepted and dropped without traffic keeps growing the delay.
                                gotFrame = true
                                backoffMs = 1_000L
                            }
                            for (r in handleFrameText(frame.readText(), nowMillis() * NS_PER_MS)) {
                                if (!bookGate.accept(r)) throw BookGapException(r.symbolRaw)
                                // Suspending send: backpressure instead of silently dropping records.
                                this@callbackFlow.send(r)
                            }
                        }
                    }
                    NetworkLog.recordWs(host, "/ws/api/v2", 1000, nowMillis() - opened, "Closed")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    val failure = t.asUpstreamIfHandshake(host)
                    NetworkLog.recordWs(host, "/ws/api/v2", -1, nowMillis() - opened, failure.message ?: "error")
                    if (failure.isRegionBlocked()) {
                        // A regional block (HTTP 451) will not clear by reconnecting: fail the flow.
                        // Other refusals, including HTTP 403 (temporary WAF limit), fall through to backoff.
                        close(failure)
                        break
                    }
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
        private const val NS_PER_MS = 1_000_000L
    }
}

/** A `book.*` delta did not chain onto the previous change_id: the local book is no longer valid. */
internal class BookGapException(symbol: String) :
    IllegalStateException("Deribit book sequence gap for $symbol; resubscribing")

/**
 * Sequence check for `book.*` channels (port of the Python DeribitBookGate). A delta must
 * chain onto the last change_id seen for its instrument (`prev_change_id == last`), otherwise
 * a delta was lost and the caller must resubscribe to rebuild the book from a snapshot.
 */
internal class DeribitBookGate {
    private val lastChangeId = HashMap<String, Long>()

    /** @return false when [record] is a book delta that does not chain (a gap). */
    fun accept(record: Record): Boolean = when (record) {
        is BookSnapshot -> {
            track(record.symbolRaw, record.seqId)
            true
        }
        is BookDelta -> {
            val chained = lastChangeId[record.symbolRaw] != null &&
                record.prevSeqId != null && record.prevSeqId == lastChangeId[record.symbolRaw]
            track(record.symbolRaw, if (chained) record.seqId else null)
            chained
        }
        else -> true
    }

    private fun track(symbol: String, seq: Long?) {
        if (seq != null) lastChangeId[symbol] = seq else lastChangeId.remove(symbol)
    }
}
