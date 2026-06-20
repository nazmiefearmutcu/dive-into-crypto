package com.diveintocrypto.android.engine.schema

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun ohlcvCarriesCommonFields() {
        val r = OHLCV(
            venue = Venue.BINANCE_USDM, symbol = "binance-usdm:BTCUSDT", symbolRaw = "BTCUSDT",
            exchangeTs = 1_700_000_000_000_000_000L, localTs = 1_700_000_000_000_000_001L,
            open = 1.0, high = 2.0, low = 0.5, close = 1.5, volume = 10.0,
            buyVolume = 6.0, sellVolume = 4.0, numTrades = 3, interval = "1m",
        )
        assertEquals(Channel.OHLCV, r.channel)
        assertEquals("binance-usdm:BTCUSDT", r.symbol)
    }

    @Test fun optionsChainHoldsGreeks() {
        val r = OptionsChain(
            venue = Venue.DERIBIT, symbol = "deribit:BTC-28MAR25-100000-C", symbolRaw = "BTC-28MAR25-100000-C",
            exchangeTs = null, localTs = 1L,
            underlying = "BTC", underlyingPrice = 95000.0, strike = 100000.0,
            expiry = 1_743_148_800_000_000_000L, optType = OptType.CALL,
            markPrice = 0.05, bidPx = 0.049, bidSz = 5.0, askPx = 0.051, askSz = 5.0, lastPrice = 0.05,
            markIv = 0.62, bidIv = 0.61, askIv = 0.63,
            delta = 0.42, gamma = 0.00001, vega = 120.0, theta = -30.0, rho = 10.0,
            openInterest = 1234.0,
        )
        assertEquals(Channel.OPTIONS_CHAIN, r.channel)
        assertEquals(0.42, r.delta)
    }

    @Test fun recordSerializationRoundTripsPolymorphically() {
        val orig: Record = Trade(
            venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL", symbolRaw = "BTC-PERPETUAL",
            exchangeTs = 1L, localTs = 2L, id = "t1", price = 95000.0, amount = 1.5,
            side = Side.BUY, liquidation = false,
        )
        val encoded = json.encodeToString(Record.serializer(), orig)
        val decoded = json.decodeFromString(Record.serializer(), encoded)
        assertTrue(decoded is Trade)
        assertEquals(95000.0, (decoded as Trade).price)
    }
}
