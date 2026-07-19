package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 1:1 port of the Python reference `hist_vol_percentile.py`.
 *
 * Historical-Volatility Percentile — exhaustion fade at volatility extremes.
 * Realised volatility = rolling sample std (ddof=1) of log returns, ranked
 * against its own trailing history. In the top volatility decile the latest
 * directional move is FADED (contrarian); otherwise NEUTRAL. Non-positive
 * closes become NaN before the log (np.where(close > 0, close, nan)) and any
 * window containing a NaN yields a NaN vol, exactly like pandas rolling std.
 * Causal.
 */
class HistVolPercentileIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "hist_vol_percentile"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val lookback = config.getInt("lookback", 100)
        val extreme = config.getDouble("extreme_percentile", 0.90)
        val veryExtreme = config.getDouble("very_extreme_percentile", 0.97)
        val moveMin = config.getDouble("move_min", 0.004)

        val n = candles.size
        if (n < period + 5) {
            return result(Signal.NEUTRAL, "HV%ile insufficient data")
        }

        val closes = candles.map { it.close }

        // logret = np.diff(np.log(np.where(close > 0, close, nan)))
        val logs = DoubleArray(n) { if (closes[it] > 0.0) ln(closes[it]) else Double.NaN }
        val m = n - 1
        val logret = DoubleArray(m) { logs[it + 1] - logs[it] }

        // Rolling sample std (ddof=1), pandas semantics: NaN until the window
        // is full and NaN whenever the window contains a NaN. Only non-NaN
        // values are collected (order preserved).
        val valid = ArrayList<Double>(m)
        window@ for (i in period - 1 until m) {
            var sum = 0.0
            for (j in i - period + 1..i) {
                val v = logret[j]
                if (v.isNaN()) continue@window
                sum += v
            }
            val mean = sum / period
            var ss = 0.0
            for (j in i - period + 1..i) {
                val d = logret[j] - mean
                ss += d * d
            }
            val std = sqrt(ss / (period - 1))
            if (!std.isNaN()) valid.add(std)
        }
        if (valid.size < 5) {
            return result(Signal.NEUTRAL, "HV%ile warming up")
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
            "hv_percentile" to roundTo(pctile, 4),
            "move" to roundTo(move, 5),
        )

        val pStr = pctile.format(2)
        if (pctile < extreme || abs(move) < moveMin) {
            return result(Signal.NEUTRAL, "vol normal (p$pStr)", raw)
        }
        val strong = pctile >= veryExtreme
        // fade the move: up-move at vol climax -> SELL, down-move -> BUY
        return if (move > 0) {
            result(if (strong) Signal.STRONG_SELL else Signal.SELL, "vol-climax fade up (p$pStr)", raw)
        } else {
            result(if (strong) Signal.STRONG_BUY else Signal.BUY, "vol-climax fade down (p$pStr)", raw)
        }
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
