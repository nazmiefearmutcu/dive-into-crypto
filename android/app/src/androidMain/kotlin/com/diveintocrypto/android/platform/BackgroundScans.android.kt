package com.diveintocrypto.android.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.diveintocrypto.android.worker.BackgroundScanScheduler

/**
 * Android actual: re-syncs the periodic WorkManager scan from the freshly
 * persisted settings (enabled → enqueue with current constraints, else cancel).
 */
@Composable
actual fun rememberBackgroundScanSync(): () -> Unit {
    val context = LocalContext.current
    return { syncBackgroundScans(context.applicationContext) }
}

/** Non-composable helper (also usable from workers/tests). */
fun syncBackgroundScans(context: Context) {
    runCatching { BackgroundScanScheduler.syncFromSettings(context) }
}
