/* ============================================================================
   Dive Into Crypto — Desktop shell.
   Single-window layout: left sidebar nav + top bar + multi-column content.
   Replaces the mobile studio/AppShell (phone frames + bottom nav). Reuses every
   screen, the theme engine, chart, ui and icons unchanged; orchestrates real data
   from the backend via window.DIVE and re-skins live via sgsBuildVars.
   ========================================================================== */

const { useState, useEffect, useCallback } = React;

const DIVE_NAV = [
  { id: "tarama", label: "Tarama", icon: "scan" },
  { id: "panel", label: "Panel", icon: "panel" },
  { id: "positions", label: "OI · L/S", icon: "oi" },
  { id: "signals", label: "Sinyal", icon: "signal" },
  { id: "leader", label: "Lider", icon: "leader" },
  { id: "logs", label: "Ağ Günlüğü", icon: "logs" },
  { id: "appearance", label: "Görünüm", icon: "appearance" },
  { id: "settings", label: "Ayarlar", icon: "settings" },
];
const DIVE_TITLES = {
  tarama: "Tarama", panel: "Panel", positions: "OI · L/S", signals: "Sinyal",
  leader: "Lider", logs: "Ağ Günlüğü", appearance: "Görünüm", settings: "Ayarlar",
};
const DIVE_SCREENS = {
  tarama: () => TaramaScreen, panel: () => PanelScreen, positions: () => PositionsScreen,
  signals: () => SinyalScreen, leader: () => LiderScreen, logs: () => LogsScreen,
  appearance: () => GorunumScreen, settings: () => AyarlarScreen,
};
const DIVE_SYMBOL_SCREENS = new Set(["panel", "positions", "signals"]);

function DiveState({ spinner, children }) {
  return <div className="dive-state">{spinner && <div className="dive-spinner" />}<div>{children}</div></div>;
}

