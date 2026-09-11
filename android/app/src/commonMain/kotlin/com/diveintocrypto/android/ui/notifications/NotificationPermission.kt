package com.diveintocrypto.android.ui.notifications

import androidx.compose.runtime.Composable

/**
 * Platform-neutral notification-permission surface for the Settings
 * "BİLDİRİMLER" card. The Android actual wires the real API 33+
 * POST_NOTIFICATIONS runtime grant; platforms without such a requirement
 * report `needed = false` (honest: nothing to ask for).
 */
data class NotificationPermissionUi(
    /** true when the OS requires (and can be asked for) a runtime grant. */
    val needed: Boolean,
    /** Current grant state. true when no grant is needed (notifications just work). */
    val granted: Boolean,
    /** Fires the OS permission dialog (no-op when [needed] is false). */
    val request: () -> Unit,
)

@Composable
expect fun rememberNotificationPermission(): NotificationPermissionUi
