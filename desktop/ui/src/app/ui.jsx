/* ============================================================================
   SGS — shared UI primitives. All theme-aware via CSS vars.
   ========================================================================== */

const SIG_LABEL = { STRONG_BUY: "GÜÇLÜ AL", BUY: "AL", NEUTRAL: "NÖTR", SELL: "SAT", STRONG_SELL: "GÜÇLÜ SAT" };
function sigColor(sig) {
  if (sig === "STRONG_BUY" || sig === "BUY") return "var(--up)";
  if (sig === "STRONG_SELL" || sig === "SELL") return "var(--down)";
  return "var(--muted)";
}

function Card({ title, right, children, style, pad = true, glass = true }) {
  return (
    <div className={"sgs-card" + (glass ? " sgs-glass" : "")} style={style}>
      {(title || right) && (
        <div className="sgs-card-head">
          {title && <span className="sgs-card-title">{title}</span>}
          {right}
        </div>
      )}
      <div style={{ padding: pad ? "var(--pad)" : 0, paddingTop: (title || right) ? "calc(var(--pad) * 0.7)" : "var(--pad)" }}>
        {children}
      </div>
    </div>
  );
}

function SignalBadge({ signal, size = "md" }) {
  const fs = size === "sm" ? 9.5 : size === "lg" ? 13 : 11;
  const col = sigColor(signal);
  const strong = signal.startsWith("STRONG");
  return (
    <span style={{
      fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: `calc(${fs}px * var(--fs))`,
      color: col, background: strong ? `color-mix(in srgb, ${col} 16%, transparent)` : "transparent",
      border: strong ? `1px solid color-mix(in srgb, ${col} 36%, transparent)` : "none",
      padding: strong ? "2px 7px" : "0", borderRadius: "var(--radius-sm)",
      letterSpacing: 0.5, whiteSpace: "nowrap", display: "inline-flex", alignItems: "center", gap: 4,
    }}>
      {SIG_LABEL[signal]}
    </span>
  );
}

function Chip({ active, onClick, children, accentActive = true }) {
  return (
    <button onClick={onClick} className="sgs-chip" style={{
      background: active ? (accentActive ? "var(--accent)" : "var(--elev2)") : "var(--elev2)",
      color: active ? (accentActive ? sgsChipText() : "var(--text)") : "var(--muted)",
      borderColor: active ? "var(--accent)" : "var(--border)",
      fontWeight: active ? 700 : 500,
    }}>{children}</button>
  );
}
/* white/black text on accent depending on theme luminance */
function sgsChipText() { return "var(--chip-fg)"; }

function Seg({ options, value, onChange, full }) {
  return (
    <div className="sgs-seg" style={{ display: full ? "grid" : "inline-grid", gridTemplateColumns: `repeat(${options.length}, 1fr)` }}>
      {options.map((o) => {
        const val = typeof o === "object" ? o.v : o;
        const lab = typeof o === "object" ? o.l : o;
        const active = val === value;
        return (
          <button key={val} onClick={() => onChange(val)} className="sgs-seg-btn"
            style={{ color: active ? "var(--chip-fg)" : "var(--muted)", background: active ? "var(--accent)" : "transparent", fontWeight: active ? 700 : 500 }}>
            {lab}
          </button>
        );
      })}
    </div>
  );
}

function Stat({ label, value, delta, valueColor = "var(--text)", dotColor }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 3, minWidth: 0 }}>
      <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
        {dotColor && <i style={{ width: 6, height: 6, borderRadius: 6, background: dotColor, flexShrink: 0 }} />}
        <span className="sgs-label">{label}</span>
      </span>
      <span style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(15px * var(--fs))", color: valueColor, lineHeight: 1.1, whiteSpace: "nowrap" }}>{value}</span>
      {delta !== undefined && (
        <span style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(10.5px * var(--fs))", color: delta >= 0 ? "var(--up)" : "var(--down)" }}>
          {delta >= 0 ? "+" : ""}{delta.toFixed(2)}%
        </span>
      )}
    </div>
  );
}

function Bar2({ a, b, colorA = "var(--up)", colorB = "var(--down)", h = 8 }) {
  const total = a + b || 1;
  return (
    <div style={{ display: "flex", height: h, borderRadius: 99, overflow: "hidden", background: "var(--elev2)" }}>
      <div style={{ width: `${(a / total) * 100}%`, background: colorA }} />
      <div style={{ width: `${(b / total) * 100}%`, background: colorB }} />
    </div>
  );
}

function Sparkline(props) { return <SGSSpark {...props} />; }

/* delta percent text */
function Delta({ v, suffix = "%" }) {
  return <span style={{ color: v >= 0 ? "var(--up)" : "var(--down)", fontFamily: "var(--font-mono)", fontWeight: 700 }}>
    {v >= 0 ? "+" : ""}{v.toFixed(2)}{suffix}
  </span>;
}

Object.assign(window, { Card, SignalBadge, Chip, Seg, Stat, Bar2, Sparkline, Delta, SIG_LABEL, sigColor });
