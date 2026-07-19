package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal

class AwesomeOscillatorIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "awesome_oscillator"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val fastPeriod = config.getInt("fast_period", 5)
        val slowPeriod = config.getInt("slow_period", 34)

        val medianPrice = candles.map { (it.high + it.low) / 2 }
        val smaFast = Series.rollingMean(medianPrice, fastPeriod)
        val smaSlow = Series.rollingMean(medianPrice, slowPeriod)

        val ao: List<Double?> = medianPrice.indices.map { i ->
            val f = smaFast[i]
            val s = smaSlow[i]
            if (f == null || s == null) null else f - s
        }

        val currentAo = ao.lastOrNull()
            ?: return result(Signal.NEUTRAL, "AO data insufficient")
        val prevAo = if (ao.size >= 2) ao[ao.size - 2] else currentAo
        val prev2Ao = if (ao.size >= 3) ao[ao.size - 3] else prevAo

        val raw = mapOf<String, Double?>(
            "ao" to round4(currentAo),
            "prev_ao" to prevAo?.let { round4(it) },
        )

        // Zero line crossover (null prev behaves like pandas NaN: comparisons are false)
        val bullishCross = prevAo != null && prevAo <= 0 && currentAo > 0
        val bearishCross = prevAo != null && prevAo >= 0 && currentAo < 0

        // Saucer (change in momentum direction)
        val bullishSaucer = currentAo > 0 && prevAo != null && prevAo > 0 &&
            prev2Ao != null && prev2Ao > prevAo && currentAo > prevAo
        val bearishSaucer = currentAo < 0 && prevAo != null && prevAo < 0 &&
            prev2Ao != null && prev2Ao < prevAo && currentAo < prevAo

        return when {
            bullishCross -> result(Signal.STRONG_BUY, "AO crossed above zero line", raw)
            bearishCross -> result(Signal.STRONG_SELL, "AO crossed below zero line", raw)
            bullishSaucer -> result(Signal.BUY, "AO bullish saucer pattern", raw)
            bearishSaucer -> result(Signal.SELL, "AO bearish saucer pattern", raw)
            currentAo > 0 && prevAo != null && currentAo > prevAo ->
                result(Signal.NEUTRAL, "AO positive and rising", raw)
            currentAo > 0 && prevAo != null && currentAo < prevAo ->
                result(Signal.NEUTRAL, "AO positive and falling", raw)
            currentAo < 0 && prevAo != null && currentAo < prevAo ->
                result(Signal.NEUTRAL, "AO negative and falling", raw)
            else -> result(Signal.NEUTRAL, "AO negative and rising", raw)
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
