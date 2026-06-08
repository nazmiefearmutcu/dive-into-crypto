package com.diveintocrypto.android.data.binance

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
 * Tests for [BinanceFuturesClient] — the only Binance client remaining after
 * the paper-trading and bot subsystems were deleted. Covers both endpoints
 * the scanner actually calls.
 *
 * Transport is now Ktor; instead of OkHttp MockWebServer we feed canned JSON
 * through a Ktor [MockEngine] injected via the client's `client` constructor
 * param (production default stays `binanceHttpClient()`).
 */
class BinanceClientsTest {

    private fun mockClient(body: String): HttpClient = HttpClient(
        MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    )

    @Test
    fun `klines parses Binance JSON array-of-arrays`() = runTest {
        val sample = """
        [
          [1700000000000,"40000.10","40100.50","39950.00","40050.20","123.45",1700003599999,"4937050.10",123,"61.72","2468525.05","0"],
          [1700003600000,"40050.20","40200.00","40000.00","40150.00","98.76",1700007199999,"3963207.00",100,"49.38","1981603.50","0"]
        ]
        """.trimIndent()

        val http = mockClient(sample)
        http.use {
            val client = BinanceFuturesClient(client = it)
            val candles = client.klines("BTCUSDT", "1h", 2)

            assertEquals(2, candles.size)
            assertEquals(40000.10, candles[0].open, 1e-9)
            assertEquals(40150.00, candles[1].close, 1e-9)
            assertEquals(1700003599999L, candles[0].closeTime)
        }
    }

    @Test
    fun `universe24h filters stablecoins and sorts by quote volume desc`() = runTest {
        // BTCUSDT > ETHUSDT > SOLUSDT by quoteVolume.
        // USDCUSDT is in SKIP_SYMBOLS → must be dropped.
        // BTCBUSD does not end with USDT → must be dropped.
        val sample = """
        [
          {"symbol":"ETHUSDT","quoteVolume":"5000000000.00"},
          {"symbol":"BTCUSDT","quoteVolume":"9000000000.00"},
          {"symbol":"USDCUSDT","quoteVolume":"7777777777.00"},
          {"symbol":"BTCBUSD","quoteVolume":"6666666666.00"},
          {"symbol":"SOLUSDT","quoteVolume":"1000000000.00"}
        ]
        """.trimIndent()

        val http = mockClient(sample)
        http.use {
            val client = BinanceFuturesClient(client = it)
            val universe = client.universe24hSortedByVolume()

            assertEquals(listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"), universe)
            assertTrue("USDCUSDT" !in universe, "USDCUSDT should be filtered")
            assertTrue("BTCBUSD" !in universe, "BTCBUSD does not end with USDT")
        }
    }
}
