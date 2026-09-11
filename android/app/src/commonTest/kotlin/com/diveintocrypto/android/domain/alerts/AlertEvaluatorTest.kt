package com.diveintocrypto.android.domain.alerts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PURE evaluator tests — every rule kind, the re-fire coalescing window, one-shot
 * auto-disable, disabled rules, and the OI-spike helper math. No clock, no I/O.
 */
class AlertEvaluatorTest {

    private fun rule(
        kind: AlertKind,
        threshold: Double = 0.0,
        direction: String = AlertRule.DIRECTION_ANY,
        oneShot: Boolean = false,
        enabled: Boolean = true,
        lastFiredTs: Long? = null,
        id: String = "r1",
    ) = AlertRule(
        id = id,
        symbol = "BTCUSDT",
        kind = kind,
        direction = direction,
        threshold = threshold,
        oneShot = oneShot,
        enabled = enabled,
        createdTs = 0L,
        lastFiredTs = lastFiredTs,
    )

    private val verdictBuy = AlertVerdict(signal = "BUY", confidence = 70, price = 100.0)

    // ── PRICE_ABOVE / PRICE_BELOW ─────────────────────────────────────────────

    @Test
    fun `priceAbove fires when the live price is above the threshold`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.PRICE_ABOVE, threshold = 65_000.0)),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 65_100.0)),
            nowMs = 1_000L,
        )
        assertEquals(1, out.fired.size)
        assertEquals(AlertKind.PRICE_ABOVE, out.fired[0].kind)
        assertEquals(1_000L, out.fired[0].firedTs)
        assertEquals(1_000L, out.rules.single().lastFiredTs)
    }

    @Test
    fun `priceAbove stays silent below the threshold`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.PRICE_ABOVE, threshold = 65_000.0)),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 64_999.0)),
            nowMs = 1_000L,
        )
        assertTrue(out.fired.isEmpty())
        assertNull(out.rules.single().lastFiredTs)
    }

    @Test
    fun `priceBelow fires below the threshold and stays silent above`() {
        val fired = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.PRICE_BELOW, threshold = 60_000.0)),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 59_500.0)),
            nowMs = 1_000L,
        )
        assertEquals(1, fired.fired.size)
        val silent = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.PRICE_BELOW, threshold = 60_000.0)),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 60_000.5)),
            nowMs = 1_000L,
        )
        assertTrue(silent.fired.isEmpty())
    }

    @Test
    fun `missing price data is honest silence — no fabricated fires`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.PRICE_ABOVE, threshold = 1.0)),
            inputs = AlertInputs(prices = emptyMap()),
            nowMs = 1_000L,
        )
        assertTrue(out.fired.isEmpty())
    }

    // ── Coalescing ────────────────────────────────────────────────────────────

    @Test
    fun `a rule never re-fires inside the coalescing window`() {
        val base = rule(AlertKind.PRICE_ABOVE, threshold = 100.0, lastFiredTs = 10_000L)
        val out = AlertEvaluator.evaluate(
            rules = listOf(base),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 200.0)),
            nowMs = 10_000L + AlertEvaluator.COALESCE_MS - 1,
        )
        assertTrue(out.fired.isEmpty(), "re-fire blocked inside the window")
    }

    @Test
    fun `a rule re-fires after the coalescing window`() {
        val base = rule(AlertKind.PRICE_ABOVE, threshold = 100.0, lastFiredTs = 10_000L)
        val out = AlertEvaluator.evaluate(
            rules = listOf(base),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 200.0)),
            nowMs = 10_000L + AlertEvaluator.COALESCE_MS,
        )
        assertEquals(1, out.fired.size)
    }

    // ── VERDICT rules ─────────────────────────────────────────────────────────

    @Test
    fun `verdict ANY fires on a BUY verdict`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.VERDICT)),
            inputs = AlertInputs(verdicts = mapOf("BTCUSDT" to verdictBuy)),
            nowMs = 1_000L,
        )
        assertEquals(1, out.fired.size)
        assertTrue(out.fired[0].message.contains("BUY"))
    }

    @Test
    fun `verdict LONG rule ignores a SHORT verdict and vice versa`() {
        val longRule = rule(AlertKind.VERDICT, direction = AlertRule.DIRECTION_LONG)
        val shortVerdict = AlertVerdict(signal = "STRONG_SELL", confidence = 80, price = 100.0)
        val out = AlertEvaluator.evaluate(
            rules = listOf(longRule),
            inputs = AlertInputs(verdicts = mapOf("BTCUSDT" to shortVerdict)),
            nowMs = 1_000L,
        )
        assertTrue(out.fired.isEmpty(), "LONG rule must not fire on SHORT")

        val shortRule = rule(AlertKind.VERDICT, direction = AlertRule.DIRECTION_SHORT)
        val out2 = AlertEvaluator.evaluate(
            rules = listOf(shortRule),
            inputs = AlertInputs(verdicts = mapOf("BTCUSDT" to shortVerdict)),
            nowMs = 1_000L,
        )
        assertEquals(1, out2.fired.size)
    }

    @Test
    fun `NEUTRAL verdict is honest silence for verdict rules`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.VERDICT)),
            inputs = AlertInputs(verdicts = mapOf("BTCUSDT" to AlertVerdict("NEUTRAL", 10, 100.0))),
            nowMs = 1_000L,
        )
        assertTrue(out.fired.isEmpty())
    }

    // ── CONFIDENCE_ABOVE ──────────────────────────────────────────────────────

    @Test
    fun `confidenceAbove respects the threshold strictly`() {
        val fire = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.CONFIDENCE_ABOVE, threshold = 60.0)),
            inputs = AlertInputs(verdicts = mapOf("BTCUSDT" to verdictBuy.copy(confidence = 61))),
            nowMs = 1_000L,
        )
        assertEquals(1, fire.fired.size)
        val silent = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.CONFIDENCE_ABOVE, threshold = 60.0)),
            inputs = AlertInputs(verdicts = mapOf("BTCUSDT" to verdictBuy.copy(confidence = 60))),
            nowMs = 1_000L,
        )
        assertTrue(silent.fired.isEmpty(), "60 is not ABOVE 60")
    }

    // ── one-shot / disabled ───────────────────────────────────────────────────

    @Test
    fun `one-shot rule auto-disables after its first fire`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.PRICE_ABOVE, threshold = 100.0, oneShot = true)),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 150.0)),
            nowMs = 1_000L,
        )
        assertEquals(1, out.fired.size)
        val updated = out.rules.single()
        assertFalse(updated.enabled, "one-shot disabled after firing")
        assertEquals(1_000L, updated.lastFiredTs)

        // Even long after the coalesce window, a disabled rule stays silent.
        val out2 = AlertEvaluator.evaluate(
            rules = listOf(updated),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 150.0)),
            nowMs = 999_999L,
        )
        assertTrue(out2.fired.isEmpty())
    }

    @Test
    fun `disabled rules never fire`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.PRICE_ABOVE, threshold = 1.0, enabled = false)),
            inputs = AlertInputs(prices = mapOf("BTCUSDT" to 100.0)),
            nowMs = 1_000L,
        )
        assertTrue(out.fired.isEmpty())
    }

    // ── OI_SPIKE_PCT ──────────────────────────────────────────────────────────

    @Test
    fun `oiSpikePct rule fires on a spike above the threshold`() {
        val out = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.OI_SPIKE_PCT, threshold = 10.0)),
            inputs = AlertInputs(oiSpikePct = mapOf("BTCUSDT" to 12.5)),
            nowMs = 1_000L,
        )
        assertEquals(1, out.fired.size)
        val silent = AlertEvaluator.evaluate(
            rules = listOf(rule(AlertKind.OI_SPIKE_PCT, threshold = 10.0)),
            inputs = AlertInputs(oiSpikePct = mapOf("BTCUSDT" to 5.0)),
            nowMs = 1_000L,
        )
        assertTrue(silent.fired.isEmpty())
    }

    @Test
    fun `oiSpikePct helper computes first-to-last percent and rejects degenerate series`() {
        assertEquals(10.0, AlertEvaluator.oiSpikePct(listOf(100.0, 105.0, 110.0))!!, 1e-9)
        assertNull(AlertEvaluator.oiSpikePct(emptyList()))
        assertNull(AlertEvaluator.oiSpikePct(listOf(0.0, 5.0)))
        assertNull(AlertEvaluator.oiSpikePct(listOf(50.0)))
    }

    // ── direction mapping ─────────────────────────────────────────────────────

    @Test
    fun `directionOf maps the five signals`() {
        assertEquals(1, AlertEvaluator.directionOf("BUY"))
        assertEquals(1, AlertEvaluator.directionOf("STRONG_BUY"))
        assertEquals(-1, AlertEvaluator.directionOf("SELL"))
        assertEquals(-1, AlertEvaluator.directionOf("STRONG_SELL"))
        assertNull(AlertEvaluator.directionOf("NEUTRAL"))
    }
}
