package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.max

/**
 * 1:1 port of the Python reference `atr_percentile.py`.
 *
 * ATR Percentile — volatility-expansion breakout confirmer. Ranks the current
 * ATR% against its own trailing history. High percentile + a directional price
 * move = breakout confirmation (strong in the move's direction); low percentile
 * (compression) stays NEUTRAL. ATR uses Wilder RMA seeded with the mean of the
 * first `period` TRs (tr[0] is 0.0, matching the reference's np.zeros).
 * Causal (Wilder RMA, trailing percentile).
 */
class AtrPercentileIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "atr_percentile"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val lookback = config.getInt("lookback", 100)
        val highP = config.getDouble("high_percentile", 0.80)
        val lowP = config.getDouble("low_percentile", 0.25)
        val moveMin = config.getDouble("move_min", 0.005)

        val n = candles.size
        if (n < period + 2) {
            return result(Signal.NEUTRAL, "ATR%ile insufficient data")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        // True Range; tr[0] stays 0.0 (np.zeros in the reference).
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val prevClose = closes[i - 1]
            tr[i] = max(highs[i] - lows[i], max(abs(highs[i] - prevClose), abs(lows[i] - prevClose)))
        }

        // Wilder RMA: seeded with mean of tr[1..period], then
        // atr[i] = (atr[i-1]*(period-1) + tr[i]) / period.
        val atr = DoubleArray(n) { Double.NaN }
        var seed = 0.0
        for (i in 1..period) seed += tr[i]
        atr[period] = seed / period
        for (i in period + 1 until n) {
            atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period
        }

        // atr_pct = atr / close; keep only non-NaN values (order preserved).
        val valid = ArrayList<Double>(n)
        for (i in 0 until n) {
            val v = atr[i] / closes[i]
            if (!v.isNaN()) valid.add(v)
        }
        if (valid.size < 5) {
            return result(Signal.NEUTRAL, "ATR%ile warming up")
        }

        // window = valid[-lookback:]
        val start = if (lookback > 0) max(0, valid.size - lookback) else 0
        val window = valid.subList(start, valid.size)
        val cur = window[window.size - 1]
        var cnt = 0
        for (v in window) if (v <= cur) cnt++
        val pctile = cnt.toDouble() / window.size

        val base = closes[n - 1 - period]
        val move = if (base != 0.0) (closes[n - 1] - base) / base else 0.0

        val raw = mapOf<String, Double?>(
            "atr_pct_percentile" to roundTo(pctile, 4),
            "move" to roundTo(move, 5),
        )

        val pStr = pctile.format(2)
        if (pctile <= lowP) {
            return result(Signal.NEUTRAL, "vol compression (p$pStr)", raw)
        }
        if (abs(move) < moveMin) {
            return result(Signal.NEUTRAL, "vol high but flat (p$pStr)", raw)
        }
        val strong = pctile >= highP
        return if (move > 0) {
            result(if (strong) Signal.STRONG_BUY else Signal.BUY, "vol-expansion up (p$pStr)", raw)
        } else {
            result(if (strong) Signal.STRONG_SELL else Signal.SELL, "vol-expansion down (p$pStr)", raw)
        }
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
