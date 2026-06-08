package com.diveintocrypto.android.platform

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Platform abstraction layer for the Compose Multiplatform port.
 *
 * Every Android-framework dependency that the shared (`commonMain`) code used
 * to reach for directly is funnelled through these `expect` declarations.
 * Android (`androidMain`) and iOS (`iosMain`) provide the `actual`s.
 */

/** Debug-level log. Android → android.util.Log.d; iOS → println (Xcode console). */
expect fun logDebug(tag: String, message: String)

/** Error-level log. Android → android.util.Log.e; iOS → println (Xcode console). */
expect fun logError(tag: String, message: String, throwable: Throwable? = null)

/**
 * Ktor HTTP engine for the running platform.
 *   - Android → OkHttp
 *   - iOS     → Darwin (NSURLSession)
 */
expect fun httpEngine(): HttpClientEngineFactory<*>

/**
 * Best-effort background-execution assertion so an IN-FLIGHT scan cycle can keep
 * running for a short window after the app is backgrounded.
 *   - iOS     → UIApplication.beginBackgroundTask (≈30 s window, then OS suspends)
 *   - Android → no-op (the process keeps running while backgrounded)
 * Returns an opaque token to pass back to [endBackgroundTask]. NOTE: iOS does not
 * permit indefinite background work; this only extends the immediate window.
 */
expect fun beginBackgroundTask(name: String): Long
expect fun endBackgroundTask(token: Long)

/** App version / build identity, replacing the Android-only generated BuildConfig. */
expect object AppInfo {
    val versionName: String
    val versionCode: Int
    val isDebug: Boolean
}
