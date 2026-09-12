package com.diveintocrypto.android.data

import com.diveintocrypto.android.domain.consensus.ConsensusEngine
import com.diveintocrypto.android.domain.consensus.DEFAULT_FULL_WEIGHTS
import com.diveintocrypto.android.domain.consensus.DEFAULT_F2_WEIGHTS
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the FULL 60-name weight wiring:
 *  - SettingsStore defaults now cover ALL indicators (previously only the 15 core
 *    names, so the 42 extended indicators silently scored at weight 1.0);
 *  - user overrides persist across store restarts;
 *  - ConsensusEngine built over the store applies the wired weights
 *    (per-indicator signalDetails expose the weight actually used).
 */
class SettingsStoreWeightsTest {

    @Test
    fun `default weights cover all 60 indicators`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        val weights = store.getSettings().weights
        assertEquals(60, weights.size)
        assertEquals(DEFAULT_FULL_WEIGHTS.keys, weights.keys)
        // Weights persist as Float, so compare with a float-precision delta.
        for ((key, expected) in DEFAULT_FULL_WEIGHTS) {
            assertEquals(expected, weights.getValue(key), 1e-5, "weight mismatch for $key")
        }
    }

    @Test
    fun `core names keep the F2 matrix values and extended names take reference weights`() {
        // The 4 core names where the two sources historically differ must keep the
        // F2 values (production behavior for the core set is unchanged).
        assertEquals(DEFAULT_F2_WEIGHTS.getValue("sma_cross"), DEFAULT_FULL_WEIGHTS.getValue("sma_cross"))
        assertEquals(DEFAULT_F2_WEIGHTS.getValue("ichimoku"), DEFAULT_FULL_WEIGHTS.getValue("ichimoku"))
        assertEquals(DEFAULT_F2_WEIGHTS.getValue("psar"), DEFAULT_FULL_WEIGHTS.getValue("psar"))
        assertEquals(DEFAULT_F2_WEIGHTS.getValue("obv"), DEFAULT_FULL_WEIGHTS.getValue("obv"))
        // Extended indicators now carry their desktop-reference weights (not 1.0).
        assertEquals(2.5, DEFAULT_FULL_WEIGHTS.getValue("squeeze"))
        assertEquals(2.0, DEFAULT_FULL_WEIGHTS.getValue("supertrend"))
        assertEquals(0.8, DEFAULT_FULL_WEIGHTS.getValue("hist_vol_percentile"))
        // Strict filter stays at 0 in both sources.
        assertEquals(0.0, DEFAULT_FULL_WEIGHTS.getValue("atr_filter"))
    }

    @Test
    fun `user weight override persists across store restart`() {
        val kv = InMemoryKeyValueStore()
        val store = SettingsStore(kv)
        val modified = store.getSettings().copy(
            weights = store.getSettings().weights + ("squeeze" to 3.0)
        )
        store.updateSettings(modified)

        val reloaded = SettingsStore(kv)
        assertEquals(3.0, reloaded.getSettings().weights.getValue("squeeze"))
        // Unmodified keys keep their defaults.
        assertEquals(
            DEFAULT_FULL_WEIGHTS.getValue("supertrend"),
            reloaded.getSettings().weights.getValue("supertrend"),
        )
    }

    @Test
    fun `consensus engine applies wired weights to extended indicators`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        val engine = ConsensusEngine(store)

        fun result(name: String) = IndicatorResult(name = name, signal = Signal.BUY, reason = "")

        val out = engine.evaluate(listOf(result("squeeze"), result("supertrend")))
        val byName = out.signalDetails.associateBy { it.name }
        assertEquals(2.5, byName.getValue("squeeze").weight)
        assertEquals(2.0, byName.getValue("supertrend").weight)
        assertTrue(out.weightedScore != 0.0)
    }
}
