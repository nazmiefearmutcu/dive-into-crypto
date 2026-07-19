package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 1:1 port of the Python reference `hurst.py`.
 *
 * Hurst-Exponent Regime — trend/mean-revert router in a single indicator.
 * Estimates the generalised Hurst exponent H from the log-price window (OLS
 * slope of log dispersion of lagged differences vs log lag; dispersion is the
 * POPULATION std, np.std default ddof=0). H>0.5 = persistent/trending,
 * H<0.5 = anti-persistent/mean-reverting. The window's net move is then read
 * THROUGH that regime: trending -> follow the move; mean-reverting -> fade it;
 * near-random (H≈0.5) -> abstain. Causal.
 */
class HurstIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "hurst"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 64)
        val trendH = config.getDouble("trend_h", 0.55)
        val revertH = config.getDouble("revert_h", 0.45)
        val moveMin = config.getDouble("move_min", 0.004)

        val closes = candles.map { it.close }
        if (closes.size < period || closes.any { it <= 0.0 }) {
            return result(Signal.NEUTRAL, "Hurst insufficient data")
        }

        val n = closes.size
        val w = DoubleArray(period) { closes[n - period + it] }
        val logp = DoubleArray(period) { ln(w[it]) }
        val h = hurst(logp)
        val move = if (w[0] != 0.0) (w[period - 1] - w[0]) / w[0] else 0.0

        val raw = mapOf<String, Double?>(
            "hurst" to roundTo(h, 4),
            "move" to roundTo(move, 5),
        )

        val hStr = h.format(2)
        if (abs(move) < moveMin || (h in revertH..trendH)) {
            return result(Signal.NEUTRAL, "H=$hStr random/flat", raw)
        }
        val up = move > 0
        if (h > trendH) { // trending: follow
            val strong = h > trendH + 0.10
            return if (up) {
                result(if (strong) Signal.STRONG_BUY else Signal.BUY, "H=$hStr trend up", raw)
            } else {
                result(if (strong) Signal.STRONG_SELL else Signal.SELL, "H=$hStr trend down", raw)
            }
        }
        // mean-reverting: fade
        val strong = h < revertH - 0.10
        return if (up) {
            result(if (strong) Signal.STRONG_SELL else Signal.SELL, "H=$hStr fade up", raw)
        } else {
            result(if (strong) Signal.STRONG_BUY else Signal.BUY, "H=$hStr fade down", raw)
        }
    }

    /**
     * Generalised Hurst exponent: OLS slope of ln(std of lag-d differences)
     * against ln(lag), lags 2 until min(20, n/2). Returns 0.5 when the window
     * is too short to estimate (max_lag < 4).
     */
    private fun hurst(logp: DoubleArray): Double {
        val n = logp.size
        val maxLag = min(20, n / 2)
        if (maxLag < 4) return 0.5
        val count = maxLag - 2 // lags = np.arange(2, max_lag)
        val logLag = DoubleArray(count)
        val logTau = DoubleArray(count)
        for (k in 0 until count) {
            val lag = k + 2
            val m = n - lag
            var mean = 0.0
            for (i in 0 until m) mean += logp[i + lag] - logp[i]
            mean /= m
            var ss = 0.0
            for (i in 0 until m) {
                val d = logp[i + lag] - logp[i] - mean
                ss += d * d
            }
            val sd = sqrt(ss / m) // np.std default ddof=0 (population)
            logLag[k] = ln(lag.toDouble())
            logTau[k] = ln(if (sd > 0.0) sd else 1e-10)
        }
        // np.polyfit(log_lags, log_tau, 1)[0] — OLS slope = cov(x,y)/var(x).
        var mx = 0.0
        var my = 0.0
        for (k in 0 until count) {
            mx += logLag[k]
            my += logTau[k]
        }
        mx /= count
        my /= count
        var num = 0.0
        var den = 0.0
        for (k in 0 until count) {
            val dx = logLag[k] - mx
            num += dx * (logTau[k] - my)
            den += dx * dx
        }
        return num / den
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
