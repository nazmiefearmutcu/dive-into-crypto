package com.diveintocrypto.android.domain.evidence

import kotlin.math.abs

/**
 * Aggregate grading stats for one bucket (a verdict class or a confidence band).
 *
 * @param samples graded records in the bucket
 * @param hitRate share of records whose forward move agreed with the verdict direction (0..1)
 * @param medianReturnPct median forward return, percent (signed)
 */
data class EvidenceBucketStats(
    val samples: Int,
    val hitRate: Double,
    val medianReturnPct: Double,
)

/**
 * Result of one grading pass over archived verdicts.
 */
data class EvidenceGrade(
    val graded: Int,
    val byVerdict: Map<String, EvidenceBucketStats>,
    val byConfidence: Map<String, EvidenceBucketStats>,
)

/**
 * PURE grader math for the Android verdict-evidence self-audit (unit-tested,
 * no network / clock / storage).
 */
object EvidenceGrader {

    /** One (record, forward return) pair handed to [grade]. */
    data class GradedSample(
        val verdict: String,
        val confidence: Int,
        val dominantDir: Int,
        /** Forward return from the record's timestamp to the horizon, in PERCENT (signed). */
        val forwardReturnPct: Double,
    )

    /** Confidence band label for a 0..100 confidence. */
    fun confidenceBucket(confidence: Int): String = when {
        confidence <= 25 -> "0-25"
        confidence <= 50 -> "26-50"
        confidence <= 75 -> "51-75"
        else -> "76-100"
    }

    /**
     * Did the forward move agree with the verdict's direction?
     * LONG hit = positive forward return; SHORT hit = negative; NEUTRAL never hits.
     * A |return| below [flatBandPct] counts as flat → not a hit (honest: a
     * +0.0001% drift is not directional proof).
     */
    fun isHit(dominantDir: Int, forwardReturnPct: Double, flatBandPct: Double = 0.01): Boolean = when {
        dominantDir > 0 -> forwardReturnPct > flatBandPct
        dominantDir < 0 -> forwardReturnPct < -flatBandPct
        else -> false
    }

    /** Median of an unsorted sample; null for empty input. Interpolates the two middle values. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Bucket stats for one set of samples. */
    fun stats(samples: List<GradedSample>): EvidenceBucketStats {
        val hits = samples.count { isHit(it.dominantDir, it.forwardReturnPct) }
        val med = median(samples.map { it.forwardReturnPct }) ?: 0.0
        return EvidenceBucketStats(
            samples = samples.size,
            hitRate = if (samples.isEmpty()) 0.0 else hits.toDouble() / samples.size,
            medianReturnPct = med,
        )
    }

    /**
     * Grades a batch: hit-rate + median forward return per verdict bucket and
     * per confidence band. Buckets with zero samples are absent from the maps.
     */
    fun grade(samples: List<GradedSample>): EvidenceGrade = EvidenceGrade(
        graded = samples.size,
        byVerdict = samples.groupBy { it.verdict }
            .mapValues { (_, group) -> stats(group) },
        byConfidence = samples.groupBy { confidenceBucket(it.confidence) }
            .mapValues { (_, group) -> stats(group) },
    )

    /**
     * Forward return in PERCENT from the last candle whose openTime is at or
     * before [recordTs] to the newest candle. Returns null when no candle covers
     * the record (symbol listed later) or the base price is degenerate — the
     * record is then honestly ungradable.
     */
    fun forwardReturnPct(
        candleOpenTimes: List<Long>,
        candleCloses: List<Double>,
        recordTs: Long,
    ): Double? {
        if (candleOpenTimes.size != candleCloses.size || candleCloses.isEmpty()) return null
        var baseIdx = -1
        for (i in candleOpenTimes.indices) {
            if (candleOpenTimes[i] <= recordTs) baseIdx = i else break
        }
        if (baseIdx < 0) return null
        val base = candleCloses[baseIdx]
        val last = candleCloses.last()
        if (base <= 0.0 || abs(base).isInfinite() || last <= 0.0) return null
        return (last - base) / base * 100.0
    }
}
