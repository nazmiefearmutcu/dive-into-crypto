package com.diveintocrypto.android.ui.notifications

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val PERMISSION_PREFS = "dive_permissions"
private const val KEY_NOTIFICATIONS_REQUESTED = "post_notifications_requested"

// Literal rather than Manifest.permission.POST_NOTIFICATIONS: that constant is API 33+ and
// minSdk is 26; every use below is already behind the SDK_INT guard in rememberNotificationPermission.
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/**
 * Android actual: API 33+ runtime POST_NOTIFICATIONS grant.
 *
 *   - The granted state is read on composition AND re-read on every ON_RESUME,
 *     so a grant (or a notifications switch) changed in system settings is
 *     reflected as soon as the user returns.
 *   - "Granted" means the runtime permission is granted AND the app's
 *     notifications are enabled at the OS level.
 *   - Once the system stops showing the dialog (permanent denial: the request
 *     was already made and shouldShowRequestPermissionRationale is false) the
 *     request action opens the app's notification settings instead of doing
 *     nothing.
 *
 * Below API 33 the permission is install-time — `needed = false`, notifications
 * simply work.
 */
@Composable
actual fun rememberNotificationPermission(): NotificationPermissionUi {
    if (Build.VERSION.SDK_INT < 33) {
        return NotificationPermissionUi(needed = false, granted = true, request = {})
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(isGranted(context)) }

    // The user may flip the switch in system settings and come back while this
    // composition is still alive: re-read the OS state on every resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> granted = isGranted(context) }

    return NotificationPermissionUi(
        needed = true,
        granted = granted,
        request = {
            val activity = context.findActivity()
            val canAskAgain = activity == null ||
                ActivityCompat.shouldShowRequestPermissionRationale(activity, POST_NOTIFICATIONS)
            val askedBefore = permissionPrefs(context).getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)
            if (askedBefore && !canAskAgain && !granted) {
                // Permanently denied (or notifications switched off in the OS):
                // the system dialog will not appear again, so go to the settings screen.
                openAppNotificationSettings(context)
            } else {
                permissionPrefs(context).edit { putBoolean(KEY_NOTIFICATIONS_REQUESTED, true) }
                launcher.launch(POST_NOTIFICATIONS)
            }
        },
    )
}

private fun isGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun permissionPrefs(context: Context) =
    context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
