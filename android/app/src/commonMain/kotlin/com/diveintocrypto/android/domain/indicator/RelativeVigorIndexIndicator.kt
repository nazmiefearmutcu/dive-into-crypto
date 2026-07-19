package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Relative Vigor Index (RVI, John Ehlers) — verbatim port of the original
 * Python reference implementation.
 *
 * Candle-body conviction oscillator: both the body (close - open) and the
 * range (high - low) are smoothed with a 4-bar Symmetric Weighted Moving
 * Average (triangular weights [1, 2, 2, 1] / 6), summed over `period` bars,
 * then divided — bounding RVI to [-1, +1]. The Signal line is the SWMA of RVI.
 * The tradable event is the RVI/Signal crossover, graded by the side of the
 * zero line; without a fresh cross, sustained strong vigor continues the bias.
 *
 * NaN warm-up regions are propagated exactly as pandas does (SWMA lags 3 bars;
 * rolling sums require a full window), so values match the Python series
 * bar-for-bar. Strictly causal: trailing FIR filters and trailing sums only.
 */
class RelativeVigorIndexIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "relative_vigor_index"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 10)
        val strongLevel = config.getDouble("strong_level", 0.5)

        val n = candles.size
        // RVI needs the 4-bar SWMA (3 lags) then a `period`-bar sum -> period+3
        // bars; the Signal line adds a further 3-bar SWMA lag -> period+6 bars.
        val minBars = period + 6
        if (n < minBars) {
            return result(
                Signal.NEUTRAL,
                "RVI data insufficient",
                mapOf("rvi" to null, "signal" to null),
            )
        }

        val body = DoubleArray(n) { candles[it].close - candles[it].open }
        val rng = DoubleArray(n) { candles[it].high - candles[it].low }

        val num = rollingSumFull(swma(body), period)
        val den = rollingSumFull(swma(rng), period)

        // den is a triangular-weighted sum of (High-Low) >= 0; it is only zero in
        // a fully dead market. Guard against divide-by-zero -> NaN there.
        val rvi = DoubleArray(n) { i ->
            if (den[i] > 0.0) num[i] / den[i] else Double.NaN
        }
        val signal = swma(rvi)

        val rviNow = rvi[n - 1]
        val sigNow = signal[n - 1]
        if (rviNow.isNaN() || sigNow.isNaN()) {
            return result(
                Signal.NEUTRAL,
                "RVI data insufficient",
                mapOf("rvi" to null, "signal" to null),
            )
        }

        val curHist = rviNow - sigNow
        var prevHist = rvi[n - 2] - signal[n - 2]
        if (prevHist.isNaN()) {
            prevHist = curHist  // no confirmed cross available yet
        }

        val raw = mapOf<String, Double?>(
            "rvi" to round4(rviNow),
            "signal" to round4(sigNow),
            "hist" to round4(curHist),
            "period" to period.toDouble(),
        )

        val bullishCross = prevHist <= 0.0 && curHist > 0.0
        val bearishCross = prevHist >= 0.0 && curHist < 0.0

        // --- Fresh RVI/Signal crossover: the primary RVI event ---------------
        if (bullishCross) {
            if (rviNow > 0.0) {
                return result(
                    Signal.STRONG_BUY,
                    "RVI=${rviNow.format(3)} crossed above signal in bullish territory",
                    raw,
                )
            }
            return result(
                Signal.BUY,
                "RVI=${rviNow.format(3)} crossed above signal (below zero, early turn)",
                raw,
            )
        }

        if (bearishCross) {
            if (rviNow < 0.0) {
                return result(
                    Signal.STRONG_SELL,
                    "RVI=${rviNow.format(3)} crossed below signal in bearish territory",
                    raw,
                )
            }
            return result(
                Signal.SELL,
                "RVI=${rviNow.format(3)} crossed below signal (above zero, early turn)",
                raw,
            )
        }

        // --- No fresh cross: sustained-vigor continuation -------------------
        if (curHist > 0.0 && rviNow >= strongLevel) {
            return result(
                Signal.BUY,
                "RVI=${rviNow.format(3)} holding above signal, strong bullish vigor",
                raw,
            )
        }
        if (curHist < 0.0 && rviNow <= -strongLevel) {
            return result(
                Signal.SELL,
                "RVI=${rviNow.format(3)} holding below signal, strong bearish vigor",
                raw,
            )
        }

        return result(
            Signal.NEUTRAL,
            "RVI=${rviNow.format(3)} vs signal=${sigNow.format(3)}: no cross / weak vigor",
            raw,
        )
    }

    /**
     * Symmetric (triangular) weighted moving average, weights [1,2,2,1]/6.
     * First 3 positions are NaN (warm-up), and NaN inputs propagate — matching
     * pandas `(s + 2*s.shift(1) + 2*s.shift(2) + s.shift(3)) / 6`.
     */
    private fun swma(a: DoubleArray): DoubleArray {
        val out = DoubleArray(a.size) { Double.NaN }
        for (i in 3 until a.size) {
            out[i] = (a[i] + 2.0 * a[i - 1] + 2.0 * a[i - 2] + a[i - 3]) / 6.0
        }
        return out
    }

    /**
     * pandas rolling(window, min_periods=window).sum(): NaN until the trailing
     * window is full AND every value inside it is non-NaN.
     */
    private fun rollingSumFull(a: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(a.size) { Double.NaN }
        outer@ for (i in a.indices) {
            if (i + 1 < window) continue
            var sum = 0.0
            for (j in i - window + 1..i) {
                val v = a[j]
                if (v.isNaN()) continue@outer
                sum += v
            }
            out[i] = sum
        }
        return out
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
