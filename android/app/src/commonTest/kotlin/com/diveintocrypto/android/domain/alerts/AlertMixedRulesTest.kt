package com.diveintocrypto.android.domain.alerts

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.engine.LiveTickerEngine
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Engine behaviour across the two hooks: AND rules that mix PRICE and scan-cycle
 * conditions, duplicate / racing evaluations, the ticker-observation flag, and
 * the v1→v2 fallback after every rule was deleted. The ticker hook is driven
 * through [AlertEngine.onTickerBatch] (the exact code the collector runs) with
 * explicit clocks, so the tests are deterministic.
 */
class AlertMixedRulesTest {

    private class RecordingNotifier : AlertNotifier {
        val fired = mutableListOf<FiredAlert>()
        override fun notify(fired: FiredAlert) { this.fired += fired }
    }

    private fun newEngine(store: SettingsStore, notifier: AlertNotifier = RecordingNotifier()) =
        AlertEngine(settingsStore = store, notifier = notifier, tickerSource = null)

    private fun ticker(symbol: String, price: Double) =
        LiveTickerEngine.LiveTicker(symbol, price, null, 0L, "WS")

    private val t0 = 1_000_000L

    // ── Mixed PRICE + scan rules ──────────────────────────────────────────────

    @Test
    fun `a PRICE plus VERDICT rule fires when the price arrives after a LONG scan`() = runTest {
        val notifier = RecordingNotifier()
        val engine = newEngine(SettingsStore(InMemoryKeyValueStore()), notifier)
        engine.addRule(
            "BTCUSDT",
            listOf(
                AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0),
                AlertCondition(AlertKind.VERDICT, AlertRule.DIRECTION_LONG),
            ),
        )
        engine.onScanResults(mapOf("BTCUSDT" to AlertVerdict("BUY", 70, 150.0)), nowMs = t0)
        assertTrue(notifier.fired.isEmpty(), "no live price yet → honest silence")

