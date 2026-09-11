package com.diveintocrypto.android.ui.alerts

import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the alert-UI helpers (UI lane): Turkish kind/direction
 * labels, fired-history line building, threshold parse/format/step/clamp and
 * the honest ETA / universe labels on the Scanner depth row. No Compose, no
 * clock, no engine state.
 */
class AlertLabelsTest {

    private fun rule(
        kind: AlertKind,
        direction: String = AlertRule.DIRECTION_ANY,
        threshold: Double = 0.0,
    ) = AlertRule(
        id = "r1",
        symbol = "BTCUSDT",
        kind = kind,
        direction = direction,
        threshold = threshold,
        oneShot = false,
        enabled = true,
        createdTs = 0L,
    )

    // ── Labels ───────────────────────────────────────────────────────

    @Test
    fun `kind labels are the TR chips the add-sheet shows`() {
        assertEquals("KARAR YÖNÜ", AlertLabels.kindLabel(AlertKind.VERDICT))
        assertEquals("GÜVEN ÜSTÜ", AlertLabels.kindLabel(AlertKind.CONFIDENCE_ABOVE))
        assertEquals("FİYAT ÜSTÜ", AlertLabels.kindLabel(AlertKind.PRICE_ABOVE))
        assertEquals("FİYAT ALT", AlertLabels.kindLabel(AlertKind.PRICE_BELOW))
        assertEquals("OI ARTIŞI", AlertLabels.kindLabel(AlertKind.OI_SPIKE_PCT))
    }

    @Test
    fun `direction labels map ANY to TUMU`() {
        assertEquals("TÜMÜ", AlertLabels.directionLabel(AlertRule.DIRECTION_ANY))
        assertEquals("LONG", AlertLabels.directionLabel(AlertRule.DIRECTION_LONG))
        assertEquals("SHORT", AlertLabels.directionLabel(AlertRule.DIRECTION_SHORT))
        assertEquals("WEIRD", AlertLabels.directionLabel("WEIRD")) // pass-through
    }

    @Test
    fun `rule line labels match the fired-history copy`() {
        assertEquals("LONG kararı", AlertLabels.ruleLineLabel(rule(AlertKind.VERDICT, AlertRule.DIRECTION_LONG)))
        assertEquals("SHORT kararı", AlertLabels.ruleLineLabel(rule(AlertKind.VERDICT, AlertRule.DIRECTION_SHORT)))
        assertEquals("karar · tümü", AlertLabels.ruleLineLabel(rule(AlertKind.VERDICT, AlertRule.DIRECTION_ANY)))
        assertEquals("güven ≥ 87", AlertLabels.ruleLineLabel(rule(AlertKind.CONFIDENCE_ABOVE, threshold = 87.0)))
        assertEquals("fiyat ≥ 65000.00", AlertLabels.ruleLineLabel(rule(AlertKind.PRICE_ABOVE, threshold = 65000.0)))
        assertEquals("fiyat ≤ 0.5000", AlertLabels.ruleLineLabel(rule(AlertKind.PRICE_BELOW, threshold = 0.5)))
        assertEquals("OI ≥ +5%", AlertLabels.ruleLineLabel(rule(AlertKind.OI_SPIKE_PCT, threshold = 5.0)))
    }

    @Test
    fun `deleted rule renders the honest silindi label`() {
        assertEquals("kural silindi", AlertLabels.ruleLineLabel(null))
    }

    // ── Threshold parsing ────────────────────────────────────────────

    @Test
    fun `parseThreshold accepts dot and comma decimals`() {
        assertEquals(12.5, AlertLabels.parseThreshold("12.5")!!, 1e-9)
        assertEquals(12.5, AlertLabels.parseThreshold("12,5")!!, 1e-9)
        assertEquals(87.0, AlertLabels.parseThreshold(" 87 ")!!, 1e-9)
        assertEquals(0.0005, AlertLabels.parseThreshold("0.0005")!!, 1e-12)
    }

    @Test
    fun `parseThreshold rejects blank garbage and non-finite values`() {
        assertNull(AlertLabels.parseThreshold(""))
        assertNull(AlertLabels.parseThreshold("   "))
        assertNull(AlertLabels.parseThreshold("abc"))
        assertNull(AlertLabels.parseThreshold("NaN"))
        assertNull(AlertLabels.parseThreshold("Infinity"))
    }

