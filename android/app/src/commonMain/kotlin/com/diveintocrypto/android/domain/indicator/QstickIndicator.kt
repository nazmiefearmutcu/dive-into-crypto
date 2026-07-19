package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * 1:1 port of the Python desktop implementation (qstick.py).
 *
 * Qstick — SMA of candle bodies (close - open).
 * Measures persistent buying/selling bias in the candle bodies over `period`,
 * normalised by price so the threshold is scale-free. Positive = bodies close
 * above their opens (accumulation). Causal (uses only the closed window).
 */
class QstickIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "qstick"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val strong = config.getDouble("strong_pct", 0.003)

        if (candles.size < period) {
            return result(Signal.NEUTRAL, "Qstick insufficient data")
        }

        val bodies = candles.takeLast(period).map { it.close - it.open }
        val q = bodies.average()
        // Python: ref = abs(float(close[-1])) or 1.0
        val lastClose = abs(candles.last().close)
        val ref = if (lastClose == 0.0) 1.0 else lastClose
        val qn = q / ref

        val raw = mapOf<String, Double?>(
            "qstick" to round6(q),
            "qstick_pct" to round6(qn),
        )
        // Python f"{qn:+.3%}" — signed percentage with three decimals.
        val qnPct = "${signed(qn * 100.0, 3)}%"

        return when {
            qn >= strong -> result(Signal.STRONG_BUY, "Qstick $qnPct strong body-buying", raw)
            q > 0.0 -> result(Signal.BUY, "Qstick $qnPct body-buying", raw)
            qn <= -strong -> result(Signal.STRONG_SELL, "Qstick $qnPct strong body-selling", raw)
            q < 0.0 -> result(Signal.SELL, "Qstick $qnPct body-selling", raw)
            else -> result(Signal.NEUTRAL, "Qstick flat", raw)
        }
    }

    private fun round6(x: Double): Double = Math.round(x * 1000000.0) / 1000000.0

    /**
     * printf-style `%+.Nf`: the sign comes from the value itself, so tiny
     * negatives that round to zero keep their "-" (matches Python formatting).
     */
    private fun signed(value: Double, decimals: Int): String {
        val sign = if (value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)) "-" else "+"
        return sign + abs(value).format(decimals)
    }
}
