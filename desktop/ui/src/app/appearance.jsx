/* ============================================================================
   SGS — Görünüm (in-app personalization studio) + Ayarlar (settings/about)
   These edit the SHARED theme state (preset + axes), so both devices re-skin.
   ========================================================================== */

function StudioRow({ icon, label, hint, children, stack }) {
  return (
    <div style={{ padding: "calc(var(--pad) * 0.85) 0", borderTop: "1px solid var(--border)" }}>
      <div style={{ display: "flex", alignItems: stack ? "flex-start" : "center", justifyContent: "space-between", gap: 12, flexDirection: stack ? "column" : "row" }}>
        <div style={{ display: "flex", gap: 9, alignItems: "center", minWidth: 0 }}>
          {icon && <span style={{ color: "var(--accent)", display: "flex", flexShrink: 0 }}>{icon}</span>}
          <div style={{ minWidth: 0 }}>
            <div style={{ color: "var(--text)", fontSize: "calc(13px * var(--fs))", fontWeight: 600 }}>{label}</div>
            {hint && <div style={{ color: "var(--dim)", fontSize: "calc(10.5px * var(--fs))", marginTop: 1 }}>{hint}</div>}
          </div>
        </div>
        <div style={{ width: stack ? "100%" : "auto", flexShrink: 0 }}>{children}</div>
      </div>
    </div>
  );
}

function Slider({ value, min, max, step, onChange, fmt }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 168 }}>
      <input type="range" className="sgs-range" min={min} max={max} step={step} value={value}
        onChange={(e) => onChange(parseFloat(e.target.value))} />
      <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))", color: "var(--accent)", fontWeight: 700, width: 40, textAlign: "right" }}>{fmt ? fmt(value) : value}</span>
    </div>
  );
}

function SwatchRow({ options, value, onChange }) {
  return (
    <div style={{ display: "flex", gap: 7, flexWrap: "wrap" }}>
      {options.map((o) => {
        const active = value === o.v;
        return (
          <button key={o.v} onClick={() => onChange(o.v)} title={o.label}
            style={{
              width: 28, height: 28, borderRadius: "var(--radius-sm)", cursor: "pointer",
              border: active ? "2px solid var(--text)" : "2px solid var(--border)",
              background: o.swatch, padding: 0, position: "relative",
              boxShadow: active ? "0 0 0 2px var(--bg)" : "none",
              display: "flex", alignItems: "center", justifyContent: "center",
            }}>
            {o.v === "auto" && <span style={{ fontSize: 8, fontFamily: "var(--font-mono)", color: "var(--text)", fontWeight: 700 }}>AUTO</span>}
          </button>
        );
      })}
    </div>
  );
}

/* mini candle preview reflecting candle scheme */
function CandlePreview({ scheme, preset }) {
  const cc = sgsCandleColors(preset, scheme);
  const bars = [{ up: true, h: 14, t: 4 }, { up: false, h: 18, t: 2 }, { up: true, h: 22, t: 0 }, { up: false, h: 12, t: 6 }, { up: true, h: 17, t: 3 }];
  return (
    <div style={{ display: "flex", gap: 4, alignItems: "center", height: 26 }}>
      {bars.map((b, i) => (
        <div key={i} style={{ width: 5, height: b.h, marginTop: b.t, borderRadius: 1, background: b.up ? cc.up : cc.down }} />
      ))}
    </div>
  );
}

const ACCENT_SWATCHES = [
  { v: "auto", label: "Tema varsayılanı", swatch: "var(--elev2)" },
  { v: "#00E5FF", label: "Cyan", swatch: "#00E5FF" },
  { v: "#8B7BFF", label: "Violet", swatch: "#8B7BFF" },
  { v: "#38FF9E", label: "Phosphor", swatch: "#38FF9E" },
  { v: "#FF8A3D", label: "Amber", swatch: "#FF8A3D" },
  { v: "#1B4DFF", label: "Blue", swatch: "#1B4DFF" },
  { v: "#D9B45A", label: "Gold", swatch: "#D9B45A" },
  { v: "#FF2BD6", label: "Magenta", swatch: "#FF2BD6" },
];
const CHART_SWATCHES = [
  { v: "accent", label: "Vurgu", swatch: "var(--accent)" },
  { v: "up", label: "Yön rengi", swatch: "var(--up)" },
  { v: "violet", label: "Violet", swatch: "#8B7BFF" },
  { v: "amber", label: "Amber", swatch: "#FF9F3D" },
  { v: "cyan", label: "Cyan", swatch: "#39D6FF" },
  { v: "white", label: "Beyaz", swatch: "#E8EEF8" },
];

