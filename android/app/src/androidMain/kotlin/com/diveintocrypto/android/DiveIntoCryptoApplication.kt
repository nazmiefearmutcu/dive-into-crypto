package com.diveintocrypto.android

import android.app.Application
import com.diveintocrypto.android.data.createAppContainer
import com.diveintocrypto.android.domain.alerts.AndroidAlertNotifier

class DiveIntoCryptoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Register the alert notification channel + app context up front. The
        // notifier itself no-ops when POST_NOTIFICATIONS is not granted.
        AndroidAlertNotifier.init(this)
        container = createAppContainer(this)
    }
}
