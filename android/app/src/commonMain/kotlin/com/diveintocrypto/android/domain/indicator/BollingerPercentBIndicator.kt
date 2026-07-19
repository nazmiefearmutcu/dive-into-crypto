package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.sqrt

/**
 * 1:1 port of the Python desktop implementation (bollinger_percent_b.py).
 *
 * Bollinger %B with a bandwidth (squeeze/expansion) gate.
 * %B = (close - lower) / (upper - lower); bandwidth = (upper - lower) / mid.
 * Distinct from the plain Bollinger signal: it is gated by bandwidth so band
 * touches during a squeeze (low bandwidth) are treated as NEUTRAL (coiling),
 * while breakouts beyond the bands during expansion emit strong signals.
 * Uses Bessel (ddof=1) std per repo convention.
 */
class BollingerPercentBIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "bollinger_percent_b"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val stdDev = config.getDouble("std_dev", 2.0)
        val squeeze = config.getDouble("squeeze_bandwidth", 0.04)

        if (candles.size < period) {
            return result(Signal.NEUTRAL, "%B insufficient data")
        }

        val window = candles.takeLast(period).map { it.close }
        val mid = window.average()
        val sd = sampleStd(window, mid)
        if (sd.isNaN() || sd == 0.0 || mid == 0.0) {
            return result(Signal.NEUTRAL, "%B undefined")
        }

        val upper = mid + stdDev * sd
        val lower = mid - stdDev * sd
        val c = candles.last().close
        val pctb = (c - lower) / (upper - lower)
        val bandwidth = (upper - lower) / mid

        val raw = mapOf<String, Double?>(
            "percent_b" to round4(pctb),
            "bandwidth" to round5(bandwidth),
        )

        return when {
            // Coiling: no directional call yet.
            bandwidth < squeeze -> result(Signal.NEUTRAL, "%B squeeze (bw ${bandwidth.format(3)})", raw)
            pctb >= 1.0 -> result(Signal.STRONG_BUY, "%B ${pctb.format(2)} breakout up", raw)
            pctb >= 0.8 -> result(Signal.BUY, "%B ${pctb.format(2)} upper band", raw)
            pctb <= 0.0 -> result(Signal.STRONG_SELL, "%B ${pctb.format(2)} breakout down", raw)
            pctb <= 0.2 -> result(Signal.SELL, "%B ${pctb.format(2)} lower band", raw)
            else -> result(Signal.NEUTRAL, "%B ${pctb.format(2)} mid-band", raw)
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

    private fun round5(x: Double): Double = Math.round(x * 100000.0) / 100000.0
}
