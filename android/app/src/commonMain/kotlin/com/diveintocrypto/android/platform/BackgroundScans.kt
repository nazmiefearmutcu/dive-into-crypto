package com.diveintocrypto.android.platform

import androidx.compose.runtime.Composable

/**
 * Platform-neutral background-scan sync hook. Android wires WorkManager's
 * [com.diveintocrypto.android.worker.BackgroundScanScheduler.syncFromSettings]:
 * call AFTER persisting `backgroundScansEnabled/Unmetered` so the periodic
 * work is enqueued (enabled) or cancelled (disabled) with fresh constraints.
 */
@Composable
expect fun rememberBackgroundScanSync(): () -> Unit

/**
 * true while the most recent background-scan sync (see [rememberBackgroundScanSync]) failed,
 * so Settings can say the scan may not run instead of showing a silent "ON". Recomposes on change.
 */
@Composable
expect fun rememberBackgroundScanSyncFailed(): Boolean
