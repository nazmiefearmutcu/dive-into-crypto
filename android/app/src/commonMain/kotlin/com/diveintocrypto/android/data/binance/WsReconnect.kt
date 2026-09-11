package com.diveintocrypto.android.data.binance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
 *     collector is gone).
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
        try {
            upstream().collect { value ->
                if (!gotFrameThisConnection) {
                    attempt = 0 // reset the backoff on the first successful frame
                    gotFrameThisConnection = true
                }
                emit(value)
            }
            // Upstream completed without exception → graceful close. Fall through
            // to the backoff and reconnect rather than ending the collector.
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Connection/protocol failure → fall through to the backoff below.
        }
        delayFn(wsBackoffMillis(attempt))
        attempt++
    }
}
