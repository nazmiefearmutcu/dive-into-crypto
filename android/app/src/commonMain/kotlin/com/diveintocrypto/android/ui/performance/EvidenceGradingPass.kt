package com.diveintocrypto.android.ui.performance

import com.diveintocrypto.android.domain.evidence.EvidenceGrader
import com.diveintocrypto.android.domain.evidence.VerdictRecord

/** Identity of one archived verdict under one grading horizon. */
internal data class GradeKey(val ts: Long, val symbol: String, val horizonHours: Long)

/**
 * PURE selection and sample logic for one evidence grading pass (no clock, no I/O).
 *
 * The settled map holds one entry per record whose outcome is final:
 *  - a non-null value is the graded forward return in percent;
 *  - a null value means candles were fetched but no candle covers the record, so it can
 *    never be graded (it is not "stale").
 * A record whose candle fetch FAILED has no entry and stays pending. Nothing is keyed
 * off a timestamp cursor, so a failed or capped symbol cannot hide other records that
 * share its scan timestamp.
 */
internal object EvidenceGradingPass {

    /** STRONG_BUY / STRONG_SELL fold into BUY / SELL: same direction, same LONG/SHORT card. */
    fun normalizeVerdict(raw: String): String = when (raw) {
        "STRONG_BUY" -> "BUY"
        "STRONG_SELL" -> "SELL"
        else -> raw
    }

    fun keyOf(record: VerdictRecord, horizonHours: Long): GradeKey =
        GradeKey(record.ts, record.symbol, horizonHours)

    /**
     * Symbols to fetch in this pass: distinct symbols of unsettled records, newest first,
     * capped at [cap]. [eligible] is in archive order (oldest first).
     */
    fun symbolsToFetch(
        eligible: List<VerdictRecord>,
        settled: Map<GradeKey, Double?>,
        horizonHours: Long,
        cap: Int,
    ): List<String> = eligible.asReversed()
        .filter { keyOf(it, horizonHours) !in settled }
        .map { it.symbol }
        .distinct()
        .take(cap)

    /** Graded samples: every eligible record whose settled forward return is non-null. */
    fun gradedSamples(
        eligible: List<VerdictRecord>,
        settled: Map<GradeKey, Double?>,
        horizonHours: Long,
    ): List<EvidenceGrader.GradedSample> = eligible.mapNotNull { rec ->
        val forward = settled[keyOf(rec, horizonHours)] ?: return@mapNotNull null
        EvidenceGrader.GradedSample(
            verdict = normalizeVerdict(rec.verdict),
            confidence = rec.confidence,
            dominantDir = rec.dominantDir,
            forwardReturnPct = forward,
            ts = rec.ts,
        )
    }

    /** Eligible records with no final outcome yet. Drives the honest stale flag. */
    fun pendingCount(
        eligible: List<VerdictRecord>,
        settled: Map<GradeKey, Double?>,
        horizonHours: Long,
    ): Int = eligible.count { keyOf(it, horizonHours) !in settled }

    /** Newest timestamp among gradable records, null when nothing is graded yet. */
    fun newestGradedTs(
        eligible: List<VerdictRecord>,
        settled: Map<GradeKey, Double?>,
        horizonHours: Long,
    ): Long? = eligible
        .filter { settled[keyOf(it, horizonHours)] != null }
        .maxOfOrNull { it.ts }
}
