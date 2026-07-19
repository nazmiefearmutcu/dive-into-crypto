package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Balance of Power (BOP) — verbatim port of the original Python reference
 * implementation.
 *
 * Per-bar BOP_raw = (close - open) / (high - low), bounded to [-1, +1]
 * (zero-range bars carry no directional info -> 0), smoothed with a trailing
 * SMA (default 14). Level bands give the graded read; a fresh zero-line cross
 * inside the near-zero band gives an early BUY/SELL.
 *
 * Strictly causal: BOP_raw[i] depends only on the OHLC of bar i, the SMA is a
 * trailing window, and the signal reads only the last two closed bars.
 */
class BalanceOfPowerIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "balance_of_power"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        // --- parameters -----------------------------------------------------
        val period = config.getInt("period", 14)
        val strongBuy = config.getDouble("strong_buy", 0.20)
        val buy = config.getDouble("buy", 0.05)
        val sell = config.getDouble("sell", -0.05)
        val strongSell = config.getDouble("strong_sell", -0.20)

        // --- guard ----------------------------------------------------------
        // Need a full window at index -1 (period bars) plus one more bar so that
        // index -2 also has a defined smoothed value for the cross/slope read.
        val minBars = period + 1
        if (candles.size < minBars) {
            return result(Signal.NEUTRAL, "insufficient data (<$minBars candles)")
        }

        val n = candles.size

        // Per-bar Balance of Power, bounded to [-1, +1].
        // Zero-range bars (high == low) carry no directional info -> 0.
        val rawBop = List(n) { i ->
            val c = candles[i]
            val rng = c.high - c.low
            if (rng > 0.0) (c.close - c.open) / rng else 0.0
        }

        // Smoothed BOP (trailing SMA, causal).
        val bop = Series.rollingMean(rawBop, period)

        val cur = bop[n - 1] ?: return result(Signal.NEUTRAL, "BOP data insufficient")
        val prev = bop[n - 2] ?: return result(Signal.NEUTRAL, "BOP data insufficient")

        val crossUp = prev <= 0.0 && cur > 0.0
        val crossDown = prev >= 0.0 && cur < 0.0

        val rawValues = mapOf<String, Double?>(
            "bop" to round4(cur),
            "bop_prev" to round4(prev),
            "bop_raw_last" to round4(rawBop[n - 1]),
            "period" to period.toDouble(),
        )

        // --- deep, sustained pressure --------------------------------------
        if (cur >= strongBuy) {
            return result(
                Signal.STRONG_BUY,
                "BOP=${cur.format(3)} deep buying pressure (>= $strongBuy)",
                rawValues,
            )
        }
        if (cur <= strongSell) {
            return result(
                Signal.STRONG_SELL,
                "BOP=${cur.format(3)} deep selling pressure (<= $strongSell)",
                rawValues,
            )
        }

        // --- moderate net pressure -----------------------------------------
        if (cur >= buy) {
            return result(Signal.BUY, "BOP=${cur.format(3)} net buying pressure (>= $buy)", rawValues)
        }
        if (cur <= sell) {
            return result(Signal.SELL, "BOP=${cur.format(3)} net selling pressure (<= $sell)", rawValues)
        }

        // --- near-zero band: fresh zero-line cross = early shift -----------
        if (crossUp) {
            return result(
                Signal.BUY, "BOP crossed above zero (${prev.format(3)}->${cur.format(3)})", rawValues,
            )
        }
        if (crossDown) {
            return result(
                Signal.SELL, "BOP crossed below zero (${prev.format(3)}->${cur.format(3)})", rawValues,
            )
        }

        return result(
            Signal.NEUTRAL, "BOP=${cur.format(3)} balanced (no directional edge)", rawValues,
        )
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
