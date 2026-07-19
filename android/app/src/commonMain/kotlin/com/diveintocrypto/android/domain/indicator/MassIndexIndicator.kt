package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Mass Index (Donald Dorsey, 1992) — verbatim port of the original Python
 * reference implementation.
 *
 *   range = high - low
 *   ema1  = EMA(range, ema_period)      (fast single-smoothed range)
 *   ema2  = EMA(ema1,  ema_period2)     (slow double-smoothed range)
 *   MI    = rolling_sum(ema1 / ema2, sum_period)
 *
 * Reversal trigger (per Dorsey): the Mass Index climbed to >= bulge_threshold
 * somewhere in the recent lookback window and has since fallen back below
 * setback_threshold on the current closed bar. Direction is supplied by the
 * slope of a short EMA of close: rising into the bulge -> top (SELL side),
 * falling -> bottom (BUY side), flat -> NEUTRAL.
 *
 * Strictly causal: recursive EMAs (adjust=False), a trailing rolling sum, and
 * a strictly backward slope; no repainting.
 */
class MassIndexIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "mass_index"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val emaPeriod = config.getInt("ema_period", 9)
        val emaPeriod2 = config.getInt("ema_period2", 9)
        val sumPeriod = config.getInt("sum_period", 25)
        val bulgeThreshold = config.getDouble("bulge_threshold", 27.0)
        val setbackThreshold = config.getDouble("setback_threshold", 26.5)
        val strongBulgeThreshold = config.getDouble("strong_bulge_threshold", 27.5)
        val reversalLookback = config.getInt("reversal_lookback", 25)
        val dirEmaPeriod = config.getInt("dir_ema_period", 9)
        val dirLookback = config.getInt("dir_lookback", 9)

        val n = candles.size
        // Need enough bars for the rolling sum plus a little bulge history.
        if (n < sumPeriod + 5) {
            return result(Signal.NEUTRAL, "Mass Index data insufficient")
        }

        val rng = List(n) { (candles[it].high - candles[it].low).coerceAtLeast(0.0) }

        // Fast single-smoothed range and slow double-smoothed range (recursive
        // EMA, adjust=False -> strictly causal).
        val ema1 = Series.ewmAdjustFalse(rng, emaPeriod)
        val ema2 = Series.ewmAdjustFalse(ema1, emaPeriod2)

        // Dimensionless bulge ratio. A perfectly flat market gives ema1==ema2==0;
        // define that degenerate ratio as 1.0 (no bulge).
        val ratio = DoubleArray(n) { i ->
            if (ema2[i] == 0.0) {
                1.0
            } else {
                val r = ema1[i] / ema2[i]
                if (r.isFinite()) r else 1.0
            }
        }

        // MI = trailing rolling sum of the ratio; valid from index sum_period-1.
        val miValid = DoubleArray(n - sumPeriod + 1)
        var windowSum = 0.0
        for (i in 0 until sumPeriod) windowSum += ratio[i]
        miValid[0] = windowSum
        for (i in sumPeriod until n) {
            windowSum += ratio[i] - ratio[i - sumPeriod]
            miValid[i - sumPeriod + 1] = windowSum
        }
        if (miValid.isEmpty()) {
            return result(Signal.NEUTRAL, "Mass Index data insufficient")
        }

        val miLast = miValid[miValid.size - 1]

        // Look for a bulge in the recent (trailing) window of Mass Index values.
        val lookback = minOf(reversalLookback, miValid.size)
        var bulgePeak = Double.NEGATIVE_INFINITY
        for (i in miValid.size - lookback until miValid.size) {
            if (miValid[i] > bulgePeak) bulgePeak = miValid[i]
        }

        // Reversal trigger: bulge occurred (peak >= threshold) AND the index has
        // since retreated below the setback level on the current closed bar.
        val triggered = bulgePeak >= bulgeThreshold && miLast < setbackThreshold
        val strongBulge = bulgePeak >= strongBulgeThreshold

        // Direction filter: slope of a short EMA of close, looking strictly back.
        val emaClose = Series.ewmAdjustFalse(List(n) { candles[it].close }, dirEmaPeriod)
        val dl = minOf(dirLookback, n - 1)
        val slope = emaClose[n - 1] - emaClose[n - 1 - dl]
        val trendUp = slope > 0
        val trendDown = slope < 0

        val raw = mapOf<String, Double?>(
            "mass_index" to round3(miLast),
            "bulge_peak" to round3(bulgePeak),
            "bulge_threshold" to bulgeThreshold,
            "setback_threshold" to setbackThreshold,
            "triggered" to if (triggered) 1.0 else 0.0,  // bool in Python; 1.0/0.0 here
            // "trend" is a string in Python ("UP"/"DOWN"/"FLAT"); numeric raws only.
            "slope" to round6(slope),
        )

        if (triggered && trendUp) {
            // Rising price into a bulge -> reversal top -> bearish.
            if (strongBulge) {
                return result(
                    Signal.STRONG_SELL,
                    "Reversal bulge (peak ${bulgePeak.format(2)}) after uptrend -> top",
                    raw,
                )
            }
            return result(
                Signal.SELL,
                "Reversal bulge (peak ${bulgePeak.format(2)}) after uptrend -> top",
                raw,
            )
        }

        if (triggered && trendDown) {
            // Falling price into a bulge -> reversal bottom -> bullish.
            if (strongBulge) {
                return result(
                    Signal.STRONG_BUY,
                    "Reversal bulge (peak ${bulgePeak.format(2)}) after downtrend -> bottom",
                    raw,
                )
            }
            return result(
                Signal.BUY,
                "Reversal bulge (peak ${bulgePeak.format(2)}) after downtrend -> bottom",
                raw,
            )
        }

        if (triggered) {  // trend FLAT
            return result(
                Signal.NEUTRAL,
                "Reversal bulge (peak ${bulgePeak.format(2)}) but no trend to reverse",
                raw,
            )
        }

        if (miLast >= bulgeThreshold) {
            return result(
                Signal.NEUTRAL,
                "Mass Index ${miLast.format(2)} bulging - awaiting setback below " +
                    setbackThreshold.format(1),
                raw,
            )
        }

        return result(
            Signal.NEUTRAL,
            "Mass Index ${miLast.format(2)} - no reversal bulge",
            raw,
        )
    }

    private fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0
    private fun round6(x: Double): Double = Math.round(x * 1000000.0) / 1000000.0
}
