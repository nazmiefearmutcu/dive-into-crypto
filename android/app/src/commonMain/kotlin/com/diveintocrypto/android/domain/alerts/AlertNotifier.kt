package com.diveintocrypto.android.domain.alerts

/**
 * Platform local-notification sink for fired alerts.
 *
 * CONTRACT: implementations MUST be failure-tolerant and MUST no-op safely when
 * the platform notification permission is absent (e.g. Android 13+
 * POST_NOTIFICATIONS not granted — the runtime permission REQUEST is the UI
 * lane's job, not the notifier's).
 */
interface AlertNotifier {
    fun notify(fired: FiredAlert)
}

/** Android → local notification on the "alerts" channel; iOS → UNUserNotification (later). */
expect fun createAlertNotifier(): AlertNotifier
