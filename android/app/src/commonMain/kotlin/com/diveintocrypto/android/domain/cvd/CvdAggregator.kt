package com.diveintocrypto.android.domain.cvd

import com.diveintocrypto.android.data.binance.AggTrade

/**
 * One per-minute cumulative-delta bucket.
 *
 * @param minuteTs bucket start, epoch millis floored to the minute
 * @param buyVol taker-bought base volume inside the bucket
 * @param sellVol taker-sold base volume inside the bucket
 * @param delta buyVol - sellVol (signed)
 */
data class CvdBucket(
    val minuteTs: Long,
    val buyVol: Double,
    val sellVol: Double,
    val delta: Double,
)

/**
 * Rolling CVD (Cumulative Volume Delta) snapshot over a time window.
 *
 * All values are computed ONLY from real aggressor-tagged trades. [cvd] is the
 * sum of per-trade signed volume inside the window; [buckets] is the same volume
 * bucketed per minute (chronological) — sparkline-friendly.
 */
data class CvdSnapshot(
    val symbol: String,
    /** Σ buyVol − sellVol over the window (base-asset units). */
    val cvd: Double,
    val buyVol: Double,
    val sellVol: Double,
    /** Per-minute buckets, chronological, only minutes that had trades. */
    val buckets: List<CvdBucket>,
    /** Snapshot wall-clock (injected). */
    val ts: Long,
    /** Trades actually aggregated — the honest coverage of the snapshot. */
    val tradeCount: Int,
    /** Earliest trade timestamp inside the window (null when empty). The venue
     *  caps aggTrades at 1000 rows, so for liquid symbols this can be later than
     *  `ts - window` — the UI can surface the true span from it. */
    val windowStartTs: Long?,
)

/**
 * PURE aggregation math (unit-tested; no network, no clock reads).
 */
object CvdAggregator {

    const val WINDOW_MS: Long = 15 * 60_000L
    const val BUCKET_MS: Long = 60_000L

    /**
     * Aggregates [trades] into a [CvdSnapshot] over the trailing [windowMs].
     *
     * Aggressor semantics: `isBuyerMaker == true` → the TAKER SOLD (sell volume);
     * `false` → the taker bought (buy volume). Trades at or newer than
     * `nowMs - windowMs` are included.
     */
    fun aggregate(
        symbol: String,
        trades: List<AggTrade>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
        bucketMs: Long = BUCKET_MS,
    ): CvdSnapshot {
        require(bucketMs > 0) { "bucketMs must be positive" }
        val cutoff = nowMs - windowMs
        var buyVol = 0.0
        var sellVol = 0.0
        var tradeCount = 0
        var windowStart: Long? = null
        val buckets = LinkedHashMap<Long, DoubleArray>() // minuteTs → [buy, sell]

        // Trades arrive ascending by id/time from the venue, but do not rely on it:
        // single pass over whatever order they came in.
        for (t in trades) {
            if (t.timestamp < cutoff || t.timestamp > nowMs) continue
            tradeCount++
            if (windowStart == null || t.timestamp < windowStart) windowStart = t.timestamp
            if (t.isBuyerMaker) sellVol += t.quantity else buyVol += t.quantity
            val bts = floorBucket(t.timestamp, bucketMs)
            val acc = buckets.getOrPut(bts) { DoubleArray(2) }
            if (t.isBuyerMaker) acc[1] += t.quantity else acc[0] += t.quantity
        }

        val bucketList = buckets.entries
            .sortedBy { it.key }
            .map { (ts, acc) ->
                CvdBucket(minuteTs = ts, buyVol = acc[0], sellVol = acc[1], delta = acc[0] - acc[1])
            }

        return CvdSnapshot(
            symbol = symbol,
            cvd = buyVol - sellVol,
            buyVol = buyVol,
            sellVol = sellVol,
            buckets = bucketList,
            ts = nowMs,
            tradeCount = tradeCount,
            windowStartTs = windowStart,
        )
    }

    private fun floorBucket(ts: Long, bucketMs: Long): Long = ts - ts.mod(bucketMs)
}
