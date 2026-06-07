package com.diveintocrypto.android.data

import android.content.Context
import com.diveintocrypto.android.AppContainer

/** SharedPreferences-backed [KeyValueStore]. */
class AndroidKeyValueStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("dive_settings", Context.MODE_PRIVATE)

    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }

    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }

    override fun getString(key: String, default: String?) = prefs.getString(key, default)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

/** Android entry to build the shared [AppContainer]. */
fun createAppContainer(context: Context): AppContainer =
    AppContainer(AndroidKeyValueStore(context.applicationContext))
