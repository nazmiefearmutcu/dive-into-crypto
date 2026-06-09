package com.diveintocrypto.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.theme.PRESETS
import com.diveintocrypto.android.ui.theme.DiveAxes
import com.diveintocrypto.android.ui.theme.DivePreset
import com.diveintocrypto.android.ui.theme.DiveThemeController
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Appearance — live theming: pick one of 9 presets and tune the
 * customization axes. Every change applies instantly and persists on-device
 * (via [DiveThemeController]). Reading the controller's state here makes this
 * screen — and the whole app — recompose on each change.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen() {
    val presetId = DiveThemeController.presetId
    val axes = DiveThemeController.axes

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.RootBg)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DiveCard(title = "THEME") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "9 ready-made themes · 3 families × 3 variants. Tap, applies instantly.",
                    color = DiveColors.TextDim, fontSize = 11.sp, fontFamily = DiveFonts.body,
                )
                PRESETS.groupBy { it.family }.forEach { (family, presets) ->
                    Text(
                        family, color = DiveColors.TextMuted, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        fontFamily = DiveFonts.body,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { p ->
                            PresetCard(
                                preset = p,
                                active = p.id == presetId,
                                onClick = { DiveThemeController.setPreset(p.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        DiveCard(title = "CUSTOMIZATION") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChipRow(
                    label = "Font Size",
                    options = listOf("Small" to 0.85f, "Normal" to 1.0f, "Large" to 1.15f, "Largest" to 1.30f),
                    selected = axes.fontScale,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(fontScale = it)) },
                )
                ChipRow(
                    label = "Contrast",
                    options = listOf("Normal" to false, "High" to true),
                    selected = axes.highContrast,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(highContrast = it)) },
                )
                ChipRow(
                    label = "OLED Pure Black (turns off pixels)",
                    options = listOf("Off" to false, "On" to true),
                    selected = axes.oled,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(oled = it)) },
                )
                AccentRow(
                    selected = axes.accentHex,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(accentHex = it)) },
                )
                ChipRow(
                    label = "Corner Roundness",
                    options = listOf("Auto" to "auto", "Sharp" to "sharp", "Soft" to "soft"),
                    selected = axes.corner,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(corner = it)) },
                )
                ChipRow(
                    label = "Candle Colors",
                    options = listOf(
                        "Auto" to "auto", "Green/Red" to "western",
                        "Red/Green" to "eastern", "Colorblind" to "colorblind", "Monochrome" to "mono",
                    ),
                    selected = axes.candle,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(candle = it)) },
                )
                ChipRow(
                    label = "Motion",
                    options = listOf("Off" to "off", "Subtle" to "subtle", "Full" to "full"),
                    selected = axes.motion,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(motion = it)) },
                )
                ChipRow(
                    label = "Scanlines (CRT)",
                    options = listOf("Auto" to "auto", "On" to "on", "Off" to "off"),
                    selected = axes.scanlines,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(scanlines = it)) },
                )
                ChipRow(
                    label = "Density",
                    options = listOf("Compact" to "compact", "Cozy" to "cozy", "Comfy" to "comfy"),
                    selected = axes.density,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(density = it)) },
                )
                ChipRow(
                    label = "Font",
                    options = listOf("Auto" to "auto", "Mono" to "mono", "Sans" to "sans", "Serif" to "serif"),
                    selected = axes.font,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(font = it)) },
                )
                ChipRow(
                    label = "Chart Type",
                    options = listOf("Candle" to "candle", "Heikin" to "heikin", "Area" to "area", "Line" to "line"),
                    selected = axes.chartType,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(chartType = it)) },
                )
                ChipRow(
                    label = "Chart Color",
                    options = listOf(
                        "Accent" to "accent", "Up" to "up", "Violet" to "violet",
                        "Amber" to "amber", "Cyan" to "cyan", "White" to "white",
                    ),
                    selected = axes.chartColor,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(chartColor = it)) },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "All preferences are stored on this device.",
                    color = DiveColors.TextDim, fontSize = 10.sp, fontFamily = DiveFonts.body,
                )
            }
        }

        DiveCard(title = "CUSTOM COLORS") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Set each color yourself — tap a swatch, slide R/G/B. Stored on-device.",
                    color = DiveColors.TextDim, fontSize = 11.sp, fontFamily = DiveFonts.body,
                )
                CustomColorEditor(axes = axes)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomColorEditor(axes: DiveAxes) {
    var editing by remember { mutableStateOf<String?>(null) }
    val preset = DiveThemeController.preset

    fun presetColor(token: String): Color = when (token) {
        "bg" -> preset.bg; "elev" -> preset.elev; "elev2" -> preset.elev2
        "border" -> preset.border; "text" -> preset.text; "muted" -> preset.muted
        "accent" -> preset.accent; "accent2" -> preset.accent2
        "up" -> preset.up; "down" -> preset.down; "warn" -> preset.warn
        else -> preset.text
    }
    fun effective(token: String): Color =
        axes.customColors[token]?.let { Color(0xFF000000L or it) } ?: presetColor(token)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DiveThemeController.CUSTOM_TOKENS.forEach { (token, label) ->
                val c = effective(token)
                val isCustom = axes.customColors.containsKey(token)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c)
                            .border(
                                if (editing == token) 3.dp else 1.dp,
                                if (editing == token) DiveColors.Accent else if (isCustom) DiveColors.Text else DiveColors.Border,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { editing = if (editing == token) null else token },
                    )
                    Text(label, color = DiveColors.TextDim, fontSize = 9.sp, fontFamily = DiveFonts.body)
                }
            }
        }
        editing?.let { token ->
            val cur = effective(token)
            val r = (cur.red * 255f).toInt().coerceIn(0, 255)
            val g = (cur.green * 255f).toInt().coerceIn(0, 255)
            val b = (cur.blue * 255f).toInt().coerceIn(0, 255)
            fun setRgb(nr: Int, ng: Int, nb: Int) {
                val packed = (nr.coerceIn(0, 255).toLong() shl 16) or
                    (ng.coerceIn(0, 255).toLong() shl 8) or nb.coerceIn(0, 255).toLong()
                DiveThemeController.updateAxes(axes.copy(customColors = axes.customColors + (token to packed)))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorSlider("R", r) { setRgb(it, g, b) }
                ColorSlider("G", g) { setRgb(r, it, b) }
                ColorSlider("B", b) { setRgb(r, g, it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#${hex2(r)}${hex2(g)}${hex2(b)}", color = DiveColors.TextMuted, fontSize = 11.sp, fontFamily = DiveFonts.Mono)
                    Spacer(Modifier.weight(1f))
                    Chip(text = "Reset", active = false, onClick = {
                        DiveThemeController.updateAxes(axes.copy(customColors = axes.customColors - token))
                    })
                }
            }
        }
        if (axes.customColors.isNotEmpty()) {
            Chip(text = "Reset All", active = false, onClick = {
                DiveThemeController.updateAxes(axes.copy(customColors = emptyMap()))
            })
        }
    }
}

