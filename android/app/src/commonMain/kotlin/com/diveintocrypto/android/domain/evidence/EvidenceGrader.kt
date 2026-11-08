package com.diveintocrypto.android.domain.evidence

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Aggregate grading stats for one bucket (a verdict class or a confidence band).
 *
 * v2 (0.3.0) adds the Wilson 95% score interval around the hit rate plus the
 * small-sample GATES:
 *   - the gates count DIRECTIONAL samples only (LONG/SHORT). NEUTRAL samples make no
 *     claim: they are excluded from the rate, not counted as misses;
 *   - `directional < [EvidenceGrader.MIN_BUCKET_N]` (5) → [hitRate] is `null`
 *     (too few directional records to even display a rate honestly) and both
 *     interval bounds are `null`;
 *   - a bucket with no directional samples (all NEUTRAL) → [hitRate] is `null`
 *     as well: there is no hit to count;
 *   - `directional < [EvidenceGrader.UNGATED_MIN_N]` (20) → [gated] is `true`
 *     (the rate and its wide interval are shown but flagged low-confidence).
 *
 * @param samples all graded records in the bucket, NEUTRAL included
 * @param hitRate share of the DIRECTIONAL records whose forward move agreed with the verdict
 *   direction (0..1); `null` when the bucket is gated below the display floor
 * @param medianReturnPct median forward return, percent (signed)
 * @param hitRateLo Wilson 95% lower bound (0..1); `null` while gated below the floor
 * @param hitRateHi Wilson 95% upper bound (0..1); `null` while gated below the floor
 * @param gated true when the interval is too wide to trust ([samples] < 20)
 */
