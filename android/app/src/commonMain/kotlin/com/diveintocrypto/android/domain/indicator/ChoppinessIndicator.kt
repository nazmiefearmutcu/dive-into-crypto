package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.math.abs
import kotlin.math.log10

/** Choppiness Index to determine if market is trending or ranging. */
class ChoppinessIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "choppiness"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val choppyThreshold = config.getDouble("choppy_threshold", 61.8)
        val trendingThreshold = config.getDouble("trending_threshold", 38.2)

        if (candles.isEmpty()) {
            return result(Signal.NEUTRAL, "Choppiness data insufficient")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        // True Range (row-wise max skips the missing prev close at i == 0, like pandas)
        val tr = candles.indices.map { i ->
            if (i == 0) highs[0] - lows[0]
            else maxOf(highs[i] - lows[i], abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
        }

        val atrSum = rollingSum(tr, period).last()
        val highestHigh = Series.rollingMax(highs, period).last()
        val lowestLow = Series.rollingMin(lows, period).last()

        if (atrSum == null || highestHigh == null || lowestLow == null) {
            return result(Signal.NEUTRAL, "Choppiness data insufficient")
        }

        // Flat window (zero total range) is defined as maximally choppy: 100
        val isFlat = atrSum == 0.0
        val currentChop = if (isFlat) {
            100.0
        } else {
            100.0 * log10(atrSum / (highestHigh - lowestLow + 1e-10)) / log10(period.toDouble())
        }

        if (currentChop.isNaN()) {
            return result(Signal.NEUTRAL, "Choppiness data insufficient")
        }

        val raw = mapOf<String, Double?>("chop" to round2(currentChop))

        val smaLast = Series.rollingMean(closes, period).last()
            ?: return result(Signal.NEUTRAL, "Choppiness data insufficient")
        val isBullish = closes.last() > smaLast

        return when {
            currentChop > choppyThreshold -> result(Signal.NEUTRAL, "Market is choppy/ranging", raw)
            currentChop < trendingThreshold ->
                if (isBullish) result(Signal.STRONG_BUY, "Strong bullish trend", raw)
                else result(Signal.STRONG_SELL, "Strong bearish trend", raw)
            else ->
                if (isBullish) result(Signal.BUY, "Mild bullish trend", raw)
                else result(Signal.SELL, "Mild bearish trend", raw)
        }
    }

    private fun rollingSum(values: List<Double>, window: Int): List<Double?> =
        values.indices.map { i ->
            if (i + 1 < window) null else values.subList(i + 1 - window, i + 1).sum()
        }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
