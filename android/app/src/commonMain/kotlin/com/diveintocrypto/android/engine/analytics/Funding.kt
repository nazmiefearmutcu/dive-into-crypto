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
}
