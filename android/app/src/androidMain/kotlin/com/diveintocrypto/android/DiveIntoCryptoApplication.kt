package com.diveintocrypto.android

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import com.diveintocrypto.android.data.createAppContainer
import com.diveintocrypto.android.domain.alerts.AndroidAlertNotifier
import com.diveintocrypto.android.platform.syncBackgroundScans

class DiveIntoCryptoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Register the alert notification channel + app context up front. The
        // notifier itself no-ops when POST_NOTIFICATIONS is not granted.
        AndroidAlertNotifier.init(this)
        container = createAppContainer(this)
        // PS-44: re-assert the persisted background-scan setting on every process start. KEEP
        // leaves an already-scheduled run alone; the failure is recorded for the Settings card.
        syncBackgroundScans(this, ExistingPeriodicWorkPolicy.KEEP)
    }
}
