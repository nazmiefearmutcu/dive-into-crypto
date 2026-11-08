package com.diveintocrypto.android

import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.diveintocrypto.android.ui.theme.DiveColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 enforces edge-to-edge: the content draws behind the status and
        // navigation bars. The bars' backgrounds are painted by the top/bottom bar
        // composables (they pad their content with statusBarsPadding /
        // navigationBarsPadding), so the window colour APIs are no longer used.
        enableEdgeToEdge()
        val container = (application as DiveIntoCryptoApplication).container
        // A notification tap carries the route to open. It is read and removed here, so a
        // recreation (rotation) does not navigate again.
        val openRoute = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        intent?.removeExtra(EXTRA_OPEN_ROUTE)
        setContent {
            App(container = container, openRoute = openRoute)
            // SYSTEM BAR ICONS FOLLOW THE ACTIVE DIVE PRESET: the app ships light presets
            // (LEDGER·Daylight, TERMINAL·Paper) alongside dark ones. DiveColors.isDark is
            // Compose state driven by DiveThemeController — this SideEffect re-runs on
            // every preset swap and flips the status/nav icon appearance so contrast
            // always holds. WindowInsetsControllerCompat no-ops gracefully where the
            // platform lacks light-nav-bar support.
            val isDark = DiveColors.isDark
            val view = LocalView.current
            SideEffect {
                if (!view.isInEditMode) {
                    applySystemBarIcons(window, isDark)
                }
            }
        }
    }

    companion object {
        /** Intent extra carrying the [com.diveintocrypto.android.ui.nav.NavRoute] slug to open. */
        const val EXTRA_OPEN_ROUTE = "com.diveintocrypto.android.extra.OPEN_ROUTE"
    }
}

/** Keeps the status/nav bar icon colour in step with the active preset. */
private fun applySystemBarIcons(window: Window, isDark: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = !isDark
    controller.isAppearanceLightNavigationBars = !isDark
}
