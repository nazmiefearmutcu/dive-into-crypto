package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.platform.httpEngine
import com.diveintocrypto.android.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Shared Ktor [HttpClient] factory for every Binance client.
 *
 * Replaces the old per-client `OkHttpClient.Builder()` setup. Timeouts mirror
 * the original OkHttp ones (connect 10s / request 15s). The [WebSockets] plugin
 * backs [BinanceWsClient.klineStream]. A centralized [HttpSend] interceptor records
 * every REST call into [NetworkLog] — the Ktor equivalent of the removed
 * `NetworkLog.okHttpInterceptor()`.
 *
 * The engine is supplied by the platform layer ([httpEngine] → OkHttp on
 * Android, Darwin/NSURLSession on iOS) so this stays in `commonMain`.
 */
internal fun binanceHttpClient(): HttpClient = HttpClient(httpEngine()) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
    }
    install(WebSockets)
}.also { client ->
    // Centralized REST logging: capture host/path/query/status/duration for
    // every call, success or failure, and forward to NetworkLog.recordRest.
    // The HttpSend plugin lets us wrap the actual network send (`execute`).
    client.plugin(HttpSend).intercept { request ->
        val started = nowMillis()
        try {
            val call = execute(request)
            val url = call.request.url
            NetworkLog.recordRest(
                method = call.request.method.value,
                host = url.host,
                path = url.encodedPath,
                query = url.encodedQuery.takeIf { it.isNotEmpty() },
                status = call.response.status.value,
                durationMs = nowMillis() - started,
                error = null,
            )
            call
        } catch (t: Throwable) {
            val url = request.url.build()
            NetworkLog.recordRest(
                method = request.method.value,
                host = url.host,
                path = url.encodedPath,
                query = url.encodedQuery.takeIf { it.isNotEmpty() },
                status = -1,
                durationMs = nowMillis() - started,
                error = t::class.simpleName + (t.message?.let { ": $it" } ?: ""),
            )
            throw t
        }
    }
}
