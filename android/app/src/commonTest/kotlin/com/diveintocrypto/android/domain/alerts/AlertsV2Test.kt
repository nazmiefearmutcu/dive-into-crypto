package com.diveintocrypto.android.domain.alerts

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Alerts v2: AND-condition groups, per-rule coalescing windows, and the v1→v2
 * persistence migration. Evaluator tests are PURE; engine tests run over
 * [InMemoryKeyValueStore] + [SettingsStore] (the production persistence path).
 */
class AlertsV2Test {

    private val inputs = AlertInputs(
        prices = mapOf("BTCUSDT" to 150.0),
        verdicts = mapOf("BTCUSDT" to AlertVerdict("BUY", 70, 150.0)),
    )

    private fun ruleWith(
        conditions: List<AlertCondition>,
        coalesceMs: Long = AlertRule.COALESCE_DEFAULT_MS,
        lastFiredTs: Long? = null,
        id: String = "r1",
    ) = AlertRule(
        id = id,
        symbol = "BTCUSDT",
        kind = conditions.first().kind,
        direction = conditions.first().direction,
        threshold = conditions.first().threshold,
        enabled = true,
        createdTs = 0L,
        lastFiredTs = lastFiredTs,
        conditions = conditions,
        coalesceMs = coalesceMs,
    )

    // ── AND semantics ─────────────────────────────────────────────────────────

