package com.diveintocrypto.android.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.diveintocrypto.android.data.KeyValueStore

/**
 * Dive Into Crypto theme presets — ported verbatim from the design
 * deliverable's `theme.js` (3 families × 3 variants = 9 presets).
 *
 *   NOVA     · futuristic (neon/glass/depth)
 *   LEDGER   · classic (clean corporate finance)
 *   TERMINAL · night (monospace, low color, CRT)
 *
 * Applying a preset swaps the live [DiveColors] tokens, re-skinning the whole app.
 */
data class DivePreset(
    val id: String,
    val family: String,
    val label: String,
    val dark: Boolean,
    val radiusDp: Int,
    val mono: Boolean,
    val bg: Color,
    val bg2: Color,
    val elev: Color,
    val elev2: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val accent: Color,
    val accent2: Color,
    val up: Color,
    val down: Color,
    val warn: Color,
    /** NOVA signature look: the color of the top-center radial neon glow.
     *  null = no mesh (flat background). Set only on NOVA presets. */
    val meshCenter: Color? = null,
)

private fun rgb(v: Long): Color = Color(0xFF000000L or v)
private fun rgba(r: Int, g: Int, b: Int, a: Double): Color =
    Color(red = r, green = g, blue = b, alpha = (a * 255.0 + 0.5).toInt().coerceIn(0, 255))

