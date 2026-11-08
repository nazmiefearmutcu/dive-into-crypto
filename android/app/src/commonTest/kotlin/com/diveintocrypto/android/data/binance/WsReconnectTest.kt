package com.diveintocrypto.android.data.binance

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the WebSocket reconnect helper ([reconnectingFlow] + [wsBackoffMillis]):
 * exponential backoff 1s→2s→4s…cap 30s, reset on the first successful frame,
 * reconnect after failure AND after graceful close, and cancellation passthrough.
 * The delay function is injected, so no real time passes.
 */
class WsReconnectTest {

    @Test
    fun `backoff schedule doubles from 1s and caps at 30s`() {
        assertEquals(1_000L, wsBackoffMillis(0))
        assertEquals(2_000L, wsBackoffMillis(1))
        assertEquals(4_000L, wsBackoffMillis(2))
        assertEquals(8_000L, wsBackoffMillis(3))
        assertEquals(16_000L, wsBackoffMillis(4))
        assertEquals(30_000L, wsBackoffMillis(5))     // cap reached
        assertEquals(30_000L, wsBackoffMillis(20))    // stays capped
    }

    @Test
    fun `jitter stays within plus-minus 20 percent`() {
        repeat(50) {
            val j = jitteredBackoff(10_000L)
            assertTrue(j in 8_000L..12_000L, "jitter $j outside [8000, 12000]")
        }
    }

    @Test
    fun `reconnects after failure and resets backoff on successful frame`() = runTest {
        var calls = 0
        val upstream: () -> kotlinx.coroutines.flow.Flow<Int> = {
            calls++
            when (calls) {
                1 -> flow { emit(1); throw RuntimeException("connection dropped mid-stream") }
                2 -> flow<Int> { throw RuntimeException("cannot connect") }
                else -> flow { emit(2); emit(3) }
            }
        }
        val delays = mutableListOf<Long>()

        val result = reconnectingFlow(upstream, delayFn = { delays.add(it) }).take(3).toList()

        assertEquals(listOf(1, 2, 3), result)
        // conn1: frame received (attempt reset→0) then failure → backoff(0)=1000, attempt=1
        // conn2: immediate failure (no frame) → backoff(1)=2000, attempt=2
        // conn3: healthy — take(3) cancels while it streams (no further delay recorded).
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals(3, calls)
    }

    @Test
    fun `graceful close also reconnects instead of ending the collector`() = runTest {
        var calls = 0
        val upstream: () -> kotlinx.coroutines.flow.Flow<String> = {
            calls++
            when (calls) {
                1 -> flow { emit("a") } // server closes the socket "cleanly" → normal completion
                else -> flow { emit("b") }
            }
        }
        val delays = mutableListOf<Long>()

        val result = reconnectingFlow(upstream, delayFn = { delays.add(it) }).take(2).toList()

        assertEquals(listOf("a", "b"), result)
        // Clean close after a successful frame → backoff resets → single 1000ms wait.
        assertEquals(listOf(1_000L), delays)
        assertEquals(2, calls)
    }

    @Test
    fun `downstream exception propagates instead of triggering a reconnect`() = runTest {
        var calls = 0
        val upstream: () -> kotlinx.coroutines.flow.Flow<Int> = {
            calls++
            flow { emit(1); emit(2) }
        }
        val delays = mutableListOf<Long>()

        val failure = assertFailsWith<IllegalStateException> {
            reconnectingFlow(upstream, delayFn = { delays.add(it) }).collect {
                throw IllegalStateException("collector failed")
            }
        }

        assertEquals("collector failed", failure.message)
        // The collector's exception must NOT be treated as a socket drop:
        // no re-subscribe, no backoff delay.
        assertEquals(1, calls)
        assertTrue(delays.isEmpty(), "unexpected reconnect delays: $delays")
    }

    @Test
    fun `regional block is surfaced once and never retried`() = runTest {
        var calls = 0
        val upstream: () -> kotlinx.coroutines.flow.Flow<Int> = {
            calls++
            flow { throw UpstreamHttpException("klines", 451) }
        }
        val delays = mutableListOf<Long>()

        val failure = assertFailsWith<UpstreamHttpException> {
            reconnectingFlow(upstream, delayFn = { delays.add(it) }).collect { }
        }

        assertEquals(451, failure.status)
        assertEquals(1, calls)
        assertTrue(delays.isEmpty(), "a regional block must not be retried: $delays")
    }

    @Test
    fun `a 403 refusal is retried with backoff so the feed resumes once it clears`() = runTest {
        var calls = 0
        val upstream: () -> kotlinx.coroutines.flow.Flow<Int> = {
            calls++
            when (calls) {
                1 -> flow { throw UpstreamHttpException("klines", 403) } // temporary WAF refusal
                else -> flow { emit(7) }
            }
        }
        val delays = mutableListOf<Long>()

        val result = reconnectingFlow(upstream, delayFn = { delays.add(it) }).take(1).toList()

        assertEquals(listOf(7), result)
        assertEquals(listOf(1_000L), delays, "one backed-off retry, then the stream resumes")
        assertEquals(2, calls)
    }

    @Test
    fun `a 403 handshake is not classified as a region block`() {
        val refused = IllegalStateException("Handshake exception, expected status code 101 but was 403")
        val mapped = refused.asUpstreamIfHandshake("wss://fstream.binance.com/ws")
        assertTrue(!mapped.isRegionBlocked())
        assertTrue(mapped.isForbidden())
    }

    @Test
    fun `a refused WebSocket upgrade keeps its HTTP status so a region block is recognised`() {
        val refused = IllegalStateException("Handshake exception, expected status code 101 but was 451")
        val mapped = refused.asUpstreamIfHandshake("wss://fstream.binance.com/ws")
        assertTrue(mapped.isRegionBlocked())
        // Unrelated failures are returned untouched.
        val other = IllegalStateException("socket reset")
        assertTrue(other.asUpstreamIfHandshake("x") === other)
    }

    @Test
    fun `take after many frames ends the flow without reconnecting`() = runTest {
        var calls = 0
        val upstream: () -> kotlinx.coroutines.flow.Flow<Int> = {
            calls++
            flow { var i = 0; while (true) emit(i++) }
        }
        val delays = mutableListOf<Long>()

        val result = reconnectingFlow(upstream, delayFn = { delays.add(it) }).take(5).toList()

        assertEquals(listOf(0, 1, 2, 3, 4), result)
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }
}
