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
}
