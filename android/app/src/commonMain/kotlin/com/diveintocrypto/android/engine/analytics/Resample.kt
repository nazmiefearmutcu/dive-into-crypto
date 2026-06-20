package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade

/** In-memory OHLCV + VWAP resampling — port of `crypcodile/resample/{ohlcv,metrics}.py`. */
object Resample {

    private fun bucketStart(localTs: Long, intervalNs: Long): Long = (localTs / intervalNs) * intervalNs

    /** Resample trades into OHLCV bars (interval in ns). Ordered by bar ascending. */
    fun resampleOhlcv(trades: List<Trade>, intervalNs: Long, interval: String = ""): List<OHLCV> {
        if (trades.isEmpty()) return emptyList()
        val byBucket = trades.groupBy { bucketStart(it.localTs, intervalNs) }
        return byBucket.toSortedMap().map { (bar, rows) ->
            val ordered = rows.sortedBy { it.localTs }
            val first = ordered.first(); val last = ordered.last()
            OHLCV(
                venue = first.venue, symbol = first.symbol, symbolRaw = first.symbolRaw,
                exchangeTs = bar, localTs = bar,
                open = first.price, high = ordered.maxOf { it.price },
                low = ordered.minOf { it.price }, close = last.price,
                volume = ordered.sumOf { it.amount },
                buyVolume = ordered.filter { it.side == Side.BUY }.sumOf { it.amount },
                sellVolume = ordered.filter { it.side == Side.SELL }.sumOf { it.amount },
                numTrades = ordered.size,
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
