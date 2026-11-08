package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade

/** In-memory OHLCV + VWAP resampling — port of `crocodile/core/resample/{ohlcv,metrics}.py`. */
object Resample {

    private const val NS_PER_DAY = 86_400L * 1_000_000_000L

    /** 1970-01-05 was a Monday: weekly bars are anchored on Monday 00:00 UTC, as in the Python path. */
    private const val MONDAY_ANCHOR_NS = 4L * NS_PER_DAY

    private val WEEK_NS = 7L * NS_PER_DAY

    /**
     * Start of the bar containing [localTs]. Buckets are epoch-aligned, except weekly bars which
     * are aligned to Monday (the epoch itself was a Thursday). The remainder is taken with a
     * non-negative modulus so pre-1970 timestamps floor the same way as later ones.
     */
    private fun bucketStart(localTs: Long, intervalNs: Long): Long {
        val anchor = if (intervalNs == WEEK_NS) MONDAY_ANCHOR_NS else 0L
        val offset = localTs - anchor
        val remainder = ((offset % intervalNs) + intervalNs) % intervalNs
        return localTs - remainder
    }

    /** Resample trades into OHLCV bars (interval in ns). Ordered by bar ascending. */
    fun resampleOhlcv(trades: List<Trade>, intervalNs: Long, interval: String = ""): List<OHLCV> {
        if (trades.isEmpty()) return emptyList()
        val byBucket = trades.groupBy { bucketStart(it.localTs, intervalNs) }
        return byBucket.toSortedMap().map { (bar, rows) ->
            val ordered = rows.sortedBy { it.localTs }
            val first = ordered.first(); val last = ordered.last()
            val volume = ordered.sumOf { it.amount }
            OHLCV(
                venue = first.venue, symbol = first.symbol, symbolRaw = first.symbolRaw,
                exchangeTs = bar, localTs = bar,
                open = first.price, high = ordered.maxOf { it.price },
                low = ordered.minOf { it.price }, close = last.price,
                volume = volume,
                // Trades carry their aggressor, so the split IS measured here
                // (UNKNOWN-side trades fall in neither: buy + sell <= volume).
                buyVolume = ordered.filter { it.side == Side.BUY }.sumOf { it.amount },
                sellVolume = ordered.filter { it.side == Side.SELL }.sumOf { it.amount },
                numTrades = ordered.size,
                vwap = if (volume != 0.0) ordered.sumOf { it.price * it.amount } / volume else null,
                interval = interval,
            )
        }
    }

    data class MetricRow(
        val bar: Long, val symbol: String, val vwap: Double,
        val dollarVolume: Double, val tradeCount: Int,
    )

    /** VWAP, dollar volume, trade count per bucket. Ordered by bar ascending. */
    fun resampleMetrics(trades: List<Trade>, intervalNs: Long): List<MetricRow> {
        if (trades.isEmpty()) return emptyList()
        return trades.groupBy { bucketStart(it.localTs, intervalNs) }.toSortedMap().map { (bar, rows) ->
            val notional = rows.sumOf { it.price * it.amount }
            val vol = rows.sumOf { it.amount }
            MetricRow(
                bar = bar, symbol = rows.first().symbol,
                vwap = if (vol != 0.0) notional / vol else Double.NaN,
                dollarVolume = notional, tradeCount = rows.size,
            )
        }
    }
}
