package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult

/**
 * Regime-Adaptive Weighting — strategy overlay. Faithful port of the Python
 * reference `engine/consensus/regime.py`.
 *
 * Detects the market regime (TREND / RANGE / MIXED) from the already-computed ADX
 * and Choppiness outputs, then re-weights the indicator vote to emphasise the
 * families that work in that regime. ADDITIVE — it surfaces a regime label and an
 * adaptively-weighted score next to the parity-locked consensus; it never replaces
 * the canonical verdict.
 */
object Regime {

    data class Detection(val regime: String, val adx: Double?, val chop: Double?)

    data class Evaluation(
        val regime: String,
        val adx: Double?,
        val chop: Double?,
        val adaptiveScore: Double,
    )

    private val TREND_FAMILY = setOf(
        "macd", "ema_cross", "sma_cross", "ichimoku", "psar", "adx_di", "supertrend", "vortex",
        "aroon_oscillator", "schaff_trend_cycle", "trix", "kst", "coppock_curve", "linreg_slope",
        "kalman_trend", "donchian_breakout", "keltner_breakout", "vwma_cross", "hurst",
        "rolling_sharpe", "elder_ray", "wavetrend",
    )
    private val RANGE_FAMILY = setOf(
        "rsi", "stochastic", "williams_r", "cci", "bollinger", "bollinger_percent_b", "mfi",
        "connors_rsi", "stoch_rsi", "ultimate_oscillator", "zscore_reversion",
        "half_life_reversion", "relative_vigor_index", "fisher_transform", "cmo",
        "hist_vol_percentile",
    )

    private fun raw(results: List<IndicatorResult>, name: String, key: String): Double? =
        results.firstOrNull { it.name == name }?.rawValues?.get(key)

    fun detect(
        results: List<IndicatorResult>,
        strongAdx: Double = 25.0,
        weakAdx: Double = 20.0,
        chopTrend: Double = 38.2,
        chopRange: Double = 61.8,
    ): Detection {
        val adx = raw(results, "adx_di", "adx")
        val chop = raw(results, "choppiness", "chop")
        var t = 0; var r = 0
        if (adx != null) {
            if (adx >= strongAdx) t++ else if (adx <= weakAdx) r++
        }
        if (chop != null) {
            if (chop <= chopTrend) t++ else if (chop >= chopRange) r++
        }
        val regime = if (t > r) "TREND" else if (r > t) "RANGE" else "MIXED"
        return Detection(regime, adx, chop)
    }

    fun adaptiveWeights(
        baseWeights: Map<String, Double>,
        regime: String,
        boost: Double = 1.5,
        damp: Double = 0.6,
    ): Map<String, Double> {
        val (up, dn) = when (regime) {
            "TREND" -> TREND_FAMILY to RANGE_FAMILY
            "RANGE" -> RANGE_FAMILY to TREND_FAMILY
            else -> return baseWeights.toMap()
        }
        val out = HashMap<String, Double>(baseWeights.size)
        for ((k, w) in baseWeights) {
            out[k] = when {
                w == 0.0 -> w            // keep pure filters (e.g. atr_filter) at 0
                k in up -> w * boost
                k in dn -> w * damp
                else -> w
            }
        }
        return out
    }

    /** Regime + adaptively-weighted score (observational; consensus untouched). */
    fun evaluate(results: List<IndicatorResult>, baseWeights: Map<String, Double>): Evaluation {
        val det = detect(results)
        val w = adaptiveWeights(baseWeights, det.regime)
        val score = Scorer.compute(results, w)
        return Evaluation(det.regime, det.adx, det.chop, score.weightedScore)
    }
}
