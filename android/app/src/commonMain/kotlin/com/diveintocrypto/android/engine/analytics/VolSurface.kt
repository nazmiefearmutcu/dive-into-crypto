package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import com.diveintocrypto.android.engine.schema.OptionsChain
import kotlin.math.abs

/** IV surface, skew, term-structure, 25Δ RR/BF — port of `crypcodile/analytics/volsurface.py`. */
object VolSurface {
    private const val NS_PER_YEAR = 365.0 * 24.0 * 3600.0 * 1e9
    private const val NS_PER_DAY = 86_400.0 * 1e9

    data class SurfacePoint(
        val expiry: Long, val strike: Double, val moneyness: Double,
        val optType: OptType, val iv: Double?, val source: String,
    )

    fun ivSurface(chain: List<OptionsChain>, atNs: Long, rate: Double = 0.0): List<SurfacePoint> {
        val visible = chain.filter { it.localTs <= atNs }
        if (visible.isEmpty()) return emptyList()
        val latest = visible
            .groupBy { Triple(it.strike, it.expiry, it.optType) }
            .map { (_, rows) -> rows.maxBy { it.localTs } }
        return latest.map { r ->
            val (iv, source) = resolveIv(r, atNs, rate)
            val up = r.underlyingPrice
            val mny = if (up != null && up > 0.0) r.strike / up else Double.NaN
            SurfacePoint(r.expiry, r.strike, mny, r.optType, iv, source)
        }
    }

    private fun resolveIv(r: OptionsChain, atNs: Long, rate: Double): Pair<Double?, String> {
        val mi = r.markIv
        if (mi != null && mi.isFinite() && mi > 0.0) return mi to "mark_iv"
        val mp = r.markPrice
        val up = r.underlyingPrice
        if (mp != null && mp.isFinite() && mp > 0.0 && up != null && up.isFinite() && up > 0.0) {
            val t = (r.expiry - atNs) / NS_PER_YEAR
            if (t > 0.0) {
                val iv = BlackScholes.impliedVol(mp, up, r.strike, t, r.optType, rate)
                if (iv != null) return iv to "computed"
            }
        }
        return null to "unavailable"
    }

    data class SkewPoint(
        val strike: Double, val moneyness: Double, val optType: OptType,
        val iv: Double?, val delta: Double?,
    )

    fun volSkew(chain: List<OptionsChain>, expiryNs: Long, atNs: Long, rate: Double = 0.0): List<SkewPoint> {
        val surface = ivSurface(chain, atNs, rate).filter { it.expiry == expiryNs }
        if (surface.isEmpty()) return emptyList()
        val up = chain.filter { it.localTs <= atNs && it.expiry == expiryNs }
            .maxByOrNull { it.localTs }?.underlyingPrice
        val t = (expiryNs - atNs) / NS_PER_YEAR
        return surface.sortedBy { it.strike }.map { s ->
            val iv = s.iv
            val delta = if (iv != null && up != null && t > 0.0)
                BlackScholes.greeks(up, s.strike, t, iv, s.optType, rate).delta
            else null
            SkewPoint(s.strike, s.moneyness, s.optType, s.iv, delta)
        }
    }

    fun riskReversalButterfly(skew: List<SkewPoint>, targetDelta: Double = 0.25): Pair<Double?, Double?> {
        if (skew.isEmpty()) return null to null
        val calls = skew.filter { it.optType == OptType.CALL && it.iv != null && it.delta != null }
        val puts = skew.filter { it.optType == OptType.PUT && it.iv != null && it.delta != null }
        val bestCall = calls.minByOrNull { abs(it.delta!! - targetDelta) }
        val bestPut = puts.minByOrNull { abs(it.delta!! - (-targetDelta)) }
        if (bestCall == null || bestPut == null) return null to null
        val all = skew.filter { it.iv != null && it.delta != null }
        val atm = all.minByOrNull { abs(abs(it.delta!!) - 0.5) } ?: return null to null
        val callIv = bestCall.iv!!
        val putIv = bestPut.iv!!
        val atmIv = atm.iv!!
        val rr = callIv - putIv
        val bf = 0.5 * (callIv + putIv) - atmIv
        return rr to bf
    }

    data class TermPoint(val expiry: Long, val daysToExpiry: Double, val atmStrike: Double, val atmIv: Double?)

    fun termStructure(chain: List<OptionsChain>, atNs: Long, rate: Double = 0.0): List<TermPoint> {
        val surface = ivSurface(chain, atNs, rate)
        if (surface.isEmpty()) return emptyList()
        val up = chain.filter { it.localTs <= atNs }.maxByOrNull { it.localTs }?.underlyingPrice
        val expiries = surface.map { it.expiry }.distinct().sorted()
        return expiries.mapNotNull { exp ->
            val rows = surface.filter { it.expiry == exp }
            if (rows.isEmpty()) return@mapNotNull null
            val best = if (up != null)
                rows.minBy { abs(it.strike - up) }
            else
                rows.minBy { abs(it.moneyness - 1.0) }
            TermPoint(exp, (exp - atNs) / NS_PER_DAY, best.strike, best.iv)
        }
    }
}
