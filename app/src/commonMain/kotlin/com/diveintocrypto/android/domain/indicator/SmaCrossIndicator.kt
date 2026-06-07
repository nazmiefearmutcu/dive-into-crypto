package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * SMA crossover — verbatim port of
 * the original Python reference implementation.
 *
 * Defaults: short=10, long=50, strong_divergence_pct=0.02. Same SHAPE as
 * EmaCrossIndicator but with Series.rollingMean. No "slope" branch — only
 * cross / alignment / divergence.
 */
class SmaCrossIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "sma_cross"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val shortPeriod = config.getInt("short_period", 10)
        val longPeriod = config.getInt("long_period", 50)
        val strongDivergencePct = config.getDouble("strong_divergence_pct", 0.02)

        if (candles.size < longPeriod + 1) {
            return result(Signal.NEUTRAL, "SMA data insufficient")
        }

        val closes = candles.map { it.close }
        val smaShort = Series.rollingMean(closes, shortPeriod)
        val smaLong = Series.rollingMean(closes, longPeriod)

        val currentShort = smaShort.last()
            ?: return result(Signal.NEUTRAL, "SMA data insufficient")
        val currentLong = smaLong.last()
            ?: return result(Signal.NEUTRAL, "SMA data insufficient")
        val prevShort = smaShort[smaShort.size - 2] ?: currentShort
        val prevLong = smaLong[smaLong.size - 2] ?: currentLong

        val divergence = if (currentLong != 0.0) (currentShort - currentLong) / currentLong else 0.0

        val raw = mapOf<String, Double?>(
            "sma_short" to round2(currentShort),
            "sma_long" to round2(currentLong),
            "divergence_pct" to round4(divergence),
        )

        val bullishCross = prevShort <= prevLong && currentShort > currentLong
        val bearishCross = prevShort >= prevLong && currentShort < currentLong

        return when {
            bullishCross && abs(divergence) > strongDivergencePct ->
                result(Signal.STRONG_BUY, "SMA golden cross with strong divergence", raw)
            bullishCross ->
                result(Signal.BUY, "SMA golden cross (short above long)", raw)
            bearishCross && abs(divergence) > strongDivergencePct ->
                result(Signal.STRONG_SELL, "SMA death cross with strong divergence", raw)
            bearishCross ->
                result(Signal.SELL, "SMA death cross (short below long)", raw)
            currentShort > currentLong -> {
                if (divergence > strongDivergencePct)
                    result(Signal.STRONG_BUY, "SMA bullish, strong divergence=${divergence.format(3)}", raw)
                else
                    result(Signal.BUY, "SMA bullish alignment, divergence=${divergence.format(3)}", raw)
            }
            currentShort < currentLong -> {
                if (abs(divergence) > strongDivergencePct)
                    result(Signal.STRONG_SELL, "SMA bearish, strong divergence=${divergence.format(3)}", raw)
                else
                    result(Signal.SELL, "SMA bearish alignment, divergence=${divergence.format(3)}", raw)
            }
            else -> result(Signal.NEUTRAL, "SMA flat / converging", raw)
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
