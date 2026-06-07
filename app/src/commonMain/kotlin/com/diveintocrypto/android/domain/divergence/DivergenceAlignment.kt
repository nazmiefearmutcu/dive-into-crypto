package com.diveintocrypto.android.domain.divergence

/**
 * Aligns the price series (candle closes) with the whale-L/S series **by timestamp**.
 *
 * Both series are in epoch-ms at the same period resolution; Binance stamps both the kline
 * `openTime` and the futures-data `timestamp` at the START of the period →
 * `t / periodMs` drops the same period into the same bucket. Missing L/S buckets are
 * forward-filled; candles before the L/S data starts are skipped.
 *
 * Pure + independent of data-layer types (takes parallel arrays) → directly testable in
 * commonTest with synthetic timestamps. (This logic used to be inline inside
 * ScannerViewModel.computeDivergence; being network-dependent it was untestable —
 * the highest-risk fix was untested.)
 */
object DivergenceAlignment {

    data class Result(
        val price: List<Double>,
        val whale: List<Double>,
        /** Number of candles matched to a real L/S bucket (NOT forward-fill). */
        val matched: Int,
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList(), 0)
        }
    }

    /**
     * @param priceTimes candle open times (ms, old→new)
     * @param priceVals  candle closes (same length as priceTimes)
     * @param lsTimes    whale-L/S timestamps (ms)
     * @param lsVals     whale-L/S ratios (same length as lsTimes)
     * @param periodMs   TF period duration (ms); ≤0 → empty result
     */
    fun align(
        priceTimes: List<Long>,
        priceVals: List<Double>,
        lsTimes: List<Long>,
        lsVals: List<Double>,
        periodMs: Long,
    ): Result {
        if (periodMs <= 0L) return Result.EMPTY
        val n = minOf(priceTimes.size, priceVals.size)
        val m = minOf(lsTimes.size, lsVals.size)
        if (n == 0 || m == 0) return Result.EMPTY

        val lsByBucket = HashMap<Long, Double>(m)
        for (i in 0 until m) lsByBucket[lsTimes[i] / periodMs] = lsVals[i]

        val price = ArrayList<Double>(n)
        val whale = ArrayList<Double>(n)
        var lastWhale = Double.NaN
        var matched = 0
        for (i in 0 until n) {
            val w = lsByBucket[priceTimes[i] / periodMs]
            if (w != null) { lastWhale = w; matched++ }
            if (!lastWhale.isNaN()) { // skip candles before the L/S data starts
                price.add(priceVals[i])
                whale.add(lastWhale)
            }
        }
        return Result(price, whale, matched)
    }
}
