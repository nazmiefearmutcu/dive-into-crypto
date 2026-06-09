/* ============================================================================
   Dive Into Crypto — Desktop · Theme Engine
   TERMINAL family only — 3 presets (Phosphor · Amber · Ice), plus live
   customization axes. Everything is expressed as CSS custom properties so the
   app re-skins live. (The NOVA/LEDGER families from the original prototype were
   removed for the terminal-only desktop edition.)
   ========================================================================== */

const SGS_FONTS = {
  grotesk: "'Space Grotesk', system-ui, sans-serif",
  spaceMono: "'Space Mono', ui-monospace, monospace",
  plexSans: "'IBM Plex Sans', system-ui, sans-serif",
  plexMono: "'IBM Plex Mono', ui-monospace, monospace",
  jet: "'JetBrains Mono', ui-monospace, monospace",
  serif: "'Newsreader', Georgia, serif",
};

/* Each preset is a full token set. `mode` drives the device chrome (status bar). */
const SGS_PRESETS = [
  /* ──────────────────────── TERMINAL · gece ──────────────────────────── */
  {
    id: "term-phosphor", family: "TERMINAL", label: "Phosphor", tagline: "Green CRT · low color",
    mode: "dark", radius: 2,
    fontBody: SGS_FONTS.jet, fontMono: SGS_FONTS.jet, fontDisplay: SGS_FONTS.jet,
    accent: "#38FF9E", accent2: "#1FB872",
    up: "#38FF9E", down: "#FF6155", warn: "#E8D44D",
    bg: "#020604", bg2: "#04100A",
    elev: "#061410", elev2: "#0A1F17",
    border: "#10402A", borderStrong: "#1C6B45",
    text: "#46FFA8", muted: "#1FB872", dim: "#0E6B43",
    glass: 0, glow: 0.7, scan: true, candleDefault: "mono",
    mesh: "none",
  },
  {
    id: "term-amber", family: "TERMINAL", label: "Amber", tagline: "Amber monochrome CRT",
    mode: "dark", radius: 2,
    fontBody: SGS_FONTS.jet, fontMono: SGS_FONTS.jet, fontDisplay: SGS_FONTS.jet,
    accent: "#FFB638", accent2: "#C8841A",
    up: "#FFC857", down: "#FF6155", warn: "#FFC857",
    bg: "#0A0600", bg2: "#140C02",
    elev: "#160D02", elev2: "#241606",
    border: "#4A2E08", borderStrong: "#7A4E12",
    text: "#FFB638", muted: "#B97E1E", dim: "#6E4A12",
    glass: 0, glow: 0.7, scan: true, candleDefault: "mono",
    mesh: "none",
  },
  {
    id: "term-ice", family: "TERMINAL", label: "Ice", tagline: "Modern · cyan minimal",
    mode: "dark", radius: 4,
    fontBody: SGS_FONTS.jet, fontMono: SGS_FONTS.jet, fontDisplay: SGS_FONTS.jet,
    accent: "#56C7FF", accent2: "#2E8FD0",
    up: "#45E0B0", down: "#FF6B7E", warn: "#E8C24D",
    bg: "#04070A", bg2: "#070C12",
    elev: "#0A111A", elev2: "#101A26",
    border: "#16242F", borderStrong: "#27414F",
    text: "#DCEBF5", muted: "#6E8597", dim: "#3E4F5C",
    glass: 0, glow: 0.45, scan: false,
    mesh: "none",
  },
];

const SGS_PRESET_MAP = Object.fromEntries(SGS_PRESETS.map((p) => [p.id, p]));

/* Default live axes — what the user can tune on top of any preset. */
const SGS_AXES_DEFAULT = {
  fontScale: 1.0,        // 0.85 – 1.30
  contrast: "normal",    // normal | high
  candle: "auto",        // auto(=preset) | western | eastern | colorblind | mono
  chartType: "candle",   // candle | line | area | heikin
  chartColor: "accent",  // accent | up | violet | amber | cyan | white
  accent: "auto",        // auto(=preset) | hex
  density: "cozy",       // compact | cozy | comfy
  motion: "full",        // off | subtle | full
  font: "auto",          // auto(=preset) | mono | sans | serif
  corner: "auto",        // auto(=preset) | sharp | soft
  scanlines: "auto",     // auto(=preset) | on | off
};

const SGS_CHART_COLORS = {
  accent: null, up: null, // resolved at runtime
  violet: "#8B7BFF", amber: "#FF9F3D", cyan: "#39D6FF", white: "#E8EEF8",
};

const SGS_FONT_CHOICE = {
  mono: SGS_FONTS.jet, sans: SGS_FONTS.plexSans, serif: SGS_FONTS.serif,
};

