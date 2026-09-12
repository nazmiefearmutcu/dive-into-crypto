package com.diveintocrypto.android.data

import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Background-scan settings keys (0.3.0): opt-in default FALSE, unmetered-only
 * default TRUE, and both round-trip through [SettingsStore.updateSettings]
 * into a fresh store over the SAME [KeyValueStore] (the restart path).
 */
class BackgroundScanSettingsTest {

    @Test
    fun `defaults are opt-in off and unmetered-only on`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        assertFalse(store.getSettings().backgroundScansEnabled)
        assertTrue(store.getSettings().backgroundScansUnmetered)
    }

    @Test
    fun `background scan flags round-trip through updateSettings`() {
        val kv = InMemoryKeyValueStore()
        val store = SettingsStore(kv)
        store.updateSettings(store.getSettings().copy(backgroundScansEnabled = true, backgroundScansUnmetered = false))

        // A fresh store over the same persisted keys sees the new values.
        val reborn = SettingsStore(kv)
        assertTrue(reborn.getSettings().backgroundScansEnabled)
        assertFalse(reborn.getSettings().backgroundScansUnmetered)
    }
}
