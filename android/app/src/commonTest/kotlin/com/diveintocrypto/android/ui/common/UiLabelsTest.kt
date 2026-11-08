package com.diveintocrypto.android.ui.common

import com.diveintocrypto.android.ui.i18n.EnStrings
import com.diveintocrypto.android.ui.i18n.TrStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PURE UI label tests (lane 5) — P&L formatters, countdown, window labels,
 * Wilson hit-rate rendering, calibration badges and the agreement-tag color
 * mapping. No Compose, no clock, no I/O. Wording comes from the DiveStrings
 * catalog, so each word-bearing label is checked in both TR and EN.
 */
class UiLabelsTest {

    // ── P&L formatters ────────────────────────────────────────────────

    @Test
    fun pnlPercentFormatsSignedWithTwoDecimals() {
        assertEquals("+12.34%", UiLabels.pnlPercentLabel(12.341))
        assertEquals("-3.20%", UiLabels.pnlPercentLabel(-3.2))
        // Rounds to zero at 2 decimals → bare zero, no sign (sign follows the rounded value).
        assertEquals("0.00%", UiLabels.pnlPercentLabel(0.001))
        assertEquals("0.00%", UiLabels.pnlPercentLabel(-0.001))
    }

    @Test
    fun pnlSignNeverContradictsRoundedLabelOrColor() {
        // Tiny negative values: label has no minus, colour is MUTED (not RED).
        assertEquals("0.00%", UiLabels.pnlPercentLabel(-0.001))
        assertEquals(UiLabels.TOK_MUTED, UiLabels.pnlColorToken(-0.001))
        assertEquals("$0.00", UiLabels.pnlNominalLabel(-0.004))
        assertEquals("$0.00", UiLabels.pnlNominalLabel(0.004))
        assertEquals(UiLabels.TOK_MUTED, UiLabels.pnlColorToken(0.004))
        // Just above the rounding boundary keeps its sign and colour.
        assertEquals("-0.01%", UiLabels.pnlPercentLabel(-0.01))
        assertEquals(UiLabels.TOK_RED, UiLabels.pnlColorToken(-0.01))
    }

    @Test
    fun priceLabelScalesDecimalsWithMagnitude() {
        assertEquals("1,234.57", UiLabels.priceLabel(1234.5678))
        assertEquals("0.5000", UiLabels.priceLabel(0.5))
        assertEquals("0.0123", UiLabels.priceLabel(0.0123456))
        assertEquals("0.000123", UiLabels.priceLabel(0.00012345678))
        assertEquals("0.00001235", UiLabels.priceLabel(0.0000123456))
        assertEquals("<0.00000001", UiLabels.priceLabel(1e-12))
        assertEquals("—", UiLabels.priceLabel(null))
        assertEquals("—", UiLabels.priceLabel(Double.NaN))
    }

    @Test
    fun pnlPercentIsHonestWhenUnknown() {
        assertEquals("—", UiLabels.pnlPercentLabel(null))
        assertEquals("—", UiLabels.pnlPercentLabel(Double.NaN))
    }

    @Test
    fun pnlNominalFormatsSignedDollars() {
        assertEquals("+$1,234.56", UiLabels.pnlNominalLabel(1234.56))
        assertEquals("-$9.99", UiLabels.pnlNominalLabel(-9.99))
        assertEquals("$0.00", UiLabels.pnlNominalLabel(0.0))
        assertEquals("—", UiLabels.pnlNominalLabel(null))
    }

    // ── Agreement tag colors ──────────────────────────────────────────

    @Test
    fun agreementTagLabelMapsTr() {
        assertEquals("UYUMLU", UiLabels.agreementTagLabel("AGREE", TrStrings))
        assertEquals("KARŞIT", UiLabels.agreementTagLabel("AGAINST", TrStrings))
        assertEquals("BELİRSİZ", UiLabels.agreementTagLabel("UNKNOWN", TrStrings))
        assertEquals("BELİRSİZ", UiLabels.agreementTagLabel(null, TrStrings))
    }

    @Test
    fun agreementTagLabelMapsEn() {
        assertEquals("AGREE", UiLabels.agreementTagLabel("AGREE", EnStrings))
        assertEquals("AGAINST", UiLabels.agreementTagLabel("AGAINST", EnStrings))
        assertEquals("UNCLEAR", UiLabels.agreementTagLabel(null, EnStrings))
    }

    @Test
    fun agreementColorTokenMapsGreenRedMuted() {
        assertEquals(UiLabels.TOK_GREEN, UiLabels.agreementColorToken("AGREE"))
        assertEquals(UiLabels.TOK_RED, UiLabels.agreementColorToken("AGAINST"))
        assertEquals(UiLabels.TOK_MUTED, UiLabels.agreementColorToken("UNKNOWN"))
        assertEquals(UiLabels.TOK_MUTED, UiLabels.agreementColorToken(null))
    }

    @Test
    fun pnlColorTokenFollowsSign() {
        assertEquals(UiLabels.TOK_GREEN, UiLabels.pnlColorToken(1.0))
        assertEquals(UiLabels.TOK_RED, UiLabels.pnlColorToken(-0.5))
        assertEquals(UiLabels.TOK_MUTED, UiLabels.pnlColorToken(0.0))
        assertEquals(UiLabels.TOK_MUTED, UiLabels.pnlColorToken(null))
    }

