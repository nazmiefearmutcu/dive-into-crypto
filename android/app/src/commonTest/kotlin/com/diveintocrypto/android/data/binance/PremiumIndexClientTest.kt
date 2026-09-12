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

/**
 * `/fapi/v1/premiumIndex` wire parsing (0.3.0 parity addition), fed through a
 * Ktor MockEngine exactly like [BinanceClientsTest].
 */
class PremiumIndexClientTest {

    @Test
    fun `premiumIndex parses the single-symbol payload`() = runTest {
        val body = """
            {"symbol":"BTCUSDT","markPrice":"101.5","indexPrice":"100.0",
             "lastFundingRate":"0.00010000","nextFundingTime":1699999999999,
             "time":1699999900000}
        """.trimIndent()
        val http = HttpClient(
            MockEngine { _ ->
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        http.use {
            val dto = BinanceFuturesClient(client = it).premiumIndex("BTCUSDT")
            assertEquals("BTCUSDT", dto.symbol)
            assertEquals(101.5, dto.markPrice, 1e-9)
            assertEquals(100.0, dto.indexPrice, 1e-9)
            assertEquals(0.0001, dto.lastFundingRate, 1e-12)
            assertEquals(1_699_999_999_999L, dto.nextFundingTime)
        }
    }

    @Test
    fun `premiumIndex surfaces a non-200 as a thrown IllegalStateException`() = runTest {
        val http = HttpClient(
            MockEngine { _ ->
                respond(content = "{}", status = HttpStatusCode.BadRequest)
            },
        )
        http.use {
            var thrown = false
            try {
                BinanceFuturesClient(client = it).premiumIndex("BTCUSDT")
            } catch (e: IllegalStateException) {
                thrown = e.message.orEmpty().contains("premiumIndex HTTP 400")
            }
            assertEquals(true, thrown, "expected the honest HTTP-400 error")
        }
    }
}
