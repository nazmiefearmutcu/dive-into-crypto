package com.diveintocrypto.android.domain.math

import kotlin.math.sqrt

/**
 * Pandas-equivalent rolling-window and exponentially-weighted helpers.
 * All functions return a list of the same length as input; positions
 * where the window is not fully populated yield `null`.
 *
 * Matches pandas semantics:
 *  - rolling(window=N).mean()         -> rollingMean
 *  - rolling(window=N).std()          -> rollingStd  (sample, ddof=1)
 *  - rolling(window=N).min() / .max() -> rollingMin / rollingMax
 *  - ewm(span=N, adjust=False).mean() -> ewmAdjustFalse
 */
object Series {

    fun rollingMean(values: List<Double>, window: Int): List<Double?> {
        require(window > 0)
        return values.indices.map { i ->
            if (i + 1 < window) null
            else values.subList(i + 1 - window, i + 1).average()
        }
    }

    fun rollingStd(values: List<Double>, window: Int): List<Double?> {
        require(window > 1) { "Sample std needs at least 2 elements" }
        return values.indices.map { i ->
            if (i + 1 < window) null
            else {
                val w = values.subList(i + 1 - window, i + 1)
                val mean = w.average()
                val sumSq = w.sumOf { (it - mean) * (it - mean) }
                sqrt(sumSq / (window - 1))
            }
        }
    }

    fun rollingMin(values: List<Double>, window: Int): List<Double?> {
        require(window > 0)
        return values.indices.map { i ->
            if (i + 1 < window) null
            else values.subList(i + 1 - window, i + 1).min()
        }
    }

    fun rollingMax(values: List<Double>, window: Int): List<Double?> {
        require(window > 0)
        return values.indices.map { i ->
            if (i + 1 < window) null
            else values.subList(i + 1 - window, i + 1).max()
        }
    }

    /**
     * y_0 = x_0
     * y_t = alpha * x_t + (1 - alpha) * y_{t-1}
     * alpha = 2 / (span + 1)
     */
    fun ewmAdjustFalse(values: List<Double>, span: Int): List<Double> {
        require(values.isNotEmpty())
        require(span > 0)
        val alpha = 2.0 / (span + 1)
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = alpha * values[i] + (1.0 - alpha) * out[i - 1]
        }
        return out.toList()
    }

    /**
     * Wilder's smoothing (a.k.a. Wilder's MA, RMA).
     *
     *   - seed = mean of the first `period` values, placed at index `period - 1`
     *   - thereafter: y_t = ((period - 1) * y_{t-1} + x_t) / period
     *     (equivalent to the recursive form y_t = y_{t-1} - y_{t-1}/period + x_t/period)
     *
     * Indices `[0, period - 2]` are returned as `null` (warm-up region) so the
     * output length matches the input. Mirrors `ta`/pandas `ewm(alpha=1/period,
     * adjust=False)` with the simple-mean seed used by Wilder's original
     * formulation. Useful for indicators such as ATR / Wilder-smoothed ADX.
     */
    fun wilderSmooth(values: List<Double>, period: Int): List<Double?> {
        require(period > 0)
        val n = values.size
        if (n < period) return List(n) { null }
        val out = arrayOfNulls<Double>(n)
        var acc = 0.0
        for (i in 0 until period) acc += values[i]
        val seed = acc / period
        out[period - 1] = seed
        var prev = seed
        for (i in period until n) {
            val next = prev - prev / period + values[i] / period
            out[i] = next
            prev = next
        }
        return out.toList()
    }
}
