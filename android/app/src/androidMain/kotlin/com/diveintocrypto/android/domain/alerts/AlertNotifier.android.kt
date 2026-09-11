package com.diveintocrypto.android.domain.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Android local-notification [AlertNotifier].
 *
 *   - Channel "alerts" registered lazily (minSdk 26 → NotificationChannel always available).
 *   - HONEST NO-OP: when POST_NOTIFICATIONS is not granted (API 33+ runtime
 *     permission — requesting it is the UI lane's job) or the notification
 *     infrastructure throws, this silently does nothing. It NEVER crashes the
 *     engine and never fabricates success.
 */
object AndroidAlertNotifier : AlertNotifier {

    const val CHANNEL_ID = "alerts"

    @Volatile private var appContext: Context? = null

    /** Called once from [com.diveintocrypto.android.DiveIntoCryptoApplication.onCreate]. */
    fun init(context: Context) {
        appContext = context.applicationContext
        ensureChannel()
    }

    private fun ensureChannel() {
        val ctx = appContext ?: return
        runCatching {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Price / verdict alert notifications"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun notify(fired: FiredAlert) {
        val ctx = appContext ?: return
        runCatching {
            val manager = NotificationManagerCompat.from(ctx)
            // Permission gate: on API 33+ without POST_NOTIFICATIONS this is false
            // and pre-33 the user may have disabled notifications for the app.
            if (!manager.areNotificationsEnabled()) return
            ensureChannel()
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(ctx.applicationInfo.icon)
                .setContentTitle(fired.symbol)
                .setContentText(fired.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fired.message))
                .setAutoCancel(true)
                .build()
            // Stable id per fire: same-millisecond fires can replace each other,
            // distinct fires stay as separate notifications.
            manager.notify((fired.firedTs xor fired.ruleId.hashCode().toLong()).toInt(), notification)
        }
    }
}

actual fun createAlertNotifier(): AlertNotifier = AndroidAlertNotifier
