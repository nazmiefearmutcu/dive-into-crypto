package com.diveintocrypto.android.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.glance.appwidget.updateAll
import com.diveintocrypto.android.widget.TopVerdictsWidget
import com.diveintocrypto.android.worker.BackgroundScanScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

@Composable
actual fun rememberOpenUrl(): (String) -> Unit {
    val context = LocalContext.current
    return { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            logError("ExternalActions", "No activity can open $url", e)
        }
    }
}

@Composable
actual fun rememberOpenEmail(): (address: String, subject: String) -> Unit {
    val context = LocalContext.current
    return { address, subject ->
        val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            logError("ExternalActions", "No mail app for $address", e)
        }
    }
}

@Composable
actual fun rememberOpenNotificationSettings(): () -> Unit {
    val context = LocalContext.current
    return {
        // minSdk is 26, so ACTION_APP_NOTIFICATION_SETTINGS is always available.
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            logError("ExternalActions", "No notification settings screen", e)
        }
    }
}

@Composable
actual fun rememberClearLocalAppData(): () -> Boolean {
    val context = LocalContext.current
    return { clearAppData(context) }
}

/** The preference file behind AndroidKeyValueStore: settings, favourites, alerts, portfolio, theme, language and the widget state. */
private const val SETTINGS_PREFS = "dive_settings"

/** Request code of the relaunch alarm (any stable value; only this app uses it). */
private const val RELAUNCH_REQUEST_CODE = 0x5245

/** Delay before the relaunch alarm fires, so the process can finish its own teardown first. */
private const val RELAUNCH_DELAY_MS = 800L

/**
 * Deletes the on-device data: the [SETTINGS_PREFS] file (which also holds the widget state), the
 * share-card cache and the periodic background scan.
 *
 * Intentional survivor: the notification-prompt flag in the separate `dive_permissions` file.
 * It records that the system dialog was already shown. Clearing it would make a permanently
 * denied POST_NOTIFICATIONS request look like a first ask, so the button would silently do
 * nothing instead of opening the app's notification settings.
 *
 * Durability: the in-memory owners (AlertEngine rules and history, SettingsStore state, the
 * portfolio store) are not reset in place. Without a restart, the next alert or setting change
 * would write the old values back into the freshly cleared file. So after a successful wipe the
 * app relaunches itself and ends the current process (see [restartAfterClear]).
 */
private fun clearAppData(context: Context): Boolean {
    val app = context.applicationContext
    return try {
        BackgroundScanScheduler.stop(app)
        File(app.cacheDir, "share_cards").deleteRecursively()
        // The clear is synchronous on purpose: the caller reports success from it, so the store is
        // checked for emptiness afterwards instead of trusting a background apply().
        val prefs = app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        prefs.edit(commit = true) { clear() }
        val cleared = prefs.all.isEmpty()
        if (cleared) restartAfterClear(app)
        cleared
    } catch (e: Exception) {
        logError("ExternalActions", "Clearing local data failed", e)
        false
    }
}

/**
 * Off the UI thread: re-renders the home-screen widget from the cleared store, schedules the
 * launcher activity to start again, then ends this process. The caller has already received its
 * result, and no in-memory copy of the deleted data survives the process end.
 */
private fun restartAfterClear(app: Context) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        runCatching { TopVerdictsWidget().updateAll(app) }
        scheduleRelaunch(app)
        Process.killProcess(Process.myPid())
    }
}

/** Starts the launcher activity again after [RELAUNCH_DELAY_MS] via an alarm the system keeps across process death. */
private fun scheduleRelaunch(app: Context) {
    val relaunch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
    relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pending = PendingIntent.getActivity(
        app,
        RELAUNCH_REQUEST_CODE,
        relaunch,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
        ?.set(AlarmManager.RTC, System.currentTimeMillis() + RELAUNCH_DELAY_MS, pending)
}
