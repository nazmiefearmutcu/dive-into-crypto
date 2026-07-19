package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Aroon Oscillator. Port of the desktop engine's `aroon_oscillator.py` — same
 * math, thresholds and signal mapping.
 *
 *   Aroon Up   = 100 * (period - bars_since_highest_high) / period
 *   Aroon Down = 100 * (period - bars_since_lowest_low)  / period
 *   osc        = Aroon Up - Aroon Down          (range: -100 .. +100)
 *
 * bars_since_* is counted over the trailing window of `period + 1` candles.
 * Magnitude gives conviction (STRONG at the extremes), a fresh zero-line
 * crossover gives the emergence trigger, and a small oscillator with no fresh
 * crossover is NEUTRAL. Strictly causal, stateless, no repainting.
 */
class AroonOscillatorIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "aroon_oscillator"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 25)
        val strongLevel = config.getDouble("strong_level", 70.0)
        val weakLevel = config.getDouble("weak_level", 30.0)

        // Need `period + 1` candles for the current oscillator value and one more
        // candle to also compute the previous value for crossover detection.
        if (period < 1 || candles.size < period + 2) {
            return result(
                Signal.NEUTRAL,
                "Aroon insufficient data (need >= ${maxOf(period + 2, 3)} bars)",
            )
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val n = highs.size

        val tail = n - (period + 2)
        for (i in tail until n) {
            if (!highs[i].isFinite() || !lows[i].isFinite()) {
                return result(Signal.NEUTRAL, "Aroon data contains NaN/inf")
            }
        }

        val (upC, downC, oscC) = aroonAt(highs, lows, n - 1, period)
        val (_, _, oscP) = aroonAt(highs, lows, n - 2, period)

        val bullCross = oscP <= 0.0 && oscC > 0.0
        val bearCross = oscP >= 0.0 && oscC < 0.0

        val raw = mapOf<String, Double?>(
            "aroon_up" to round2(upC),
            "aroon_down" to round2(downC),
            "oscillator" to round2(oscC),
            "prev_oscillator" to round2(oscP),
            "period" to period.toDouble(),
        )

        // --- Bullish side --------------------------------------------------
        if (oscC > 0.0) {
            if (oscC >= strongLevel) {
                return result(
                    Signal.STRONG_BUY,
                    "Aroon osc=${oscC.format(0)} (up=${upC.format(0)}/down=${downC.format(0)}) dominant uptrend",
                    raw,
                )
            }
            if (bullCross) {
                return result(Signal.BUY, "Aroon osc=${oscC.format(0)} bullish zero-cross: uptrend emerging", raw)
            }
            if (oscC >= weakLevel) {
                return result(Signal.BUY, "Aroon osc=${oscC.format(0)} uptrend intact", raw)
            }
            return result(Signal.NEUTRAL, "Aroon osc=${oscC.format(0)} weak/undefined (no fresh cross)", raw)
        }

        // --- Bearish side --------------------------------------------------
        if (oscC < 0.0) {
            if (oscC <= -strongLevel) {
                return result(
                    Signal.STRONG_SELL,
                    "Aroon osc=${oscC.format(0)} (up=${upC.format(0)}/down=${downC.format(0)}) dominant downtrend",
                    raw,
                )
            }
            if (bearCross) {
                return result(Signal.SELL, "Aroon osc=${oscC.format(0)} bearish zero-cross: downtrend emerging", raw)
            }
            if (oscC <= -weakLevel) {
                return result(Signal.SELL, "Aroon osc=${oscC.format(0)} downtrend intact", raw)
            }
            return result(Signal.NEUTRAL, "Aroon osc=${oscC.format(0)} weak/undefined (no fresh cross)", raw)
        }

        // osc == 0 : Aroon Up == Aroon Down exactly -> balanced / no trend.
        return result(Signal.NEUTRAL, "Aroon osc=0 balanced", raw)
    }

    /**
     * Aroon Up, Aroon Down and Oscillator for the candle at index [end], using
     * only the trailing window `[end - period, end]`. On ties the most recent
     * occurrence of the extreme wins (smallest "bars since"), matching the
     * standard Aroon convention.
     */
    private fun aroonAt(
        highs: List<Double>,
        lows: List<Double>,
        end: Int,
        period: Int,
    ): Triple<Double, Double, Double> {
        val start = end - period
        var hPos = 0
        var lPos = 0
        var hMax = highs[start]
        var lMin = lows[start]
        for (i in start..end) {
            if (highs[i] >= hMax) {
                hMax = highs[i]
                hPos = i - start
            }
            if (lows[i] <= lMin) {
                lMin = lows[i]
                lPos = i - start
            }
        }
        val aroonUp = 100.0 * hPos / period
        val aroonDown = 100.0 * lPos / period
        return Triple(aroonUp, aroonDown, aroonUp - aroonDown)
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
