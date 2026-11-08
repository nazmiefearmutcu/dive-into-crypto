package com.diveintocrypto.android.data

/**
 * Minimal persistent key-value backing for [SettingsStore]. Replaces direct use
 * of Android `SharedPreferences` so the settings logic can live in commonMain.
 *
 *   - Android → SharedPreferences (AndroidKeyValueStore) — the only implementation today.
 *
 * Writes persist immediately (the original batched then `apply()`; semantics are
 * identical — every value is durably saved).
 */
interface KeyValueStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)

    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)

    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)

    /**
     * Applies several writes as one batch. Implementations that can commit atomically (one
     * SharedPreferences edit) override this; the default simply performs the puts in order.
     */
    fun edit(writes: KeyValueEditor.() -> Unit) {
        val target = this
        writes(object : KeyValueEditor {
            override fun putInt(key: String, value: Int) = target.putInt(key, value)
            override fun putBoolean(key: String, value: Boolean) = target.putBoolean(key, value)
            override fun putFloat(key: String, value: Float) = target.putFloat(key, value)
            override fun putString(key: String, value: String) = target.putString(key, value)
        })
    }
}

/** Write-only view handed to [KeyValueStore.edit]; reads go through the store itself. */
interface KeyValueEditor {
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun putFloat(key: String, value: Float)
    fun putString(key: String, value: String)
}
