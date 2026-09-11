package com.diveintocrypto.android

import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.diveintocrypto.android.ui.theme.DiveColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DiveIntoCryptoApplication).container
        setContent {
            App(container = container)
            // SYSTEM CHROME FOLLOWS THE ACTIVE DIVE PRESET: the app ships light presets
            // (LEDGER·Daylight, TERMINAL·Paper) alongside dark ones, so the status/nav
            // bars can't stay pinned dark. DiveColors.isDark is Compose state driven by
            // DiveThemeController — this SideEffect re-runs on every preset swap and
            // re-skins the window: bar backgrounds take the preset surfaces and the
            // icon appearance flips so contrast always holds. API 26-safe
            // (WindowInsetsControllerCompat no-ops gracefully where the platform
            // lacks light-nav-bar support).
            val isDark = DiveColors.isDark
            val view = LocalView.current
            SideEffect {
                if (!view.isInEditMode) {
                    applySystemChrome(window, isDark)
                }
            }
        }
    }
}

/** Pushes the current preset's surfaces into the Activity window's system bars. */
@Suppress("DEPRECATION") // statusBarColor/navigationBarColor setters: simplest API-26-compatible path
private fun applySystemChrome(window: Window, isDark: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = !isDark
    controller.isAppearanceLightNavigationBars = !isDark
    window.statusBarColor = DiveColors.BgCard.toArgb()   // matches the top bar surface
    window.navigationBarColor = DiveColors.Bg.toArgb()   // matches the app background
}
