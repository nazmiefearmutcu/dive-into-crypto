package com.diveintocrypto.android.ui.panel.components

import com.diveintocrypto.android.domain.cvd.CvdBucket
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * PURE chart-scale math for the Panel candlestick chart (no Compose, no clock,
 * no I/O — fully unit-tested in commonTest). The composable in [CandleChart.kt]
 * consumes these; every mapping decision lives here so the visual code stays
 * dumb and the math stays honest.
 */
object ChartMath {

    /** Inclusive price domain of a chart viewport. */
    data class ChartRange(val min: Double, val max: Double) {
        val width: Double get() = max - min
    }

    /**
     * Price domain over the candle highs/lows UNIONed with every finite Bollinger
     * band value (the band must never be clipped outside the viewport), padded
     * symmetrically so candles don't kiss the frame. Degenerate inputs degrade
     * honestly: no finite data → 0..1; flat series → ±1% around the single value.
     */
    fun chartPriceRange(
        candles: List<Candle>,
        bbUpper: List<Double?> = emptyList(),
        bbLower: List<Double?> = emptyList(),
        padFraction: Double = 0.06,
    ): ChartRange {
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (c in candles) {
            if (c.low.isFinite()) min = minOf(min, c.low)
            if (c.high.isFinite()) max = maxOf(max, c.high)
        }
        for (series in listOf(bbUpper, bbLower)) {
            for (v in series) {
                if (v != null && v.isFinite()) {
                    min = minOf(min, v)
                    max = maxOf(max, v)
                }
            }
        }
        if (min > max) return ChartRange(0.0, 1.0) // nothing finite
        if (min == max) {
            val pad = if (min != 0.0) abs(min) * 0.01 else 1.0
            return ChartRange(min - pad, max + pad)
        }
        val pad = (max - min) * padFraction
        return ChartRange(min - pad, max + pad)
    }

    /**
     * Maps a price to a pixel Y inside [topPx, bottomPx] (top = range.max).
     * Clamped to the area; degenerate range maps to the middle (honest flat line).
     */
    fun scaleY(value: Double, range: ChartRange, topPx: Float, bottomPx: Float): Float {
        val t = if (range.max <= range.min) {
            0.5f
        } else {
            ((value - range.min) / (range.max - range.min)).toFloat().coerceIn(0f, 1f)
        }
        return bottomPx - t * (bottomPx - topPx)
    }

    /**
     * Decimal count for the right-edge price-axis labels, chosen from the
     * viewport WIDTH (not the price magnitude): a $60k chart with a $40 range
     * needs more decimals than a $0.02 chart with a $0.01 range.
     */
    fun axisDecimals(range: ChartRange): Int = when {
        range.width <= 0.0 -> 2
        range.width < 0.001 -> 6
        range.width < 0.01 -> 5
        range.width < 0.1 -> 4
        range.width < 1.0 -> 3
        range.width < 100.0 -> 2
        range.width < 1000.0 -> 1
        else -> 0
    }

    /**
     * Decimal count for ONE price value (magnitude-based) — used for threshold
     * formatting and the live-price cell, where there is no viewport to measure.
     */
    fun priceDecimalsFor(price: Double): Int = when {
        price <= 0.0 -> 2
        price < 0.01 -> 6
        price < 0.1 -> 5
        price < 1.0 -> 4
        price < 10.0 -> 3
        else -> 2
    }

    /** Axis label text (un-grouped so the right gutter stays narrow). */
    fun formatAxisPrice(value: Double, decimals: Int): String = value.format(decimals.coerceIn(0, 8))

    /** Largest candle volume in the viewport (0 when empty). */
    fun maxVolume(candles: List<Candle>): Double =
        candles.fold(0.0) { acc, c -> if (c.volume.isFinite()) maxOf(acc, c.volume) else acc }

    /** Largest |delta| across the CVD minute buckets (0 when empty). */
    fun maxAbsDelta(buckets: List<CvdBucket>): Double =
        buckets.fold(0.0) { acc, b -> if (b.delta.isFinite()) maxOf(acc, abs(b.delta)) else acc }
}
