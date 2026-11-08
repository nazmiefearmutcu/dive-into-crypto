package com.diveintocrypto.android.domain.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.diveintocrypto.android.MainActivity
import com.diveintocrypto.android.R
import com.diveintocrypto.android.data.AndroidKeyValueStore
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.ui.i18n.DiveStrings
import com.diveintocrypto.android.ui.i18n.languageFor
import com.diveintocrypto.android.ui.nav.NavRoute

/**
 * Android local-notification [AlertNotifier].
 *
 *   - Channel "alerts" (minSdk 26 → NotificationChannel always available). Its
 *     name and description follow the app language and are refreshed on every
 *     post, so a language switch is picked up.
 *   - Tapping a notification opens [MainActivity] (immutable PendingIntent).
 *   - The status-bar small icon is the monochrome `ic_stat_cool` silhouette; the
 *     full-colour launcher PNG renders as a solid blob in the status bar.
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

    private fun strings(ctx: Context): DiveStrings = languageFor(AndroidKeyValueStore(ctx))

    /**
     * Creates the channel, or updates its name/description when it already exists
     * (NotificationManager applies the new label to an existing channel).
     */
    private fun ensureChannel() {
        val ctx = appContext ?: return
        runCatching {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val text = strings(ctx)
            val channel = NotificationChannel(
                CHANNEL_ID,
                text.platChannelName,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = text.platChannelDesc
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun notify(fired: FiredAlert) {
        val ctx = appContext ?: return
        runCatching {
            val manager = NotificationManagerCompat.from(ctx)
            // Permission gate: on API 33+ without POST_NOTIFICATIONS this is false
            // and pre-33 the user may have disabled notifications for the app.
            if (!manager.areNotificationsEnabled()) return
            // API 33+: the runtime grant is checked explicitly right before posting (lint
            // MissingPermission). A revoked grant is an honest no-op, never a crash.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel()
            // Stable id per fire: same-millisecond fires can replace each other,
            // distinct fires stay as separate notifications.
            val notificationId = (fired.firedTs xor fired.ruleId.hashCode().toLong()).toInt()
            val openApp = PendingIntent.getActivity(
                ctx,
                notificationId,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    // A tap opens the Alarms route (consumed once by MainActivity / the nav graph).
                    .putExtra(MainActivity.EXTRA_OPEN_ROUTE, NavRoute.ALERTS.slug),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_cool)
                .setContentTitle(fired.symbol)
                .setContentText(fired.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fired.message))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(notificationId, notification)
            } catch (e: SecurityException) {
                // The grant was withdrawn between the check above and the post.
                logError("AlertNotifier", "notification post refused", e)
            }
        }
    }
}

actual fun createAlertNotifier(): AlertNotifier = AndroidAlertNotifier
