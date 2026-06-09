package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.engine.schema.Trade

/** Spot-future & perpetual basis — Kotlin port of `crypcodile/analytics/basis.py` (in-memory). */
object BasisAnalytics {

    data class SpotFutureBasisRow(
        val localTs: Long, val futurePrice: Double, val spotPrice: Double,
        val basis: Double, val basisPct: Double, val annualizedPct: Double? = null,
    )

    private const val NS_PER_DAY = 86_400.0 * 1e9

    /**
     * ASOF basis: each future trade paired with the nearest prior spot trade
     * (largest spot.localTs <= future.localTs, spotPrice > 0). Ordered by future localTs.
     * When [expiryNs] given, adds annualizedPct = basisPct * 365 / daysToExpiry
     * (null when daysToExpiry <= 0).
     */
    fun spotFutureBasis(
        futures: List<Trade>, spot: List<Trade>, expiryNs: Long? = null,
    ): List<SpotFutureBasisRow> {
        if (futures.isEmpty() || spot.isEmpty()) return emptyList()
        val spotSorted = spot.sortedBy { it.localTs }
        val futSorted = futures.sortedBy { it.localTs }
        val out = ArrayList<SpotFutureBasisRow>()
        for (fut in futSorted) {
            val prior = spotSorted.lastOrNull { it.localTs <= fut.localTs } ?: continue
            if (prior.price <= 0.0) continue
            val basis = fut.price - prior.price
            val basisPct = basis / prior.price
            val ann: Double? = if (expiryNs != null) {
                val days = (expiryNs - fut.localTs) / NS_PER_DAY
                if (days > 0.0) basisPct * 365.0 / days else null
            } else null
            out.add(SpotFutureBasisRow(fut.localTs, fut.price, prior.price, basis, basisPct, ann))
        }
        return out
    }

    data class PerpBasisRow(
        val localTs: Long, val markPrice: Double, val indexPrice: Double,
        val basis: Double, val basisPct: Double,
    )

    /** Perp basis (mark vs index). Drops rows with null/<=0 mark or index. Ordered by localTs. */
    fun perpBasis(tickers: List<DerivativeTicker>): List<PerpBasisRow> =
        tickers.asSequence()
            .filter { (it.markPrice ?: 0.0) > 0.0 && (it.indexPrice ?: 0.0) > 0.0 }
            .sortedBy { it.localTs }
            .map { t ->
                val m = t.markPrice!!; val i = t.indexPrice!!
                PerpBasisRow(t.localTs, m, i, m - i, (m - i) / i)
            }.toList()
}
