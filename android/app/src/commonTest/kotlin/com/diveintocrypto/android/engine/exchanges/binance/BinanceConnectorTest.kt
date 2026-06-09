package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.OHLCV
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinanceConnectorTest {
    private fun mockKlines(): HttpClient {
        val body = """[[1700000000000,"1.0","2.0","0.5","1.5","10.0",1700000059999,"0",0,"0","0","0"]]"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) }
        return HttpClient(engine)
    }

    @Test fun backfillOhlcvReturnsCanonicalRecords() = runTest {
        val futures = BinanceFuturesClient(client = mockKlines())
        val connector = BinanceConnector(futures = futures)
        val recs = connector.backfill(Channel.OHLCV, "BTCUSDT", 0, Long.MAX_VALUE, limit = 1)
        assertEquals(1, recs.size)
        val o = recs.first()
        assertTrue(o is OHLCV)
        assertEquals(1.5, (o as OHLCV).close)
        assertEquals("binance-usdm:BTCUSDT", o.symbol)
    }
}
