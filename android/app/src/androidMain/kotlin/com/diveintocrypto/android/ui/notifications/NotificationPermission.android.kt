package com.diveintocrypto.android.ui.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android actual: API 33+ runtime POST_NOTIFICATIONS grant via
 * [rememberLauncherForActivityResult] + [ActivityResultContracts.RequestPermission].
 * Below API 33 the permission is install-time — `needed = false`, notifications
 * simply work. The granted state is snapshotted on composition and updated by
 * the launcher result (an honest session view; no polling).
 */
@Composable
actual fun rememberNotificationPermission(): NotificationPermissionUi {
    if (Build.VERSION.SDK_INT < 33) {
        return NotificationPermissionUi(needed = false, granted = true, request = {})
    }

    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    return NotificationPermissionUi(
        needed = true,
        granted = granted,
        request = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
    )
}
