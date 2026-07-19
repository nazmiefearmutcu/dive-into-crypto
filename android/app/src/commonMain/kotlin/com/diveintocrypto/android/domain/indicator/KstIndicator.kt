package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.max

/**
 * KST (Know Sure Thing) — Martin Pring's weighted sum of four smoothed ROCs
 * plus an SMA signal line.
 *
 *     RCMA_i = SMA( ROC(close, roc_i), sma_i )
 *     KST    = 1*RCMA1 + 2*RCMA2 + 3*RCMA3 + 4*RCMA4
 *     Signal = SMA(KST, signal_period)
 *
 * Defaults are Pring's canonical set: ROC 10/15/20/30, SMA 10/10/10/15,
 * weights 1/2/3/4, signal 9. Port of the Python `kst.py` — same math,
 * thresholds, and signal mapping.
 */
class KstIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "kst"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        // --- Parameters (Pring canonical defaults) ---
        val roc1 = config.getInt("roc1_period", 10)
        val roc2 = config.getInt("roc2_period", 15)
        val roc3 = config.getInt("roc3_period", 20)
        val roc4 = config.getInt("roc4_period", 30)
        val sma1 = config.getInt("sma1_period", 10)
        val sma2 = config.getInt("sma2_period", 10)
        val sma3 = config.getInt("sma3_period", 10)
        val sma4 = config.getInt("sma4_period", 15)
        val w1 = config.getDouble("weight1", 1.0)
        val w2 = config.getDouble("weight2", 2.0)
        val w3 = config.getDouble("weight3", 3.0)
        val w4 = config.getDouble("weight4", 4.0)
        val signalPeriod = config.getInt("signal_period", 9)

        val closes = candles.map { it.close }
        val n = closes.size

        // Minimum bars for the last KST *and* its signal line to be fully formed,
        // plus one extra bar so index -2 is also valid (crossover detection).
        val minRequired = max(
            max(roc1 + sma1, roc2 + sma2),
            max(roc3 + sma3, roc4 + sma4),
        ) + signalPeriod + 1
        if (n < minRequired) {
            return result(Signal.NEUTRAL, "KST data insufficient")
        }

        // Smoothed rate-of-change moving averages (trailing SMAs -> causal).
        val rcma1 = rollingMean(roc(closes, roc1), sma1)
        val rcma2 = rollingMean(roc(closes, roc2), sma2)
        val rcma3 = rollingMean(roc(closes, roc3), sma3)
        val rcma4 = rollingMean(roc(closes, roc4), sma4)

        val kst = DoubleArray(n) { i ->
            w1 * rcma1[i] + w2 * rcma2[i] + w3 * rcma3[i] + w4 * rcma4[i]
        }
        val signalLine = rollingMean(kst, signalPeriod)

        val kstNow = kst[n - 1]
        val kstPrev = kst[n - 2]
        val sigNow = signalLine[n - 1]
        val sigPrev = signalLine[n - 2]

        if (kstNow.isNaN() || kstPrev.isNaN() || sigNow.isNaN() || sigPrev.isNaN()) {
            return result(Signal.NEUTRAL, "KST data insufficient")
        }

        val raw = mapOf<String, Double?>(
            "kst" to round4(kstNow),
            "signal" to round4(sigNow),
            "hist" to round4(kstNow - sigNow),
        )

        val bullishCross = kstPrev <= sigPrev && kstNow > sigNow
        val bearishCross = kstPrev >= sigPrev && kstNow < sigNow
        val rising = kstNow > kstPrev

        if (bullishCross) {
            if (kstNow < 0.0) {
                return result(
                    Signal.STRONG_BUY,
                    "KST bullish crossover below zero (KST=${kstNow.format(2)})",
                    raw,
                )
            }
            return result(
                Signal.BUY,
                "KST bullish crossover above zero (KST=${kstNow.format(2)})",
                raw,
            )
        }
        if (bearishCross) {
            if (kstNow > 0.0) {
                return result(
                    Signal.STRONG_SELL,
                    "KST bearish crossover above zero (KST=${kstNow.format(2)})",
                    raw,
                )
            }
            return result(
                Signal.SELL,
                "KST bearish crossover below zero (KST=${kstNow.format(2)})",
                raw,
            )
        }

        // No fresh crossover: grade the sustained regime.
        if (kstNow > sigNow) {
            if (kstNow > 0.0) {
                return result(
                    Signal.BUY, "KST above signal and above zero (KST=${kstNow.format(2)})", raw,
                )
            }
            if (rising) {
                return result(
                    Signal.BUY, "KST above signal, recovering below zero (KST=${kstNow.format(2)})", raw,
                )
            }
            return result(
                Signal.NEUTRAL, "KST above signal but weak below zero (KST=${kstNow.format(2)})", raw,
            )
        }
        if (kstNow < sigNow) {
            if (kstNow < 0.0) {
                return result(
                    Signal.SELL, "KST below signal and below zero (KST=${kstNow.format(2)})", raw,
                )
            }
            if (!rising) {
                return result(
                    Signal.SELL, "KST below signal, fading above zero (KST=${kstNow.format(2)})", raw,
                )
            }
            return result(
                Signal.NEUTRAL, "KST below signal but firm above zero (KST=${kstNow.format(2)})", raw,
            )
        }

        return result(Signal.NEUTRAL, "KST flat at signal (KST=${kstNow.format(2)})", raw)
    }

    /** (close - close.shift(period)) / close.shift(period) * 100; NaN warm-up. */
    private fun roc(closes: List<Double>, period: Int): DoubleArray =
        DoubleArray(closes.size) { i ->
            if (i < period) Double.NaN
            else (closes[i] - closes[i - period]) / closes[i - period] * 100.0
        }

    /** pandas rolling(window).mean(): NaN until the window holds `window` valid values. */
    private fun rollingMean(values: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in values.indices) {
            if (i + 1 < window) continue
            var sum = 0.0
            var ok = true
            for (j in i + 1 - window..i) {
                val v = values[j]
                if (v.isNaN()) {
                    ok = false
                    break
                }
                sum += v
            }
            if (ok) out[i] = sum / window
        }
        return out
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
