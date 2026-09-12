package com.diveintocrypto.android.ui.common

import com.diveintocrypto.android.platform.format

/**
 * PURE UI label helpers shared across the campaign screens (no Compose, no
 * clock, no I/O — unit-tested in commonTest). Screens stay dumb renderers:
 * they map the returned color TOKENS ("GREEN"/"RED"/"WARN"/"MUTED") onto
 * [com.diveintocrypto.android.ui.theme.DiveColors] at the call site so every
 * rule stays preset-aware.
 */
object UiLabels {

    // ── Color tokens (stable strings, mapped to theme colors in the UI) ──
    const val TOK_GREEN = "GREEN"
    const val TOK_RED = "RED"
    const val TOK_WARN = "WARN"
    const val TOK_MUTED = "MUTED"

    // ── P&L formatting (Portföy rows) ─────────────────────────────────────

    /** Signed percent with 2 decimals; "—" when unknown (never a fake 0%). */
    fun pnlPercentLabel(pct: Double?): String = when {
        pct == null || !pct.isFinite() -> "—"
        else -> pct.format(2, plus = true) + "%"
    }

    /** Signed quote-currency P&L; "—" when unknown. Uses "," thousands grouping. */
    fun pnlNominalLabel(pnl: Double?): String = when {
        pnl == null || !pnl.isFinite() -> "—"
        // Exact zero renders bare — a "+$0.00" would be noise, not a sign.
        pnl == 0.0 -> "$0.00"
        else -> {
            // Sign BEFORE the currency symbol: "+$1,234.56" / "-$9.99".
            val sign = if (pnl > 0) "+" else "-"
            sign + "$" + kotlin.math.abs(pnl).format(2, grouped = true)
        }
    }

    /** Token for a signed percent: >0 GREEN, <0 RED, else (incl. null) MUTED. */
    fun pnlColorToken(pct: Double?): String = when {
        pct == null || !pct.isFinite() || pct == 0.0 -> TOK_MUTED
        pct > 0 -> TOK_GREEN
        else -> TOK_RED
    }

    // ── Engine-agreement tag (Portföy rows) ───────────────────────────────

    /** "AGREE"→"UYUMLU" · "AGAINST"→"KARŞIT" · anything else/null → "BELİRSİZ". */
    fun agreementTagLabel(tag: String?): String = when (tag) {
        "AGREE" -> "UYUMLU"
        "AGAINST" -> "KARŞIT"
        else -> "BELİRSİZ"
    }

    /** Token for the agreement tag: AGREE GREEN · AGAINST RED · else MUTED. */
    fun agreementColorToken(tag: String?): String = when (tag) {
        "AGREE" -> TOK_GREEN
        "AGAINST" -> TOK_RED
        else -> TOK_MUTED
    }

    // ── Countdown (funding settlement) ────────────────────────────────────

    /**
     * Tabular-numeric countdown: "H:MM:SS" ≥1h, "MM:SS" below.
     * null / negative → "—" (honest: no data, or the moment already passed).
     */
    fun countdownLabel(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        fun p2(n: Long) = n.toString().padStart(2, '0')
        return if (h > 0) "$h:${p2(m)}:${p2(s)}" else "${p2(m)}:${p2(s)}"
    }

    // ── Evidence windows (7G / 30G / TÜMÜ) ────────────────────────────────

    /** "7d"→"7G" · "30d"→"30G" · "all"→"TÜMÜ"; unknown passes through uppercased. */
    fun windowLabel(window: String): String = when (window) {
        "7d" -> "7G"
        "30d" -> "30G"
        "all" -> "TÜMÜ"
        else -> window.uppercase()
    }

    // ── Evidence v2 hit-rate rendering ────────────────────────────────────

    /**
     * "57% [45–89] · n=214" (Wilson 95% bounds, percent, 0-decimals).
     * Rate null / n < 5 → "—" (the grader's display floor — no fake rate).
     * Bounds null (legacy shape) → "57% · n=214".
     */
    fun hitRateLabel(samples: Int, hitRate: Double?, lo: Double?, hi: Double?): String {
        if (hitRate == null || !hitRate.isFinite() || samples < 5) return "—"
        val rate = "${(hitRate * 100).format(0)}%"
        return if (lo != null && hi != null) {
            "$rate [${(lo * 100).format(0)}–${(hi * 100).format(0)}] · n=$samples"
        } else "$rate · n=$samples"
    }

    /**
     * Small-sample honesty note: gated buckets (n in 5..19) render the dim
     * "yetersiz örnek (n<20)" tag; n < 5 the label is already "—" so no note.
     */
    fun smallSampleNote(samples: Int, gated: Boolean): String? =
        if (gated && samples in 5..19) "yetersiz örnek (n<20)" else null

    // ── Calibration badges ────────────────────────────────────────────────

    /** ECE as percent points, 1 decimal; "—" when null (no graded samples). */
    fun eceLabel(ece: Double?): String =
        if (ece == null || !ece.isFinite()) "—" else "${(ece * 100).format(1)}%"

    /** Brier skill with explicit sign; "—" when null (degenerate reference). */
    fun brierSkillLabel(skill: Double?): String =
        if (skill == null || !skill.isFinite()) "—" else skill.format(2, plus = true)

    /**
     * Calibration-bin bar color token from |predicted − observed| gap:
     * < 0.05 GREEN (well calibrated) · < 0.15 WARN · else RED. Empty bin → MUTED.
     */
    fun binGapColorToken(gap: Double?): String = when {
        gap == null -> TOK_MUTED
        gap < 0.05 -> TOK_GREEN
        gap < 0.15 -> TOK_WARN
        else -> TOK_RED
    }

    /**
     * Relative heights (0..1) for the 10-bin mini bar strip, each bin scaled
     * against the busiest bin (all-empty input → all zeros — honest absence).
     */
    fun binBarFractions(samples: List<Int>): List<Float> {
        val max = samples.maxOrNull() ?: return List(10) { 0f }
        if (max <= 0) return List(samples.size) { 0f }
        return samples.map { (it.toFloat() / max).coerceIn(0f, 1f) }
    }

    // ── Funding / cone captions ───────────────────────────────────────────

    /** Funding regime from the PREDICTED rate (percent per 8h): NÖTR ±0.01 band. */
    fun fundingRegimeLabel(predictedRatePct: Double): String = when {
        predictedRatePct > 0.01 -> "POZİTİF"
        predictedRatePct < -0.01 -> "NEGATİF"
        else -> "NÖTR"
    }

    /** Honest vol-cone caption — a projection from history, never a prediction. */
    const val CONE_CAPTION: String =
        "√t ölçekli vol projeksiyonu · geçmiş σ'dan — yön/fiyat tahmini değil"
}
