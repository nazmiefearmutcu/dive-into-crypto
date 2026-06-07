package com.diveintocrypto.android.data

/**
 * Minimal persistent key-value backing for [SettingsStore]. Replaces direct use
 * of Android `SharedPreferences` so the settings logic can live in commonMain.
 *
 *   - Android → SharedPreferences (AndroidKeyValueStore)
 *   - iOS     → NSUserDefaults     (IosKeyValueStore)
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
}
