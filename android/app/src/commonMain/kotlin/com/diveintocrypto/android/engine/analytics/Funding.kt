package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.Funding

/** Funding APR analytics — Kotlin port of `crypcodile/analytics/funding.py` (in-memory). */
object FundingAnalytics {
    private const val DEFAULT_INTERVAL_HOURS = 8

    /** 8760 / interval_hours (hours per non-leap year). */
    fun periodsPerYear(intervalHours: Int): Double {
        require(intervalHours > 0) { "interval_hours must be positive, got $intervalHours" }
        return 8760.0 / intervalHours
    }

    /** Simple (non-compounded) annualisation: rate * periodsPerYear. */
    fun aprFromRate(rate: Double, intervalHours: Int): Double =
        rate * periodsPerYear(intervalHours)

    data class FundingRow(
        val fundingTs: Long, val fundingRate: Double, val intervalHours: Int,
        val apr: Double, val cumulativeFunding: Double,
    )

    /** Per-event APR + running cumulative funding, sorted by fundingTs ascending. */
    fun fundingApr(funding: List<Funding>): List<FundingRow> {
        if (funding.isEmpty()) return emptyList()
        val sorted = funding.sortedBy { it.fundingTs }
        var running = 0.0
        return sorted.map { fr ->
            val ih = if (fr.intervalHours > 0) fr.intervalHours else DEFAULT_INTERVAL_HOURS
            running += fr.fundingRate
            FundingRow(
                fundingTs = fr.fundingTs, fundingRate = fr.fundingRate, intervalHours = ih,
                apr = fr.fundingRate * (8760.0 / ih), cumulativeFunding = running,
            )
        }
    }

    data class FundingSummaryRow(
        val nEvents: Int, val meanRate: Double, val meanApr: Double, val totalFunding: Double,
    )

    /** Single-row summary, or null when empty. */
    fun fundingSummary(funding: List<Funding>): FundingSummaryRow? {
        val rows = fundingApr(funding)
        if (rows.isEmpty()) return null
        val n = rows.size
        val meanRate = rows.sumOf { it.fundingRate } / n
        val meanApr = rows.sumOf { it.apr } / n
        val total = rows.sumOf { it.fundingRate }
        return FundingSummaryRow(n, meanRate, meanApr, total)
    }

    // ── Funding lens (0.3.0 parity addition) ──────────────────────────────────

    /**
     * The additive per-symbol funding lens shown on the panel:
     *
     * @param predictedRatePct venue's PREDICTED funding for the NEXT settlement
     *        (premiumIndex.lastFundingRate), percent per 8h interval
     * @param lastSettledRatePct the actually SETTLED last funding (fundingRate history tail),
     *        percent per 8h interval
     * @param aprPct simple annualisation of the predicted rate, PERCENT per year
     * @param secondsToFunding ms→s to [nextFundingMs]; null when the venue gave no time
     */
    data class FundingLens(
        val predictedRatePct: Double,
        val lastSettledRatePct: Double,
        val aprPct: Double,
        val secondsToFunding: Long?,
    )

    /**
     * PURE funding lens. Rates are wire fractions (0.0001 = 0.01%); anything
     * non-finite is rejected (null) — honest unavailability.
     */
    fun fundingLens(
        predictedRate: Double,
        lastSettledRate: Double,
        nextFundingMs: Long,
        nowMs: Long,
    ): FundingLens? {
        if (!predictedRate.isFinite() || !lastSettledRate.isFinite()) return null
        val predictedPct = predictedRate * 100.0
        return FundingLens(
            predictedRatePct = predictedPct,
            lastSettledRatePct = lastSettledRate * 100.0,
            aprPct = aprFromRate(predictedRate, DEFAULT_INTERVAL_HOURS) * 100.0,
            secondsToFunding = if (nextFundingMs > 0) (nextFundingMs - nowMs) / 1000 else null,
        )
    }
}
