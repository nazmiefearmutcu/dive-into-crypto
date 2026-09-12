package com.diveintocrypto.android.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.diveintocrypto.android.data.createAppContainer
import java.util.concurrent.TimeUnit

/**
 * Start/stop helpers for the periodic background scan.
 *
 * CONTRACT (honest scheduling):
 *   - 15-minute PeriodicWorkRequest — the SMALLEST interval WorkManager allows;
 *     under Doze / App Standby the OS batches and defers runs arbitrarily.
 *     Nothing anywhere in the app promises exact background timing.
 *   - Constraints: charging NOT required (spec: NOT_REQUIRE_CHARGING); network
 *     CONNECTED, tightened to CONNECTED_UNMETERED when the
 *     `background_scans_unmetered` setting is on (default).
 *   - Opt-in: [syncFromSettings] enqueues only when `background_scans_enabled`
 *     (default FALSE) is set, and cancels otherwise.
 */
object BackgroundScanScheduler {

    /** Unique periodic-work name (idempotent enqueues). */
    const val UNIQUE_WORK_NAME = "dive_scan_periodic"

    /** Periodic interval — WorkManager's hard minimum is 15 minutes. */
    const val PERIOD_MINUTES: Long = 15L

    /**
     * (Re-)enqueues the periodic scan with the CURRENT stored constraints.
     * UPDATE keeps the existing cadence when only constraints changed.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        val container = createAppContainer(app)
        val settings = container.settingsStore.getSettings()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.backgroundScansUnmetered) NetworkType.UNMETERED
                else NetworkType.CONNECTED
            )
            // Spec: NOT_REQUIRE_CHARGING — never gate the scan on the charger.
            .setRequiresCharging(false)
            .build()
        val request = PeriodicWorkRequestBuilder<DiveScanWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Cancels the periodic scan (idempotent). */
    fun stop(context: Context) {
        val app = context.applicationContext
        WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Settings-driven sync — call after the user flips the background-scan
     * toggle (and on app start). Enabled → start (constraints re-read),
     * disabled → stop.
     */
    fun syncFromSettings(context: Context) {
        val settings = createAppContainer(context).settingsStore.getSettings()
        if (settings.backgroundScansEnabled) start(context) else stop(context)
    }
}
