package com.diveintocrypto.android.platform

import android.util.Log
import com.diveintocrypto.android.BuildConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

actual fun logDebug(tag: String, message: String) {
    Log.d(tag, message)
}

actual fun logError(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
}

actual fun httpEngine(): HttpClientEngineFactory<*> = OkHttp

// Android: the process keeps running while backgrounded (no explicit assertion needed
// for a short-lived scan). True long-running background would need a foreground service.
actual fun beginBackgroundTask(name: String): Long = 0L
actual fun endBackgroundTask(token: Long) {}

actual object AppInfo {
    actual val versionName: String = BuildConfig.VERSION_NAME
    actual val versionCode: Int = BuildConfig.VERSION_CODE
    actual val isDebug: Boolean = BuildConfig.DEBUG
}
