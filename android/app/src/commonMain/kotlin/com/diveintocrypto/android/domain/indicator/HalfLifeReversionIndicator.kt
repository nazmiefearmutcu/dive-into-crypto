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
 * (β = OLS slope, identical to np.polyfit(lagged, delta, 1)[0]). Gates, in order:
 *   1. β < 0 (reversion direction) and β > -1 (stable AR(1));
 *   2. Dickey-Fuller style t-stat on β (OLS standard error from the residuals)
 *      at or below `adf_t_critical` (default -2.86);
 *   3. half-life = −ln2/ln(1+β) of at least one bar and at most `max_half_life`;
 *   4. finite window (non-finite closes are NEUTRAL).
 * Only then does it fade the current z-score deviation (z uses the window's
 * sample std, ddof=1). Causal.
 */
class HalfLifeReversionIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "half_life_reversion"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 50)
        val maxHl = config.getDouble("max_half_life", 30.0)
        val strongZ = config.getDouble("strong_z", 2.0)
        val weakZ = config.getDouble("weak_z", 1.0)
        val adfCrit = config.getDouble("adf_t_critical", -2.86)

        val closes = candles.map { it.close }
        val n = closes.size
        if (n < period || period < 4) {
            return result(Signal.NEUTRAL, "Half-life insufficient data")
        }

        val y = DoubleArray(period) { closes[n - period + it] }
        if (y.any { !it.isFinite() }) {
            return result(Signal.NEUTRAL, "Half-life insufficient data")
        }

        // beta = OLS slope of delta on lagged, with lagged = y[:-1], delta = diff(y).
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
        if (den == 0.0) {
            return result(Signal.NEUTRAL, "flat series")
        }
        val beta = num / den
        val intercept = my - beta * mx

        if (beta >= 0.0) {
            return result(
                Signal.NEUTRAL,
                "not mean-reverting (β≥0)",
                mapOf<String, Double?>("beta" to roundTo(beta, 6)),
            )
        }
        if (beta <= -1.0) {
            return result(
                Signal.NEUTRAL,
                "AR(1) unstable (β≤-1)",
                mapOf<String, Double?>("beta" to roundTo(beta, 6)),
            )
        }

        // OLS standard error of beta from the residual variance (m - 2 degrees of freedom).
        var ssRes = 0.0
        for (i in 0 until m) {
            val e = (y[i + 1] - y[i]) - (beta * y[i] + intercept)
            ssRes += e * e
        }
        val s2 = ssRes / (m - 2)
        val se = if (s2 > 0.0) sqrt(s2 / den) else 0.0
        val tStat = if (se > 0.0) beta / se else Double.NEGATIVE_INFINITY
        if (tStat > adfCrit) {
            return result(
                Signal.NEUTRAL,
                "not statistically mean-reverting (t=${tStat.format(2)} > ${adfCrit.format(2)})",
                mapOf<String, Double?>("beta" to roundTo(beta, 6)),
            )
        }

        val hl = -ln(2.0) / ln1p(beta)

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
        if (hl < 1.0) {
            return result(Signal.NEUTRAL, "half-life ${hl.format(2)} below 1 bar (implausible)", raw)
        }
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
