package com.diveintocrypto.android.testutil

import com.diveintocrypto.android.data.KeyValueStore

/** In-memory [KeyValueStore] implementation for tests — no persistence, no I/O. */
class InMemoryKeyValueStore : KeyValueStore {
    private val ints = mutableMapOf<String, Int>()
    private val booleans = mutableMapOf<String, Boolean>()
    private val floats = mutableMapOf<String, Float>()
    private val strings = mutableMapOf<String, String?>()

    override fun getInt(key: String, default: Int): Int = ints.getOrDefault(key, default)
    override fun putInt(key: String, value: Int) { ints[key] = value }

    override fun getBoolean(key: String, default: Boolean): Boolean = booleans.getOrDefault(key, default)
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }

    override fun getFloat(key: String, default: Float): Float = floats.getOrDefault(key, default)
    override fun putFloat(key: String, value: Float) { floats[key] = value }

    override fun getString(key: String, default: String?): String? = strings.getOrElse(key) { default }
    override fun putString(key: String, value: String) { strings[key] = value }
}
