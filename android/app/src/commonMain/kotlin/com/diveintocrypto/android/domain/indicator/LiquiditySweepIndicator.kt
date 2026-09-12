package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.max

/**
 * Liquidity sweep (stop-hunt) indicator — verbatim port of the Python
 * reference `liquidity_sweep.py`.
 *
 * Detects a wick that PIERCES the prior N-bar swing high/low while the bar
 * CLOSES back inside that range — the classic stop-hunt / liquidity-grab
 * reversal:
 *
 *   sweep of lows:  low_cur < min(low of prior `lookback` bars)  AND
 *                   close_cur back above that low  -> BUY
 *   sweep of highs: mirrored -> SELL
 *
 * Strength grading: STRONG when the wick penetration beyond the swing level
 * is at least `strong_penetration_atr` (default 0.5) x ATR (`atr_period`,
 * default 14, simple rolling mean of True Range ending at the current bar —
 * same ATR definition as the atr_filter reference). Causal: the swing window
 * covers the `lookback` bars strictly before the current one.
 */
class LiquiditySweepIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "liquidity_sweep"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val lookback = config.getInt("lookback", 10)
        val atrPeriod = config.getInt("atr_period", 14)
        val strongPen = config.getDouble("strong_penetration_atr", 0.5)

        val n = candles.size
        // Need `lookback` prior bars for the swing window plus `atr_period`
        // bars for a defined ATR on the current bar.
        if (n < max(lookback, atrPeriod) + 1) {
            return result(Signal.NEUTRAL, "Liquidity sweep data insufficient")
        }

        // Swing extremes over the `lookback` bars STRICTLY BEFORE the current
        // one: indices [n-1-lookback .. n-2] (rolling(lookback).shift(1)).
        var swingLow = Double.MAX_VALUE
        var swingHigh = -Double.MAX_VALUE
        for (j in (n - 1 - lookback) until (n - 1)) {
            if (candles[j].low < swingLow) swingLow = candles[j].low
            if (candles[j].high > swingHigh) swingHigh = candles[j].high
        }

        // True Range; tr[0] = high-low (matches the pandas max-skipna seed).
        // ATR = simple mean of TR over the window ending at the current bar.
        val tr = DoubleArray(n)
        tr[0] = candles[0].high - candles[0].low
        for (i in 1 until n) {
            val prevClose = candles[i - 1].close
            tr[i] = max(
                candles[i].high - candles[i].low,
                max(abs(candles[i].high - prevClose), abs(candles[i].low - prevClose)),
            )
        }
        var trSum = 0.0
        for (i in (n - atrPeriod) until n) trSum += tr[i]
        val atr = trSum / atrPeriod

        val cur = candles[n - 1]
        val c = cur.close
        val l = cur.low
        val h = cur.high

        // Penetration beyond the swept level (>= 0 when the wick pierces).
        val lowPen = swingLow - l
        val highPen = h - swingHigh

        val sweptLow = l < swingLow && c > swingLow
        val sweptHigh = h > swingHigh && c < swingHigh

        val baseRaw = mapOf<String, Double?>(
            "swing_low" to round6(swingLow),
            "swing_high" to round6(swingHigh),
            "low_penetration" to round6(lowPen),
            "high_penetration" to round6(highPen),
            "atr" to round6(atr),
            "strong_penetration_atr" to strongPen,
        )

        if (sweptLow && sweptHigh) {
            // Both sides swept in one bar — conflicting stop-hunts, no edge.
            return result(
                Signal.NEUTRAL,
                "Both swing sides swept and rejected — conflicting liquidity grab",
                baseRaw + ("direction" to 0.0),
            )
        }

        if (sweptLow) {
            val strong = lowPen >= strongPen * atr
            val raw = baseRaw + ("direction" to 1.0)
            return if (strong) {
                result(
                    Signal.STRONG_BUY,
                    "Swept ${lookback}-bar low ${swingLow.format(4)} by ${lowPen.format(4)} " +
                        "(>= $strongPen x ATR) and closed back above",
                    raw,
                )
            } else {
                result(
                    Signal.BUY,
                    "Swept ${lookback}-bar low ${swingLow.format(4)} and closed back above " +
                        "(stop-hunt reversal)",
                    raw,
                )
            }
        }

        if (sweptHigh) {
            val strong = highPen >= strongPen * atr
            val raw = baseRaw + ("direction" to -1.0)
            return if (strong) {
                result(
                    Signal.STRONG_SELL,
                    "Swept ${lookback}-bar high ${swingHigh.format(4)} by ${highPen.format(4)} " +
                        "(>= $strongPen x ATR) and closed back below",
                    raw,
                )
            } else {
                result(
                    Signal.SELL,
                    "Swept ${lookback}-bar high ${swingHigh.format(4)} and closed back below " +
                        "(stop-hunt reversal)",
                    raw,
                )
            }
        }

        return result(
            Signal.NEUTRAL,
            "No sweep: price stayed inside the prior range",
            baseRaw + ("direction" to 0.0),
        )
    }

    private fun round6(x: Double): Double = Math.round(x * 1000000.0) / 1000000.0
}
