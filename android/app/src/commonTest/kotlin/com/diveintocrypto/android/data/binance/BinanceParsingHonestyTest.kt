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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Honest-parsing contract for the Binance REST clients: a row with a missing required
 * field is dropped (never zero-filled), a non-2xx answer is an [UpstreamHttpException],
 * and the kline window parameters reach the wire.
 */
class BinanceParsingHonestyTest {

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK, sink: ((String) -> Unit)? = null): HttpClient =
        HttpClient(
            MockEngine { request ->
                sink?.invoke(request.url.toString())
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    @Test
    fun `aggTrade without an aggressor flag is dropped instead of counted as a taker buy`() = runTest {
        val body = """[
            {"a":1,"p":"65100.10","q":"0.5","T":1700000000000,"m":true},
            {"a":2,"p":"65100.50","q":"1.25","T":1700000000500}
        ]"""
        client(body).use {
            val trades = BinanceFuturesClient(client = it).aggTrades("BTCUSDT", 1000)
            assertEquals(1, trades.size)
            assertEquals(1L, trades[0].aggTradeId)
            assertTrue(trades[0].isBuyerMaker)
        }
    }

    @Test
    fun `ticker row missing a required number is dropped, not zero-filled`() = runTest {
        val body = """[
            {"symbol":"BTCUSDT","lastPrice":"65000","priceChangePercent":"1.2","quoteVolume":"100","highPrice":"66000","lowPrice":"64000"},
            {"symbol":"ETHUSDT","lastPrice":"3400","quoteVolume":"50","highPrice":"3500","lowPrice":"3300"}
        ]"""
        client(body).use {
            val rows = BinanceFuturesClient(client = it).ticker24hAll()
            assertEquals(listOf("BTCUSDT"), rows.map { r -> r.symbol })
        }
    }

    @Test
    fun `funding row without a rate is dropped`() = runTest {
        val body = """[
            {"symbol":"BTCUSDT","fundingTime":1700000000000,"fundingRate":"0.0001"},
            {"symbol":"BTCUSDT","fundingTime":1700028800000}
        ]"""
        client(body).use {
            val rows = BinanceFuturesClient(client = it).fundingRate("BTCUSDT", limit = 2)
            assertEquals(1, rows.size)
            assertEquals(0.0001, rows[0].fundingRate, 1e-12)
        }
    }

    @Test
    fun `a regional block on klines is an UpstreamHttpException with the status`() = runTest {
        client("{}", HttpStatusCode(451, "Unavailable For Legal Reasons")).use {
            val e = assertFailsWith<UpstreamHttpException> {
                BinanceFuturesClient(client = it).klines("BTCUSDT", "1m", 10)
            }
            assertEquals(451, e.status)
            assertTrue(e.regionBlocked)
            assertTrue(!e.transient)
        }
    }

    @Test
    fun `a rate limit is transient and not a region block`() = runTest {
        client("{}", HttpStatusCode(429, "Too Many Requests")).use {
            val e = assertFailsWith<UpstreamHttpException> {
                BinanceSpotClient(client = it).klines("BTCUSDT", "1m", 10)
            }
            assertTrue(e.transient)
            assertTrue(!e.regionBlocked)
        }
    }

    @Test
    fun `klines sends the requested window in milliseconds`() = runTest {
        var requested = ""
        val body = """[[1700000000000,"1.0","2.0","0.5","1.5","10.0",1700000059999,"0",0,"0","0","0"]]"""
        client(body) { requested = it }.use {
            BinanceFuturesClient(client = it).klines(
                symbol = "BTCUSDT",
                interval = "1m",
                limit = 1,
                startTimeMs = 1_700_000_000_000L,
                endTimeMs = 1_700_000_060_000L,
            )
        }
        assertTrue(requested.contains("startTime=1700000000000"), requested)
        assertTrue(requested.contains("endTime=1700000060000"), requested)
    }

    @Test
    fun `funding interval map keeps positive hours and skips malformed rows`() = runTest {
        val body = """[
            {"symbol":"BTCUSDT","fundingIntervalHours":8},
            {"symbol":"ALTUSDT","fundingIntervalHours":4},
            {"symbol":"BADUSDT","fundingIntervalHours":0}
        ]"""
        client(body).use {
            val map = BinanceFuturesClient(client = it).fundingIntervalHours()
            assertEquals(mapOf("BTCUSDT" to 8, "ALTUSDT" to 4), map)
        }
    }
}