function GorunumScreen({ ctx }) {
  const cur = SGS_PRESET_MAP[ctx.presetId];
  const fam = "TERMINAL";  // terminal-only edition
  const t = ctx.t, set = ctx.setAxis;

  return (
    <div className="sgs-screen">
      <ScreenTitle title="Görünüm" sub="Uygulamayı tamamen kendine göre ayarla" />

      <div style={{ color: "var(--dim)", fontSize: "calc(10.5px * var(--fs))", margin: "2px 2px var(--gap)" }}>Terminal temaları · monospace · CRT estetiği</div>

      {/* preset cards for the family */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: "var(--gap)", marginBottom: "var(--gap)" }}>
        {SGS_PRESETS.filter((p) => p.family === fam).map((p) => {
          const active = p.id === ctx.presetId;
          return (
            <button key={p.id} onClick={() => ctx.setPreset(p.id)} className="sgs-preset-card"
              style={{ borderColor: active ? "var(--accent)" : "var(--border)", boxShadow: active ? "0 0 0 2px var(--accent-soft)" : "none" }}>
              <div style={{ height: 38, borderRadius: "var(--radius-sm)", background: p.bg, border: `1px solid ${p.border}`, position: "relative", overflow: "hidden", marginBottom: 6 }}>
                <div style={{ position: "absolute", inset: 0, background: p.mesh !== "none" ? p.mesh : "none" }} />
                <div style={{ position: "absolute", left: 6, bottom: 6, display: "flex", gap: 3, alignItems: "flex-end" }}>
                  <i style={{ width: 4, height: 10, background: p.up, borderRadius: 1 }} />
                  <i style={{ width: 4, height: 16, background: p.accent, borderRadius: 1 }} />
                  <i style={{ width: 4, height: 8, background: p.down, borderRadius: 1 }} />
                </div>
                <div style={{ position: "absolute", right: 6, top: 6, width: 10, height: 10, borderRadius: 99, background: p.accent }} />
              </div>
              <div style={{ fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))", fontWeight: 700, color: active ? "var(--accent)" : "var(--text)" }}>{p.label}</div>
            </button>
          );
        })}
      </div>

      <Card title="TİPOGRAFİ & YERLEŞİM" pad={false}>
        <div style={{ padding: "0 var(--pad)" }}>
          <StudioRow icon={SGSIcons.type({ size: 18 })} label="Yazı boyutu" hint="Kompakt → büyük">
            <Slider value={t.fontScale} min={0.85} max={1.3} step={0.05} onChange={(v) => set("fontScale", v)} fmt={(v) => Math.round(v * 100) + "%"} />
          </StudioRow>
          <StudioRow icon={SGSIcons.contrast({ size: 18 })} label="Kontrast">
            <Seg options={[{ v: "normal", l: "Normal" }, { v: "high", l: "Yüksek" }]} value={t.contrast} onChange={(v) => set("contrast", v)} />
          </StudioRow>
          <StudioRow icon={SGSIcons.density({ size: 18 })} label="Yoğunluk">
            <Seg options={[{ v: "compact", l: "Sık" }, { v: "cozy", l: "Orta" }, { v: "comfy", l: "Ferah" }]} value={t.density} onChange={(v) => set("density", v)} />
          </StudioRow>
          <StudioRow icon={SGSIcons.type({ size: 18 })} label="Yazı tipi">
            <Seg options={[{ v: "auto", l: "Tema" }, { v: "mono", l: "Mono" }, { v: "sans", l: "Sans" }, { v: "serif", l: "Serif" }]} value={t.font} onChange={(v) => set("font", v)} />
          </StudioRow>
          <StudioRow icon={SGSIcons.appearance({ size: 18 })} label="Köşe yuvarlaklığı">
            <Seg options={[{ v: "sharp", l: "Keskin" }, { v: "auto", l: "Tema" }, { v: "soft", l: "Yumuşak" }]} value={t.corner} onChange={(v) => set("corner", v)} />
          </StudioRow>
          <StudioRow icon={SGSIcons.motion({ size: 18 })} label="Hareket / efekt">
            <Seg options={[{ v: "off", l: "Kapalı" }, { v: "subtle", l: "Az" }, { v: "full", l: "Tam" }]} value={t.motion} onChange={(v) => set("motion", v)} />
          </StudioRow>
        </div>
      </Card>

      <Card title="RENK" pad={false}>
        <div style={{ padding: "0 var(--pad)" }}>
          <StudioRow icon={SGSIcons.dot({ size: 18 })} label="Vurgu rengi" hint="Accent" stack>
            <SwatchRow options={ACCENT_SWATCHES} value={t.accent} onChange={(v) => set("accent", v)} />
          </StudioRow>
          <StudioRow label="Mum renkleri" hint="Yükseliş / düşüş" stack
            icon={SGSIcons.candleType({ size: 18 })}>
            <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              <Seg full options={[{ v: "auto", l: "Tema" }, { v: "western", l: "Yeşil/Kırmızı" }, { v: "eastern", l: "Kırmızı/Yeşil" }]} value={t.candle} onChange={(v) => set("candle", v)} />
              <div style={{ display: "flex", gap: 8 }}>
                <div style={{ flex: 1 }}><Seg full options={[{ v: "colorblind", l: "Renk körü" }, { v: "mono", l: "Tek renk" }]} value={["colorblind", "mono"].includes(t.candle) ? t.candle : ""} onChange={(v) => set("candle", v)} /></div>
                <div style={{ background: "var(--bg2)", border: "1px solid var(--border)", borderRadius: "var(--radius-sm)", padding: "0 8px", display: "flex", alignItems: "center" }}><CandlePreview scheme={t.candle} preset={cur} /></div>
              </div>
            </div>
          </StudioRow>
          <StudioRow icon={SGSIcons.lineType({ size: 18 })} label="Grafik tipi" stack>
            <Seg full options={[{ v: "candle", l: "Mum" }, { v: "line", l: "Çizgi" }, { v: "area", l: "Alan" }, { v: "heikin", l: "Heikin" }]} value={t.chartType} onChange={(v) => set("chartType", v)} />
          </StudioRow>
          <StudioRow icon={SGSIcons.areaType({ size: 18 })} label="Grafik rengi" stack>
            <SwatchRow options={CHART_SWATCHES} value={t.chartColor} onChange={(v) => set("chartColor", v)} />
          </StudioRow>
          {cur.family === "TERMINAL" && (
            <StudioRow icon={SGSIcons.motion({ size: 18 })} label="Tarama çizgileri" hint="CRT scanline efekti">
              <Seg options={[{ v: "auto", l: "Tema" }, { v: "on", l: "Açık" }, { v: "off", l: "Kapalı" }]} value={t.scanlines} onChange={(v) => set("scanlines", v)} />
            </StudioRow>
          )}
        </div>
      </Card>

      <button className="sgs-ghost-btn" onClick={ctx.resetAxes}>{SGSIcons.refresh({ size: 15 })} Özelleştirmeleri sıfırla</button>
    </div>
  );
}

