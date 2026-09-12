package com.diveintocrypto.android.ui.alerts

import com.diveintocrypto.android.domain.alerts.AlertCondition
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v2 alert-UI label tests — cooldown chip mapping and the condition-summary
 * label (v2 rules show N conditions; v1 rules fold into exactly 1 — the
 * automatic migration display).
 */
class AlertLabelsV2Test {

    private fun rule(
        conditions: List<AlertCondition>,
        oneShot: Boolean = false,
        coalesceMs: Long = AlertRule.COALESCE_1M,
    ) = AlertRule(
        id = "r1",
        symbol = "BTCUSDT",
        kind = conditions.first().kind,
        direction = conditions.first().direction,
        threshold = conditions.first().threshold,
        oneShot = oneShot,
        createdTs = 0L,
        conditions = conditions,
        coalesceMs = coalesceMs,
    )

    // ── Cooldown chip mapping ─────────────────────────────────────────

    @Test
    fun cooldownChipsMapToEngineConstants() {
        val chips = AlertLabels.COOLDOWN_CHIPS
        assertEquals(4, chips.size)
        assertEquals("1 DK", chips[0].label)
        assertEquals(AlertRule.COALESCE_1M, chips[0].coalesceMs)
        assertEquals("15 DK", chips[1].label)
        assertEquals(AlertRule.COALESCE_15M, chips[1].coalesceMs)
        assertEquals("1 SA", chips[2].label)
        assertEquals(AlertRule.COALESCE_1H, chips[2].coalesceMs)
        assertTrue(chips[3].oneShot)
        assertEquals("TEK ATIŞ", chips[3].label)
    }

    @Test
    fun cooldownLabelRespectsOneShotOverWindow() {
        assertEquals("TEK ATIŞ", AlertLabels.cooldownLabel(oneShot = true, coalesceMs = AlertRule.COALESCE_1H))
        assertEquals("1 SA", AlertLabels.cooldownLabel(oneShot = false, coalesceMs = AlertRule.COALESCE_1H))
        assertEquals("15 DK", AlertLabels.cooldownLabel(oneShot = false, coalesceMs = AlertRule.COALESCE_15M))
        // Honest fallback for a non-preset window.
        assertEquals("7 dk", AlertLabels.cooldownLabel(oneShot = false, coalesceMs = 7 * 60_000L))
    }

    @Test
    fun selectedCooldownChipRoundTrips() {
        val chip = AlertLabels.selectedCooldownChip(oneShot = false, coalesceMs = AlertRule.COALESCE_15M)
        assertEquals("15 DK", chip.label)
        val once = AlertLabels.selectedCooldownChip(oneShot = true, coalesceMs = 0L)
        assertTrue(once.oneShot)
        // Unknown window falls back to the first (1 DK) chip — never a crash.
        assertEquals("1 DK", AlertLabels.selectedCooldownChip(false, 123_456L).label)
    }

    // ── Condition summary label ───────────────────────────────────────

    @Test
    fun conditionSummaryShowsCountKindsAndCooldown() {
        val r = rule(
            listOf(
                AlertCondition(AlertKind.VERDICT, AlertRule.DIRECTION_LONG, 0.0),
                AlertCondition(AlertKind.CONFIDENCE_ABOVE, AlertRule.DIRECTION_ANY, 60.0),
            ),
            coalesceMs = AlertRule.COALESCE_15M,
        )
        assertEquals("2 koşul · KARAR YÖNÜ(LONG)+GÜVEN ÜSTÜ · 15 DK", AlertLabels.conditionSummaryLabel(r))
    }

    @Test
    fun conditionSummaryCollapsesLongKindLists() {
        val r = rule(
            listOf(
                AlertCondition(AlertKind.PRICE_ABOVE, AlertRule.DIRECTION_ANY, 1.0),
                AlertCondition(AlertKind.PRICE_BELOW, AlertRule.DIRECTION_ANY, 1.0),
                AlertCondition(AlertKind.OI_SPIKE_PCT, AlertRule.DIRECTION_ANY, 5.0),
            ),
        )
        assertEquals("3 koşul · FİYAT ÜSTÜ+FİYAT ALT+1 · 1 DK", AlertLabels.conditionSummaryLabel(r))
    }

    @Test
    fun v1RuleMigratesToExactlyOneCondition() {
        // v1 shape: conditions list EMPTY — kind/direction/threshold carry the rule.
        val v1 = AlertRule(
            id = "old",
            symbol = "ETHUSDT",
            kind = AlertKind.OI_SPIKE_PCT,
            direction = AlertRule.DIRECTION_ANY,
            threshold = 5.0,
            oneShot = false,
            createdTs = 0L,
            conditions = emptyList(),
            coalesceMs = 0L,
        )
        val summary = AlertLabels.conditionSummaryLabel(v1)
        assertTrue(summary.startsWith("1 koşul · "), "v1 must render as 1 condition, got: $summary")
        assertTrue(summary.contains("OI ARTIŞI"))
    }
}
