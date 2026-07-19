package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.max
import kotlin.math.min

/**
 * Ultimate Oscillator (UO). Port of the desktop engine's
 * `ultimate_oscillator.py` — same math, thresholds and signal mapping.
 *
 * Per candle:
 *   prior_close = close[t-1]
 *   BP = close - min(low, prior_close)          (buying pressure)
 *   TR = max(high, prior_close) - min(low, prior_close)
 *   Avg_n = sum(BP, n) / sum(TR, n)
 *   UO = 100 * (4*Avg7 + 2*Avg14 + 1*Avg28) / 7   (weights configurable)
 *
 * Bounded in [0, 100], strictly causal, never repaints.
 */
class UltimateOscillatorIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "ultimate_oscillator"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val shortPeriod = config.getInt("short_period", 7)
        val midPeriod = config.getInt("mid_period", 14)
        val longPeriod = config.getInt("long_period", 28)
        val wShort = config.getDouble("weight_short", 4.0)
        val wMid = config.getDouble("weight_mid", 2.0)
        val wLong = config.getDouble("weight_long", 1.0)
        val strongBuyLevel = config.getDouble("strong_buy", 20.0)
        val buyLevel = config.getDouble("buy", 30.0)
        val sellLevel = config.getDouble("sell", 70.0)
        val strongSellLevel = config.getDouble("strong_sell", 80.0)

        val maxPeriod = maxOf(shortPeriod, midPeriod, longPeriod)
        // Longest rolling window plus one row for the prior-close shift.
        if (candles.size < maxPeriod + 1) {
            return result(Signal.NEUTRAL, "Ultimate Oscillator data insufficient")
        }

        val n = candles.size
        // Index 0 has no prior close (pandas shift) -> NaN, poisoning any
        // rolling sum that still includes it (min_periods == window).
        val bp = DoubleArray(n) { Double.NaN }
        val tr = DoubleArray(n) { Double.NaN }
        for (i in 1 until n) {
            val priorClose = candles[i - 1].close
            val trueLow = min(candles[i].low, priorClose)
            val trueHigh = max(candles[i].high, priorClose)
            bp[i] = candles[i].close - trueLow  // buying pressure
            tr[i] = trueHigh - trueLow          // true range
        }

        val avgShort = uoAverage(bp, tr, shortPeriod)
        val avgMid = uoAverage(bp, tr, midPeriod)
        val avgLong = uoAverage(bp, tr, longPeriod)

        val weightTotal = wShort + wMid + wLong
        if (weightTotal == 0.0) {
            return result(Signal.NEUTRAL, "Ultimate Oscillator weights invalid")
        }

        val uo = DoubleArray(n) { i ->
            100.0 * (wShort * avgShort[i] + wMid * avgMid[i] + wLong * avgLong[i]) / weightTotal
        }

        val currentUo = uo[n - 1]
        val prevUo = if (n >= 2) uo[n - 2] else currentUo

        if (currentUo.isNaN()) {
            return result(Signal.NEUTRAL, "Ultimate Oscillator data insufficient")
        }

        val raw = mapOf<String, Double?>(
            "uo" to round2(currentUo),
            "prev_uo" to if (prevUo.isNaN()) null else round2(prevUo),
        )

        return when {
            currentUo <= strongBuyLevel ->
                result(Signal.STRONG_BUY, "UO=${currentUo.format(1)} deeply oversold across 7/14/28", raw)
            currentUo <= buyLevel ->
                result(Signal.BUY, "UO=${currentUo.format(1)} oversold buying-pressure zone", raw)
            currentUo >= strongSellLevel ->
                result(Signal.STRONG_SELL, "UO=${currentUo.format(1)} deeply overbought across 7/14/28", raw)
            currentUo >= sellLevel ->
                result(Signal.SELL, "UO=${currentUo.format(1)} overbought buying-pressure zone", raw)
            else ->
                result(Signal.NEUTRAL, "UO=${currentUo.format(1)} neutral buying-pressure balance", raw)
        }
    }

    /**
     * Avg_n = rolling_sum(BP, n) / rolling_sum(TR, n) with a flat-market guard:
     * TR sum of zero (or an incomplete/NaN window) yields NaN -> NEUTRAL fallback.
     */
    private fun uoAverage(bp: DoubleArray, tr: DoubleArray, period: Int): DoubleArray {
        val bpSum = rollingSumStrict(bp, period)
        val trSum = rollingSumStrict(tr, period)
        return DoubleArray(bp.size) { i ->
            val t = trSum[i]
            if (t.isNaN() || t == 0.0) Double.NaN else bpSum[i] / t
        }
    }

    /** pandas rolling(window).sum(): incomplete window or NaN in window -> NaN. */
    private fun rollingSumStrict(values: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        outer@ for (i in window - 1 until values.size) {
            var sum = 0.0
            for (j in i - window + 1..i) {
                val v = values[j]
                if (v.isNaN()) continue@outer
                sum += v
            }
            out[i] = sum
        }
        return out
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
