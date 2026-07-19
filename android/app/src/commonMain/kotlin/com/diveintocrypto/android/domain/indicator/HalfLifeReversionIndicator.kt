package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * 1:1 port of the Python reference `half_life_reversion.py`.
 *
 * OU Half-Life Mean Reversion — statistically-gated reversion. Fits an
 * AR(1)/Ornstein-Uhlenbeck model over the window: Δprice_t = a + β·price_{t-1}
 * (β = np.polyfit(lagged, delta, 1)[0], i.e. OLS slope). β<0 implies mean
 * reversion with half-life = −ln2/ln(1+β). Only when the series is
 * statistically mean-reverting AND the half-life is short enough to act on
 * does it fade the current z-score deviation (z uses the window's sample std,
 * ddof=1). Causal.
 */
class HalfLifeReversionIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "half_life_reversion"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 50)
        val maxHl = config.getDouble("max_half_life", 30.0)
        val strongZ = config.getDouble("strong_z", 2.0)
        val weakZ = config.getDouble("weak_z", 1.0)

        val closes = candles.map { it.close }
        val n = closes.size
        if (n < period) {
            return result(Signal.NEUTRAL, "Half-life insufficient data")
        }

        val y = DoubleArray(period) { closes[n - period + it] }

        // beta = np.polyfit(lagged, delta, 1)[0] with lagged = y[:-1],
        // delta = np.diff(y). OLS slope = cov(x, y) / var(x).
        val m = period - 1
        var mx = 0.0
        var my = 0.0
        for (i in 0 until m) {
            mx += y[i]
            my += y[i + 1] - y[i]
        }
        mx /= m
        my /= m
        var num = 0.0
        var den = 0.0
        for (i in 0 until m) {
            val dx = y[i] - mx
            num += dx * (y[i + 1] - y[i] - my)
            den += dx * dx
        }
        // den == 0 (constant window ⇒ delta ≡ 0) would give NaN; np.polyfit's
        // lstsq returns the minimum-norm solution beta = 0.0 there instead
        // (with a RankWarning), which then hits the β≥0 NEUTRAL branch.
        val beta = if (den == 0.0) 0.0 else num / den

        if (beta >= 0.0) {
            return result(
                Signal.NEUTRAL,
                "not mean-reverting (β≥0)",
                mapOf<String, Double?>("beta" to roundTo(beta, 6)),
            )
        }
        val hl = if (beta > -1.0 && beta < 0.0) -ln(2.0) / ln1p(beta) else Double.POSITIVE_INFINITY

        var mean = 0.0
        for (v in y) mean += v
        mean /= period
        var ss = 0.0
        for (v in y) {
            val d = v - mean
            ss += d * d
        }
        val sd = sqrt(ss / (period - 1)) // std(ddof=1)
        if (sd == 0.0) {
            return result(Signal.NEUTRAL, "flat series")
        }
        val z = (y[period - 1] - mean) / sd

        val raw = mapOf<String, Double?>(
            "beta" to roundTo(beta, 6),
            "half_life" to roundTo(hl, 2),
            "zscore" to roundTo(z, 4),
        )

        // Python `{hl:.1f}` prints "inf" for float('inf').
        val hlStr = if (hl.isFinite()) hl.format(1) else "inf"
        if (hl > maxHl || hl.isNaN()) {
            return result(Signal.NEUTRAL, "half-life $hlStr too slow", raw)
        }
        // mean-reverting & actionable: fade the deviation
        val zStr = z.format(2, plus = true)
        return when {
            z >= strongZ -> result(Signal.STRONG_SELL, "revert (hl $hlStr, z $zStr)", raw)
            z >= weakZ -> result(Signal.SELL, "revert (hl $hlStr, z $zStr)", raw)
            z <= -strongZ -> result(Signal.STRONG_BUY, "revert (hl $hlStr, z $zStr)", raw)
            z <= -weakZ -> result(Signal.BUY, "revert (hl $hlStr, z $zStr)", raw)
            else -> result(Signal.NEUTRAL, "near mean (hl $hlStr, z $zStr)", raw)
        }
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
