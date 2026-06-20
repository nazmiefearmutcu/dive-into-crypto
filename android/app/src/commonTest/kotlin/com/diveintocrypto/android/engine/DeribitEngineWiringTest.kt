package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
import com.diveintocrypto.android.engine.exchanges.deribit.DeribitConnector
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertTrue

class DeribitEngineWiringTest {
    private fun engine() = MarketDataEngine(
        BinanceConnector(BinanceSpotClient(), BinanceFuturesClient(), BinanceWsClient()),
        SettingsStore(InMemoryKeyValueStore()),
        DeribitConnector(),
    )

    @Test fun engineExposesDeribitConnector() {
        val e = engine()
        val wire = e.deribit.buildChannels(setOf("BTC-PERPETUAL"), setOf(Channel.DERIVATIVE_TICKER))
        assertTrue("ticker.BTC-PERPETUAL.100ms" in wire)
    }

    @Test fun handleFrameThroughEngineConnector() {
        val e = engine()
        val text = """{"params":{"channel":"ticker.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,"mark_price":1.0,"index_price":1.0}}}"""
        assertTrue(e.deribit.handleFrameText(text, 5L).any { it is DerivativeTicker })
    }
}