val PRESETS: List<DivePreset> = listOf(
    // ── NOVA · futuristic ────────────────────────────────────────────────
    DivePreset("nova-cyber", "NOVA", "Cyber", dark = true, radiusDp = 18, mono = false,
        bg = rgb(0x05060B), bg2 = rgb(0x080A14),
        elev = rgba(20, 26, 46, 0.62), elev2 = rgba(30, 38, 66, 0.70),
        border = rgba(120, 160, 255, 0.16), borderStrong = rgba(120, 180, 255, 0.34),
        text = rgb(0xEAF0FF), muted = rgb(0x8C95BC), dim = rgb(0x535C82),
        accent = rgb(0x00E5FF), accent2 = rgb(0xFF2BD6),
        up = rgb(0x22F5A6), down = rgb(0xFF3B6B), warn = rgb(0xFFC53D),
        meshCenter = rgb(0x12222E)),
    DivePreset("nova-aurora", "NOVA", "Aurora", dark = true, radiusDp = 22, mono = false,
        bg = rgb(0x0A0817), bg2 = rgb(0x0E0B20),
        elev = rgba(28, 24, 54, 0.60), elev2 = rgba(40, 34, 76, 0.70),
        border = rgba(150, 135, 255, 0.18), borderStrong = rgba(150, 135, 255, 0.36),
        text = rgb(0xF0ECFF), muted = rgb(0x9C93C8), dim = rgb(0x5E5688),
        accent = rgb(0x8B7BFF), accent2 = rgb(0x3BE0C8),
        up = rgb(0x38E8B0), down = rgb(0xFF6B8A), warn = rgb(0xFFD06B),
        meshCenter = rgb(0x1B1838)),
    DivePreset("nova-solar", "NOVA", "Solar", dark = true, radiusDp = 16, mono = false,
        bg = rgb(0x0C0805), bg2 = rgb(0x120B06),
        elev = rgba(40, 28, 18, 0.60), elev2 = rgba(56, 38, 22, 0.70),
        border = rgba(255, 160, 90, 0.16), borderStrong = rgba(255, 160, 90, 0.36),
        text = rgb(0xFFF1E6), muted = rgb(0xC4A088), dim = rgb(0x7E6048),
        accent = rgb(0xFF8A3D), accent2 = rgb(0xFFD23D),
        up = rgb(0x6FE85A), down = rgb(0xFF4D4D), warn = rgb(0xFFD23D),
        meshCenter = rgb(0x231509)),
    // ── LEDGER · classic ─────────────────────────────────────────────────
    DivePreset("ledger-daylight", "LEDGER", "Daylight", dark = false, radiusDp = 10, mono = false,
        bg = rgb(0xF1F3F7), bg2 = rgb(0xE9ECF2),
        elev = rgb(0xFFFFFF), elev2 = rgb(0xF6F8FB),
        border = rgb(0xE1E5EC), borderStrong = rgb(0xC9D0DC),
        text = rgb(0x0E1422), muted = rgb(0x5A6478), dim = rgb(0x9AA3B5),
        accent = rgb(0x1B4DFF), accent2 = rgb(0x16357A),
        up = rgb(0x0F9E6E), down = rgb(0xE0334B), warn = rgb(0xC88A00)),
    DivePreset("ledger-graphite", "LEDGER", "Graphite", dark = true, radiusDp = 10, mono = false,
        bg = rgb(0x14161B), bg2 = rgb(0x181B22),
        elev = rgb(0x1D2027), elev2 = rgb(0x252932),
        border = rgb(0x2C313B), borderStrong = rgb(0x3C4250),
        text = rgb(0xE6E9EF), muted = rgb(0x99A1B0), dim = rgb(0x646C7C),
        accent = rgb(0x4D7CFF), accent2 = rgb(0x6E92FF),
        up = rgb(0x1FBF7A), down = rgb(0xF0506A), warn = rgb(0xE0A21E)),
    DivePreset("ledger-royal", "LEDGER", "Royal", dark = true, radiusDp = 8, mono = false,
        bg = rgb(0x0A0F1E), bg2 = rgb(0x0D1426),
        elev = rgb(0x121A30), elev2 = rgb(0x1A2440),
        border = rgb(0x243254), borderStrong = rgb(0x37487A),
        text = rgb(0xEEF2FB), muted = rgb(0x9FB0D0), dim = rgb(0x5E6E92),
        accent = rgb(0xD9B45A), accent2 = rgb(0xF0CE7A),
        up = rgb(0x3FBF8F), down = rgb(0xE06A6A), warn = rgb(0xD9B45A)),
    // ── TERMINAL · night ─────────────────────────────────────────────────
    DivePreset("term-phosphor", "TERMINAL", "Phosphor", dark = true, radiusDp = 2, mono = true,
        bg = rgb(0x020604), bg2 = rgb(0x04100A),
        elev = rgb(0x061410), elev2 = rgb(0x0A1F17),
        border = rgb(0x10402A), borderStrong = rgb(0x1C6B45),
        text = rgb(0x46FFA8), muted = rgb(0x1FB872), dim = rgb(0x0E6B43),
        accent = rgb(0x38FF9E), accent2 = rgb(0x1FB872),
        up = rgb(0x38FF9E), down = rgb(0xFF6155), warn = rgb(0xE8D44D)),
    DivePreset("term-amber", "TERMINAL", "Amber", dark = true, radiusDp = 2, mono = true,
        bg = rgb(0x0A0600), bg2 = rgb(0x140C02),
        elev = rgb(0x160D02), elev2 = rgb(0x241606),
        border = rgb(0x4A2E08), borderStrong = rgb(0x7A4E12),
        text = rgb(0xFFB638), muted = rgb(0xB97E1E), dim = rgb(0x6E4A12),
        accent = rgb(0xFFB638), accent2 = rgb(0xC8841A),
        up = rgb(0xFFC857), down = rgb(0xFF6155), warn = rgb(0xFFC857)),
    DivePreset("term-ice", "TERMINAL", "Ice", dark = true, radiusDp = 4, mono = true,
        bg = rgb(0x04070A), bg2 = rgb(0x070C12),
        elev = rgb(0x0A111A), elev2 = rgb(0x101A26),
        border = rgb(0x16242F), borderStrong = rgb(0x27414F),
        text = rgb(0xDCEBF5), muted = rgb(0x6E8597), dim = rgb(0x3E4F5C),
        accent = rgb(0x56C7FF), accent2 = rgb(0x2E8FD0),
        up = rgb(0x45E0B0), down = rgb(0xFF6B7E), warn = rgb(0xE8C24D)),
)

const val DEFAULT_PRESET = "nova-cyber"

fun divePresetById(id: String?): DivePreset =
    PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

/** Live customization axes layered on top of any preset (the "Appearance" screen). */
data class DiveAxes(
    val fontScale: Float = 1.0f,       // 0.85 .. 1.30
    val highContrast: Boolean = false,
    val candle: String = "auto",        // auto | western | eastern | colorblind | mono
    val accentHex: Long? = null,        // null = preset accent; else 0xRRGGBB
    val corner: String = "auto",        // auto | sharp | soft
    val motion: String = "full",        // off | subtle | full
    val scanlines: String = "auto",     // auto | on | off
    val density: String = "cozy",       // compact | cozy | comfy
    val font: String = "auto",          // auto | mono | sans | serif
    val chartType: String = "candle",   // candle | heikin | area | line
    val chartColor: String = "accent",  // accent | up | violet | amber | cyan | white
    /** Custom theme: token-name → 0xRRGGBB sparse override. When empty, the preset color is used. */
    val customColors: Map<String, Long> = emptyMap(),
    /** OLED pure-black mode: makes bg/elev #000000 (OLED pixels turn off), disables the mesh. */
    val oled: Boolean = false,
)

