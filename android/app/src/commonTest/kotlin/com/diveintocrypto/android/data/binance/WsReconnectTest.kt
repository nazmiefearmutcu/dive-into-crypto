package com.diveintocrypto.android.data.binance

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
