package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * 1:1 port of the Python reference `kalman_trend.py`.
 *
 * Kalman-Filter Trend — 1-D random-walk-plus-noise level estimate. Runs a
 * scalar Kalman filter over the close series to get a smooth, low-lag `level`.
 * The signal combines price-vs-level position with the level's own slope:
 * price above a rising level = uptrend. Inherently causal (each estimate uses
 * only past+current observations). Gains are set by the process/measurement
 * variance ratio.
 */
class KalmanTrendIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "kalman_trend"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val q = config.getDouble("process_var", 1e-4)
        val r = config.getDouble("measure_var", 1e-2)
        val strong = config.getDouble("strong_pct", 0.01)

        val closes = candles.map { it.close }
        val n = closes.size
        if (n < 10) {
            return result(Signal.NEUTRAL, "Kalman insufficient data")
        }

        var x = closes[0]
        var p = 1.0
        var prev = x
        for (i in 1 until n) {
            p += q                       // predict
            val k = p / (p + r)          // gain
            prev = x
            x += k * (closes[i] - x)     // update
            p *= (1.0 - k)
        }

        val level = x
        val slope = x - prev
        val price = closes[n - 1]
        if (level == 0.0) {
            return result(Signal.NEUTRAL, "Kalman undefined")
        }
        val dev = (price - level) / level

        val raw = mapOf<String, Double?>(
            "level" to roundTo(level, 4),
            "dev_pct" to roundTo(dev, 5),
            "slope" to roundTo(slope, 6),
        )

        // Python `{dev:+.2%}`
        val devStr = "${(dev * 100.0).format(2, plus = true)}%"
        if (price > level && slope > 0) {
            return result(
                if (dev >= strong) Signal.STRONG_BUY else Signal.BUY,
                "price>level rising ($devStr)",
                raw,
            )
        }
        if (price < level && slope < 0) {
            return result(
                if (dev <= -strong) Signal.STRONG_SELL else Signal.SELL,
                "price<level falling ($devStr)",
                raw,
            )
        }
        return result(Signal.NEUTRAL, "price/level unresolved ($devStr)", raw)
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
