package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.BookDelta
import com.diveintocrypto.android.engine.schema.BookSnapshot
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeribitConnectorTest {
    private val c = DeribitConnector()

    @Test fun venueIsDeribit() { assertEquals(Venue.DERIBIT, c.venue) }

    @Test fun buildChannelsMapsAndDedups() {
        val chans = c.buildChannels(
            setOf("BTC-PERPETUAL"),
            setOf(Channel.DERIVATIVE_TICKER, Channel.OPTIONS_CHAIN, Channel.TRADE, Channel.BOOK_DELTA, Channel.BOOK_SNAPSHOT),
        )
        assertTrue("ticker.BTC-PERPETUAL.100ms" in chans)
        assertTrue("trades.BTC-PERPETUAL.100ms" in chans)
        assertTrue("book.BTC-PERPETUAL.100ms" in chans)
        assertEquals(chans, chans.distinct())
    }

    @Test fun subscribeFrameIsJsonRpc() {
        val frame = c.subscribeFrame(listOf("ticker.BTC-PERPETUAL.100ms"))
        assertTrue(frame.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(frame.contains("public/subscribe"))
        assertTrue(frame.contains("ticker.BTC-PERPETUAL.100ms"))
    }

    @Test fun handleFrameTextNormalizes() {
        val text = """{"params":{"channel":"ticker.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,
          "mark_price":95010.0,"index_price":95005.0,"last_price":95000.0}}}"""
        val recs = c.handleFrameText(text, localTs = 5L)
        assertTrue(recs.any { it is DerivativeTicker })
    }

    @Test fun handleFrameTextSwallowsBadJson() {
        assertTrue(c.handleFrameText("not json", localTs = 5L).isEmpty())
    }

    @Test fun bookGateAcceptsChainedDeltasAndFlagsASequenceGap() {
        val gate = DeribitBookGate()
        val sym = "BTC-PERPETUAL"
        fun snap(seq: Long) = BookSnapshot(Venue.DERIBIT, "deribit:$sym", sym, null, 1L, emptyList(), emptyList(), seqId = seq)
        fun delta(seq: Long, prev: Long) = BookDelta(Venue.DERIBIT, "deribit:$sym", sym, null, 1L, emptyList(), emptyList(), seqId = seq, prevSeqId = prev)

        assertTrue(gate.accept(snap(100L)))
        assertTrue(gate.accept(delta(101L, prev = 100L)))
        assertTrue(gate.accept(delta(102L, prev = 101L)))
        // 103 was lost: 104 claims to follow 103, so the local book is no longer valid.
        assertFalse(gate.accept(delta(104L, prev = 103L)))
    }

    @Test fun bookGateRejectsDeltaWithoutASnapshot() {
        val gate = DeribitBookGate()
        assertFalse(gate.accept(BookDelta(Venue.DERIBIT, "deribit:X", "X", null, 1L, emptyList(), emptyList(), seqId = 5L, prevSeqId = 4L)))
    }
}