    @Test
    fun `AND group fires only when every condition is met`() {
        val rule = ruleWith(
            listOf(AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0), AlertCondition(AlertKind.CONFIDENCE_ABOVE, threshold = 60.0)),
        )
        val out = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = 1_000L)
        assertEquals(1, out.fired.size)
        assertTrue(out.fired.single().message.contains(" AND "), "multi-condition message joined")
        assertTrue(out.fired.single().message.contains("price above"))
        assertTrue(out.fired.single().message.contains("confidence 70%"))
    }

    @Test
    fun `AND group stays silent when one condition is unmet`() {
        val rule = ruleWith(
            listOf(AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0), AlertCondition(AlertKind.CONFIDENCE_ABOVE, threshold = 80.0)),
        )
        val out = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = 1_000L)
        assertTrue(out.fired.isEmpty())
        assertNull(out.rules.single().lastFiredTs)
    }

    @Test
    fun `AND group stays silent when data for one condition is missing - honest`() {
        val rule = ruleWith(
            listOf(AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0), AlertCondition(AlertKind.OI_SPIKE_PCT, threshold = 5.0)),
        )
        // prices present, NO OI data → the whole rule must stay silent.
        val out = AlertEvaluator.evaluate(
            listOf(rule),
            AlertInputs(prices = inputs.prices),
            nowMs = 1_000L,
        )
        assertTrue(out.fired.isEmpty())
    }

    @Test
    fun `single-condition rule keeps the exact v1 behavior`() {
        val rule = ruleWith(listOf(AlertCondition(AlertKind.PRICE_ABOVE, threshold = 65_000.0)))
        val out = AlertEvaluator.evaluate(
            listOf(rule),
            AlertInputs(prices = mapOf("BTCUSDT" to 65_100.0)),
            nowMs = 1_000L,
        )
        assertEquals(1, out.fired.size)
        assertEquals("BTCUSDT price above 65000.0 (now 65100.0)", out.fired.single().message)
    }

    @Test
    fun `a condition with no data for its symbol is honest silence`() {
        assertNull(AlertEvaluator.conditionMessage(AlertCondition(AlertKind.VERDICT), "NOSUCH", inputs))
        assertNull(
            AlertEvaluator.conditionMessage(
                AlertCondition(AlertKind.PRICE_ABOVE, threshold = 1.0),
                "NOSUCH",
                inputs,
            ),
        )
    }

    // ── Per-rule coalescing ───────────────────────────────────────────────────

    @Test
    fun `per-rule coalesce window overrides the engine default`() {
        val rule = ruleWith(
            listOf(AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0)),
            coalesceMs = AlertRule.COALESCE_15M,
            lastFiredTs = 0L,
        )
        // 61s after the last fire: inside the rule's own 15m window → silent,
        // even though the engine default (60s) has already elapsed.
        val blocked = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = 61_000L)
        assertTrue(blocked.fired.isEmpty())
        // Exactly at the rule's window boundary → fires.
        val allowed = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = AlertRule.COALESCE_15M)
        assertEquals(1, allowed.fired.size)
    }

    @Test
    fun `coalesceMs 0 falls back to the engine default`() {
        val rule = ruleWith(
            listOf(AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0)),
            coalesceMs = 0L,
            lastFiredTs = 10_000L,
        )
        val blocked = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = 10_000L + 59_999L)
        assertTrue(blocked.fired.isEmpty(), "still inside the default 60s window")
        val allowed = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = 70_000L)
        assertEquals(1, allowed.fired.size)
    }

    // ── Migration v1 → v2 ─────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun engine(store: SettingsStore) =
        AlertEngine(settingsStore = store, notifier = object : AlertNotifier {
            override fun notify(fired: FiredAlert) {}
        })

    @Test
    fun `v1 blob migrates into a one-condition v2 rule and persists forward`() = runTest {
        val store = SettingsStore(InMemoryKeyValueStore())
        // A REAL v1 blob: no "conditions" / "coalesceMs" fields at all.
        val v1Json =
            """[{"id":"v1-1","symbol":"BTCUSDT","kind":"price_above","direction":"ANY",""" +
            """"threshold":65000.0,"oneShot":false,"enabled":true,"createdTs":111,"lastFiredTs":null}]"""
        store.putRaw(AlertEngine.KEY_RULES, v1Json)
        assertNull(store.getRaw(AlertEngine.KEY_RULES_V2))

        val engine = engine(store)
        val migrated = engine.rules.value
        assertEquals(1, migrated.size)
        val rule = migrated.single()
        assertEquals("v1-1", rule.id)
        assertEquals(AlertKind.PRICE_ABOVE, rule.kind)
        assertEquals(65_000.0, rule.threshold, 1e-9)
        assertEquals(1, rule.conditions.size)
        assertEquals(AlertKind.PRICE_ABOVE, rule.conditions.single().kind)
        assertEquals(65_000.0, rule.conditions.single().threshold, 1e-9)
        assertEquals(AlertRule.COALESCE_DEFAULT_MS, rule.coalesceMs)

        // The migration was persisted forward into the v2 key.
        val v2Raw = store.getRaw(AlertEngine.KEY_RULES_V2)
        assertTrue(!v2Raw.isNullOrBlank())
        val decoded = json.decodeFromString(ListSerializer(AlertRule.serializer()), v2Raw!!)
        assertEquals(1, decoded.size)
        assertEquals(1, decoded.single().conditions.size)

        // The evaluator actually fires the migrated rule (price 65_100 > 65_000).
        engine.startTickerObservation() // no ticker source → no-op, evaluates manually below
        val out = AlertEvaluator.evaluate(
            engine.rules.value,
            AlertInputs(prices = mapOf("BTCUSDT" to 65_100.0)),
            nowMs = 1_000L,
        )
        assertEquals(1, out.fired.size)
    }

    @Test
    fun `v2 blob wins over a stale v1 blob`() = runTest {
        val store = SettingsStore(InMemoryKeyValueStore())
        store.putRaw(
            AlertEngine.KEY_RULES,
            """[{"id":"old","symbol":"ETHUSDT","kind":"verdict","direction":"ANY",""" +
                """"threshold":0.0,"oneShot":false,"enabled":true,"createdTs":1}]""",
        )
        store.putRaw(
            AlertEngine.KEY_RULES_V2,
            """[{"id":"new","symbol":"BTCUSDT","kind":"price_below","direction":"ANY",""" +
                """"threshold":10.0,"oneShot":false,"enabled":true,"createdTs":2,""" +
                """"lastFiredTs":null,"conditions":[{"kind":"price_below","direction":"ANY","threshold":10.0}],"coalesceMs":60000}]""",
        )
        val engine = engine(store)
        assertEquals(listOf("new"), engine.rules.value.map { it.id })
        assertEquals("BTCUSDT", engine.rules.value.single().symbol)
    }

    @Test
    fun `engine v2 addRule keeps conditions and coalescing across a restart`() = runTest {
        val store = SettingsStore(InMemoryKeyValueStore())
        val engine = engine(store)
        val added = engine.addRule(
            "btcusdt",
            listOf(
                AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0),
                AlertCondition(AlertKind.CONFIDENCE_ABOVE, threshold = 60.0),
            ),
            coalesceMs = AlertRule.COALESCE_1H,
        )
        assertEquals(AlertKind.PRICE_ABOVE, added.kind, "primary mirrors conditions[0] for the UI")
        assertEquals(2, added.conditions.size)

        val reborn = engine(store)
        val rule = reborn.rules.value.single()
        assertEquals(added.id, rule.id)
        assertEquals(2, rule.conditions.size)
        assertEquals(AlertRule.COALESCE_1H, rule.coalesceMs)
    }

    @Test
    fun `setRuleCoalesceMs persists`() = runTest {
        val store = SettingsStore(InMemoryKeyValueStore())
        val engine = engine(store)
        val rule = engine.addRule("BTCUSDT", AlertKind.PRICE_ABOVE, threshold = 1.0)
        engine.setRuleCoalesceMs(rule.id, AlertRule.COALESCE_15M)

        val reborn = engine(store)
        assertEquals(AlertRule.COALESCE_15M, reborn.rules.value.single().coalesceMs)
    }

    @Test
    fun `effectiveConditions derives the single primary condition from v1 fields`() {
        val legacy = AlertRule(
            id = "x", symbol = "BTCUSDT", kind = AlertKind.CONFIDENCE_ABOVE,
            threshold = 55.0, enabled = true, createdTs = 0L,
        )
        assertEquals(0, legacy.conditions.size, "constructed empty stays empty (v1 shape)")
        val derived = legacy.effectiveConditions
        assertEquals(listOf(AlertCondition(AlertKind.CONFIDENCE_ABOVE, AlertRule.DIRECTION_ANY, 55.0)), derived)
    }
}