    // ── Countdown ─────────────────────────────────────────────────────

    @Test
    fun countdownFormatsTabularAndHonest() {
        assertEquals("—", UiLabels.countdownLabel(null))
        assertEquals("—", UiLabels.countdownLabel(-5))
        assertEquals("00:59", UiLabels.countdownLabel(59))
        assertEquals("02:05", UiLabels.countdownLabel(125))
        assertEquals("1:02:03", UiLabels.countdownLabel(3723))
    }

    // ── Evidence windows ──────────────────────────────────────────────

    @Test
    fun windowLabelsMapTr() {
        assertEquals("7G", UiLabels.windowLabel("7d", TrStrings))
        assertEquals("30G", UiLabels.windowLabel("30d", TrStrings))
        assertEquals("TÜMÜ", UiLabels.windowLabel("all", TrStrings))
        assertEquals("X", UiLabels.windowLabel("x", TrStrings))
    }

    @Test
    fun windowLabelsMapEn() {
        assertEquals("7D", UiLabels.windowLabel("7d", EnStrings))
        assertEquals("30D", UiLabels.windowLabel("30d", EnStrings))
        assertEquals("ALL", UiLabels.windowLabel("all", EnStrings))
    }

    // ── Wilson hit-rate rendering (Evidence v2) ───────────────────────

    @Test
    fun hitRateLabelRendersWilsonInterval() {
        assertEquals(
            "57% [45–89] · n=214",
            UiLabels.hitRateLabel(214, 0.5712, 0.449, 0.891),
        )
    }

    @Test
    fun hitRateLabelFloorsBelowFiveSamples() {
        assertEquals("—", UiLabels.hitRateLabel(4, 0.75, 0.2, 0.9))
        assertEquals("—", UiLabels.hitRateLabel(10, null, null, null))
        // Legacy shape (no bounds) still renders the rate.
        assertEquals("60% · n=20", UiLabels.hitRateLabel(20, 0.6, null, null))
    }

    @Test
    fun smallSampleNoteOnlyForGatedBuckets() {
        assertEquals("yetersiz örnek (n<20)", UiLabels.smallSampleNote(7, gated = true, s = TrStrings))
        assertEquals("small sample (n<20)", UiLabels.smallSampleNote(7, gated = true, s = EnStrings))
        assertEquals(null, UiLabels.smallSampleNote(25, gated = false, s = TrStrings))
        assertEquals(null, UiLabels.smallSampleNote(3, gated = true, s = TrStrings)) // label is already "—"
    }

    // ── Calibration badges ────────────────────────────────────────────

    @Test
    fun eceAndBrierSkillLabels() {
        assertEquals("3.2%", UiLabels.eceLabel(0.032))
        assertEquals("—", UiLabels.eceLabel(null))
        assertEquals("+0.12", UiLabels.brierSkillLabel(0.123))
        assertEquals("-0.05", UiLabels.brierSkillLabel(-0.051))
        assertEquals("—", UiLabels.brierSkillLabel(null))
    }

    @Test
    fun binBarFractionsScaleToBusiestBin() {
        val f = UiLabels.binBarFractions(listOf(0, 5, 10))
        assertEquals(0f, f[0])
        assertEquals(0.5f, f[1])
        assertEquals(1f, f[2])
        assertTrue(UiLabels.binBarFractions(List(10) { 0 }).all { it == 0f })
    }

    // ── Funding regime ────────────────────────────────────────────────

    @Test
    fun fundingRegimeBandsAroundNeutral() {
        assertEquals("POZİTİF", UiLabels.fundingRegimeLabel(0.02, TrStrings))
        assertEquals("NEGATİF", UiLabels.fundingRegimeLabel(-0.02, TrStrings))
        // The ±0.01%/8h band (inclusive) stays neutral.
        assertEquals("NÖTR", UiLabels.fundingRegimeLabel(0.01, TrStrings))
        assertEquals("NÖTR", UiLabels.fundingRegimeLabel(-0.01, TrStrings))
        assertEquals("NÖTR", UiLabels.fundingRegimeLabel(0.005, TrStrings))
    }

    @Test
    fun fundingRegimeBandsEn() {
        assertEquals("POSITIVE", UiLabels.fundingRegimeLabel(0.02, EnStrings))
        assertEquals("NEGATIVE", UiLabels.fundingRegimeLabel(-0.02, EnStrings))
        assertEquals("NEUTRAL", UiLabels.fundingRegimeLabel(0.005, EnStrings))
    }

    // ── Cone caption ──────────────────────────────────────────────────

    @Test
    fun coneCaptionFollowsLanguage() {
        assertEquals(TrStrings.i18nConeCaption, UiLabels.coneCaption(TrStrings))
        assertEquals(EnStrings.i18nConeCaption, UiLabels.coneCaption(EnStrings))
        assertTrue(UiLabels.coneCaption(TrStrings) != UiLabels.coneCaption(EnStrings))
    }
}