data class EvidenceBucketStats(
    val samples: Int,
    val hitRate: Double?,
    val medianReturnPct: Double,
    val hitRateLo: Double? = null,
    val hitRateHi: Double? = null,
    val gated: Boolean = true,
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
        /** Record wall-clock ms (for the rolling-window lens; 0 = unknown → "all" only). */
        val ts: Long = 0L,
    )

    /** Wilson 95% z-score. */
    const val WILSON_Z: Double = 1.96

    /** Below this many graded samples a bucket shows NO hit rate at all (null). */
    const val MIN_BUCKET_N: Int = 5

    /** Below this many graded samples a bucket's interval is flagged [EvidenceBucketStats.gated]. */
    const val UNGATED_MIN_N: Int = 20

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

    /**
     * PURE Wilson score interval (95%, z=[WILSON_Z]) around a hit ratio.
     * Returns null bounds for degenerate input (n <= 0). Known-value anchor:
     * p̂=0.5, n=10 → [0.2366, 0.7634] (to rounding).
     */
    fun wilsonInterval(hits: Int, n: Int, z: Double = WILSON_Z): Pair<Double, Double>? {
        if (n <= 0) return null
        val p = hits.toDouble() / n
        val z2 = z * z
        val denom = 1.0 + z2 / n
        val center = (p + z2 / (2 * n)) / denom
        val half = (z / denom) * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))
        return (center - half).coerceIn(0.0, 1.0) to (center + half).coerceIn(0.0, 1.0)
    }

    /**
     * Bucket stats for one set of samples (hit rate + Wilson interval + gates). The hit rate, its
     * interval and the gates count only DIRECTIONAL samples (LONG/SHORT). NEUTRAL samples make no
     * claim, so they are left out of the denominator instead of being counted as misses.
     * [EvidenceBucketStats.samples] stays the total graded in the bucket.
     */
    fun stats(samples: List<GradedSample>): EvidenceBucketStats {
        val total = samples.size
        val med = median(samples.map { it.forwardReturnPct }) ?: 0.0
        val directional = samples.filter { it.dominantDir != 0 }
        val n = directional.size
        // Nothing directional to score (empty, or all NEUTRAL): no hit rate, not a structural 0%.
        if (n == 0) {
            return EvidenceBucketStats(
                samples = total,
                hitRate = null,
                medianReturnPct = med,
                hitRateLo = null,
                hitRateHi = null,
                gated = total < UNGATED_MIN_N,
            )
        }
        if (n < MIN_BUCKET_N) {
            return EvidenceBucketStats(
                samples = total,
                hitRate = null,
                medianReturnPct = med,
                hitRateLo = null,
                hitRateHi = null,
                gated = true,
            )
        }
        val hits = directional.count { isHit(it.dominantDir, it.forwardReturnPct) }
        val (lo, hi) = wilsonInterval(hits, n)
            ?: return EvidenceBucketStats(total, null, med, null, null, true)
        return EvidenceBucketStats(
            samples = total,
            hitRate = hits.toDouble() / n,
            medianReturnPct = med,
            hitRateLo = lo,
            hitRateHi = hi,
            gated = n < UNGATED_MIN_N,
        )
    }

    /**
     * Grades a batch: hit-rate (+ Wilson interval, + gates) and median forward
     * return per verdict bucket and per confidence band. Buckets with zero
     * samples are absent from the maps.
     */
    fun grade(samples: List<GradedSample>): EvidenceGrade = EvidenceGrade(
        graded = samples.size,
        byVerdict = samples.groupBy { it.verdict }
            .mapValues { (_, group) -> stats(group) },
        byConfidence = samples.groupBy { confidenceBucket(it.confidence) }
            .mapValues { (_, group) -> stats(group) },
    )

    // ── Calibration (ECE) ─────────────────────────────────────────────────────

    /** One calibration bin over the confidence axis. */
    data class CalibrationBin(
        /** Inclusive lower confidence bound of the bin. */
        val confLo: Int,
        /** Inclusive upper confidence bound of the bin. */
        val confHi: Int,
        val samples: Int,
        /** Mean stated confidence in the bin, 0..1 (predicted probability). */
        val predicted: Double?,
        /** Observed hit rate in the bin, 0..1. */
        val observed: Double?,
    ) {
        /** |predicted − observed| weighted by the bin's share of all samples. */
        val gap: Double?
            get() = if (predicted == null || observed == null) null else abs(predicted - observed)
    }

    /** Expected Calibration Error result: fixed 10-bin reliability table + scalar ECE. */
    data class EceResult(
        /** All [ECE_BINS] bins in confidence order (empty bins included, samples=0). */
        val bins: List<CalibrationBin>,
        /** Σ (n_bin / N) · |pred − obs| over non-empty bins; null when no samples. */
        val ece: Double?,
    )

    /** Number of equal-width confidence bins used for the reliability table. */
    const val ECE_BINS: Int = 10

    /**
     * PURE calibration: predicted probability = stated confidence / 100,
     * observed outcome = [isHit] as 0/1. Bins are fixed equal-width bands of 10
     * confidence points ([ECE_BINS] bins). NEUTRAL samples (dominantDir = 0)
     * never hit by definition, so they count with observed 0 — an honest
     * penalty for predicting while being directionless.
     */
    fun ece(samples: List<GradedSample>): EceResult {
        if (samples.isEmpty()) {
            return EceResult(
                bins = (0 until ECE_BINS).map { k ->
                    CalibrationBin(k * 10, k * 10 + 9, 0, null, null)
                },
                ece = null,
            )
        }
        val width = 100.0 / ECE_BINS
        val byBin = samples.groupBy { s ->
            (s.confidence / width).toInt().coerceIn(0, ECE_BINS - 1)
        }
        var weightedGap = 0.0
        val bins = (0 until ECE_BINS).map { k ->
            val group = byBin[k].orEmpty()
            val nBin = group.size
            val predicted = if (nBin == 0) null else group.sumOf { it.confidence.toDouble() } / nBin / 100.0
            val observed = if (nBin == 0) null else {
                group.count { isHit(it.dominantDir, it.forwardReturnPct) }.toDouble() / nBin
            }
            if (nBin > 0) weightedGap += (nBin.toDouble() / samples.size) * abs(predicted!! - observed!!)
            CalibrationBin(
                confLo = (k * width).toInt(),
                confHi = (((k + 1) * width).toInt() - 1).coerceAtMost(99).let { if (k == ECE_BINS - 1) 100 else it },
                samples = nBin,
                predicted = predicted,
                observed = observed,
            )
        }
        return EceResult(bins, weightedGap)
    }

    // ── Brier score + skill vs the base rate ─────────────────────────────────

    /** Brier result: score (0 = perfect, 0.25 = coin-flip at p=0.5) + skill vs base rate. */
    data class BrierResult(
        /** Mean squared error of stated confidence (0..1) vs the 0/1 outcome. */
        val brier: Double,
        /** Overall hit share (the reference forecast). */
        val baseRate: Double,
        /** Brier of the constant base-rate forecast. */
        val brierBase: Double,
        /** 1 − brier/brierBase; >0 = better than always guessing the base rate.
         *  null when the reference forecast is degenerate (brierBase = 0). */
        val skill: Double?,
    )

    /**
     * PURE Brier score of the stated confidences against the 0/1 directional
     * outcomes, plus the Brier Skill Score against the constant base-rate
     * forecast (the honest reference: "what if you always predicted the
     * historical hit share?").
     */
    fun brier(samples: List<GradedSample>): BrierResult? {
        if (samples.isEmpty()) return null
        val outcomes = samples.map { if (isHit(it.dominantDir, it.forwardReturnPct)) 1.0 else 0.0 }
        val brier = outcomes.mapIndexed { i, o ->
            val p = samples[i].confidence / 100.0
            (p - o) * (p - o)
        }.average()
        val baseRate = outcomes.average()
        val brierBase = outcomes.sumOf { o -> (baseRate - o) * (baseRate - o) } / outcomes.size
        val skill = if (brierBase <= 0.0) null else 1.0 - brier / brierBase
        return BrierResult(brier, baseRate, brierBase, skill)
    }

    // ── Rolling windows ───────────────────────────────────────────────────────

    /** Rolling-window labels (newest record age relative to [gradeWindows]' nowMs). */
    const val WINDOW_7D = "7d"
    const val WINDOW_30D = "30d"
    const val WINDOW_ALL = "all"

    /** Day in ms. */
    const val DAY_MS: Long = 86_400_000L

    /**
     * PURE rolling-window grading: one [EvidenceGrade] per window over the
     * samples whose `ts` falls inside `nowMs − window` (the "all" window covers
     * everything). Sample ordering does not matter.
     */
    fun gradeWindows(samples: List<GradedSample>, nowMs: Long): Map<String, EvidenceGrade> = mapOf(
        WINDOW_7D to grade(samples.filter { nowMs - it.ts <= 7 * DAY_MS }),
        WINDOW_30D to grade(samples.filter { nowMs - it.ts <= 30 * DAY_MS }),
        WINDOW_ALL to grade(samples),
    )

    /** Candle interval of the klines the Performance screen grades against (1h). */
    const val GRADE_CANDLE_MS: Long = 3_600_000L

    /**
     * Forward return in PERCENT, measured over exactly one horizon:
     *   - the BASE is [basePrice], the verdict-time price archived with the record
     *     (no look-ahead: candle closes after the record are never used as a base);
     *   - only candles that OPEN after [recordTs] are considered. The candle that contains the
     *     verdict timestamp is never graded on: its close is partly before the verdict;
     *   - the END is the close of the candle that COVERS `recordTs + horizonMs`
     *     (open ≤ target < open + [candleMs]). Its close lies at most one candle
     *     interval after the target. That candle must also be CLOSED at [nowMs]: a candle
     *     still forming has a provisional close, which must not be stored as final.
     * Returns null — pending, retried on a later pass — when the base is degenerate, the
     * horizon candle is not in the supplied window yet, it is still open at [nowMs], or the
     * candle data is malformed.
     */
    fun forwardReturnPct(
        candleOpenTimes: List<Long>,
        candleCloses: List<Double>,
        recordTs: Long,
        basePrice: Double,
        horizonMs: Long,
        nowMs: Long,
        candleMs: Long = GRADE_CANDLE_MS,
    ): Double? {
        if (candleOpenTimes.size != candleCloses.size || candleCloses.isEmpty()) return null
        if (basePrice <= 0.0 || basePrice.isNaN() || basePrice.isInfinite()) return null
        if (horizonMs <= 0L || candleMs <= 0L) return null
        val targetTs = recordTs + horizonMs
        var endIdx = -1
        for (i in candleOpenTimes.indices) {
            val open = candleOpenTimes[i]
            if (open <= recordTs) continue // the verdict's own candle, and anything before it, is never graded on
            if (open <= targetTs) endIdx = i else break
        }
        if (endIdx < 0) return null
        val endOpen = candleOpenTimes[endIdx]
        // The candle must cover the target instant; otherwise the horizon has not
        // been reached by the supplied candles yet.
        if (endOpen + candleMs <= targetTs) return null
        // The horizon candle must have closed by now; a forming candle is still pending.
        if (endOpen + candleMs > nowMs) return null
        val end = candleCloses[endIdx]
        if (end <= 0.0 || end.isNaN() || end.isInfinite()) return null
        return (end - basePrice) / basePrice * 100.0
    }
}