function DesktopShell() {
  const [presetId, setPresetId] = useState(() => localStorage.getItem("dive_preset") || "term-phosphor");
  const [axes, setAxes] = useState(() => {
    try { return { ...SGS_AXES_DEFAULT, ...JSON.parse(localStorage.getItem("dive_axes") || "{}") }; }
    catch { return { ...SGS_AXES_DEFAULT }; }
  });
  const [route, setRoute] = useState("tarama");
  const [symbol, setSymbol] = useState(null);
  const [tf, setTf] = useState("1h");
  const [period, setPeriod] = useState("1h");
  const [favorites, setFavorites] = useState([]);
  const [tfModal, setTfModal] = useState(null);
  const [version, setVersion] = useState(0);
  const [booted, setBooted] = useState(false);
  const [err, setErr] = useState(null);
  const [query, setQuery] = useState("");

  // data callback: any DIVE.* fetch bumps this → re-render (screens read globals)
  useEffect(() => { window.__diveOnData = () => setVersion((v) => v + 1); return () => { window.__diveOnData = null; }; }, []);
  useEffect(() => { localStorage.setItem("dive_preset", presetId); }, [presetId]);
  useEffect(() => { localStorage.setItem("dive_axes", JSON.stringify(axes)); }, [axes]);

  // boot: universe → favorites + first symbol
  useEffect(() => {
    (async () => {
      try {
        const u = await DIVE.universe(60);
        const top = u.slice(0, 8).map((r) => r.s);
        setFavorites(top);
        setSymbol((s) => s || top[0] || "BTCUSDT");
        setBooted(true);
      } catch (e) {
        setErr("Backend'e bağlanılamadı. Terminalde `uv run dive-desktop` çalışıyor mu?");
      }
    })();
  }, []);

  // fetch the active symbol's full object when a symbol-screen needs it
  useEffect(() => { if (symbol && DIVE_SYMBOL_SCREENS.has(route)) DIVE.symbol(symbol).catch(() => {}); }, [symbol, route]);

  // periodic scan (on Tarama) + a logs refresh on every route
  useEffect(() => {
    let live = true;
    const tick = async () => {
      if (!live) return;
      try { if (route === "tarama") await DIVE.scan(15, 24); } catch {}
      try { await DIVE.logs(); } catch {}
    };
    tick();
    const id = setInterval(tick, route === "tarama" ? 20000 : 15000);
    return () => { live = false; clearInterval(id); };
  }, [route]);

  useEffect(() => { if (route === "leader") DIVE.leaders().catch(() => {}); }, [route]);

  const setAxis = useCallback((k, v) => setAxes((a) => ({ ...a, [k]: v })), []);
  const resetAxes = useCallback(() => setAxes({ ...SGS_AXES_DEFAULT }), []);
  const theme = sgsBuildVars(presetId, axes);

  const ctx = {
    route, setRoute, symbol, setSymbol, tf, setTf, period, setPeriod, favorites,
    addFav: (s) => setFavorites((f) => (f.includes(s) ? f : [...f, s])),
    removeFav: (s) => setFavorites((f) => f.filter((x) => x !== s)),
    presetId, setPreset: setPresetId, t: axes, setAxis, resetAxes,
    openTfChart: (t) => setTfModal(t),
  };

  const submitQuery = (e) => {
    e.preventDefault();
    let s = query.trim().toUpperCase();
    if (!s) return;
    if (!s.endsWith("USDT")) s += "USDT";
    setSymbol(s);
    if (!DIVE_SYMBOL_SCREENS.has(route)) setRoute("panel");
    setQuery("");
  };

  const refresh = () => {
    if (route === "tarama") DIVE.scan(15, 24).catch(() => {});
    else if (route === "leader") DIVE.leaders().catch(() => {});
    else if (symbol && DIVE_SYMBOL_SCREENS.has(route)) DIVE.symbol(symbol).catch(() => {});
    DIVE.logs().catch(() => {});
  };

  const Screen = DIVE_SCREENS[route]();
  const symObj = symbol ? SGS_DATA_MAP[symbol] : null;
  const needSymbol = DIVE_SYMBOL_SCREENS.has(route) && !(symObj && symObj.multiTf && symObj.multiTf.length);

  let body;
  if (err) body = <DiveState>{err}</DiveState>;
  else if (!booted) body = <DiveState spinner>Canlı piyasa verisi yükleniyor…</DiveState>;
  else if (needSymbol) body = <DiveState spinner>{(symbol || "").replace("USDT", "")} verisi çekiliyor…</DiveState>;
  else body = <Screen ctx={ctx} />;

  const THEME_DOTS = SGS_PRESETS.map((p) => ({ id: p.id, c: p.accent }));

  return (
    <div className="dive-app" data-mode={theme.mode} style={{ ...theme.vars }}>
      <div className="sgs-app-bg" />
      {theme.scan && <div className="sgs-scanlines" />}

      <aside className="dive-sidebar">
        <div className="dive-brand">
          <SGSMark size={30} glow={theme.glow > 0.3} />
          <div>
            <div className="nm">Dive Into Crypto</div>
            <div className="sub">Desktop · Terminal</div>
          </div>
        </div>
        {DIVE_NAV.map((n) => (
          <button key={n.id} className="dive-nav-item" data-active={route === n.id} onClick={() => setRoute(n.id)}>
            <span className="ic">{SGSIcons[n.icon]({ size: 18 })}</span>
            <span>{n.label}</span>
          </button>
        ))}
        <div className="dive-sidebar-foot">
          <div className="row">{SGSIcons.dot({ size: 7 })} Binance USDT-M · canlı</div>
          <div className="row" style={{ marginTop: 3 }}>Crypcodile · v0.1.0</div>
        </div>
      </aside>

      <div className="dive-main-col">
        <header className="dive-topbar">
          <span className="title">{DIVE_TITLES[route]}</span>
          <div style={{ flex: 1 }} />
          <form className="dive-search" onSubmit={submitQuery}>
            <span style={{ color: "var(--dim)", display: "flex" }}>{SGSIcons.search({ size: 14 })}</span>
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Sembol ara (örn. SOL)…" aria-label="Sembol ara" />
          </form>
          <div className="dive-theme-dots">
            {THEME_DOTS.map((d) => (
              <button key={d.id} className="dive-theme-dot" data-active={d.id === presetId}
                style={{ background: d.c }} title={d.id} aria-label={`Tema: ${d.id}`} onClick={() => setPresetId(d.id)} />
            ))}
          </div>
          <button className="sgs-icon-btn" onClick={refresh} title="Yenile" aria-label="Yenile">{SGSIcons.refresh({ size: 18 })}</button>
        </header>

        <main className="dive-content" data-route={route}>{body}</main>
      </div>

      {tfModal && symObj && symObj.candles && symObj.candles.length > 0 && (
        <TfChartModal ctx={ctx} tf={tfModal} onClose={() => setTfModal(null)} />
      )}
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root")).render(<DesktopShell />);
