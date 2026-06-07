package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.platform.logDebug
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.platform.randomId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory ring buffer of recent HTTP calls — feeds the Logs (Network Log)
 * screen. Replaces the old paper-trade event log.
 *
 * A single [NetworkLog] is shared across every Binance client. The Ktor client
 * registers REST calls via [recordRest] (centralized in `binanceHttpClient()`'s
 * Send interceptor) and the WebSocket layer registers manually via [recordWs].
 * The buffer caps at [CAP] entries; older entries are dropped to keep memory
 * bounded over a long session.
 */
object NetworkLog {

    private const val CAP = 200

    private val _entries = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val entries: StateFlow<List<NetworkLogEntry>> = _entries.asStateFlow()

    /**
     * Record a REST call from the Ktor `HttpSend` interceptor in `binanceHttpClient()`.
     * Mirrors [recordWs]; a `status` of `-1` means the call failed before getting
     * a response.
     */
    fun recordRest(
        method: String,
        host: String,
        path: String,
        query: String?,
        status: Int,
        durationMs: Long,
        error: String? = null,
    ) {
        append(
            NetworkLogEntry(
                startedAtMs = nowMillis() - durationMs,
                durationMs = durationMs,
                method = method,
                host = host,
                path = path,
                query = query,
                status = status,
                error = error,
                kind = NetworkLogKind.REST,
            ),
        )
    }

    /** Record an arbitrary entry from outside the Ktor call chain (e.g. WS). */
    fun recordWs(host: String, path: String, status: Int, durationMs: Long, error: String? = null) {
        append(
            NetworkLogEntry(
                startedAtMs = nowMillis() - durationMs,
                durationMs = durationMs,
                method = "WSS",
                host = host,
                path = path,
                query = null,
                status = status,
                error = error,
                kind = NetworkLogKind.WS,
            ),
        )
    }

    private fun append(entry: NetworkLogEntry) {
        _entries.update { (it + entry).takeLast(CAP) }
        logDebug(
            "DiveIntoCrypto",
            "Kind: ${entry.kind}, Method: ${entry.method}, URL: ${entry.host}${entry.path}, Status: ${entry.status}, Duration: ${entry.durationMs}ms, Error: ${entry.error}"
        )
    }

    /** Wipe the buffer — exposed for the "Clear" button. */
    fun clear() {
        _entries.value = emptyList()
    }
}

data class NetworkLogEntry(
    val id: String = randomId(),
    val startedAtMs: Long,
    val durationMs: Long,
    val method: String,
    val host: String,
    val path: String,
    val query: String?,
    /** HTTP status. `-1` means the call failed before getting a response. */
    val status: Int,
    val error: String?,
    val kind: NetworkLogKind,
)

enum class NetworkLogKind { REST, WS }
