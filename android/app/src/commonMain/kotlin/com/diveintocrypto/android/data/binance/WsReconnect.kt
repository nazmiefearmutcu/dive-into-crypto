package com.diveintocrypto.android.data.binance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential reconnect backoff: 1s → 2s → 4s → 8s → 16s → capped at 30s.
 *
 * [attempt] is the number of consecutive failed attempts since the last
 * SUCCESSFUL frame (i.e. 0 = the first retry after a working connection dropped).
 * Deterministic and unit-testable — jitter is applied separately by [jittered].
 */
fun wsBackoffMillis(attempt: Int, baseMs: Long = 1_000L, capMs: Long = 30_000L): Long {
    require(baseMs > 0) { "baseMs must be positive" }
    val raw = if (attempt <= 0) baseMs else baseMs * (1L shl min(attempt, 24))
    return min(raw, capMs).coerceIn(0L, capMs)
}

/** ktor's wording for a refused WebSocket upgrade, e.g. "... expected status code 101 but was 451". */
private val HANDSHAKE_STATUS = Regex("expected status code 101 but was (\\d{3})")

/**
 * A refused WebSocket upgrade (ktor "Handshake exception") carries the real HTTP status in its
 * message. Surfacing it as [UpstreamHttpException] lets [reconnectingFlow] recognise a regional
 * block and stop retrying instead of reconnecting forever. Any other throwable is returned as is.
 */
internal fun Throwable.asUpstreamIfHandshake(endpoint: String): Throwable {
    val code = HANDSHAKE_STATUS.find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        ?: return this
    return UpstreamHttpException(endpoint, code)
}

/**
 * Applies ±20% multiplicative jitter to a backoff interval so a fleet of
 * clients does not reconnect in lockstep. Production default for [WsReconnect];
 * tests inject an identity function instead.
 */
fun jitteredBackoff(millis: Long, random: Random = Random.Default): Long =
    (millis * (0.8 + 0.4 * random.nextDouble())).toLong().coerceAtLeast(0L)

/**
 * Wraps a cold [Flow] factory into a self-healing flow:
 *
 *   - any upstream FAILURE (exception) → wait the exponential backoff for the
 *     current attempt, then re-subscribe;
 *   - a graceful upstream COMPLETION (e.g. the server closed the socket) is
 *     treated as a disconnect too — the collector never silently dies;
 *   - the attempt counter RESETS to 0 as soon as a frame is received on the new
 *     connection, so a stable stream always retries from 1s;
 *   - downstream CANCELLATION propagates immediately (no reconnect after the
 *     collector is gone);
 *   - a downstream exception propagates too (it is not a socket drop);
 *   - a REGIONAL BLOCK (HTTP 451, see [UpstreamHttpException]) fails the flow immediately:
 *     retrying cannot succeed, so it is never retried in a loop. Any other refusal, including
 *     HTTP 403 (a temporary WAF / rate-limit refusal), is retried with the normal backoff so the
 *     feed resumes once the refusal clears.
 *
 * The deterministic schedule from [wsBackoffMillis] is handed to [delayFn];
 * the production default applies ±20% jitter inside the delay path. Tests
 * inject a recording [delayFn] to assert the exact backoff schedule.
 */
fun <T> reconnectingFlow(
    upstream: () -> Flow<T>,
    delayFn: suspend (Long) -> Unit = { delay(jitteredBackoff(it)) },
): Flow<T> = flow {
    var attempt = 0
    while (true) {
        var gotFrameThisConnection = false
        // Only UPSTREAM failures are handled here. `catch` is exception-transparent:
        // an exception thrown by the downstream collector (including take()'s
        // AbortFlowException and the collector's own failures) propagates out of
        // this flow instead of being mistaken for a socket drop and re-subscribing.
        upstream()
            .onEach {
                if (!gotFrameThisConnection) {
                    attempt = 0 // reset the backoff on the first successful frame
                    gotFrameThisConnection = true
                }
            }
            .catch { e ->
                // Cancellation and regional blocks (HTTP 451 only) are not transient:
                // propagate them instead of retrying forever. HTTP 403 is retried with backoff.
                if (e is CancellationException || e.isRegionBlocked()) throw e
            }
            .collect { emit(it) }
        // Upstream completed (graceful close) or failed → backoff, then reconnect.
        delayFn(wsBackoffMillis(attempt))
        attempt++
    }
}
