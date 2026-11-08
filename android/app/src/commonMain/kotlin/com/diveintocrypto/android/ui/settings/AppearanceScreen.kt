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
import com.diveintocrypto.android.ui.i18n.LocalDiveStrings
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Appearance — live theming: pick one of the bundled presets (count derived from
 * [PRESETS]) and tune the customization axes. Every change applies instantly and
 * persists on-device (via [DiveThemeController]). Reading the controller's state
 * here makes this screen — and the whole app — recompose on each change.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen() {
    val strings = LocalDiveStrings.current
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
        DiveCard(title = strings.i18nAppThemeTitle) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    // Derived from PRESETS — the count text can never go stale again.
                    strings.i18nAppPresetsSummary(PRESETS.size, PRESETS.map { it.family }.distinct().size),
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

        DiveCard(title = strings.appCustomization) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChipRow(
                    label = strings.appFontSize,
                    options = listOf(
                        strings.i18nAppSizeSmall to 0.85f,
                        strings.i18nAppNormal to 1.0f,
                        strings.i18nAppSizeLarge to 1.15f,
                        strings.i18nAppSizeLargest to 1.30f,
                    ),
                    selected = axes.fontScale,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(fontScale = it)) },
                )
                ChipRow(
                    label = strings.i18nAppContrast,
                    options = listOf(strings.i18nAppNormal to false, strings.i18nAppHigh to true),
                    selected = axes.highContrast,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(highContrast = it)) },
                )
                ChipRow(
                    label = strings.appOled,
                    options = listOf(strings.switchOff to false, strings.switchOn to true),
                    selected = axes.oled,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(oled = it)) },
                )
                AccentRow(
                    selected = axes.accentHex,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(accentHex = it)) },
                )
                ChipRow(
                    label = strings.i18nAppCorner,
                    options = listOf(
                        strings.i18nAppAuto to "auto",
                        strings.i18nAppSharp to "sharp",
                        strings.i18nAppSoft to "soft",
                    ),
                    selected = axes.corner,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(corner = it)) },
                )
                ChipRow(
                    label = strings.i18nAppCandleColors,
                    options = listOf(
                        strings.i18nAppAuto to "auto",
                        strings.i18nAppGreenRed to "western",
                        strings.i18nAppRedGreen to "eastern",
                        strings.i18nAppColorblind to "colorblind",
                        strings.i18nAppMonochrome to "mono",
                    ),
                    selected = axes.candle,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(candle = it)) },
                )
                ChipRow(
                    label = strings.i18nAppScanlines,
                    options = listOf(
                        strings.i18nAppAuto to "auto",
                        strings.switchOn to "on",
                        strings.switchOff to "off",
                    ),
                    selected = axes.scanlines,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(scanlines = it)) },
                )
                ChipRow(
                    label = strings.i18nAppDensity,
                    options = listOf(
                        strings.i18nAppCompact to "compact",
                        strings.i18nAppCozy to "cozy",
                        strings.i18nAppComfy to "comfy",
                    ),
                    selected = axes.density,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(density = it)) },
                )
                ChipRow(
                    label = strings.i18nAppFont,
                    options = listOf(
                        strings.i18nAppAuto to "auto",
                        strings.i18nAppFontMono to "mono",
                        strings.i18nAppFontSans to "sans",
                        strings.i18nAppFontSerif to "serif",
                    ),
                    selected = axes.font,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(font = it)) },
                )
                ChipRow(
                    label = strings.i18nAppChartType,
                    options = listOf(
                        strings.i18nAppCandle to "candle",
                        strings.i18nAppHeikin to "heikin",
                        strings.i18nAppArea to "area",
                        strings.i18nAppLine to "line",
                    ),
                    selected = axes.chartType,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(chartType = it)) },
                )
                ChipRow(
                    label = strings.i18nAppChartColor,
                    options = listOf(
                        strings.i18nAppColorAccent to "accent",
                        strings.i18nAppColorUp to "up",
                        strings.i18nAppColorViolet to "violet",
                        strings.i18nAppColorAmber to "amber",
                        strings.i18nAppColorCyan to "cyan",
                        strings.i18nAppColorWhite to "white",
                    ),
                    selected = axes.chartColor,
                    onSelect = { DiveThemeController.updateAxes(axes.copy(chartColor = it)) },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    strings.appStoredNote,
                    color = DiveColors.TextDim, fontSize = 10.sp, fontFamily = DiveFonts.body,
                )
            }
        }

        DiveCard(title = strings.appCustomColors) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    strings.appCustomColorsNote,
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
    val strings = LocalDiveStrings.current
    var editing by remember { mutableStateOf<String?>(null) }

    // The editor starts from what is ACTUALLY on screen: DiveColors holds the applied
    // colours (preset + accent override + high-contrast + OLED), so the swatch and the
    // RGB sliders never show a different colour than the one the user sees.
    fun appliedColor(token: String): Color = when (token) {
        "bg" -> DiveColors.Bg; "elev" -> DiveColors.BgCard; "elev2" -> DiveColors.BgCardHover
        "border" -> DiveColors.Border; "text" -> DiveColors.Text; "muted" -> DiveColors.TextMuted
        "accent" -> DiveColors.Accent; "accent2" -> DiveColors.Accent2
        "up" -> DiveColors.Green; "down" -> DiveColors.Red; "warn" -> DiveColors.Warn
        else -> DiveColors.Text
    }
    fun effective(token: String): Color =
        axes.customColors[token]?.let { Color(0xFF000000L or it) } ?: appliedColor(token)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DiveThemeController.CUSTOM_TOKENS.forEach { (token, _) ->
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
                    Text(tokenLabel(strings, token), color = DiveColors.TextDim, fontSize = 9.sp, fontFamily = DiveFonts.body)
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
                    Chip(text = strings.appReset, active = false, onClick = {
                        DiveThemeController.updateAxes(axes.copy(customColors = axes.customColors - token))
                    })
                }
            }
        }
        if (axes.customColors.isNotEmpty()) {
            Chip(text = strings.appResetAll, active = false, onClick = {
                DiveThemeController.updateAxes(axes.copy(customColors = emptyMap()))
            })
        }
    }
}

