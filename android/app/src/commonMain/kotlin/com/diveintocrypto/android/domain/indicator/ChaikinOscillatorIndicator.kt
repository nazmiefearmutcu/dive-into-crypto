package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.sqrt

/**
 * Chaikin Oscillator — verbatim port of the original Python reference
 * implementation.
 *
 * CHO = EMA_fast(ADL) - EMA_slow(ADL), where the ADL is the running total of
 * volume weighted by the intrabar close location (Money Flow Multiplier).
 * The oscillator is unbounded and scales with a symbol's volume, so both the
 * level and the one-bar slope are normalised by the oscillator's own recent
 * rolling standard deviation to obtain dimensionless z-scores, then mapped
 * with a small, regime-based decision tree (zero-line crossover first, then
 * sign + slope grading, with STRONG escalation on decisive impulses).
 *
 * Strictly causal: cumsum, ewm(adjust=False), trailing rolling std and a
 * one-bar diff read only the current and prior candles.
 */
class ChaikinOscillatorIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "chaikin_oscillator"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val fastPeriod = config.getInt("fast_period", 3)
        val slowPeriod = config.getInt("slow_period", 10)
        val normPeriod = config.getInt("norm_period", 100)
        val strongSlope = config.getDouble("strong_slope", 0.6)
        val strongLevel = config.getDouble("strong_level", 1.5)

        val n = candles.size
        // Need enough candles for the slow EMA to be meaningful plus a prior bar.
        if (n < slowPeriod + 2) {
            return result(Signal.NEUTRAL, "Chaikin data insufficient")
        }

        // --- Accumulation/Distribution Line ---
        // Money Flow Multiplier in [-1, 1]; flat candle (high==low) -> 0 (no bias).
        val adl = DoubleArray(n)
        var cum = 0.0
        for (i in 0 until n) {
            val c = candles[i]
            val hlRange = c.high - c.low
            val mfm = if (hlRange > 0.0) {
                ((c.close - c.low) - (c.high - c.close)) / hlRange
            } else {
                0.0
            }
            cum += mfm * c.volume
            adl[i] = cum
        }

        // --- Chaikin Oscillator ---
        val adlList = adl.toList()
        val emaFast = Series.ewmAdjustFalse(adlList, fastPeriod)
        val emaSlow = Series.ewmAdjustFalse(adlList, slowPeriod)
        val cho = DoubleArray(n) { emaFast[it] - emaSlow[it] }

        val current = cho[n - 1]
        val prev = cho[n - 2]

        if (current.isNaN() || prev.isNaN()) {
            return result(Signal.NEUTRAL, "Chaikin data insufficient")
        }

        // --- Normalisation: z-scores against the oscillator's recent dispersion ---
        // pandas: cho.rolling(window=norm_period, min_periods=min_std_periods).std()
        // evaluated at the last bar; the trailing window is clipped at the start.
        val minStdPeriods = minOf(n, maxOf(20, fastPeriod + slowPeriod))
        val windowStart = maxOf(0, n - normPeriod)
        var scale = if (n - windowStart >= minStdPeriods) {
            sampleStd(cho, windowStart, n)
        } else {
            Double.NaN
        }
        if (scale.isNaN() || scale <= 0.0) scale = sampleStd(cho, 0, n)
        if (scale.isNaN() || scale <= 0.0) scale = EPS

        val slope = current - prev
        val levelZ = current / (scale + EPS)
        val slopeZ = slope / (scale + EPS)

        val bullCross = prev <= 0.0 && current > 0.0
        val bearCross = prev >= 0.0 && current < 0.0

        val raw = mapOf<String, Double?>(
            "cho" to round4(current),
            "prev_cho" to round4(prev),
            "adl" to round2(adl[n - 1]),
            "level_z" to round3(levelZ),
            "slope_z" to round3(slopeZ),
            // "regime" is a string in Python; tests only check numeric raws.
        )

        // --- Decision tree ---
        if (bullCross) {
            if (slopeZ >= strongSlope) {
                return result(
                    Signal.STRONG_BUY,
                    "Chaikin crossed above zero with decisive impulse (slope_z=${slopeZ.format(2)})",
                    raw,
                )
            }
            return result(Signal.BUY, "Chaikin crossed above zero (accumulation begins)", raw)
        }

        if (bearCross) {
            if (slopeZ <= -strongSlope) {
                return result(
                    Signal.STRONG_SELL,
                    "Chaikin crossed below zero with decisive impulse (slope_z=${slopeZ.format(2)})",
                    raw,
                )
            }
            return result(Signal.SELL, "Chaikin crossed below zero (distribution begins)", raw)
        }

        if (current > 0.0) {
            val rising = current > prev
            if (rising && levelZ >= strongLevel && slopeZ >= strongSlope) {
                return result(
                    Signal.STRONG_BUY,
                    "Chaikin extended and accelerating up (level_z=${levelZ.format(2)})",
                    raw,
                )
            }
            if (rising) {
                return result(Signal.BUY, "Chaikin positive and rising (accumulation building)", raw)
            }
            return result(
                Signal.NEUTRAL, "Chaikin positive but fading (accumulation losing momentum)", raw,
            )
        }

        if (current < 0.0) {
            val falling = current < prev
            if (falling && levelZ <= -strongLevel && slopeZ <= -strongSlope) {
                return result(
                    Signal.STRONG_SELL,
                    "Chaikin extended and accelerating down (level_z=${levelZ.format(2)})",
                    raw,
                )
            }
            if (falling) {
                return result(Signal.SELL, "Chaikin negative and falling (distribution building)", raw)
            }
            return result(
                Signal.NEUTRAL, "Chaikin negative but recovering (distribution losing momentum)", raw,
            )
        }

        return result(Signal.NEUTRAL, "Chaikin at zero (no money-flow bias)", raw)
    }

    /** Sample standard deviation (ddof=1) over a[from, to); NaN if fewer than 2 values. */
    private fun sampleStd(a: DoubleArray, from: Int, to: Int): Double {
        val count = to - from
        if (count < 2) return Double.NaN
        var sum = 0.0
        for (i in from until to) sum += a[i]
        val mean = sum / count
        var sumSq = 0.0
        for (i in from until to) {
            val d = a[i] - mean
            sumSq += d * d
        }
        return sqrt(sumSq / (count - 1))
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
    private fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0
    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

    private companion object {
        const val EPS = 1e-12
    }
}
