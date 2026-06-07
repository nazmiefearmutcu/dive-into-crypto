package com.diveintocrypto.android.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Dive Into Crypto design tokens — now **live & preset-driven**.
 *
 * Each token is backed by Compose state, so [apply] swaps the entire palette at
 * runtime (any of the 9 presets) and every screen recomposes — without a
 * single screen-code change, since they all still read `DiveColors.X`.
 *
 * Defaults are the "Nova Cyber" preset (futuristic neon cyan/magenta).
 */
object DiveColors {
    var Bg by mutableStateOf(Color(0xFF05060B))
    var BgCard by mutableStateOf(Color(0x9E141A2E))
    var BgCardHover by mutableStateOf(Color(0xB31E2642))
    var Border by mutableStateOf(Color(0x2978A0FF))
    var BorderStrong by mutableStateOf(Color(0x5778B4FF))
    var Text by mutableStateOf(Color(0xFFEAF0FF))
    var TextMuted by mutableStateOf(Color(0xFF8C95BC))
    var TextDim by mutableStateOf(Color(0xFF535C82))
    var Accent by mutableStateOf(Color(0xFF00E5FF))
    var Accent2 by mutableStateOf(Color(0xFFFF2BD6))
    var Green by mutableStateOf(Color(0xFF22F5A6))
    var GreenDim by mutableStateOf(Color(0xFF22F5A6))
    var Red by mutableStateOf(Color(0xFFFF3B6B))
    var RedDim by mutableStateOf(Color(0xFFFF3B6B))
    // Categorical series colors — kept fixed & distinct (NOT collapsed onto the
    // preset's 2 brand colors) so chart series stay distinguishable in every theme.
    var Yellow by mutableStateOf(Color(0xFFEAB308))
    var Orange by mutableStateOf(Color(0xFFF97316))
    var Blue by mutableStateOf(Color(0xFF3B82F6))
    var Purple by mutableStateOf(Color(0xFF8B5CF6))
    var Cyan by mutableStateOf(Color(0xFF06B6D4))

    /** Primary chart-line color (driven by the "Grafik Rengi" axis). */
    var Chart by mutableStateOf(Color(0xFF00E5FF))

    var GreenTint15 by mutableStateOf(Color(0xFF22F5A6).copy(alpha = 0.15f))
    var RedTint15 by mutableStateOf(Color(0xFFFF3B6B).copy(alpha = 0.15f))
    var YellowTint15 by mutableStateOf(Color(0xFFFFC53D).copy(alpha = 0.15f))
    var NeutralTint15 by mutableStateOf(Color(0xFF8C95BC).copy(alpha = 0.15f))
    var GreenTint25 by mutableStateOf(Color(0xFF22F5A6).copy(alpha = 0.25f))
    var RedTint25 by mutableStateOf(Color(0xFFFF3B6B).copy(alpha = 0.25f))

    /** Drives the device chrome (status bar) + Material color scheme. */
    var isDark by mutableStateOf(true)

    /** NOVA radial mesh glow center (null = flat background). The app root draws based on this. */
    var MeshCenter by mutableStateOf<Color?>(null)

    /** Screen-root background color: Transparent when the mesh is active (so the root glow shows
     *  through), otherwise the flat [Bg]. All full-screen roots use this. */
    var RootBg by mutableStateOf(Color(0xFF05060B))

    fun apply(p: DivePreset) {
        Bg = p.bg
        MeshCenter = p.meshCenter
        RootBg = if (p.meshCenter != null) Color.Transparent else p.bg
        BgCard = p.elev
        BgCardHover = p.elev2
        Border = p.border
        BorderStrong = p.borderStrong
        Text = p.text
        TextMuted = p.muted
        TextDim = p.dim
        Accent = p.accent
        Accent2 = p.accent2
        Green = p.up
        GreenDim = p.up
        Red = p.down
        RedDim = p.down
        // Cyan/Purple/Blue/Orange/Yellow are intentionally NOT re-themed — they are
        // fixed, distinct categorical series colors (kept distinguishable per theme).
        GreenTint15 = p.up.copy(alpha = 0.15f)
        RedTint15 = p.down.copy(alpha = 0.15f)
        YellowTint15 = p.warn.copy(alpha = 0.15f)
        NeutralTint15 = p.muted.copy(alpha = 0.15f)
        GreenTint25 = p.up.copy(alpha = 0.25f)
        RedTint25 = p.down.copy(alpha = 0.25f)
        isDark = p.dark
        DiveDims.apply(p.radiusDp)
    }
}

object DiveDims {
    var Radius by mutableStateOf(18.dp)
    var RadiusSm by mutableStateOf(14.dp)
    var CardPadH by mutableStateOf(14.dp)
    var CardPadV by mutableStateOf(12.dp)

    fun apply(radiusDp: Int) {
        Radius = radiusDp.dp
        RadiusSm = (radiusDp - 4).coerceAtLeast(1).dp
    }

    /** Density → card padding. compact | cozy | comfy. */
    fun applyDensity(density: String) {
        val (h, v) = when (density) {
            "compact" -> 10 to 8
            "comfy" -> 18 to 16
            else -> 14 to 12
        }
        CardPadH = h.dp
        CardPadV = v.dp
    }
}

object DiveFonts {
    val Mono = FontFamily.Monospace
    val Sans = FontFamily.SansSerif

    /** Live body font (drives screen text that reads DiveFonts.body). */
    var body by mutableStateOf(FontFamily.Monospace)

    /** Font → body. auto follows the preset (mono for TERMINAL). */
    fun applyFont(font: String, presetMono: Boolean) {
        body = when (font) {
            "mono" -> FontFamily.Monospace
            "sans" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            else -> if (presetMono) FontFamily.Monospace else FontFamily.SansSerif
        }
    }
}
