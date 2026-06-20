package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeribitNormalizeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    @Test fun optionTickerToOptionsChainWithIvPercentAndRawGreeks() {
        val msg = obj("""
        {"params":{"channel":"ticker.BTC-27JUN25-100000-C.100ms","data":{
          "instrument_name":"BTC-27JUN25-100000-C","timestamp":1700000000000,
          "underlying_price":95000.0,"mark_price":0.05,"mark_iv":65.0,
          "best_bid_price":0.049,"best_bid_amount":5.0,"bid_iv":64.0,
          "best_ask_price":0.051,"best_ask_amount":5.0,"ask_iv":66.0,
          "last_price":0.05,"open_interest":1234.0,
          "greeks":{"delta":0.42,"gamma":0.00001,"vega":120.0,"theta":-30.0,"rho":10.0}
        }}}""")
        val recs = DeribitNormalize.normalizeMessage(msg, localTs = 9L)
        assertEquals(1, recs.size)
        val oc = recs[0] as OptionsChain
        assertEquals("deribit:BTC-27JUN25-100000-C", oc.symbol)
        assertEquals(0.65, oc.markIv!!, 1e-12)
        assertEquals(0.64, oc.bidIv!!, 1e-12)
        assertEquals(0.42, oc.delta!!, 1e-12)
        assertEquals(120.0, oc.vega!!, 1e-12)
        assertEquals(100000.0, oc.strike)
        assertEquals(OptType.CALL, oc.optType)
        assertEquals(1700000000000L * 1_000_000L, oc.exchangeTs)
        assertEquals(9L, oc.localTs)
    }

    @Test fun perpTickerToDerivativeTickerAndFunding() {
        val msg = obj("""
        {"params":{"channel":"ticker.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,
          "last_price":95000.0,"mark_price":95010.0,"index_price":95005.0,
          "current_funding":0.0001,"funding_8h":0.0002,"open_interest":50000.0
        }}}""")
        val recs = DeribitNormalize.normalizeMessage(msg, localTs = 9L)
        assertEquals(2, recs.size)
        val dt = recs[0] as DerivativeTicker
        assertEquals(95010.0, dt.markPrice!!); assertEquals(95005.0, dt.indexPrice!!)
        assertEquals(0.0001, dt.fundingRate!!, 1e-12)
        val f = recs[1] as Funding
        assertEquals(0.0001, f.fundingRate, 1e-12); assertEquals(8, f.intervalHours)
    }

    @Test fun tradesWithLiquidationEmitsTradeAndLiquidation() {
        val msg = obj("""
        {"params":{"channel":"trades.BTC-PERPETUAL.100ms","data":[
          {"instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,"trade_id":"t1",
           "price":95000.0,"amount":10.0,"direction":"sell","liquidation":"M"}
        ]}}""")
        val recs = DeribitNormalize.normalizeMessage(msg, localTs = 9L)
        assertEquals(2, recs.size)
        val t = recs[0] as Trade
        assertEquals(Side.SELL, t.side); assertTrue(t.liquidation)
        assertTrue(recs[1] is Liquidation)
    }

    @Test fun bookSnapshotAndDeltaLevels() {
        val snap = obj("""
        {"params":{"channel":"book.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,"type":"snapshot",
          "change_id":100,"bids":[["new",95000.0,5.0]],"asks":[["new",95010.0,3.0]]
        }}}""")
        val s = DeribitNormalize.normalizeMessage(snap, localTs = 9L)[0] as BookSnapshot
        assertEquals(1, s.bids.size); assertEquals(95000.0, s.bids[0].price); assertEquals(5.0, s.bids[0].amount)
        assertEquals(2, s.depth)

        val delta = obj("""
        {"params":{"channel":"book.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000001,"type":"change",
          "change_id":101,"prev_change_id":100,"bids":[["delete",95000.0,0.0]],"asks":[]
        }}}""")
        val d = DeribitNormalize.normalizeMessage(delta, localTs = 9L)[0] as BookDelta
        assertEquals(0.0, d.bids[0].amount)
        assertEquals(101L, d.seqId); assertEquals(100L, d.prevSeqId)
    }

    @Test fun unknownChannelYieldsNothing() {
        assertTrue(DeribitNormalize.normalizeMessage(obj("""{"params":{"channel":"x.y","data":{}}}"""), 1L).isEmpty())
        assertTrue(DeribitNormalize.normalizeMessage(obj("""{"foo":1}"""), 1L).isEmpty())
    }
}
