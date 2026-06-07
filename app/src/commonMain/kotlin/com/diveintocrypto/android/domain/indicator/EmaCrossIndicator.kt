package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

class EmaCrossIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "ema_cross"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val shortP = config.getInt("short_period", 9)
        val longP = config.getInt("long_period", 21)
        val strongDiv = config.getDouble("strong_divergence_pct", 0.02)

        if (candles.size < longP + 2) return result(Signal.NEUTRAL, "EMA data insufficient")

        val closes = candles.map { it.close }
        val emaShort = Series.ewmAdjustFalse(closes, shortP)
        val emaLong = Series.ewmAdjustFalse(closes, longP)

        val curS = emaShort.last()
        val curL = emaLong.last()
        val prevS = emaShort.dropLast(1).last()
        val prevL = emaLong.dropLast(1).last()

        val divergence = if (curL != 0.0) (curS - curL) / curL else 0.0
        val shortSlope = if (prevS != 0.0) (curS - prevS) / prevS else 0.0

        val raw = mapOf<String, Double?>(
            "ema_short" to round2(curS),
            "ema_long" to round2(curL),
            "divergence_pct" to round4(divergence),
            "short_slope" to round6(shortSlope),
        )

        val bullishCross = prevS <= prevL && curS > curL
        val bearishCross = prevS >= prevL && curS < curL

        return when {
            bullishCross && abs(divergence) > strongDiv ->
                result(Signal.STRONG_BUY, "EMA bullish crossover with strong divergence", raw)
            bullishCross -> result(Signal.BUY, "EMA bullish crossover", raw)
            bearishCross && abs(divergence) > strongDiv ->
                result(Signal.STRONG_SELL, "EMA bearish crossover with strong divergence", raw)
            bearishCross -> result(Signal.SELL, "EMA bearish crossover", raw)
            curS > curL && shortSlope > 0 -> {
                if (divergence > strongDiv) result(Signal.STRONG_BUY, "EMA bullish with positive slope, div=${divergence.format(3)}", raw)
                else result(Signal.BUY, "EMA bullish alignment, slope positive", raw)
            }
            curS < curL && shortSlope < 0 -> {
                if (abs(divergence) > strongDiv) result(Signal.STRONG_SELL, "EMA bearish with negative slope, div=${divergence.format(3)}", raw)
                else result(Signal.SELL, "EMA bearish alignment, slope negative", raw)
            }
            else -> result(Signal.NEUTRAL, "EMA neutral / mixed signals", raw)
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
    private fun round6(x: Double): Double = Math.round(x * 1_000_000.0) / 1_000_000.0
}
