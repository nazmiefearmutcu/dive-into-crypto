package com.diveintocrypto.android.engine.schema

import kotlin.test.Test
import kotlin.test.assertEquals

class EnumsTest {
    @Test fun venueWireKeysMatchCrypcodile() {
        assertEquals("deribit", Venue.DERIBIT.wire)
        assertEquals("binance-spot", Venue.BINANCE_SPOT.wire)
        assertEquals("binance-usdm", Venue.BINANCE_USDM.wire)
    }

    @Test fun sideFromAggressorFlag() {
        assertEquals(Side.BUY, Side.fromBuyerMaker(false))   // taker bought
        assertEquals(Side.SELL, Side.fromBuyerMaker(true))   // taker sold
    }

    @Test fun channelWireKeys() {
        assertEquals("options_chain", Channel.OPTIONS_CHAIN.wire)
        assertEquals("derivative_ticker", Channel.DERIVATIVE_TICKER.wire)
    }
}
