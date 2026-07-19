package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.ln

/**
 * Fisher Transform indicator (Ehlers). Port of the desktop engine's
 * `fisher_transform.py` — same math, thresholds and signal mapping.
 *
 * Per bar t, Price = (High + Low) / 2:
 *   ratio_t  = (Price_t - MinL_t) / (MaxH_t - MinL_t)
 *   norm_t   = 2 * (ratio_t - 0.5)
 *   value_t  = 0.33 * norm_t + 0.67 * value_{t-1}, clamped to [-0.999, 0.999]
 *   fisher_t = 0.5 * ln((1 + value_t) / (1 - value_t)) + 0.5 * fisher_{t-1}
 *
 * Strictly causal: rolling extremes are trailing-only and both recursions are
 * IIR filters reading past state only.
 */
class FisherTransformIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "fisher_transform"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 9)
        val extreme = config.getDouble("extreme", 1.5)
        val strongExtreme = config.getDouble("strong_extreme", 2.5)

        val n = candles.size
        if (n < period + 1) {
            return result(Signal.NEUTRAL, "Fisher Transform data insufficient", mapOf("fisher" to null))
        }

        // Median price = (High + Low) / 2  (Ehlers' "Price").
        val price = candles.map { (it.high + it.low) / 2.0 }

        val rollMax = Series.rollingMax(price, period)
        val rollMin = Series.rollingMin(price, period)

        val fisherSeries = DoubleArray(n) { Double.NaN }
        var value = 0.0   // smoothed normalized price (state)
        var fisher = 0.0  // transformed output (state)

        for (i in 0 until n) {
            // Warm-up window not yet full: hold seeds, emit NaN.
            val hi = rollMax[i] ?: continue
            val lo = rollMin[i] ?: continue
            val rng = hi - lo
            val ratio = if (rng <= 0.0) 0.5 else (price[i] - lo) / rng
            val norm = 2.0 * (ratio - 0.5)              // in [-1, 1]
            value = 0.33 * norm + 0.67 * value          // forward IIR smoothing
            if (value > 0.999) value = 0.999 else if (value < -0.999) value = -0.999
            fisher = 0.5 * ln((1.0 + value) / (1.0 - value)) + 0.5 * fisher
            fisherSeries[i] = fisher
        }

        val fisherNow = fisherSeries[n - 1]
        if (fisherNow.isNaN()) {
            return result(Signal.NEUTRAL, "Fisher Transform data insufficient", mapOf("fisher" to null))
        }

        var fisherPrev = if (n >= 2) fisherSeries[n - 2] else Double.NaN
        if (fisherPrev.isNaN()) fisherPrev = fisherNow  // no confirmed turn / cross available

        val raw = mapOf<String, Double?>(
            "fisher" to round4(fisherNow),
            "trigger" to round4(fisherPrev),
            "period" to period.toDouble(),
        )

        val turningUp = fisherNow > fisherPrev
        val turningDown = fisherNow < fisherPrev

        // --- Deep oversold ------------------------------------------------
        if (fisherNow <= -strongExtreme) {
            if (turningUp) {
                return result(Signal.STRONG_BUY, "Fisher=${fisherNow.format(2)} deep oversold reversal (turning up)", raw)
            }
            return result(Signal.BUY, "Fisher=${fisherNow.format(2)} deeply stretched down, mean-reversion setup", raw)
        }

        // --- Oversold -----------------------------------------------------
        if (fisherNow <= -extreme) {
            if (turningUp) {
                return result(Signal.BUY, "Fisher=${fisherNow.format(2)} oversold reversal (turning up)", raw)
            }
            return result(Signal.NEUTRAL, "Fisher=${fisherNow.format(2)} oversold but still falling", raw)
        }

        // --- Deep overbought ---------------------------------------------
        if (fisherNow >= strongExtreme) {
            if (turningDown) {
                return result(Signal.STRONG_SELL, "Fisher=${fisherNow.format(2)} deep overbought reversal (turning down)", raw)
            }
            return result(Signal.SELL, "Fisher=${fisherNow.format(2)} deeply stretched up, mean-reversion setup", raw)
        }

        // --- Overbought ---------------------------------------------------
        if (fisherNow >= extreme) {
            if (turningDown) {
                return result(Signal.SELL, "Fisher=${fisherNow.format(2)} overbought reversal (turning down)", raw)
            }
            return result(Signal.NEUTRAL, "Fisher=${fisherNow.format(2)} overbought but still rising", raw)
        }

        // --- Mid zone: zero-line cross as fresh momentum ------------------
        if (fisherPrev <= 0.0 && fisherNow > 0.0) {
            return result(Signal.BUY, "Fisher=${fisherNow.format(2)} crossed above zero (bullish momentum)", raw)
        }
        if (fisherPrev >= 0.0 && fisherNow < 0.0) {
            return result(Signal.SELL, "Fisher=${fisherNow.format(2)} crossed below zero (bearish momentum)", raw)
        }

        return result(Signal.NEUTRAL, "Fisher=${fisherNow.format(2)} neutral zone", raw)
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
