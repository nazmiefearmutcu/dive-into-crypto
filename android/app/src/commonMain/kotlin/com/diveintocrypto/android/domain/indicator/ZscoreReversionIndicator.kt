package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 1:1 port of the Python desktop implementation (zscore_reversion.py).
 *
 * Price Z-Score Mean Reversion — contrarian.
 * z = (close - rollingMean) / rollingStd over `period`. Stretched-above-mean
 * (high z) is faded SHORT; stretched-below (low z) is faded LONG. This is
 * intentionally the OPPOSITE sign to the trend/momentum indicators, so it
 * contributes orthogonal mean-reversion information to the vote.
 * Bessel (ddof=1) std. Causal.
 */
class ZscoreReversionIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "zscore_reversion"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val strong = config.getDouble("strong", 2.5)
        val weak = config.getDouble("weak", 1.2)

        if (candles.size < period) {
            return result(Signal.NEUTRAL, "Z-score insufficient data")
        }

        val window = candles.takeLast(period).map { it.close }
        val mean = window.average()
        val sd = sampleStd(window, mean)
        if (sd.isNaN() || sd == 0.0) {
            return result(Signal.NEUTRAL, "Z-score undefined")
        }

        val z = (candles.last().close - mean) / sd
        val raw = mapOf<String, Double?>("zscore" to round4(z))
        // Python f"{z:+.2f}" — signed with two decimals.
        val zStr = signed(z, 2)

        // Contrarian: above-mean -> revert down (sell); below-mean -> revert up (buy).
        return when {
            z >= strong -> result(Signal.STRONG_SELL, "z $zStr over-extended (fade down)", raw)
            z >= weak -> result(Signal.SELL, "z $zStr stretched up (fade)", raw)
            z <= -strong -> result(Signal.STRONG_BUY, "z $zStr over-extended (fade up)", raw)
            z <= -weak -> result(Signal.BUY, "z $zStr stretched down (fade)", raw)
            else -> result(Signal.NEUTRAL, "z $zStr near mean", raw)
        }
    }

    /** pandas rolling(period).std(ddof=1) on the final window — Bessel-corrected sample std. */
    private fun sampleStd(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return Double.NaN
        var ss = 0.0
        for (v in values) {
            val d = v - mean
            ss += d * d
        }
        return sqrt(ss / (values.size - 1))
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

    /**
     * printf-style `%+.Nf`: the sign comes from the value itself, so tiny
     * negatives that round to zero keep their "-" (matches Python formatting).
     */
    private fun signed(value: Double, decimals: Int): String {
        val sign = if (value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)) "-" else "+"
        return sign + abs(value).format(decimals)
    }
}
