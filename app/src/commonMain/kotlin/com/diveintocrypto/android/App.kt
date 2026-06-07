package com.diveintocrypto.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.diveintocrypto.android.ui.mobile.MobileShell
import com.diveintocrypto.android.ui.theme.DiveThemeController
import com.diveintocrypto.android.ui.theme.DiveIntoCryptoTheme
import com.diveintocrypto.android.ui.theme.DiveColors

/**
 * Shared Compose entry point. Both the Android `MainActivity` and the iOS
 * `MainViewController` render this — guaranteeing a single, identical UI tree
 * across platforms.
 *
 * Applies the two global appearance axes that can't live in a color token:
 *   - **font scale** — overrides [LocalDensity.fontScale], scaling every `sp`.
 *   - **scanlines**  — a subtle CRT overlay for TERMINAL presets.
 */
@Composable
fun App(container: AppContainer) {
    DiveIntoCryptoTheme {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, base.fontScale * DiveThemeController.fontScale)
        ) {
            val scan = DiveThemeController.scanlines
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Flat background + (NOVA) a radial neon mesh glow at the top-center.
                    // Screen roots use RootBg=Transparent so this glow shows through from behind;
                    // NOVA cards are semi-transparent (elev α≈0.62) so it also bleeds through slightly.
                    .drawBehind {
                        drawRect(DiveColors.Bg)
                        DiveColors.MeshCenter?.let { mc ->
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(mc, Color.Transparent),
                                    center = Offset(size.width * 0.5f, size.height * -0.10f),
                                    radius = maxOf(size.width, size.height) * 0.95f,
                                ),
                            )
                        }
                    }
                    .then(
                        if (scan) Modifier.drawWithContent {
                            drawContent()
                            var y = 0f
                            while (y < size.height) {
                                drawLine(
                                    color = Color(0x14000000),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
                                y += 3f
                            }
                        } else Modifier
                    )
            ) {
                MobileShell(container = container)
            }
        }
    }
}