/** Resolve the chart-line color for a chartColor choice. */
private fun diveChartColor(choice: String, accent: Color, up: Color): Color = when (choice) {
    "up" -> up
    "violet" -> Color(0xFF8B7BFF)
    "amber" -> Color(0xFFFF9F3D)
    "cyan" -> Color(0xFF39D6FF)
    "white" -> Color(0xFFE8EEF8)
    else -> accent
}

/** Up/Down (green/red) colors for the selected candle scheme — mirrors theme.js. */
private fun diveCandle(p: DivePreset, scheme: String): Pair<Color, Color> {
    val s = if (scheme == "auto") (if (p.mono) "mono" else "western") else scheme
    return when (s) {
        "western" -> Color(0xFF22C55E) to Color(0xFFEF4444)
        "eastern" -> Color(0xFFEF4444) to Color(0xFF22C55E)
        "colorblind" -> Color(0xFF2D8CFF) to Color(0xFFFF8A3D)
        "mono" -> p.accent to p.muted
        else -> p.up to p.down
    }
}

/**
 * Single source of truth for the live theme. Holds the chosen preset + axes,
 * resolves them into the [DiveColors]/[DiveDims] tokens + a few global flags read
 * by `App()` (font scale, scanline overlay, motion). Persists every change to
 * the device via [KeyValueStore].
 */
object DiveThemeController {
    var presetId by mutableStateOf(DEFAULT_PRESET)
    var axes by mutableStateOf(DiveAxes())

    // Globals consumed by App()/overlays:
    var fontScale by mutableStateOf(1.0f)
    var scanlines by mutableStateOf(false)
    var motionFull by mutableStateOf(true)
    var chartArea by mutableStateOf(true)
    var meshBg by mutableStateOf(true)   // NOVA radial neon mesh background

    val preset: DivePreset get() = divePresetById(presetId)

    private var kv: KeyValueStore? = null

    fun init(store: KeyValueStore) {
        kv = store
        presetId = store.getString(K_PRESET, DEFAULT_PRESET) ?: DEFAULT_PRESET
        val schema = store.getInt(K_SCHEMA, 0)
        var chartType = store.getString(K_CHARTTYPE, "candle") ?: "candle"
        // Migration v2: installs from before the candle chart persisted a default of "area"/"line"
        // → give the user "candle" once (they can pick whatever they want afterward).
        if (schema < 2 && (chartType == "area" || chartType == "line")) chartType = "candle"
        axes = DiveAxes(
            fontScale = store.getFloat(K_FONT, 1.0f),
            highContrast = store.getBoolean(K_CONTRAST, false),
            candle = store.getString(K_CANDLE, "auto") ?: "auto",
            accentHex = store.getString(K_ACCENT, "")?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
            corner = store.getString(K_CORNER, "auto") ?: "auto",
            motion = store.getString(K_MOTION, "full") ?: "full",
            scanlines = store.getString(K_SCAN, "auto") ?: "auto",
            density = store.getString(K_DENSITY, "cozy") ?: "cozy",
            font = store.getString(K_FONTFAM, "auto") ?: "auto",
            chartType = chartType,
            chartColor = store.getString(K_CHARTCOLOR, "accent") ?: "accent",
            customColors = unpackCustom(store.getString(K_CUSTOM, "") ?: ""),
            oled = store.getBoolean(K_OLED, false),
        )
        if (schema < SCHEMA_VERSION) store.putInt(K_SCHEMA, SCHEMA_VERSION)
        apply()
    }

    fun setPreset(id: String) { presetId = id; persist(); apply() }
    fun updateAxes(update: DiveAxes) { axes = update; persist(); apply() }

    private fun persist() {
        val s = kv ?: return
        s.putString(K_PRESET, presetId)
        s.putFloat(K_FONT, axes.fontScale)
        s.putBoolean(K_CONTRAST, axes.highContrast)
        s.putString(K_CANDLE, axes.candle)
        s.putString(K_ACCENT, axes.accentHex?.toString() ?: "")
        s.putString(K_CORNER, axes.corner)
        s.putString(K_MOTION, axes.motion)
        s.putString(K_SCAN, axes.scanlines)
        s.putString(K_DENSITY, axes.density)
        s.putString(K_FONTFAM, axes.font)
        s.putString(K_CHARTTYPE, axes.chartType)
        s.putString(K_CHARTCOLOR, axes.chartColor)
        s.putString(K_CUSTOM, packCustom(axes.customColors))
        s.putBoolean(K_OLED, axes.oled)
        s.putInt(K_SCHEMA, SCHEMA_VERSION)
    }

