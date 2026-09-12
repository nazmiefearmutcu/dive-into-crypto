package com.diveintocrypto.android.domain.portfolio

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.domain.evidence.EvidenceStore
import com.diveintocrypto.android.domain.evidence.VerdictRecord
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portfolio tracker: PURE P&L math, the engine-agreement tag, and the
 * SettingsStore raw-JSON persistence round-trip. No network, no real clock.
 */
class PortfolioStoreTest {

    private fun entry(
        symbol: String = "BTCUSDT",
        entryPrice: Double = 100.0,
        size: Double = 2.0,
        direction: String = PortfolioEntry.DIRECTION_LONG,
    ) = PortfolioEntry(id = "e1", symbol = symbol, entryPrice = entryPrice, size = size, direction = direction, createdTs = 42L)

    // ── PURE math ─────────────────────────────────────────────────────────────

    @Test
    fun `unrealizedPct is direction-adjusted`() {
        val long = entry(direction = "LONG")
        val short = entry(direction = "SHORT")
        assertEquals(10.0, PortfolioMath.unrealizedPct(long, 110.0)!!, 1e-9)
        assertEquals(-10.0, PortfolioMath.unrealizedPct(short, 110.0)!!, 1e-9)
    }

    @Test
    fun `unrealizedPnl is size times the direction-adjusted move`() {
        val long = entry(direction = "LONG")
        val short = entry(direction = "SHORT")
        assertEquals(20.0, PortfolioMath.unrealizedPnl(long, 110.0)!!, 1e-9)
        assertEquals(-20.0, PortfolioMath.unrealizedPnl(short, 110.0)!!, 1e-9)
    }

    @Test
    fun `degenerate prices and unknown direction reject honestly`() {
        assertNull(PortfolioMath.unrealizedPct(entry(entryPrice = 0.0), 110.0))
        assertNull(PortfolioMath.unrealizedPct(entry(), 0.0))
        assertNull(PortfolioMath.unrealizedPct(entry(direction = "sideways"), 110.0))
        assertNull(PortfolioMath.unrealizedPnl(entry(), -5.0))
        assertEquals(0, PortfolioEntry.directionSign("sideways"))
    }

    @Test
    fun `agreementTag matrix - AGREE AGAINST UNKNOWN`() {
        assertEquals("AGREE", PortfolioMath.agreementTag(entry(direction = "LONG"), 1))
        assertEquals("AGAINST", PortfolioMath.agreementTag(entry(direction = "LONG"), -1))
        assertEquals("AGREE", PortfolioMath.agreementTag(entry(direction = "SHORT"), -1))
        assertEquals("AGAINST", PortfolioMath.agreementTag(entry(direction = "SHORT"), 1))
        assertEquals("UNKNOWN", PortfolioMath.agreementTag(entry(direction = "LONG"), null))
        assertEquals("UNKNOWN", PortfolioMath.agreementTag(entry(direction = "LONG"), 0))
        assertNull(PortfolioMath.agreementTag(entry(direction = "??"), 1))
    }

    // ── Store + persistence ───────────────────────────────────────────────────

    @Test
    fun `entries round-trip through the raw-JSON blob into a fresh store`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        val p1 = PortfolioStore(store, clock = { 7L })
        p1.add("btcusdt", 65_000.0, 0.5, "long")
        p1.add("ethusdt", 3_000.0, 1.5, "SHORT")

