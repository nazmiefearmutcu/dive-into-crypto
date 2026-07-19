package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * 1:1 port of the Python desktop implementation (tsi.py).
 *
 * True Strength Index (TSI) — double-smoothed momentum with signal-line cross.
 * TSI = 100 * DS(mom) / DS(|mom|) where mom = close.diff() (first element 0)
 * and DS = EMA(long) then EMA(short), both `adjust=False` style (seeded with
 * the first value). A signal line = EMA(TSI, signal). Direction from TSI sign
 * plus signal cross. Strictly causal.
 */
class TsiIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "tsi"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val longLen = config.getInt("long", 25)
        val shortLen = config.getInt("short", 13)
        val signalLen = config.getInt("signal", 13)
        val strong = config.getDouble("strong", 25.0)

        if (candles.size < longLen + shortLen + 2) {
            return result(Signal.NEUTRAL, "TSI insufficient data")
        }

        val n = candles.size
        val closes = DoubleArray(n) { candles[it].close }

        // Python: mom = close.diff().fillna(0.0) — first element treated as 0.
        val mom = DoubleArray(n)
        for (i in 1 until n) mom[i] = closes[i] - closes[i - 1]
        val absMom = DoubleArray(n) { abs(mom[it]) }

        val num = ema(ema(mom, longLen), shortLen)
        val den = ema(ema(absMom, longLen), shortLen)

        // tsi[i] is NaN where den[i] == 0 (Python: denom.replace(0.0, NaN)).
        // Since |mom| is non-negative and its EMA stays positive once positive,
        // NaNs can only form a prefix of the series.
        val tsi = DoubleArray(n) { if (den[it] == 0.0) Double.NaN else 100.0 * num[it] / den[it] }

        // Signal line = EMA(tsi, signalLen); pandas ewm seeds at the first
        // non-NaN value and carries forward across the leading NaNs.
        val alphaSig = 2.0 / (signalLen + 1)
        var sg = Double.NaN
        for (i in 0 until n) {
            val v = tsi[i]
            if (v.isNaN()) continue
            sg = if (sg.isNaN()) v else alphaSig * v + (1.0 - alphaSig) * sg
        }

        val t = tsi[n - 1]
        if (t.isNaN() || sg.isNaN()) {
            return result(Signal.NEUTRAL, "TSI undefined")
        }

        val raw = mapOf<String, Double?>("tsi" to round4(t), "signal" to round4(sg))
        return when {
            t > 0.0 && t > sg -> result(
                if (t >= strong) Signal.STRONG_BUY else Signal.BUY,
                "TSI ${t.format(1)} > signal (bull)",
                raw,
            )
            t < 0.0 && t < sg -> result(
                if (t <= -strong) Signal.STRONG_SELL else Signal.SELL,
                "TSI ${t.format(1)} < signal (bear)",
                raw,
            )
            else -> result(
                Signal.NEUTRAL,
                "TSI ${t.format(1)} vs signal ${sg.format(1)} unresolved",
                raw,
            )
        }
    }

    /** pandas ewm(span=span, adjust=False).mean(): alpha = 2/(span+1), seeded with x[0]. */
    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val alpha = 2.0 / (span + 1)
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) {
            out[i] = alpha * x[i] + (1.0 - alpha) * out[i - 1]
        }
        return out
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
