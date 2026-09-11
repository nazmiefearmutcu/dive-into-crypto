package com.diveintocrypto.android.data.binance

import com.diveintocrypto.android.data.ScanUniverseMode
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * aggTrades REST parsing (CVD input) + the new scanner-universe settings keys.
 */
class AggTradesAndUniverseSettingsTest {

    private fun mockClient(body: String): HttpClient = HttpClient(
        MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    @Test
    fun `aggTrades parses price qty time and the aggressor flag`() = runTest {
        val sample = """
        [
          {"a":1,"p":"65100.10","q":"0.5","f":100,"l":100,"T":1700000000000,"m":true,"M":true},
          {"a":2,"p":"65100.50","q":"1.25","f":101,"l":101,"T":1700000000500,"m":false,"M":true}
        ]
        """.trimIndent()
        val http = mockClient(sample)
        http.use {
            val trades = BinanceFuturesClient(client = it).aggTrades("BTCUSDT", 1000)
            assertEquals(2, trades.size)
            assertEquals(65100.10, trades[0].price, 1e-9)
            assertEquals(0.5, trades[0].quantity, 1e-9)
            assertEquals(1700000000000L, trades[0].timestamp)
            assertTrue(trades[0].isBuyerMaker, "m=true → taker SOLD")
            assertTrue(!trades[1].isBuyerMaker, "m=false → taker bought")
        }
    }

    @Test
    fun `scan_universe and scan_depth_top persist and load with defaults`() {
        val store = com.diveintocrypto.android.data.SettingsStore(InMemoryKeyValueStore())
        // Defaults: TOP50 universe, phase-2 depth 50.
        assertEquals("TOP50", store.getSettings().scanUniverse)
        assertEquals(50, store.getSettings().scanDepthTop)

        store.updateSettings(store.getSettings().copy(scanUniverse = "ALL", scanDepthTop = 120))

        val persisted = store.getSettings()
        assertEquals("ALL", persisted.scanUniverse)
        assertEquals(120, persisted.scanDepthTop)
        assertEquals(ScanUniverseMode.ALL, ScanUniverseMode.fromLabel(persisted.scanUniverse))
    }
}