    private fun packCustom(m: Map<String, Long>): String =
        m.entries.joinToString("|") { "${it.key}:${it.value.toString(16).padStart(6, '0')}" }

    private fun unpackCustom(s: String): Map<String, Long> {
        if (s.isBlank()) return emptyMap()
        return s.split("|").mapNotNull { part ->
            val i = part.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val hex = part.substring(i + 1).toLongOrNull(16) ?: return@mapNotNull null
            part.substring(0, i) to hex
        }.toMap()
    }

    fun apply() {
        val p = preset
        val a = axes
        // Custom color override: if the user set a token, use it; otherwise the base.
        fun pick(name: String, base: Color): Color =
            a.customColors[name]?.let { Color(0xFF000000L or it) } ?: base

        val accentBase = a.accentHex?.let { Color(0xFF000000L or it) } ?: p.accent
        val accent = pick("accent", accentBase)
        val textBase = if (a.highContrast) (if (p.dark) Color(0xFFFFFFFF) else Color(0xFF000000)) else p.text
        val text = pick("text", textBase)
        val borderBase = if (a.highContrast) p.borderStrong else p.border
        val border = pick("border", borderBase)
        val (upBase, downBase) = diveCandle(p, a.candle)
        val up = pick("up", upBase)
        val down = pick("down", downBase)
        val radius = when (a.corner) {
            "sharp" -> minOf(p.radiusDp, 3)
            "soft" -> p.radiusDp + 10
            else -> p.radiusDp
        }
        var resolved = p.copy(
            bg = pick("bg", p.bg), bg2 = pick("bg2", p.bg2),
            elev = pick("elev", p.elev), elev2 = pick("elev2", p.elev2),
            border = border, borderStrong = pick("borderStrong", p.borderStrong),
            text = text, muted = pick("muted", p.muted), dim = pick("dim", p.dim),
            accent = accent, accent2 = pick("accent2", p.accent2),
            up = up, down = down, warn = pick("warn", p.warn),
            meshCenter = p.meshCenter?.takeIf { meshBg }, // flat background when meshBg is off
        )
        // OLED pure black: make all surfaces #000000 (pixels turn off), disable the mesh, and
        // strengthen the card border so hairline edges stay visible, with fully-white text.
        if (a.oled) {
            val black = Color(0xFF000000)
            resolved = resolved.copy(
                bg = black, bg2 = black, elev = black, elev2 = black,
                meshCenter = null,
                text = Color(0xFFFFFFFF),
                border = resolved.borderStrong,
            )
        }
        DiveColors.apply(resolved)
        DiveColors.Chart = diveChartColor(a.chartColor, accent, up)
        DiveDims.apply(radius)
        DiveDims.applyDensity(a.density)
        DiveFonts.applyFont(a.font, p.mono)
        fontScale = a.fontScale.coerceIn(0.7f, 1.5f)
        motionFull = a.motion != "off"
        scanlines = when (a.scanlines) { "on" -> true; "off" -> false; else -> p.mono }
        chartArea = a.chartType != "line"
    }

    private const val K_PRESET = "dive_preset"
    private const val K_FONT = "dive_fontScale"
    private const val K_CONTRAST = "dive_contrast"
    private const val K_CANDLE = "dive_candle"
    private const val K_ACCENT = "dive_accentHex"
    private const val K_CORNER = "dive_corner"
    private const val K_MOTION = "dive_motion"
    private const val K_SCAN = "dive_scanlines"
    private const val K_DENSITY = "dive_density"
    private const val K_FONTFAM = "dive_font"
    private const val K_CHARTTYPE = "dive_chartType"
    private const val K_CHARTCOLOR = "dive_chartColor"
    private const val K_CUSTOM = "dive_customColors"
    private const val K_OLED = "dive_oled"
    private const val K_SCHEMA = "dive_schema"
    private const val SCHEMA_VERSION = 2

    /** Tokens editable in the custom-color panel (the UI iterates over these). */
    val CUSTOM_TOKENS: List<Pair<String, String>> = listOf(
        "bg" to "Background", "elev" to "Card", "elev2" to "Card (Hover)",
        "border" to "Border", "text" to "Text", "muted" to "Muted Text",
        "accent" to "Accent", "accent2" to "Accent 2", "up" to "Up", "down" to "Down",
        "warn" to "Warning",
    )
}
