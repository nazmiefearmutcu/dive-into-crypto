package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
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

class MarketDataEngineTest {
    private fun klineClient(): HttpClient {
        val body = """[[1700000000000,"1.0","2.0","0.5","1.5","10.0",1700000059999,"0",0,"0","0","0"]]"""
        return HttpClient(MockEngine { respond(body, HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) })
    }

    @Test fun futuresHistoryDelegatesToConnector() = runTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        val connector = BinanceConnector(
            spot = BinanceSpotClient(client = klineClient()),
            futures = BinanceFuturesClient(client = klineClient()),
            ws = BinanceWsClient(),
        )
        val engine = MarketDataEngine(connector, settings)
        val candles = engine.futuresHistory("BTCUSDT", "1m", limit = 1)
        assertEquals(1, candles.size)
        assertEquals(1.5, candles.first().close)
    }

    @Test fun historyCachesAndDoesNotRefetchIfSufficient() = runTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            val now = com.diveintocrypto.android.platform.nowMillis()
            val body = """[[$now,"1.0","2.0","0.5","1.5","10.0",${now + 59999},"0",0,"0","0","0"]]"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val connector = BinanceConnector(
            spot = BinanceSpotClient(client = HttpClient(mockEngine)),
            futures = BinanceFuturesClient(),
            ws = BinanceWsClient(),
        )
        val engine = MarketDataEngine(connector, settings)

        // 1st request -> fetches
        val res1 = engine.history("BTCUSDT", "1m", limit = 1)
        assertEquals(1, res1.size)
        assertEquals(1, requestCount)

        // 2nd request -> cached
        val res2 = engine.history("BTCUSDT", "1m", limit = 1)
        assertEquals(1, res2.size)
        assertEquals(1, requestCount) // Request count remains 1
    }

    @Test fun historyRefetchesIfCacheIsStale() = runTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            // Hardcoded stale open time
            val body = """[[1700000000000,"1.0","2.0","0.5","1.5","10.0",1700000059999,"0",0,"0","0","0"]]"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val connector = BinanceConnector(
            spot = BinanceSpotClient(client = HttpClient(mockEngine)),
            futures = BinanceFuturesClient(),
            ws = BinanceWsClient(),
        )
        val engine = MarketDataEngine(connector, settings)

        // 1st request -> fetches
        val res1 = engine.history("BTCUSDT", "1m", limit = 1)
        assertEquals(1, res1.size)
        assertEquals(1, requestCount)

        // 2nd request -> stale (age >= 2 * 60s) -> refetches
        val res2 = engine.history("BTCUSDT", "1m", limit = 1)
        assertEquals(1, res2.size)
        assertEquals(2, requestCount)
    }

    @Test fun cacheSizeDoesNotExceed1000() = runTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        val mockEngine = MockEngine {
            // Return 1005 candles
            val list = (0..1004).map { i ->
                val t = 1700000000000L + i * 60000L
                """[$t,"1.0","2.0","0.5","1.5","10.0",${t + 59999},"0",0,"0","0","0"]"""
            }
            respond(list.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val connector = BinanceConnector(
            spot = BinanceSpotClient(client = HttpClient(mockEngine)),
            futures = BinanceFuturesClient(),
            ws = BinanceWsClient(),
        )
        val engine = MarketDataEngine(connector, settings)

        // Fetch 1005 candles
        val res = engine.history("BTCUSDT", "1m", limit = 1005)
        
        // The merged/pruned list returned should be capped to 1000
        assertEquals(1000, res.size)
        // The first candle in the list should have openTime index 5
        assertEquals(1700000000000L + 5 * 60000L, res.first().openTime)
    }

    @Test fun restEndpointsCacheWithTtl() = runTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            val body = """[{"symbol":"BTCUSDT","sumOpenInterest":"100.0","sumOpenInterestValue":"50000.0","timestamp":1700000000000}]"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val connector = BinanceConnector(
            spot = BinanceSpotClient(),
            futures = BinanceFuturesClient(client = HttpClient(mockEngine)),
            ws = BinanceWsClient(),
        )
        val engine = MarketDataEngine(connector, settings)

        // 1st request -> fetches
        val res1 = engine.openInterestHist("BTCUSDT", "1h", limit = 30)
        assertEquals(1, res1.size)
        assertEquals(1, requestCount)

        // 2nd request -> cached (within 30s)
        val res2 = engine.openInterestHist("BTCUSDT", "1h", limit = 30)
        assertEquals(1, res2.size)
        assertEquals(1, requestCount) // Request count remains 1
    }
}