private fun hex2(v: Int): String = v.coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()

@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = DiveColors.TextMuted, fontSize = 11.sp, fontFamily = DiveFonts.body, modifier = Modifier.width(14.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(value.toString(), color = DiveColors.TextDim, fontSize = 10.sp, fontFamily = DiveFonts.body, modifier = Modifier.width(30.dp))
    }
}

@Composable
private fun PresetCard(preset: DivePreset, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DiveDims.RadiusSm))
            .background(preset.bg)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) DiveColors.Accent else DiveColors.Border,
                shape = RoundedCornerShape(DiveDims.RadiusSm),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Dot(preset.accent); Dot(preset.up); Dot(preset.down)
        }
        Text(
            preset.label, color = preset.text, fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = DiveColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = DiveFonts.body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (disp, value) ->
                Chip(text = disp, active = value == selected, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DiveDims.RadiusSm))
            .background(if (active) DiveColors.Accent.copy(alpha = 0.18f) else DiveColors.BgCardHover)
            .border(1.dp, if (active) DiveColors.Accent else DiveColors.Border, RoundedCornerShape(DiveDims.RadiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text, color = if (active) DiveColors.Accent else DiveColors.Text,
            fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontFamily = DiveFonts.body,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccentRow(selected: Long?, onSelect: (Long?) -> Unit) {
    val swatches: List<Pair<String, Long?>> = listOf(
        "Theme" to null, "Cyan" to 0x00E5FFL, "Violet" to 0x8B7BFFL,
        "Amber" to 0xFF9F3DL, "Green" to 0x22F5A6L, "Red" to 0xFF3B6BL, "Blue" to 0x4D7CFFL,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Accent Color", color = DiveColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = DiveFonts.body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { (label, hex) ->
                val active = selected == hex
                val color = hex?.let { Color(0xFF000000L or it) } ?: DiveColors.Accent
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(2.dp, if (active) DiveColors.Text else DiveColors.Border, CircleShape)
                            .clickable { onSelect(hex) },
                    )
                    Text(label, color = if (active) DiveColors.Text else DiveColors.TextDim, fontSize = 9.sp, fontFamily = DiveFonts.body)
                }
            }
        }
    }
}