    @Test
    fun `default threshold text re-seeds when the kind changes`() {
        assertEquals("0", AlertLabels.defaultThresholdText(AlertKind.VERDICT, null))
        assertEquals("60", AlertLabels.defaultThresholdText(AlertKind.CONFIDENCE_ABOVE, null))
        assertEquals("5", AlertLabels.defaultThresholdText(AlertKind.OI_SPIKE_PCT, null))
        assertEquals("65432.10", AlertLabels.defaultThresholdText(AlertKind.PRICE_ABOVE, 65432.10))
        assertEquals("0", AlertLabels.defaultThresholdText(AlertKind.PRICE_BELOW, null))
    }

    // ── Stepper + clamp ──────────────────────────────────────────────

    @Test
    fun `price steps scale with magnitude`() {
        assertEquals(0.0005, AlertLabels.priceStepFor(0.003), 1e-12)
        assertEquals(0.05, AlertLabels.priceStepFor(0.5), 1e-12)
        assertEquals(1.0, AlertLabels.priceStepFor(50.0), 1e-12)
        assertEquals(10.0, AlertLabels.priceStepFor(500.0), 1e-12)
        assertEquals(100.0, AlertLabels.priceStepFor(60000.0), 1e-12)
        assertEquals(1.0, AlertLabels.priceStepFor(0.0)) // guard
    }

    @Test
    fun `threshold step follows the kind`() {
        assertEquals(5.0, AlertLabels.thresholdStepFor(AlertKind.CONFIDENCE_ABOVE, 60.0), 1e-12)
        assertEquals(1.0, AlertLabels.thresholdStepFor(AlertKind.OI_SPIKE_PCT, 5.0), 1e-12)
        assertEquals(100.0, AlertLabels.thresholdStepFor(AlertKind.PRICE_ABOVE, 60000.0), 1e-12)
    }

    @Test
    fun `clamp keeps each kind in its valid band`() {
        assertEquals(0.0, AlertLabels.clampThreshold(AlertKind.VERDICT, 99.0), 1e-12)
        assertEquals(100.0, AlertLabels.clampThreshold(AlertKind.CONFIDENCE_ABOVE, 150.0), 1e-12)
        assertEquals(0.0, AlertLabels.clampThreshold(AlertKind.CONFIDENCE_ABOVE, -5.0), 1e-12)
        assertEquals(1000.0, AlertLabels.clampThreshold(AlertKind.OI_SPIKE_PCT, 5000.0), 1e-12)
        assertEquals(0.0, AlertLabels.clampThreshold(AlertKind.PRICE_ABOVE, -1.0), 1e-12)
    }

    @Test
    fun `only VERDICT rules can skip the threshold`() {
        assertFalse(AlertLabels.thresholdRequired(AlertKind.VERDICT))
        assertTrue(AlertLabels.thresholdRequired(AlertKind.CONFIDENCE_ABOVE))
        assertTrue(AlertLabels.thresholdRequired(AlertKind.PRICE_ABOVE))
        assertTrue(AlertLabels.thresholdRequired(AlertKind.PRICE_BELOW))
        assertTrue(AlertLabels.thresholdRequired(AlertKind.OI_SPIKE_PCT))
    }

    // ── Scanner depth-row labels ─────────────────────────────────────

    @Test
    fun `eta label is honest dash when no rate yet`() {
        assertEquals("—", AlertLabels.etaLabel(null))
        assertEquals("—", AlertLabels.etaLabel(-1L))
        assertEquals("~45 sn", AlertLabels.etaLabel(45L))
        assertEquals("~4 dk", AlertLabels.etaLabel(240L))
        assertEquals("~1 dk", AlertLabels.etaLabel(60L))
        assertEquals("~2 sa", AlertLabels.etaLabel(7200L))
    }

    @Test
    fun `universe label renames ALL and flags the full-universe warning`() {
        assertEquals("TÜMÜ", AlertLabels.universeLabel("ALL"))
        assertEquals("TOP50", AlertLabels.universeLabel("TOP50"))
        assertEquals("TOP250", AlertLabels.universeLabel("TOP250"))
        assertTrue(AlertLabels.isFullUniverse("ALL"))
        assertFalse(AlertLabels.isFullUniverse("TOP50"))
    }
}