/* ── Ayarlar ──────────────────────────────────────────────────────────────── */
function AyarlarScreen({ ctx }) {
  const [conf, setConf] = React.useState(() => {
    const saved = localStorage.getItem("dive_conf");
    return saved ? parseInt(saved, 10) : 55;
  });
  const [trade, setTrade] = React.useState(() => {
    const saved = localStorage.getItem("dive_trade");
    return saved ? parseInt(saved, 10) : 70;
  });
  React.useEffect(() => {
    localStorage.setItem("dive_conf", conf);
  }, [conf]);
  React.useEffect(() => {
    localStorage.setItem("dive_trade", trade);
  }, [trade]);
  const [q, setQ] = React.useState("");
  const allSyms = SGS_DATA.map((d) => d.s);
  const matches = q ? allSyms.filter((s) => s.includes(q.toUpperCase()) && !ctx.favorites.includes(s)).slice(0, 4) : [];

  return (
    <div className="sgs-screen">
      <ScreenTitle title="Ayarlar" sub="Dive Into Crypto · Desktop" />

      <div className="sgs-about-hero">
        <SGSLogo size={30} glow />
        <div style={{ marginTop: 10 }}>
          <div style={{ fontFamily: "var(--font-display)", fontWeight: 800, fontSize: "calc(17px * var(--fs))", color: "var(--text)" }}>Dive Into Crypto</div>
          <div style={{ color: "var(--muted)", fontSize: "calc(11px * var(--fs))", marginTop: 2 }}>Binance USDT-M Futures · konsensüs tarayıcı</div>
        </div>
      </div>

      <button className="sgs-nav-link" onClick={() => ctx.setRoute("appearance")}>
        <span style={{ color: "var(--accent)", display: "flex" }}>{SGSIcons.appearance({ size: 20 })}</span>
        <div style={{ flex: 1, textAlign: "left" }}>
          <div style={{ color: "var(--text)", fontWeight: 700, fontSize: "calc(13px * var(--fs))" }}>Görünüm & Tema</div>
          <div style={{ color: "var(--dim)", fontSize: "calc(10.5px * var(--fs))" }}>{SGS_PRESET_MAP[ctx.presetId].family} · {SGS_PRESET_MAP[ctx.presetId].label}</div>
        </div>
        <span style={{ color: "var(--dim)", display: "flex" }}>{SGSIcons.chevron({ size: 16 })}</span>
      </button>

      <Card title="FAVORİ COİNLER">
        <div className="sgs-hscroll" style={{ marginBottom: 10 }}>
          {ctx.favorites.map((s) => (
            <span key={s} className="sgs-fav-chip">
              {s.replace("USDT", "")}
              <button onClick={() => ctx.removeFav(s)} style={{ background: "none", border: "none", color: "var(--down)", cursor: "pointer", fontSize: 14, lineHeight: 1, padding: 0 }}>×</button>
            </span>
          ))}
        </div>
        <div className="sgs-input">
          <span style={{ color: "var(--dim)", display: "flex" }}>{SGSIcons.search({ size: 15 })}</span>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Coin ara, ekle…" />
        </div>
        {matches.map((s) => (
          <button key={s} className="sgs-match" onClick={() => { ctx.addFav(s); setQ(""); }}>
            <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(12px * var(--fs))", color: "var(--text)" }}>{s}</span>
            <span style={{ color: "var(--accent)", fontWeight: 700, fontSize: "calc(11px * var(--fs))" }}>+ Ekle</span>
          </button>
        ))}
      </Card>

      <Card title="ANALİZ ALGORİTMASI" pad={false}>
        <div style={{ padding: "0 var(--pad)" }}>
          <StudioRow label="Min konsensüs eşiği" hint="Confidence">
            <Stepper value={conf} suffix="%" onDec={() => setConf((v) => Math.max(10, v - 5))} onInc={() => setConf((v) => Math.min(90, v + 5))} />
          </StudioRow>
          <StudioRow label="Min işlem eşiği" hint="Trade signal">
            <Stepper value={trade} suffix="%" onDec={() => setTrade((v) => Math.max(15, v - 5))} onInc={() => setTrade((v) => Math.min(95, v + 5))} />
          </StudioRow>
        </div>
      </Card>

      <Card title="HAKKINDA" pad={false}>
        {[["Uygulama", "Dive Into Crypto · Desktop"], ["Versiyon", "0.1.0"], ["Veri kaynağı", "Binance USDT-M · Crypcodile"], ["İndikatör", "15 · konsensüs motoru"], ["Zaman dilimi", "12"], ["Tema", "3 terminal teması"]].map((r, i) => (
          <div key={r[0]} className="sgs-about-row" style={{ borderTop: i ? "1px solid var(--border)" : "none" }}>
            <span style={{ color: "var(--muted)", fontSize: "calc(12px * var(--fs))" }}>{r[0]}</span>
            <span style={{ fontFamily: "var(--font-mono)", color: "var(--text)", fontSize: "calc(12px * var(--fs))", fontWeight: 600 }}>{r[1]}</span>
          </div>
        ))}
      </Card>
      <div style={{ height: 8 }} />
    </div>
  );
}

function Stepper({ value, suffix = "", onDec, onInc }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
      <button className="sgs-step" onClick={onDec}>{SGSIcons.minus({ size: 14 })}</button>
      <span style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(12.5px * var(--fs))", color: "var(--accent)", width: 44, textAlign: "center" }}>{value}{suffix}</span>
      <button className="sgs-step" onClick={onInc}>{SGSIcons.plus({ size: 14 })}</button>
    </div>
  );
}

Object.assign(window, { GorunumScreen, AyarlarScreen });
