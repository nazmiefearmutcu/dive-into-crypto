package com.diveintocrypto.android.domain.overlay

import kotlin.math.abs

/**
 * Multi-Timeframe Confluence — strategy overlay. Faithful port of the Python
 * reference `scan/mtf.py`.
 *
 * Scores agreement across the per-timeframe verdicts, weighting higher timeframes
 * more and scaling by each TF's confidence. Surfaces a signed score, the dominant
 * direction, the higher-TF agreement fraction, and a boolean gate. ADDITIVE — does
 * not change any verdict.
 */
object MtfConfluence {

    data class TfVerdict(val tf: String, val signal: String, val confidence: Int)

    data class Result(
        val score: Double,       // signed −100..+100
        val direction: Int,      // +1 / −1 / 0
        val gate: Boolean,       // higher-TF stack agrees
        val htfAgree: Double,    // 0..1
        val label: String,       // STRONG | WEAK | NEUTRAL
    )

    private val TF_WEIGHT = mapOf(
        "1m" to 1, "3m" to 1, "5m" to 2, "15m" to 3, "30m" to 4, "1h" to 6,
        "2h" to 7, "4h" to 9, "6h" to 10, "8h" to 11, "12h" to 12, "1d" to 14,
    )
    private val HTF = setOf("1h", "2h", "4h", "6h", "8h", "12h", "1d")

    private fun dir(sig: String): Int = when {
        sig.contains("BUY") -> 1
        sig.contains("SELL") -> -1
        else -> 0
    }

    fun confluence(multiTf: List<TfVerdict>, minGate: Double = 0.6): Result {
        var wsum = 0.0
        var acc = 0.0
        val htfDirs = ArrayList<Int>()
        for (m in multiTf) {
            val d = dir(m.signal)
            val conf = m.confidence.coerceAtLeast(0)
            val w = (TF_WEIGHT[m.tf] ?: 1).toDouble()
            acc += d * w * (0.5 + 0.5 * minOf(conf.toDouble(), 100.0) / 100.0)
            wsum += w
            if (m.tf in HTF) htfDirs += d
        }
        if (wsum == 0.0) return Result(0.0, 0, false, 0.0, "NEUTRAL")

        val score = kotlin.math.round(acc / wsum * 1000.0) / 10.0
        val dom = if (score > 0) 1 else if (score < 0) -1 else 0
        val nonzero = htfDirs.filter { it != 0 }
        val htfAgree = if (nonzero.isEmpty()) 0.0
            else nonzero.count { it == dom }.toDouble() / nonzero.size
        val gate = htfAgree >= minGate && dom != 0
        val mag = abs(score)
        val label = if (mag >= 55) "STRONG" else if (mag >= 20) "WEAK" else "NEUTRAL"
        return Result(score, dom, gate, kotlin.math.round(htfAgree * 100.0) / 100.0, label)
    }
}
