package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * 1:1 port of the original Python reference implementation.
 *
 * %R = -100 * (highest_high - close) / (highest_high - lowest_low)
 * where highest_high / lowest_low are rolling extrema over `period`.
 */
class WilliamsRIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "williams_r"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val oversold = config.getDouble("oversold", -80.0)
        val overbought = config.getDouble("overbought", -20.0)

        if (candles.size < period + 1) {
            return result(Signal.NEUTRAL, "Williams %R data insufficient")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        val highestHigh = Series.rollingMax(highs, period)
        val lowestLow = Series.rollingMin(lows, period)

        // Compute %R per index — null where window not full or denom==0.
        val wr: List<Double?> = closes.indices.map { i ->
            val hh = highestHigh[i] ?: return@map null
            val ll = lowestLow[i] ?: return@map null
            val denom = hh - ll
            if (denom == 0.0) null else -100.0 * (hh - closes[i]) / denom
        }

        val curWr = wr.last()
        val prevWr = wr.dropLast(1).lastOrNull() ?: curWr

        if (curWr == null) {
            return result(Signal.NEUTRAL, "Williams %R data insufficient")
        }
        // Match Python: if prev is NaN, the comparisons in Python would be
        // False — which Kotlin Double comparisons on a non-null already handle
        // since `prevWr` here is never null when curWr != null at the tail
        // (rolling extrema produce contiguous non-null tails).
        val prev = prevWr ?: curWr

        val raw = mapOf<String, Double?>("williams_r" to round2(curWr))

        // Oversold reversal
        if (curWr < oversold && curWr > prev) {
            return if (curWr < -95.0) {
                result(
                    Signal.STRONG_BUY,
                    "Williams %R=${curWr.format(1)} extreme oversold reversal",
                    raw,
                )
            } else {
                result(
                    Signal.BUY,
                    "Williams %R=${curWr.format(1)} oversold reversal",
                    raw,
                )
            }
        }

        // Overbought reversal
        if (curWr > overbought && curWr < prev) {
            return if (curWr > -5.0) {
                result(
                    Signal.STRONG_SELL,
                    "Williams %R=${curWr.format(1)} extreme overbought reversal",
                    raw,
                )
            } else {
                result(
                    Signal.SELL,
                    "Williams %R=${curWr.format(1)} overbought reversal",
                    raw,
                )
            }
        }

        // Still oversold
        if (curWr < oversold) {
            return result(
                Signal.BUY,
                "Williams %R=${curWr.format(1)} oversold zone",
                raw,
            )
        }

        // Still overbought
        if (curWr > overbought) {
            return result(
                Signal.SELL,
                "Williams %R=${curWr.format(1)} overbought zone",
                raw,
            )
        }

        return result(
            Signal.NEUTRAL,
            "Williams %R=${curWr.format(1)} neutral",
            raw,
        )
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
