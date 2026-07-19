package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Schaff Trend Cycle (STC) — a MACD line passed through a *double* stochastic
 * transform with recursive smoothing; a fast, bounded (0..100) trend-cycle
 * oscillator that turns earlier than raw MACD and whipsaws less than a raw
 * stochastic. Port of the Python `schaff_trend_cycle.py` — same math,
 * thresholds, and signal mapping. Warm-up regions are modelled with
 * Double.NaN, matching pandas rolling semantics.
 */
class SchaffTrendCycleIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "schaff_trend_cycle"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val macdFast = config.getInt("macd_fast", 23)
        val macdSlow = config.getInt("macd_slow", 50)
        val cycle = config.getInt("cycle", 10)
        val smooth = config.getDouble("smooth_factor", 0.5)
        val lower = config.getDouble("lower_band", 25.0)
        val upper = config.getDouble("upper_band", 75.0)

        val minBars = macdSlow + cycle
        if (candles.size < minBars) {
            return result(Signal.NEUTRAL, "STC insufficient data (<$minBars bars)")
        }

        val closes = candles.map { it.close }
        val n = closes.size

        // --- 1) MACD line (unbounded momentum series) ---
        val emaFast = Series.ewmAdjustFalse(closes, macdFast)
        val emaSlow = Series.ewmAdjustFalse(closes, macdSlow)
        val macd = DoubleArray(n) { emaFast[it] - emaSlow[it] }

        // --- 2) First stochastic of the MACD line, then recursive smoothing (PF) ---
        val ll1 = rollingMin(macd, cycle)
        val hh1 = rollingMax(macd, cycle)
        val pf = doubleSmoothStoch(macd, ll1, hh1, smooth)

        // --- 3) Second stochastic of PF, then recursive smoothing -> STC ---
        val ll2 = rollingMin(pf, cycle)
        val hh2 = rollingMax(pf, cycle)
        val stc = doubleSmoothStoch(pf, ll2, hh2, smooth)

        var stcNow = stc[n - 1]
        var stcPrev = if (n >= 2) stc[n - 2] else Double.NaN

        if (stcNow.isNaN() || stcPrev.isNaN()) {
            return result(Signal.NEUTRAL, "STC not yet warmed up")
        }

        // Clamp for clean reporting (numerically already within [0, 100]).
        stcNow = minOf(100.0, maxOf(0.0, stcNow))
        stcPrev = minOf(100.0, maxOf(0.0, stcPrev))

        val rising = stcNow > stcPrev
        val falling = stcNow < stcPrev

        val raw = mapOf<String, Double?>(
            "stc" to round2(stcNow),
            "prev" to round2(stcPrev),
            "macd" to round6(macd[n - 1]),
            "lower" to lower,
            "upper" to upper,
        )

        // Primary signals: a band cross = a fresh trend-cycle turn (strongest).
        val crossUpLower = stcPrev <= lower && lower < stcNow
        val crossDownUpper = stcPrev >= upper && upper > stcNow

        if (crossUpLower) {
            return result(
                Signal.STRONG_BUY,
                "STC turned up through ${lower.format(0)} (new bullish cycle) STC=${stcNow.format(1)}",
                raw,
            )
        }
        if (crossDownUpper) {
            return result(
                Signal.STRONG_SELL,
                "STC turned down through ${upper.format(0)} (new bearish cycle) STC=${stcNow.format(1)}",
                raw,
            )
        }

        // Oversold zone: only a rising STC is actionable (early accumulation).
        if (stcNow < lower) {
            if (rising) {
                return result(
                    Signal.BUY, "STC rising inside oversold zone STC=${stcNow.format(1)}", raw,
                )
            }
            return result(
                Signal.NEUTRAL, "STC deeply oversold, no turn yet STC=${stcNow.format(1)}", raw,
            )
        }

        // Overbought zone: only a falling STC is actionable (early distribution).
        if (stcNow > upper) {
            if (falling) {
                return result(
                    Signal.SELL, "STC falling inside overbought zone STC=${stcNow.format(1)}", raw,
                )
            }
            return result(
                Signal.NEUTRAL, "STC deeply overbought, no turn yet STC=${stcNow.format(1)}", raw,
            )
        }

        // Mid zone (lower..upper): slope carries the cycle direction.
        if (rising) {
            return result(
                Signal.BUY, "STC rising through mid-zone STC=${stcNow.format(1)}", raw,
            )
        }
        if (falling) {
            return result(
                Signal.SELL, "STC falling through mid-zone STC=${stcNow.format(1)}", raw,
            )
        }

        return result(Signal.NEUTRAL, "STC flat STC=${stcNow.format(1)}", raw)
    }

    /**
     * Stochastic %K of `src` over a trailing window, then recursive smoothing.
     *
     * %K(i) = 100 * (src - ll) / (hh - ll); if the window range is zero the prior
     * %K is carried forward (Schaff's convention). The smoothed leg is an EMA-like
     * recursion: out(i) = out(i-1) + smooth * (%K(i) - out(i-1)), seeded on the
     * first valid bar. Purely forward -> causal, no repainting.
     */
    private fun doubleSmoothStoch(
        src: DoubleArray,
        ll: DoubleArray,
        hh: DoubleArray,
        smooth: Double,
    ): DoubleArray {
        val n = src.size
        val out = DoubleArray(n) { Double.NaN }
        var fracPrev = Double.NaN
        var outPrev = Double.NaN
        for (i in 0 until n) {
            if (ll[i].isNaN() || hh[i].isNaN() || src[i].isNaN()) continue
            val rng = hh[i] - ll[i]
            val frac = if (rng > 0.0) {
                (src[i] - ll[i]) / rng * 100.0
            } else {
                if (!fracPrev.isNaN()) fracPrev else 50.0
            }
            fracPrev = frac
            val outI = if (outPrev.isNaN()) frac else outPrev + smooth * (frac - outPrev)
            out[i] = outI
            outPrev = outI
        }
        return out
    }

    /** pandas rolling(window).min(): NaN until `window` valid values are in the window. */
    private fun rollingMin(values: DoubleArray, window: Int): DoubleArray =
        rollingExtreme(values, window, isMin = true)

    /** pandas rolling(window).max(): NaN until `window` valid values are in the window. */
    private fun rollingMax(values: DoubleArray, window: Int): DoubleArray =
        rollingExtreme(values, window, isMin = false)

    private fun rollingExtreme(values: DoubleArray, window: Int, isMin: Boolean): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in values.indices) {
            if (i + 1 < window) continue
            var extreme = Double.NaN
            var ok = true
            for (j in i + 1 - window..i) {
                val v = values[j]
                if (v.isNaN()) {
                    ok = false
                    break
                }
                extreme = when {
                    extreme.isNaN() -> v
                    isMin -> minOf(extreme, v)
                    else -> maxOf(extreme, v)
                }
            }
            if (ok) out[i] = extreme
        }
        return out
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
    private fun round6(x: Double): Double = Math.round(x * 1_000_000.0) / 1_000_000.0
}