        engine.onTickerBatch(mapOf("BTCUSDT" to ticker("BTCUSDT", 150.0)), nowMs = t0 + 1_000L)
        assertEquals(1, notifier.fired.size)
        assertTrue(notifier.fired.single().message.contains(" AND "), "both conditions in the message")
        assertEquals(1, engine.firedHistory.value.size)
        engine.close()
    }

    @Test
    fun `a PRICE plus VERDICT rule fires from the scan side when the price was seen first`() = runTest {
        val notifier = RecordingNotifier()
        val engine = newEngine(SettingsStore(InMemoryKeyValueStore()), notifier)
        engine.addRule(
            "BTCUSDT",
            listOf(
                AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0),
                AlertCondition(AlertKind.VERDICT, AlertRule.DIRECTION_LONG),
            ),
        )
        engine.onTickerBatch(mapOf("BTCUSDT" to ticker("BTCUSDT", 150.0)), nowMs = t0)
        assertTrue(notifier.fired.isEmpty(), "no verdict yet")

        engine.onScanResults(mapOf("BTCUSDT" to AlertVerdict("BUY", 70, 150.0)), nowMs = t0 + 5_000L)
        assertEquals(1, notifier.fired.size)
        engine.close()
    }

    @Test
    fun `a remembered scan verdict expires after SCAN_TTL_MS`() = runTest {
        val notifier = RecordingNotifier()
        val engine = newEngine(SettingsStore(InMemoryKeyValueStore()), notifier)
        engine.addRule(
            "BTCUSDT",
            listOf(
                AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0),
                AlertCondition(AlertKind.VERDICT, AlertRule.DIRECTION_LONG),
            ),
        )
        engine.onScanResults(mapOf("BTCUSDT" to AlertVerdict("BUY", 70, 150.0)), nowMs = t0)
        engine.onTickerBatch(
            mapOf("BTCUSDT" to ticker("BTCUSDT", 150.0)),
            nowMs = t0 + AlertEngine.SCAN_TTL_MS + 1L,
        )
        assertTrue(notifier.fired.isEmpty(), "stale verdict no longer satisfies the rule")
        engine.close()
    }

    @Test
    fun `a remembered price expires before a scan can use it`() = runTest {
        val notifier = RecordingNotifier()
        val engine = newEngine(SettingsStore(InMemoryKeyValueStore()), notifier)
        engine.addRule(
            "BTCUSDT",
            listOf(
                AlertCondition(AlertKind.PRICE_ABOVE, threshold = 100.0),
                AlertCondition(AlertKind.VERDICT, AlertRule.DIRECTION_LONG),
            ),
        )
        engine.onTickerBatch(mapOf("BTCUSDT" to ticker("BTCUSDT", 150.0)), nowMs = t0)
        engine.onScanResults(
            mapOf("BTCUSDT" to AlertVerdict("BUY", 70, 150.0)),
            nowMs = t0 + AlertEngine.PRICE_TTL_MS + 1L,
        )
        assertTrue(notifier.fired.isEmpty(), "price older than PRICE_TTL_MS is not used")
        engine.close()
    }

    @Test
    fun `a pure VERDICT rule is not re-fired by ticks from a stale scan snapshot`() = runTest {
        val notifier = RecordingNotifier()
        val engine = newEngine(SettingsStore(InMemoryKeyValueStore()), notifier)
        engine.addRule("BTCUSDT", AlertKind.VERDICT, direction = AlertRule.DIRECTION_LONG)
        engine.onScanResults(mapOf("BTCUSDT" to AlertVerdict("BUY", 70, 150.0)), nowMs = t0)
        assertEquals(1, notifier.fired.size)

        engine.onTickerBatch(
            mapOf("BTCUSDT" to ticker("BTCUSDT", 150.0)),
            nowMs = t0 + AlertRule.COALESCE_DEFAULT_MS + 1L,
        )
        assertEquals(1, notifier.fired.size, "ticks do not evaluate verdict-only rules")
        engine.close()
    }

    // ── Race hygiene on applyOutcome ──────────────────────────────────────────

    @Test
    fun `two evaluations over the same data fire a rule only once`() {
        val notifier = RecordingNotifier()
        val engine = newEngine(SettingsStore(InMemoryKeyValueStore()), notifier)
        engine.addRule("BTCUSDT", AlertKind.PRICE_ABOVE, threshold = 100.0)
        val snapshot = engine.rules.value
        val inputs = AlertInputs(prices = mapOf("BTCUSDT" to 150.0))
        val first = AlertEvaluator.evaluate(snapshot, inputs, nowMs = 1_000L)
        val second = AlertEvaluator.evaluate(snapshot, inputs, nowMs = 1_500L)

        engine.applyOutcome(first)
        engine.applyOutcome(second)
        assertEquals(1, notifier.fired.size, "duplicate fire inside the coalescing window is dropped")
        assertEquals(1, engine.firedHistory.value.size)
    }

    @Test
    fun `a rule disabled while its evaluation was in flight does not fire`() {
        val notifier = RecordingNotifier()
        val store = SettingsStore(InMemoryKeyValueStore())
        val engine = newEngine(store, notifier)
        val rule = engine.addRule("BTCUSDT", AlertKind.PRICE_ABOVE, threshold = 100.0)
        val outcome = AlertEvaluator.evaluate(
            engine.rules.value,
            AlertInputs(prices = mapOf("BTCUSDT" to 150.0)),
            nowMs = 1_000L,
        )
        engine.toggleRule(rule.id, false)

        engine.applyOutcome(outcome)
        assertFalse(engine.rules.value.single().enabled, "the user's disable is not reverted")
        assertTrue(notifier.fired.isEmpty())
        assertTrue(engine.firedHistory.value.isEmpty())
    }

    // ── Ticker-observation flag ───────────────────────────────────────────────

    @Test
    fun `restored price rules arm the ticker loop once and it stays armed`() {
        val kv = InMemoryKeyValueStore()
        val store = SettingsStore(kv)
        newEngine(store).addRule("BTCUSDT", AlertKind.PRICE_ABOVE, threshold = 100.0)

        val source = MutableStateFlow<Map<String, LiveTickerEngine.LiveTicker>>(emptyMap())
        val reborn = AlertEngine(settingsStore = store, notifier = RecordingNotifier(), tickerSource = source)
        assertTrue(reborn.isTickerObserving, "init arms the loop for restored PRICE rules")
        reborn.startTickerObservation()
        assertTrue(reborn.isTickerObserving, "a second start is a no-op, not a reset")
        reborn.close()
    }

    // ── v1 → v2 after every rule was deleted ──────────────────────────────────

    @Test
    fun `deleting every migrated rule does not resurrect the v1 blob on restart`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        store.putRaw(
            AlertEngine.KEY_RULES,
            """[{"id":"v1-1","symbol":"BTCUSDT","kind":"price_above","direction":"ANY",""" +
                """"threshold":65000.0,"oneShot":false,"enabled":true,"createdTs":111,"lastFiredTs":null}]""",
        )
        val first = newEngine(store)
        assertEquals(1, first.rules.value.size)
        first.removeRule(first.rules.value.single().id)
        assertTrue(first.rules.value.isEmpty())

        val reborn = newEngine(store)
        assertTrue(reborn.rules.value.isEmpty(), "the empty v2 list is authoritative")
        assertEquals("[]", store.getRaw(AlertEngine.KEY_RULES_V2))
        assertNotNull(store.getRaw(AlertEngine.KEY_RULES), "the v1 blob itself is left untouched")
    }
}
