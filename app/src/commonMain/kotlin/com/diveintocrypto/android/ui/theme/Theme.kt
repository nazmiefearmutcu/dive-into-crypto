package com.diveintocrypto.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dive Into Crypto theme. The color scheme is built *inside* the composable so
 * it reads the live, preset-driven [DiveColors] state — switching a preset at
 * runtime recomposes the whole tree. Honors light presets (LEDGER · Daylight).
 */
@Composable
fun DiveIntoCryptoTheme(content: @Composable () -> Unit) {
    val scheme = if (DiveColors.isDark) {
        darkColorScheme(
            background = DiveColors.Bg,
            surface = DiveColors.BgCard,
            surfaceVariant = DiveColors.BgCardHover,
            onBackground = DiveColors.Text,
            onSurface = DiveColors.Text,
            primary = DiveColors.Accent,
            onPrimary = DiveColors.Bg,
            secondary = DiveColors.Accent2,
            error = DiveColors.Red,
            outline = DiveColors.Border,
        )
    } else {
        lightColorScheme(
            background = DiveColors.Bg,
            surface = DiveColors.BgCard,
            surfaceVariant = DiveColors.BgCardHover,
            onBackground = DiveColors.Text,
            onSurface = DiveColors.Text,
            primary = DiveColors.Accent,
            onPrimary = Color.White,
            secondary = DiveColors.Accent2,
            error = DiveColors.Red,
            outline = DiveColors.Border,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
