package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal

/**
 * 1:1 port of the Python reference `range_expansion.py`.
 *
 * Range Expansion (NR7 / WR7) — compression/expansion breakout setup. Compares
 * the current bar's high-low range to the last `lookback` bars. Widest-range
 * bar (WR7) = expansion breakout -> strong signal in that bar's direction.
 * Narrowest-range bar (NR7) = compression -> NEUTRAL (coiled). Otherwise a
 * weak signal only when the last bar agrees with the short trend. Causal (no
 * future bars).
 */
class RangeExpansionIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "range_expansion"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val lookback = config.getInt("lookback", 7)

        val n = candles.size
        if (n < lookback + 1) {
            return result(Signal.NEUTRAL, "Range insufficient data")
        }

        // rng = (high - low)[-lookback:]
        val rng = DoubleArray(lookback) {
            val c = candles[n - lookback + it]
            c.high - c.low
        }
        val cur = rng[lookback - 1]
        var rngMax = rng[0]
        var rngMin = rng[0]
        for (v in rng) {
            if (v > rngMax) rngMax = v
            if (v < rngMin) rngMin = v
        }
        val isWr = cur >= rngMax
        val isNr = cur <= rngMin

        val lastClose = candles[n - 1].close
        val lastOpen = candles[n - 1].open
        val lastDir = if (lastClose > lastOpen) 1 else if (lastClose < lastOpen) -1 else 0
        val trendBase = candles[n - lookback].close
        val trend = if (lastClose > trendBase) 1 else if (lastClose < trendBase) -1 else 0

        val raw = mapOf<String, Double?>(
            "cur_range" to roundTo(cur, 6),
            "is_wr7" to if (isWr) 1.0 else 0.0,
            "is_nr7" to if (isNr) 1.0 else 0.0,
        )

        if (isNr) {
            return result(Signal.NEUTRAL, "NR7 compression (coiled)", raw)
        }
        if (isWr && lastDir != 0) {
            return result(
                if (lastDir > 0) Signal.STRONG_BUY else Signal.STRONG_SELL,
                "WR7 expansion breakout",
                raw,
            )
        }
        if (lastDir != 0 && lastDir == trend) {
            return result(if (lastDir > 0) Signal.BUY else Signal.SELL, "range/trend agree", raw)
        }
        return result(Signal.NEUTRAL, "no expansion setup", raw)
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
