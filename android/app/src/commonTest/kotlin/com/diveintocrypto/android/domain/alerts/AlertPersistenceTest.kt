package com.diveintocrypto.android.domain.alerts

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Alert persistence round-trips: rules + fired history survive a full engine
 * re-creation over the SAME store (the app-restart path). Uses
 * [InMemoryKeyValueStore] + [SettingsStore.putRaw]/getRaw — exactly the
 * production persistence path, no I/O.
 */
class AlertPersistenceTest {

    private class RecordingNotifier : AlertNotifier {
        val fired = mutableListOf<FiredAlert>()
        override fun notify(fired: FiredAlert) { this.fired += fired }
    }

    private fun newEngine(store: SettingsStore, notifier: AlertNotifier): AlertEngine =
        AlertEngine(settingsStore = store, notifier = notifier, tickerSource = null)

    @Test
    fun `rules round-trip through the store into a fresh engine`() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = SettingsStore(kv)
        val notifier = RecordingNotifier()
        val engine = newEngine(store, notifier)

        val added = engine.addRule("btcusdt", AlertKind.PRICE_ABOVE, threshold = 65_000.0)
        engine.addRule("ETHUSDT", AlertKind.VERDICT, direction = AlertRule.DIRECTION_LONG, oneShot = true)
        assertEquals(2, engine.rules.value.size)

        // Simulate an app restart: a brand-new engine over the same store.
        val reborn = newEngine(store, RecordingNotifier())
        assertEquals(2, reborn.rules.value.size)
        val btc = reborn.rules.value.first { it.symbol == "BTCUSDT" }
        assertEquals(added.id, btc.id)
        assertEquals(AlertKind.PRICE_ABOVE, btc.kind)
        assertEquals(65_000.0, btc.threshold, 1e-9)
        assertTrue(btc.enabled)
        val eth = reborn.rules.value.first { it.symbol == "ETHUSDT" }
        assertEquals(AlertRule.DIRECTION_LONG, eth.direction)
        assertTrue(eth.oneShot)
        assertNull(notifier.fired.firstOrNull { it.symbol == "BTCUSDT" })
    }

    @Test
    fun `fired history persists and the ring caps at 100 newest-first`() = runTest {
        val store = SettingsStore(InMemoryKeyValueStore())
        val notifier = RecordingNotifier()
        val engine = newEngine(store, notifier)

        // 105 enabled verdict rules → one scan pass fires them all (no coalesce on
        // first fire) → the history ring must keep exactly the 100 newest.
        repeat(105) { i ->
            engine.addRule("SYM$i", AlertKind.VERDICT)
        }
        val verdicts = (0 until 105).associate { i ->
            "SYM$i" to AlertVerdict("BUY", confidence = 50, price = 1.0)
        }
        engine.onScanResults(verdicts)

        assertEquals(100, engine.firedHistory.value.size, "ring cap 100")
        assertEquals(105, notifier.fired.size, "notifier saw every fire")
        // Newest-first: SYM104 (last fired) is at the head.
        assertEquals("SYM104", engine.firedHistory.value.first().symbol)
        assertNotNull(engine.banner.value)

        // Restart → history survives.
        val reborn = newEngine(store, RecordingNotifier())
        assertEquals(100, reborn.firedHistory.value.size)
        assertEquals("SYM104", reborn.firedHistory.value.first().symbol)
    }

    @Test
    fun `toggleRule and removeRule persist`() = runTest {
        val store = SettingsStore(InMemoryKeyValueStore())
        val engine = newEngine(store, RecordingNotifier())
        val r1 = engine.addRule("BTCUSDT", AlertKind.PRICE_ABOVE, threshold = 1.0)
        val r2 = engine.addRule("ETHUSDT", AlertKind.PRICE_BELOW, threshold = 1.0)

        engine.toggleRule(r1.id)
        engine.removeRule(r2.id)

        val reborn = newEngine(store, RecordingNotifier())
        assertEquals(listOf("BTCUSDT"), reborn.rules.value.map { it.symbol })
        assertFalse(reborn.rules.value.single().enabled, "toggled-off state persisted")
    }

    @Test
    fun `one-shot scan fire persists as disabled`() = runTest {
        val store = SettingsStore(InMemoryKeyValueStore())
        val engine = newEngine(store, RecordingNotifier())
        engine.addRule("BTCUSDT", AlertKind.VERDICT, direction = AlertRule.DIRECTION_ANY, oneShot = true)

        engine.onScanResults(mapOf("BTCUSDT" to AlertVerdict("STRONG_BUY", 90, 100.0)))
        assertFalse(engine.rules.value.single().enabled)
        assertEquals(1, engine.firedHistory.value.size)

        // Second scan pass: no double-fire, and the disabled state survives a restart.
        engine.onScanResults(mapOf("BTCUSDT" to AlertVerdict("STRONG_BUY", 90, 100.0)))
        assertEquals(1, engine.firedHistory.value.size)
        val reborn = newEngine(store, RecordingNotifier())
        assertFalse(reborn.rules.value.single().enabled)
        assertEquals(1, reborn.firedHistory.value.size)
    }
}
