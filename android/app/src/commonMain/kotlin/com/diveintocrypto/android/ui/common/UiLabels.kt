package com.diveintocrypto.android.ui.common

import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.i18n.DiveStrings

/**
 * PURE UI label helpers shared across the campaign screens (no Compose, no
 * clock, no I/O — unit-tested in commonTest). Screens stay dumb renderers:
 * they map the returned color TOKENS ("GREEN"/"RED"/"WARN"/"MUTED") onto
 * [com.diveintocrypto.android.ui.theme.DiveColors] at the call site so every
 * rule stays preset-aware.
 *
 * Every visible word comes from the [DiveStrings] catalog passed in (TR/EN);
 * numeric output is language-neutral.
 */
object UiLabels {

    // ── Color tokens (stable strings, mapped to theme colors in the UI) ──
    const val TOK_GREEN = "GREEN"
    const val TOK_RED = "RED"
    const val TOK_WARN = "WARN"
    const val TOK_MUTED = "MUTED"

    // ── P&L formatting (Portföy rows) ─────────────────────────────────────

    /**
     * True when [x] rounds to zero at [decimals] places (HALF_UP, same rule as
     * [format]). Sign and colour are decided AFTER this, so a value that prints as
     * "0.00" never carries a "+" or "-" and never gets a RED/GREEN token.
     */
    private fun roundsToZero(x: Double, decimals: Int): Boolean {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return kotlin.math.abs(x) * factor < 0.5
    }

    /** Signed percent with 2 decimals; "—" when unknown (never a fake 0%). */
    fun pnlPercentLabel(pct: Double?): String = when {
        pct == null || !pct.isFinite() -> "—"
        roundsToZero(pct, 2) -> "0.00%"
        else -> pct.format(2, plus = true) + "%"
    }

    /** Signed quote-currency P&L; "—" when unknown. Uses "," thousands grouping. */
    fun pnlNominalLabel(pnl: Double?): String = when {
        pnl == null || !pnl.isFinite() -> "—"
        // Rounds to zero at cents (incl. -0.004): a bare "$0.00", never "+$0.00" / "-$0.00".
        roundsToZero(pnl, 2) -> "$0.00"
        else -> {
            // Sign BEFORE the currency symbol: "+$1,234.56" / "-$9.99".
            val sign = if (pnl > 0) "+" else "-"
            sign + "$" + kotlin.math.abs(pnl).format(2, grouped = true)
        }
    }

    /**
     * Token for a signed value: MUTED when it rounds to zero at 2 decimals (matches the
     * label, so "0.00%" is never coloured), GREEN when positive, RED when negative.
     */
    fun pnlColorToken(pct: Double?): String = when {
        pct == null || !pct.isFinite() || roundsToZero(pct, 2) -> TOK_MUTED
        pct > 0 -> TOK_GREEN
        else -> TOK_RED
    }

    /**
     * Price text for scanner/live-price cells, numeric part only (caller adds "$").
     * Decimals follow magnitude so sub-cent contracts stay readable:
     *   ≥ 1 → 2 decimals (grouped) · ≥ 0.01 → 4 · ≥ 0.0001 → 6 · smaller → 8.
     * Anything that still rounds to zero at 8 decimals shows "<0.00000001".
     */
    fun priceLabel(price: Double?): String {
        if (price == null || !price.isFinite()) return "—"
        val mag = kotlin.math.abs(price)
        return when {
            mag >= 1.0 -> price.format(2, grouped = true)
            mag >= 0.01 -> price.format(4, grouped = true)
            mag >= 0.0001 -> price.format(6)
            roundsToZero(price, 8) && mag != 0.0 -> "<0.00000001"
            else -> price.format(8)
        }
    }

    // ── Engine-agreement tag (Portföy rows) ───────────────────────────────

    /** "AGREE" → agreeTag · "AGAINST" → againstTag · anything else/null → unclearTag. */
    fun agreementTagLabel(tag: String?, s: DiveStrings): String = when (tag) {
        "AGREE" -> s.agreeTag
        "AGAINST" -> s.againstTag
        else -> s.unclearTag
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
     * Purely numeric: it carries no words, so it needs no catalog.
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

    /** "7d" → window7d · "30d" → window30d · "all" → windowAll; unknown passes through uppercased. */
    fun windowLabel(window: String, s: DiveStrings): String = when (window) {
        "7d" -> s.window7d
        "30d" -> s.window30d
        "all" -> s.windowAll
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
     * small-sample tag (i18nSmallSampleNote); n < 5 the label is already "—" so no note.
     */
    fun smallSampleNote(samples: Int, gated: Boolean, s: DiveStrings): String? =
        if (gated && samples in 5..19) s.i18nSmallSampleNote else null

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

    /** Funding regime from the PREDICTED rate (percent per 8h): NEUTRAL ±0.01 band. */
    fun fundingRegimeLabel(predictedRatePct: Double, s: DiveStrings): String = when {
        predictedRatePct > 0.01 -> s.i18nFundingPositive
        predictedRatePct < -0.01 -> s.i18nFundingNegative
        else -> s.i18nFundingNeutral
    }

    /** Honest vol-cone caption — a projection from history, never a prediction. */
    fun coneCaption(s: DiveStrings): String = s.i18nConeCaption
}
