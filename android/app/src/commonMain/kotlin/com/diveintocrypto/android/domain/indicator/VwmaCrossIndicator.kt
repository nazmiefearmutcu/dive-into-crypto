package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * 1:1 port of the Python desktop implementation (vwma_cross.py).
 *
 * Volume-Weighted Moving Average cross (VWMA short vs long).
 * VWMA(p) = sum(close*volume) / sum(volume) over the last p bars. A short/long
 * VWMA cross weights price by traded volume, so moves on real participation
 * dominate — distinct from the plain SMA/EMA crosses and from VWAP (a single
 * anchored average). Causal.
 */
class VwmaCrossIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "vwma_cross"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val shortPeriod = config.getInt("short_period", 9)
        val longPeriod = config.getInt("long_period", 21)
        val strong = config.getDouble("strong_divergence_pct", 0.02)

        if (candles.size < longPeriod) {
            return result(Signal.NEUTRAL, "VWMA insufficient data")
        }

        val closes = candles.map { it.close }
        val volumes = candles.map { it.volume }

        fun vwma(p: Int): Double {
            val c = closes.takeLast(p)
            val v = volumes.takeLast(p)
            val sv = v.sum()
            if (sv > 0.0) {
                var pv = 0.0
                for (i in c.indices) pv += c[i] * v[i]
                return pv / sv
            }
            return c.average()
        }

        val s = vwma(shortPeriod)
        val l = vwma(longPeriod)
        if (l == 0.0) {
            return result(Signal.NEUTRAL, "VWMA long is zero")
        }

        val div = (s - l) / l
        val raw = mapOf<String, Double?>(
            "vwma_short" to round4(s),
            "vwma_long" to round4(l),
            "divergence_pct" to round5(div),
        )
        // Python f"{div:+.2%}" — signed percentage with two decimals.
        val divPct = "${signed(div * 100.0, 2)}%"

        return when {
            s > l -> result(
                if (div >= strong) Signal.STRONG_BUY else Signal.BUY,
                "VWMA short>long ($divPct)",
                raw,
            )
            s < l -> result(
                if (div <= -strong) Signal.STRONG_SELL else Signal.SELL,
                "VWMA short<long ($divPct)",
                raw,
            )
            else -> result(Signal.NEUTRAL, "VWMA aligned", raw)
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

    private fun round5(x: Double): Double = Math.round(x * 100000.0) / 100000.0

    /**
     * printf-style `%+.Nf`: the sign comes from the value itself, so tiny
     * negatives that round to zero keep their "-" (matches Python formatting).
     */
    private fun signed(value: Double, decimals: Int): String {
        val sign = if (value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)) "-" else "+"
        return sign + abs(value).format(decimals)
    }
}
