/* ============================================================================
   SGS — logo/monogram + line icon set. All use currentColor / accent so they
   re-skin with the theme.  Mark concept: a scanner viewfinder bracket framing
   three rising signal bars (the middle one "locked" = the active scan hit).
   ========================================================================== */

function SGSMark({ size = 28, accent = "var(--accent)", accent2 = "var(--accent2)", glow = false }) {
  const gid = React.useMemo(() => "sgsg" + Math.random().toString(36).slice(2, 7), []);
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none"
      style={{ filter: glow ? `drop-shadow(0 0 6px ${typeof accent === "string" && accent.startsWith("#") ? accent : "var(--accent)"})` : "none", flexShrink: 0 }}>
      <defs>
        <linearGradient id={gid} x1="6" y1="6" x2="42" y2="42" gradientUnits="userSpaceOnUse">
          <stop stopColor={accent} />
          <stop offset="1" stopColor={accent2} />
        </linearGradient>
      </defs>
      {/* viewfinder corner brackets */}
      <path d="M14 5H8a3 3 0 0 0-3 3v6M34 5h6a3 3 0 0 1 3 3v6M14 43H8a3 3 0 0 1-3-3v-6M34 43h6a3 3 0 0 0 3-3v-6"
        stroke={`url(#${gid})`} strokeWidth="3" strokeLinecap="round" />
      {/* three scan bars, middle locked */}
      <rect x="13" y="27" width="4.6" height="9" rx="1.4" fill={accent} opacity="0.55" />
      <rect x="21.7" y="14" width="4.6" height="22" rx="1.6" fill={`url(#${gid})`} />
      <rect x="30.4" y="22" width="4.6" height="14" rx="1.4" fill={accent} opacity="0.8" />
      {/* lock pip on the middle bar */}
      <circle cx="24" cy="12" r="2.6" fill={accent2} />
    </svg>
  );
}

function SGSLogo({ size = 26, showWord = true, accent = "var(--accent)", accent2 = "var(--accent2)", glow = false, weight = 700 }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: size * 0.34 }}>
      <SGSMark size={size * 1.18} accent={accent} accent2={accent2} glow={glow} />
      {showWord && (
        <span style={{
          fontFamily: "var(--font-display)", fontWeight: weight,
          fontSize: size * 0.84, letterSpacing: size * 0.04, color: "var(--text)",
          lineHeight: 1, display: "inline-flex", alignItems: "baseline",
        }}>
          DIC
        </span>
      )}
    </div>
  );
}

/* ── line icons (24-grid, stroke=currentColor) ───────────────────────────── */
const I = (paths, props = {}) => ({ size = 22, stroke = 2, ...rest } = {}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" {...props} {...rest}>
    {paths}
  </svg>
);

const SGSIcons = {
  panel: I(<><rect x="3" y="3" width="7.5" height="9" rx="1.4" /><rect x="13.5" y="3" width="7.5" height="5.5" rx="1.4" /><rect x="3" y="15" width="7.5" height="6" rx="1.4" /><rect x="13.5" y="11.5" width="7.5" height="9.5" rx="1.4" /></>),
  scan: I(<><path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2" /><path d="M4 12h16" /></>),
  oi: I(<><path d="M12 21a9 9 0 1 0-9-9" /><path d="M12 12l5-3" /><path d="M3 12h3M12 3v3" /></>),
  signal: I(<><path d="M4 18v-4M9 18v-9M14 18v-6M19 18V6" /></>),
  leader: I(<><path d="M3 17l5-6 4 4 8-9" /><path d="M16 6h5v5" /></>),
  logs: I(<><rect x="4" y="3" width="16" height="18" rx="2" /><path d="M8 8h8M8 12h8M8 16h5" /></>),
  settings: I(<><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M22 12h-3M5 12H2M19 5l-2 2M7 17l-2 2M19 19l-2-2M7 7L5 5" /></>),
  appearance: I(<><circle cx="12" cy="12" r="9" /><path d="M12 3a9 9 0 0 1 0 18" fill="currentColor" stroke="none" opacity="0.18" /><circle cx="8.5" cy="9" r="1.1" fill="currentColor" stroke="none" /><circle cx="15.5" cy="9" r="1.1" fill="currentColor" stroke="none" /><circle cx="9" cy="15" r="1.1" fill="currentColor" stroke="none" /></>),
  search: I(<><circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" /></>),
  chevron: I(<path d="M9 6l6 6-6 6" />),
  chevronDown: I(<path d="M6 9l6 6 6-6" />),
  refresh: I(<><path d="M21 12a9 9 0 1 1-2.6-6.4" /><path d="M21 3v5h-5" /></>),
  plus: I(<><path d="M12 5v14M5 12h14" /></>),
  minus: I(<path d="M5 12h14" />),
  check: I(<path d="M4 12l5 5L20 6" />),
  close: I(<path d="M6 6l12 12M18 6L6 18" />),
  arrowUp: I(<path d="M12 19V5M6 11l6-6 6 6" />),
  arrowDown: I(<path d="M12 5v14M6 13l6 6 6-6" />),
  star: I(<path d="M12 3l2.6 5.6 6 .8-4.4 4.2 1.1 6L12 17.8 6.7 19.6l1.1-6L3.4 9.4l6-.8L12 3z" />),
  bell: I(<><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.7 21a2 2 0 0 1-3.4 0" /></>),
  sliders: I(<><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3" /><path d="M2 14h4M10 8h4M18 12h4" /></>),
  flame: I(<path d="M12 3c1 4 4 5 4 9a4 4 0 0 1-8 0c0-1.5.6-2.4 1.4-3.2C10 10 11.5 8 12 3z" />),
  bolt: I(<path d="M13 2L4 13h6l-1 9 9-12h-6l1-8z" />),
  dot: I(<circle cx="12" cy="12" r="3.5" fill="currentColor" stroke="none" />),
  candleType: I(<><path d="M7 4v3M7 17v3M17 6v2M17 15v3" /><rect x="4.5" y="7" width="5" height="10" rx="1" /><rect x="14.5" y="8" width="5" height="7" rx="1" /></>),
  lineType: I(<path d="M3 16l5-6 4 3 9-9" />),
  areaType: I(<><path d="M3 16l5-6 4 3 9-9" /><path d="M3 16l5-6 4 3 9-9V20H3z" fill="currentColor" stroke="none" opacity="0.18" /></>),
  contrast: I(<><circle cx="12" cy="12" r="9" /><path d="M12 3v18a9 9 0 0 0 0-18z" fill="currentColor" stroke="none" /></>),
  type: I(<><path d="M4 7V5h16v2M9 19h6M12 5v14" /></>),
  density: I(<><path d="M4 6h16M4 10h16M4 14h16M4 18h16" /></>),
  motion: I(<><circle cx="12" cy="12" r="2" /><path d="M12 4v3M12 17v3M4 12h3M17 12h3" /></>),
  globe: I(<><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18" /></>),
  info: I(<><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 8h.01" /></>),
  shield: I(<><path d="M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6l7-3z" /></>),
};

Object.assign(window, { SGSMark, SGSLogo, SGSIcons });
