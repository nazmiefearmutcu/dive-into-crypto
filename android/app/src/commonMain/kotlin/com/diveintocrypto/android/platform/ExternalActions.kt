package com.diveintocrypto.android.platform

import androidx.compose.runtime.Composable

/**
 * Platform hooks for leaving the app or touching on-device data. Each one is a
 * composable factory so the Android actual can read LocalContext once.
 */

/** Opens an https URL in the system browser (no-op when nothing can handle it). */
@Composable
expect fun rememberOpenUrl(): (String) -> Unit

/** Opens the mail composer addressed to [address] with [subject]. */
@Composable
expect fun rememberOpenEmail(): (address: String, subject: String) -> Unit

/** Opens this app's system notification settings (permanently denied / disabled state). */
@Composable
expect fun rememberOpenNotificationSettings(): () -> Unit

/**
 * Deletes the on-device app data: the settings/preferences store (including the
 * widget state), the share-card cache and scheduled background scans. The
 * notification-prompt flag in the separate `dive_permissions` file is kept on
 * purpose (clearing it would silently break the permanently-denied notification
 * flow). Returns true when the preference store was cleared. The Android actual
 * then restarts the app, so no in-memory state survives the clear.
 */
@Composable
expect fun rememberClearLocalAppData(): () -> Boolean