private fun hex2(v: Int): String = v.coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()

/** Localized label for a custom-colour token (the token ids stay stable; only the text follows the language). */
private fun tokenLabel(s: com.diveintocrypto.android.ui.i18n.DiveStrings, token: String): String = when (token) {
    "bg" -> s.i18nTokenBackground
    "elev" -> s.i18nTokenCard
    "elev2" -> s.i18nTokenCardHover
    "border" -> s.i18nTokenBorder
    "text" -> s.i18nTokenText
    "muted" -> s.i18nTokenMuted
    "accent" -> s.i18nTokenAccent
    "accent2" -> s.i18nTokenAccent2
    "up" -> s.i18nTokenUp
    "down" -> s.i18nTokenDown
    "warn" -> s.i18nTokenWarn
    else -> token
}

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
    val strings = LocalDiveStrings.current
    val swatches: List<Pair<String, Long?>> = listOf(
        strings.i18nAppColorTheme to null,
        strings.i18nAppColorCyan to 0x00E5FFL,
        strings.i18nAppColorViolet to 0x8B7BFFL,
        strings.i18nAppColorAmber to 0xFF9F3DL,
        strings.i18nAppColorGreen to 0x22F5A6L,
        strings.i18nAppColorRed to 0xFF3B6BL,
        strings.i18nAppColorBlue to 0x4D7CFFL,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.appAccentColor, color = DiveColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = DiveFonts.body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { (label, hex) ->
                val active = selected == hex
                val color = hex?.let { Color(0xFF000000L or it) } ?: DiveColors.Accent
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 48dp touch target around the 30dp swatch (PS-30).
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onSelect(hex) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(2.dp, if (active) DiveColors.Text else DiveColors.Border, CircleShape),
                        )
                    }
                    Text(label, color = if (active) DiveColors.Text else DiveColors.TextDim, fontSize = 9.sp, fontFamily = DiveFonts.body)
                }
            }
        }
    }
}
