package com.diveintocrypto.android.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.work.ExistingPeriodicWorkPolicy
import com.diveintocrypto.android.worker.BackgroundScanScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Outcome of the most recent settings-driven WorkManager sync. The Settings card
 * can observe [lastSyncFailed] to show a warning instead of a silent "ON".
 */
object BackgroundScanSyncStatus {
    private val failed = MutableStateFlow(false)

    /** true when the last enqueue/cancel threw; false after a successful sync. */
    val lastSyncFailed: StateFlow<Boolean> = failed.asStateFlow()

    internal fun record(syncFailed: Boolean) {
        failed.value = syncFailed
    }
}

/**
 * Android actual: re-syncs the periodic WorkManager scan from the freshly
 * persisted settings (enabled → enqueue with current constraints, else cancel).
 */
@Composable
actual fun rememberBackgroundScanSync(): () -> Unit {
    val context = LocalContext.current
    return { syncBackgroundScans(context.applicationContext) }
}

@Composable
actual fun rememberBackgroundScanSyncFailed(): Boolean =
    BackgroundScanSyncStatus.lastSyncFailed.collectAsState().value

/**
 * Non-composable helper (also usable from workers/tests). Returns false and logs
 * the cause when WorkManager rejected the enqueue/cancel, and records the failure
 * in [BackgroundScanSyncStatus] so the UI can say so. [policy] is UPDATE for setting
 * changes and KEEP for the app-start sync (see [BackgroundScanScheduler.syncFromSettings]).
 */
fun syncBackgroundScans(
    context: Context,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
): Boolean {
    return try {
        BackgroundScanScheduler.syncFromSettings(context, policy)
        BackgroundScanSyncStatus.record(syncFailed = false)
        true
    } catch (t: Throwable) {
        logError("BackgroundScans", "background scan sync failed", t)
        BackgroundScanSyncStatus.record(syncFailed = true)
        false
    }
}
