package com.diveintocrypto.android.engine.analytics

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Volatility cone envelope — log-normal expected-move cone, faithful port of the
 * desktop reference (`diveintocrypto_desktop/scan/structure.py::vol_cone`).
 *
 * Given a 1h close series:
 *   - returns are LOG returns r_t = ln(c_t / c_{t-1}) over consecutive positive closes
 *   - σ1h  = SAMPLE stdev of the log returns (n−1 denominator, like desktop `_std`)
 *   - envelope(h) = P0·exp(±z·σ·√h) − 1 with z = [CONE_Z] = 1 — expressed as
 *     PERCENT vs the last close (exp() is always positive, so the projected
 *     prices can never cross zero; `up`/`down` are signed % moves)
 *   - percentile = rank (0..100) of the latest 24h |Σr| within the trailing
 *     overlapping 24h windows — omitted (null) below [CONE_MIN_WINDOWS] windows
 *
 * Fewer than [MIN_RETURNS] log returns, or a degenerate (zero) σ → null — the
 * caller then OMITS the cone (never ships a fake one).
 */
object VolCone {

    data class ConeEnv(
        /** σ of the 1h log returns, PERCENT (desktop `sigma_1h`). */
        val sigma1hPct: Double,
        /** 24h up envelope: (e^{σ·√24} − 1)·100 (desktop `env_24h.up`). */
        val env24hUpPct: Double,
        /** 24h down envelope: (e^{−σ·√24} − 1)·100 — NEGATIVE percent (desktop `env_24h.down`). */
        val env24hDownPct: Double,
        /** 48h up envelope: (e^{σ·√48} − 1)·100 (desktop `env_48h.up`). */
        val env48hUpPct: Double,
        /** 48h down envelope: (e^{−σ·√48} − 1)·100 — NEGATIVE percent (desktop `env_48h.down`). */
        val env48hDownPct: Double,
        /**
         * Percentile-rank (0..100, 1 decimal) of the latest 24h absolute log-move
         * within the trailing overlapping 24h windows (desktop `percentile`);
         * null when fewer than [CONE_MIN_WINDOWS] windows fit (honest omission).
         */
        val percentile: Double?,
        /** √t projection σ1h·√24·100 (chart label — small-σ limit of |env_24h|). */
        val vol24hPct: Double,
        /** √t projection σ1h·√48·100. */
        val vol48hPct: Double,
        /** Chart convenience: 24h DOWN magnitude as a positive percent (−env24hDownPct). */
        val envLowPct: Double,
        /** Chart convenience: 24h UP magnitude as a positive percent (= env24hUpPct). */
        val envHighPct: Double,
    )

    /** Envelope width in σ (log-normal P0·exp(±z·σ√h)) — published, not fitted. */
    const val CONE_Z: Double = 1.0

    /** Minimum number of 1h log returns for an honest cone (desktop MIN_RETURNS). */
    const val MIN_RETURNS: Int = 30

    /** Overlapping 24h windows needed for an honest percentile (desktop CONE_MIN_WINDOWS). */
    const val CONE_MIN_WINDOWS: Int = 30

    /** 24h horizon in 1h buckets. */
    const val H24: Double = 24.0

    /** 48h horizon in 1h buckets. */
    const val H48: Double = 48.0

    /**
     * SAMPLE stdev (n−1 denominator, matching desktop `_std`); null when fewer
     * than two values.
     */
    fun stdev(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.sumOf { it } / values.size
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1.0))
    }

    /**
     * PURE cone from a 1h close series (chronological). null when fewer than
     * [MIN_RETURNS] log returns fit or σ is degenerate (≤ 0) — honest omission.
     */
    fun fromCloses(closes: List<Double>): ConeEnv? {
        // Desktop filters consecutive positive pairs instead of rejecting the
        // whole series; non-finite prices are skipped the same way.
        val returns = (1 until closes.size).mapNotNull { i ->
            val a = closes[i - 1]
            val b = closes[i]
            if (a.isFinite() && b.isFinite() && a > 0.0 && b > 0.0) ln(b / a) else null
        }
        if (returns.size < MIN_RETURNS) return null
        val sigma = stdev(returns)
        if (sigma == null || sigma <= 0.0) return null

        fun envelope(h: Double): Pair<Double, Double> {
            val drift = CONE_Z * sigma * sqrt(h)
            return (exp(drift) - 1.0) * 100.0 to (exp(-drift) - 1.0) * 100.0
        }

        val (up24, down24) = envelope(H24)
        val (up48, down48) = envelope(H48)

        // percentile: rank the latest 24h |Σr| within the trailing overlapping
        // 24h windows (desktop windows24) — null below CONE_MIN_WINDOWS windows.
        val windows24 = (0..returns.size - 24).map { k ->
            kotlin.math.abs(returns.subList(k, k + 24).sumOf { it })
        }
        val percentile = if (windows24.size >= CONE_MIN_WINDOWS) {
            val last = windows24.last()
            val below = windows24.count { it <= last }
            round(below / windows24.size.toDouble() * 1000.0) / 10.0
        } else null

        return ConeEnv(
            sigma1hPct = sigma * 100.0,
            env24hUpPct = up24,
            env24hDownPct = down24,
            env48hUpPct = up48,
            env48hDownPct = down48,
            percentile = percentile,
            vol24hPct = sigma * sqrt(H24) * 100.0,
            vol48hPct = sigma * sqrt(H48) * 100.0,
            envLowPct = -down24,
            envHighPct = up24,
        )
    }
}
