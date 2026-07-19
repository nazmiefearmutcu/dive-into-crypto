package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Stochastic RSI (StochRSI). Port of the desktop engine's `stoch_rsi.py` —
 * same math, thresholds and signal mapping.
 *
 * Pipeline (all trailing / causal):
 *   1. RSI over `rsi_period` (repo rolling-mean RSI convention).
 *   2. StochRSI_raw = 100 * (RSI - min(RSI, N)) / (max(RSI, N) - min(RSI, N))
 *      over `stoch_period` (N); flat RSI range -> undefined (NaN).
 *   3. %K = SMA(StochRSI_raw, k_smooth); %D = SMA(%K, d_smooth).
 *
 * Signal comes from the %K zone plus the %K/%D crossover direction.
 */
class StochRsiIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "stoch_rsi"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val rsiPeriod = config.getInt("rsi_period", 14)
        val stochPeriod = config.getInt("stoch_period", 14)
        val kSmooth = config.getInt("k_smooth", 3)
        val dSmooth = config.getInt("d_smooth", 3)
        val oversold = config.getDouble("oversold", 20.0)
        val overbought = config.getDouble("overbought", 80.0)

        val needed = rsiPeriod + stochPeriod + kSmooth + dSmooth
        if (candles.size < needed) {
            return result(Signal.NEUTRAL, "StochRSI data insufficient", mapOf("stoch_rsi_k" to null))
        }

        val closes = candles.map { it.close }
        val n = closes.size
        val rsi = rollingRsi(closes, rsiPeriod)

        val rsiMin = rollingMinStrict(rsi, stochPeriod)
        val rsiMax = rollingMaxStrict(rsi, stochPeriod)

        val stochRsiRaw = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val mn = rsiMin[i]
            val mx = rsiMax[i]
            if (mn.isNaN() || mx.isNaN()) continue
            val denom = mx - mn
            if (denom == 0.0) continue // pandas: zero range -> NaN
            stochRsiRaw[i] = 100.0 * (rsi[i] - mn) / denom
        }

        val kLine = rollingMeanStrict(stochRsiRaw, kSmooth)
        val dLine = rollingMeanStrict(kLine, dSmooth)

        val currentK = kLine[n - 1]
        val currentD = dLine[n - 1]
        val prevK = if (n >= 2) kLine[n - 2] else currentK
        val prevD = if (n >= 2) dLine[n - 2] else currentD

        if (currentK.isNaN() || currentD.isNaN() || prevK.isNaN() || prevD.isNaN()) {
            return result(Signal.NEUTRAL, "StochRSI data insufficient", mapOf("stoch_rsi_k" to null))
        }

        val raw = mapOf<String, Double?>(
            "stoch_rsi_k" to round2(currentK),
            "stoch_rsi_d" to round2(currentD),
        )

        // %K/%D crossovers (momentum-of-momentum turn confirmation).
        val crossUp = prevK <= prevD && currentK > currentD
        val crossDn = prevK >= prevD && currentK < currentD

        // STRONG signals: a fresh %K/%D cross while inside an extreme zone.
        if (crossUp && currentK <= oversold) {
            return result(Signal.STRONG_BUY, "StochRSI bullish cross in oversold K=${currentK.format(1)}", raw)
        }
        if (crossDn && currentK >= overbought) {
            return result(Signal.STRONG_SELL, "StochRSI bearish cross in overbought K=${currentK.format(1)}", raw)
        }

        // BUY: turning up while still oversold, or a bullish cross below midline.
        if ((currentK < oversold && currentK > prevK) || (crossUp && currentK < 50.0)) {
            return result(Signal.BUY, "StochRSI turning up from low K=${currentK.format(1)}", raw)
        }

        // SELL: turning down while still overbought, or a bearish cross above midline.
        if ((currentK > overbought && currentK < prevK) || (crossDn && currentK > 50.0)) {
            return result(Signal.SELL, "StochRSI turning down from high K=${currentK.format(1)}", raw)
        }

        return result(Signal.NEUTRAL, "StochRSI neutral K=${currentK.format(1)} D=${currentD.format(1)}", raw)
    }

    /**
     * Simple rolling-mean RSI matching the desktop repo's rsi.py conventions:
     * warm-up -> NaN, flat window -> 50.0, all-gains window -> 100.0.
     */
    private fun rollingRsi(values: List<Double>, period: Int): DoubleArray {
        val n = values.size
        val gain = DoubleArray(n)
        val loss = DoubleArray(n)
        for (i in 1 until n) {
            val d = values[i] - values[i - 1]
            if (d > 0.0) gain[i] = d else if (d < 0.0) loss[i] = -d
        }
        val out = DoubleArray(n) { Double.NaN }
        for (i in period - 1 until n) {
            var g = 0.0
            var l = 0.0
            for (j in i - period + 1..i) {
                g += gain[j]
                l += loss[j]
            }
            val avgGain = g / period
            val avgLoss = l / period
            out[i] = when {
                avgGain == 0.0 && avgLoss == 0.0 -> 50.0
                avgLoss == 0.0 -> 100.0
                else -> 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
            }
        }
        return out
    }

    /** pandas rolling(window, min_periods=window).min(): NaN in window -> NaN. */
    private fun rollingMinStrict(values: DoubleArray, window: Int): DoubleArray =
        rollingStrict(values, window) { acc, v -> if (v < acc) v else acc }

    /** pandas rolling(window, min_periods=window).max(): NaN in window -> NaN. */
    private fun rollingMaxStrict(values: DoubleArray, window: Int): DoubleArray =
        rollingStrict(values, window) { acc, v -> if (v > acc) v else acc }

    /** pandas rolling(window, min_periods=window).mean(): NaN in window -> NaN. */
    private fun rollingMeanStrict(values: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        outer@ for (i in window - 1 until values.size) {
            var sum = 0.0
            for (j in i - window + 1..i) {
                val v = values[j]
                if (v.isNaN()) continue@outer
                sum += v
            }
            out[i] = sum / window
        }
        return out
    }

    private inline fun rollingStrict(
        values: DoubleArray,
        window: Int,
        combine: (Double, Double) -> Double,
    ): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        outer@ for (i in window - 1 until values.size) {
            var acc = values[i - window + 1]
            if (acc.isNaN()) continue@outer
            for (j in i - window + 2..i) {
                val v = values[j]
                if (v.isNaN()) continue@outer
                acc = combine(acc, v)
            }
            out[i] = acc
        }
        return out
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