        val reborn = PortfolioStore(store, clock = { 8L })
        val entries = reborn.entries.value
        assertEquals(2, entries.size)
        val btc = entries.first { it.symbol == "BTCUSDT" }
        assertEquals(65_000.0, btc.entryPrice, 1e-9)
        assertEquals(0.5, btc.size, 1e-9)
        assertEquals("LONG", btc.direction)
        assertEquals(7L, btc.createdTs)
        assertTrue(entries.any { it.symbol == "ETHUSDT" && it.direction == "SHORT" })
    }

    @Test
    fun `remove and update persist`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        val p1 = PortfolioStore(store)
        val a = p1.add("BTCUSDT", 100.0, 1.0, "LONG")
        val b = p1.add("ETHUSDT", 200.0, 1.0, "LONG")
        p1.remove(a.id)
        p1.update(b.id, entryPrice = 210.0, direction = "short")

        val reborn = PortfolioStore(store)
        val entries = reborn.entries.value
        assertEquals(1, entries.size)
        assertEquals("ETHUSDT", entries.single().symbol)
        assertEquals(210.0, entries.single().entryPrice, 1e-9)
        assertEquals("SHORT", entries.single().direction)
    }

    @Test
    fun `recomputePnl uses the given marks and stays honestly null without them`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        val p1 = PortfolioStore(store)
        p1.add("BTCUSDT", 100.0, 2.0, "LONG")

        // No marks yet → markPrice/pct/pnl all null (no fabricated zeros).
        var rows = p1.pnl.value
        assertEquals(1, rows.size)
        assertNull(rows.single().markPrice)
        assertNull(rows.single().unrealizedPct)

        // Marks supplied → P&L computed.
        p1.recomputePnl(mapOf("BTCUSDT" to 110.0))
        rows = p1.pnl.value
        assertEquals(110.0, rows.single().markPrice!!, 1e-9)
        assertEquals(10.0, rows.single().unrealizedPct!!, 1e-9)
        assertEquals(20.0, rows.single().unrealizedPnl!!, 1e-9)
    }

    @Test
    fun `engine agreement comes from the evidence archive provider`() {
        val kv = InMemoryKeyValueStore()
        val store = SettingsStore(kv)
        val evidence = EvidenceStore(kv)
        // Two records: the NEWEST (higher ts) says SHORT — the provider must pick it.
        evidence.append(VerdictRecord(ts = 1L, symbol = "BTCUSDT", verdict = "BUY", confidence = 60, risk = "LOW", price = 100.0, dominantDir = 1))
        evidence.append(VerdictRecord(ts = 2L, symbol = "BTCUSDT", verdict = "SELL", confidence = 60, risk = "LOW", price = 100.0, dominantDir = -1))

        val p1 = PortfolioStore(
            store,
            verdictProvider = EvidenceStoreVerdictProvider(evidence),
        )
        p1.add("BTCUSDT", 100.0, 1.0, "LONG")
        p1.recomputePnl(mapOf("BTCUSDT" to 110.0))
        assertEquals("AGAINST", p1.pnl.value.single().engineAgreement)
    }

    @Test
    fun `no provider leaves the agreement honestly null`() {
        val p1 = PortfolioStore(SettingsStore(InMemoryKeyValueStore()))
        p1.add("BTCUSDT", 100.0, 1.0, "LONG")
        p1.recomputePnl(mapOf("BTCUSDT" to 110.0))
        assertNull(p1.pnl.value.single().engineAgreement)
    }

    // ── Verdict-provider caching (QA FIX 1: no per-tick archive re-parse) ─────

    /** [InMemoryKeyValueStore] that counts raw-string reads (readAll ⇒ 1 read). */
    private class CountingKeyValueStore : com.diveintocrypto.android.data.KeyValueStore {
        private val delegate = InMemoryKeyValueStore()
        val getStringCalls = mutableMapOf<String, Int>()
        override fun getInt(key: String, default: Int): Int = delegate.getInt(key, default)
        override fun putInt(key: String, value: Int) = delegate.putInt(key, value)
        override fun getBoolean(key: String, default: Boolean): Boolean = delegate.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
        override fun getFloat(key: String, default: Float): Float = delegate.getFloat(key, default)
        override fun putFloat(key: String, value: Float) = delegate.putFloat(key, value)
        override fun getString(key: String, default: String?): String? {
            getStringCalls[key] = (getStringCalls[key] ?: 0) + 1
            return delegate.getString(key, default)
        }
        override fun putString(key: String, value: String) = delegate.putString(key, value)
    }

    private fun record(ts: Long, dir: Int) = VerdictRecord(
        ts = ts, symbol = "BTCUSDT", verdict = if (dir > 0) "BUY" else "SELL",
        confidence = 60, risk = "LOW", price = 100.0, dominantDir = dir,
    )

    @Test
    fun `verdict provider does NOT re-read the archive on every tick and still updates on append`() {
        val kv = CountingKeyValueStore()
        val evidence = EvidenceStore(kv)
        evidence.append(record(ts = 1L, dir = 1))

        val provider = EvidenceStoreVerdictProvider(evidence, clock = { 1_000L })
        // The append above already consumed one raw read (read-modify-write) —
        // that is the baseline.
        val baseline = kv.getStringCalls[EvidenceStore.KEY] ?: 0
        // 50 ticker ticks worth of lookups — the cached provider reads the
        // JSONL archive ONCE for all of them (the old provider re-parsed the
        // whole ring per lookup).
        repeat(50) { provider.lastVerdictDirection("BTCUSDT") }
        assertEquals(baseline + 1, kv.getStringCalls[EvidenceStore.KEY], "one readAll for 50 ticks")

        // A new verdict is archived → invalidation → the agreement updates on
        // the very next lookup, at the cost of exactly one more readAll
        // (+1 raw read for the append's own read-modify-write).
        evidence.append(record(ts = 2L, dir = -1))
        assertEquals(-1, provider.lastVerdictDirection("BTCUSDT"), "agreement follows the newest verdict")
        assertEquals(baseline + 3, kv.getStringCalls[EvidenceStore.KEY])
    }

    @Test
    fun `verdict provider floor forces a refresh within 30s even without invalidation`() {
        var now = 0L
        val kv = CountingKeyValueStore()
        val evidence = EvidenceStore(kv)
        evidence.append(record(ts = 1L, dir = 1))
        val provider = EvidenceStoreVerdictProvider(evidence, clock = { now })
        assertEquals(1, provider.lastVerdictDirection("BTCUSDT"))

        // Out-of-band append (another store instance over the SAME kv): the
        // provider's version counter does not see it — the cached read stands
        // while inside the 30s wall-clock floor…
        EvidenceStore(kv).append(record(ts = 2L, dir = -1))
        now = 10_000L
        assertEquals(1, provider.lastVerdictDirection("BTCUSDT"), "cached within the floor")
        // …and past the floor the map is recomputed and picks up the new verdict.
        now = EvidenceStoreVerdictProvider.REFRESH_FLOOR_MS + 1_000L
        assertEquals(-1, provider.lastVerdictDirection("BTCUSDT"), "floor expired → fresh read")
    }
}
