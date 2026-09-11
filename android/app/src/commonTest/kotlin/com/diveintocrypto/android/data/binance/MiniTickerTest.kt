package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.data.binance.dto.WsMiniTickerEnvelope
import com.diveintocrypto.android.engine.LiveTickerEngine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `!miniTicker@arr` envelope parsing (same decode path the WS client uses) and
 * the PURE merge semantics of [LiveTickerEngine] — stream price over REST price,
 * 24h change% only ever from REST (never derived, never fabricated).
 */
class MiniTickerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `miniTicker envelope array parses into typed ticks`() {
        val wire = """
        [
          {"e":"24hrMiniTicker","E":123456789,"s":"BTCUSDT","c":"65123.40","o":"64000.00","h":"66000.00","l":"63500.00","v":"10000","q":"18"},
          {"e":"24hrMiniTicker","E":123456790,"s":"ETHUSDT","c":"3456.78","o":"3400.00","h":"3500.00","l":"3390.00","v":"500","q":"9"}
        ]
        """.trimIndent()

        val envelopes = json.decodeFromString(ListSerializer(WsMiniTickerEnvelope.serializer()), wire)
        assertEquals(2, envelopes.size)
        assertEquals("BTCUSDT", envelopes[0].symbol)
        assertEquals(123456789L, envelopes[0].eventTime)
        assertEquals("65123.40", envelopes[0].close)

        // The client's mapping (string → double).
        val tick = envelopes[0].let {
            BinanceWsClient.MiniTicker(
                symbol = it.symbol,
                lastPrice = it.close.toDoubleOrNull() ?: 0.0,
                openPrice = it.open.toDoubleOrNull() ?: 0.0,
                highPrice = it.high.toDoubleOrNull() ?: 0.0,
                lowPrice = it.low.toDoubleOrNull() ?: 0.0,
                eventTime = it.eventTime,
            )
        }
        assertEquals(65123.40, tick.lastPrice, 1e-9)
        assertEquals(64000.00, tick.openPrice, 1e-9)
    }

    @Test
    fun `mergeStream folds the batch, keeps REST changePercent, never invents one`() {
        val restMerged = mapOf(
            "BTCUSDT" to LiveTickerEngine.LiveTicker("BTCUSDT", 64_000.0, 1.5, 1L, "REST"),
        )
        val batch = listOf(
            BinanceWsClient.MiniTicker("BTCUSDT", 65_100.0, 64_000.0, 66_000.0, 63_500.0, 2L),
            BinanceWsClient.MiniTicker("NEWUSDT", 5.0, 4.9, 5.1, 4.8, 2L),
        )
        val out = LiveTickerEngine.mergeStream(restMerged, batch, nowMs = 2_000L)

        // Stream price overrides REST price; the REST 24h change% is PRESERVED.
        val btc = out.getValue("BTCUSDT")
        assertEquals(65_100.0, btc.price, 1e-9)
        assertEquals(1.5, btc.changePercent!!, 1e-9)
        assertEquals("WS", btc.source)
        assertEquals(2_000L, btc.ts)

        // A symbol never seen by REST has NO change% (null) — not a fabricated 0.
        val neu = out.getValue("NEWUSDT")
        assertEquals(5.0, neu.price, 1e-9)
        assertNull(neu.changePercent)
    }

    @Test
    fun `mergeRest supplies changePercent but never clobbers a fresher WS price`() {
        val wsMerged = mapOf(
            "BTCUSDT" to LiveTickerEngine.LiveTicker("BTCUSDT", 65_100.0, null, 2_000L, "WS"),
        )
        val rest = listOf(
            Ticker24h("BTCUSDT", 64_900.0, 1.7, 9_000_000.0, 66_000.0, 63_500.0),
            Ticker24h("ETHUSDT", 3_450.0, -0.5, 5_000_000.0, 3_500.0, 3_390.0),
        )
        val out = LiveTickerEngine.mergeRest(wsMerged, rest, nowMs = 61_000L)

        // WS price is fresher → survives; change% updated from REST.
        val btc = out.getValue("BTCUSDT")
        assertEquals(65_100.0, btc.price, 1e-9, "REST must not clobber the fresher WS price")
        assertEquals(1.7, btc.changePercent!!, 1e-9)
        assertEquals("WS", btc.source)

        // REST-only symbol: full snapshot.
        val eth = out.getValue("ETHUSDT")
        assertEquals(3_450.0, eth.price, 1e-9)
        assertEquals(-0.5, eth.changePercent!!, 1e-9)
        assertEquals("REST", eth.source)
        assertTrue(eth.ts == 61_000L)
    }

    @Test
    fun `merge with empty inputs is a no-op`() {
        val existing = mapOf("A" to LiveTickerEngine.LiveTicker("A", 1.0, null, 0L, "WS"))
        assertTrue(LiveTickerEngine.mergeStream(existing, emptyList(), 5L) === existing)
        assertTrue(LiveTickerEngine.mergeRest(existing, emptyList(), 5L) === existing)
    }
}