function sgsHexToRgb(hex) {
  const h = hex.replace("#", "");
  const n = parseInt(h.length === 3 ? h.split("").map((c) => c + c).join("") : h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function sgsRgba(hex, a) {
  const [r, g, b] = sgsHexToRgb(hex);
  return `rgba(${r},${g},${b},${a})`;
}
function sgsMix(hex1, hex2, t) {
  const a = sgsHexToRgb(hex1), b = sgsHexToRgb(hex2);
  const m = a.map((v, i) => Math.round(v + (b[i] - v) * t));
  return `rgb(${m[0]},${m[1]},${m[2]})`;
}

/* Resolve up/down based on candle scheme. */
function sgsCandleColors(preset, scheme) {
  const s = scheme === "auto" ? (preset.candleDefault || "western") : scheme;
  switch (s) {
    case "western": return { up: "#22C55E", down: "#EF4444" };
    case "eastern": return { up: "#EF4444", down: "#22C55E" };
    case "colorblind": return { up: "#2D8CFF", down: "#FF8A3D" };
    case "mono": return { up: preset.accent, down: sgsMix(preset.muted, preset.bg, 0.15) };
    default: return { up: preset.up, down: preset.down };
  }
}

/* Build the full CSS-variable object for a preset + axes combination. */
function sgsBuildVars(presetId, axes) {
  const p = SGS_PRESET_MAP[presetId] || SGS_PRESETS[0];
  const a = { ...SGS_AXES_DEFAULT, ...(axes || {}) };

  const accent = a.accent && a.accent !== "auto" ? a.accent : p.accent;
  const cc = sgsCandleColors(p, a.candle === "auto" ? "auto" : a.candle);

  // contrast tuning
  const high = a.contrast === "high";
  const text = high ? (p.mode === "light" ? "#000000" : "#FFFFFF") : p.text;
  const muted = high ? sgsMix(p.muted, text, 0.35) : p.muted;
  const border = high ? p.borderStrong : p.border;

  // corner
  let radius = p.radius;
  if (a.corner === "sharp") radius = Math.min(p.radius, 3);
  else if (a.corner === "soft") radius = p.radius + 10;

  // density
  const dens = { compact: { gap: 6, pad: 11, row: 38, hero: 0.92 },
                 cozy: { gap: 9, pad: 14, row: 46, hero: 1.0 },
                 comfy: { gap: 13, pad: 18, row: 54, hero: 1.06 } }[a.density] || { gap: 9, pad: 14, row: 46, hero: 1.0 };

  // font family
  const fontBody = a.font && a.font !== "auto" ? SGS_FONT_CHOICE[a.font] : p.fontBody;
  const fontMono = a.font === "sans" || a.font === "serif" ? p.fontMono : (a.font === "mono" ? SGS_FONTS.jet : p.fontMono);

  // chart color
  let chartColor = accent;
  if (a.chartColor === "up") chartColor = cc.up;
  else if (a.chartColor !== "accent" && SGS_CHART_COLORS[a.chartColor]) chartColor = SGS_CHART_COLORS[a.chartColor];

  const glow = a.motion === "off" ? 0 : p.glow * (a.motion === "subtle" ? 0.5 : 1);
  const scan = (a.scanlines === "on") || (a.scanlines === "auto" && p.scan);

  // text color that reads on top of the accent fill
  const [ar, ag, ab] = sgsHexToRgb(accent);
  const lum = (0.299 * ar + 0.587 * ag + 0.114 * ab) / 255;
  const chipFg = lum > 0.6 ? "#08121f" : "#ffffff";

  const vars = {
    "--bg": p.bg, "--bg2": p.bg2,
    "--elev": p.elev, "--elev2": p.elev2,
    "--border": border, "--border-strong": p.borderStrong,
    "--text": text, "--muted": muted, "--dim": p.dim,
    "--accent": accent, "--accent2": p.accent2,
    "--accent-soft": sgsRgba(accent, 0.14), "--accent-line": sgsRgba(accent, 0.30),
    "--up": cc.up, "--down": cc.down, "--warn": p.warn,
    "--up-soft": sgsRgba(cc.up, 0.15), "--down-soft": sgsRgba(cc.down, 0.15),
    "--chart": chartColor, "--chart-soft": sgsRgba(chartColor, 0.16),
    "--radius": radius + "px", "--radius-sm": Math.max(1, radius - 4) + "px",
    "--radius-lg": (radius + 6) + "px",
    "--font": fontBody, "--font-mono": fontMono, "--font-display": (a.font && a.font !== "auto") ? fontBody : p.fontDisplay,
    "--fs": a.fontScale, "--gap": dens.gap + "px", "--pad": dens.pad + "px",
    "--row": dens.row + "px", "--hero-mul": dens.hero,
    "--glass": p.glass + "px",
    "--glow": glow, "--accent-glow": glow ? sgsRgba(accent, 0.45 * glow) : "transparent",
    "--mesh": a.motion === "off" ? "none" : p.mesh,
    "--shadow": p.mode === "light"
      ? "0 1px 2px rgba(16,22,40,0.06), 0 8px 24px rgba(16,22,40,0.06)"
      : "0 1px 0 rgba(255,255,255,0.03), 0 14px 40px rgba(0,0,0,0.5)",
    "--scan": scan ? "1" : "0",
    "--motion": a.motion === "off" ? "0" : "1",
    "--chip-fg": chipFg,
    "--card-blur": p.glass ? `blur(${p.glass}px) saturate(150%)` : "none",
  };
  return { vars, preset: p, mode: p.mode, accent, cc, scan, glow };
}

window.SGS_PRESETS = SGS_PRESETS;
window.SGS_PRESET_MAP = SGS_PRESET_MAP;
window.SGS_AXES_DEFAULT = SGS_AXES_DEFAULT;
window.sgsBuildVars = sgsBuildVars;
window.sgsRgba = sgsRgba;
window.sgsMix = sgsMix;
window.SGS_FONTS = SGS_FONTS;
