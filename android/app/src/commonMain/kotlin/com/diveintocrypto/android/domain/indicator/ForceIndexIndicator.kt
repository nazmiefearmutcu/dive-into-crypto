package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * 1:1 port of the Python desktop implementation (force_index.py).
 *
 * Force Index (Elder) — EMA-smoothed price-change * volume.
 * FI = (close - prev_close) * volume, smoothed by EMA(period). Combines
 * direction, magnitude and volume into one momentum-of-money measure.
 * Strength is judged relative to recent |FI| so it is scale-free across
 * symbols. Causal.
 */
class ForceIndexIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "force_index"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 13)
        val strong = config.getDouble("strong_ratio", 1.5)

        if (candles.size < period + 1) {
            return result(Signal.NEUTRAL, "Force Index insufficient data")
        }

        val n = candles.size
        // Python: fi = (close.diff() * vol).fillna(0.0) — first element 0.
        val fi = DoubleArray(n)
        for (i in 1 until n) {
            fi[i] = (candles[i].close - candles[i - 1].close) * candles[i].volume
        }

        // pandas ewm(span=period, adjust=False).mean(): alpha = 2/(period+1),
        // seeded with the first value.
        val alpha = 2.0 / (period + 1)
        val fiEma = DoubleArray(n)
        fiEma[0] = fi[0]
        for (i in 1 until n) {
            fiEma[i] = alpha * fi[i] + (1.0 - alpha) * fiEma[i - 1]
        }

        val value = fiEma[n - 1]
        // Python: recent = float(fi_ema.abs().tail(period).mean()) or 1.0
        var absSum = 0.0
        for (i in n - period until n) absSum += abs(fiEma[i])
        val recentMean = absSum / period
        val recent = if (recentMean == 0.0) 1.0 else recentMean
        val ratio = value / recent

        val raw = mapOf<String, Double?>(
            "force_index" to round4(value),
            "ratio" to round4(ratio),
        )

        return when {
            value > 0.0 -> result(
                if (ratio >= strong) Signal.STRONG_BUY else Signal.BUY,
                "Force Index +${ratio.format(2)} (buying)",
                raw,
            )
            value < 0.0 -> result(
                if (ratio <= -strong) Signal.STRONG_SELL else Signal.SELL,
                "Force Index ${ratio.format(2)} (selling)",
                raw,
            )
            else -> result(Signal.NEUTRAL, "Force Index flat", raw)
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
