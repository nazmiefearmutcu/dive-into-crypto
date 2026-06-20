package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Black-76 option pricing, greeks, and implied-vol solver — Kotlin port of
 * `crypcodile/analytics/blackscholes.py`. European options on a forward/index F
 * with discount factor D = exp(-rate*t). Pure `kotlin.math`.
 */
object BlackScholes {

    /** Standard normal PDF: exp(-x²/2)/sqrt(2π). Exact. */
    fun normPdf(x: Double): Double = exp(-0.5 * x * x) / sqrt(2.0 * PI)

    /** Standard normal CDF via Abramowitz-Stegun 26.2.17 (abs error < 7.5e-8). */
    fun normCdf(x: Double): Double {
        if (x == 0.0) return 0.5
        val l = abs(x)
        val k = 1.0 / (1.0 + 0.2316419 * l)
        val a1 = 0.319381530; val a2 = -0.356563782; val a3 = 1.781477937
        val a4 = -1.821255978; val a5 = 1.330274429
        val w = 1.0 - normPdf(l) * (a1 * k + a2 * k * k + a3 * k * k * k +
                a4 * k * k * k * k + a5 * k * k * k * k * k)
        return if (x >= 0.0) w else 1.0 - w
    }

    data class Greeks(
        val delta: Double, val gamma: Double, val vega: Double,
        val theta: Double, val rho: Double,
    )

    /** Black-76 price. Expired (t<=0) or vol==0 → discounted intrinsic. */
    fun price(forward: Double, strike: Double, tYears: Double, vol: Double,
              optType: OptType, rate: Double = 0.0): Double {
        require(vol >= 0) { "vol must be >= 0, got $vol" }
        val d = if (tYears > 0) exp(-rate * tYears) else 1.0
        if (tYears <= 0 || vol == 0.0) {
            return if (optType == OptType.CALL) d * maxOf(forward - strike, 0.0)
                   else d * maxOf(strike - forward, 0.0)
        }
        val sqrtT = sqrt(tYears)
        val d1 = (ln(forward / strike) + 0.5 * vol * vol * tYears) / (vol * sqrtT)
        val d2 = d1 - vol * sqrtT
        return if (optType == OptType.CALL)
            d * (forward * normCdf(d1) - strike * normCdf(d2))
        else
            d * (strike * normCdf(-d2) - forward * normCdf(-d1))
    }

    /** Black-76 greeks. Expired (t<=0) or vol==0 → all zeros. */
    fun greeks(forward: Double, strike: Double, tYears: Double, vol: Double,
               optType: OptType, rate: Double = 0.0): Greeks {
        require(vol >= 0) { "vol must be >= 0, got $vol" }
        if (tYears <= 0 || vol == 0.0) return Greeks(0.0, 0.0, 0.0, 0.0, 0.0)
        val d = exp(-rate * tYears)
        val sqrtT = sqrt(tYears)
        val d1 = (ln(forward / strike) + 0.5 * vol * vol * tYears) / (vol * sqrtT)
        val nd1 = normCdf(d1)
        val npd1 = normPdf(d1)
        val delta = if (optType == OptType.CALL) d * nd1 else -d * normCdf(-d1)
        val gamma = d * npd1 / (forward * vol * sqrtT)
        val vega = d * forward * npd1 * sqrtT
        val priceVal = price(forward, strike, tYears, vol, optType, rate)
        val theta = -d * forward * npd1 * vol / (2.0 * sqrtT) + rate * priceVal
        val rho = -tYears * priceVal
        return Greeks(delta, gamma, vega, theta, rho)
    }

    private const val IV_MIN = 1e-6
    private const val IV_MAX = 10.0
    private const val TOL = 1e-6
    private const val MAX_ITER = 100

    /**
     * Solve implied vol reproducing [targetPrice]. Newton (seed 0.5) → bisection
     * fallback on [1e-6, 10]. Returns null when expired, outside no-arb bounds,
     * or non-convergent.
     */
    fun impliedVol(targetPrice: Double, forward: Double, strike: Double, tYears: Double,
                   optType: OptType, rate: Double = 0.0): Double? {
        if (tYears <= 0) return null
        val d = exp(-rate * tYears)
        val intrinsic: Double; val upper: Double
        if (optType == OptType.CALL) { intrinsic = d * maxOf(forward - strike, 0.0); upper = d * forward }
        else { intrinsic = d * maxOf(strike - forward, 0.0); upper = d * strike }
        if (targetPrice <= intrinsic || targetPrice >= upper) return null

        // --- Newton-Raphson, seeded at 0.5; break out to bisection on failure ---
        var vol = 0.5
        for (i in 0 until MAX_ITER) {
            val p = price(forward, strike, tYears, vol, optType, rate)
            val diff = p - targetPrice
            if (abs(diff) < TOL) return vol
            val v = greeks(forward, strike, tYears, vol, optType, rate).vega
            if (abs(v) < 1e-10) break                       // vega≈0 → bisection
            val volNew = vol - diff / v
            if (volNew < IV_MIN || volNew > IV_MAX) break    // stepped out → bisection
            vol = volNew
        }

        // --- bisection fallback on [IV_MIN, IV_MAX] ---
        var lo = IV_MIN; var hi = IV_MAX
        val pLo = price(forward, strike, tYears, lo, optType, rate)
        val pHi = price(forward, strike, tYears, hi, optType, rate)
        if (!(pLo <= targetPrice && targetPrice <= pHi)) return null
        for (i in 0 until MAX_ITER) {
            val mid = 0.5 * (lo + hi)
            val pMid = price(forward, strike, tYears, mid, optType, rate)
            if (abs(pMid - targetPrice) < TOL) return mid
            if (pMid < targetPrice) lo = mid else hi = mid
        }
        return null
    }
}
