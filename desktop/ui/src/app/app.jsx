/* ============================================================================
   SGS — App shell. Top bar + scrollable screen + bottom nav + "Daha" sheet.
   Receives shared theme (vars) + holds its own per-device navigation state.
   ========================================================================== */

const NAV_PRIMARY = [
  { id: "panel", label: "Panel", icon: "panel" },
  { id: "tarama", label: "Tarama", icon: "scan" },
  { id: "positions", label: "OI·L/S", icon: "oi" },
  { id: "signals", label: "Sinyal", icon: "signal" },
];
const NAV_MORE = [
  { id: "leader", label: "Lider", icon: "leader" },
  { id: "logs", label: "Ağ Günlüğü", icon: "logs" },
  { id: "appearance", label: "Görünüm", icon: "appearance" },
  { id: "settings", label: "Ayarlar", icon: "settings" },
];
const ROUTE_TITLE = {
  panel: "Panel", tarama: "Tarama", positions: "OI · L/S", signals: "Sinyal",
  leader: "Lider", logs: "Ağ Günlüğü", appearance: "Görünüm", settings: "Ayarlar",
};

function AppShell({ platform, theme, presetId, setPreset, t, setAxis, resetAxes }) {
  const ios = platform === "ios";
  const [route, setRoute] = React.useState(ios ? "panel" : "positions");
  const [symbol, setSymbol] = React.useState(ios ? "BTCUSDT" : "SOLUSDT");
  const [tf, setTf] = React.useState("1h");
  const [period, setPeriod] = React.useState("1h");
  const [favorites, setFavorites] = React.useState(["BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT", "ARBUSDT", "SUIUSDT", "WIFUSDT"]);
  const [sheet, setSheet] = React.useState(false);
  const [tfModal, setTfModal] = React.useState(null);
  const [refreshKey, setRefreshKey] = React.useState(0);   // bump = remount current screen (re-runs scans)
  const scrollRef = React.useRef(null);

  React.useEffect(() => { if (scrollRef.current) scrollRef.current.scrollTop = 0; }, [route]);

  const ctx = {
    route, setRoute: (r) => { setRoute(r); setSheet(false); },
    symbol, setSymbol, tf, setTf, period, setPeriod,
    favorites,
    addFav: (s) => setFavorites((f) => f.includes(s) ? f : [...f, s]),
    removeFav: (s) => setFavorites((f) => f.filter((x) => x !== s)),
    presetId, setPreset, t, setAxis, resetAxes,
    openTfChart: (tf) => setTfModal(tf),
  };

  const moreActive = NAV_MORE.some((m) => m.id === route);
  const Screen = {
    panel: PanelScreen, tarama: TaramaScreen, positions: PositionsScreen,
    signals: SinyalScreen, leader: LiderScreen, logs: LogsScreen,
    appearance: GorunumScreen, settings: AyarlarScreen,
  }[route] || PanelScreen;

  return (
    <div className="sgs-app" data-mode={theme.mode} style={{ ...theme.vars }}>
      <div className="sgs-app-bg" />
      {theme.scan && <div className="sgs-scanlines" />}

      {/* top bar */}
      <header className="sgs-topbar" style={{ paddingTop: ios ? 48 : 10 }}>
        <SGSLogo size={20} glow={theme.glow > 0.3} />
        <span className="sgs-topbar-sep" />
        <span className="sgs-topbar-title">{ROUTE_TITLE[route]}</span>
        <div style={{ flex: 1 }} />
        <button className="sgs-icon-btn" onClick={() => ctx.setRoute("appearance")} title="Görünüm" aria-label="Görünüm ayarları">{SGSIcons.appearance({ size: 18 })}</button>
        <button className="sgs-icon-btn" onClick={() => setRefreshKey((k) => k + 1)} title="Yenile" aria-label="Ekranı yenile">{SGSIcons.refresh({ size: 18 })}</button>
      </header>

      {/* scroll area */}
      <main ref={scrollRef} className="sgs-main">
        <Screen key={refreshKey} ctx={ctx} />
        <div style={{ height: ios ? 96 : 78 }} />
      </main>

      {/* bottom nav */}
      <nav className="sgs-bottomnav" style={{ paddingBottom: ios ? 26 : 10 }} data-platform={platform}>
        {NAV_PRIMARY.map((n) => (
          <NavCell key={n.id} n={n} active={route === n.id} ios={ios} onClick={() => ctx.setRoute(n.id)} />
        ))}
        <NavCell n={{ id: "more", label: "Daha", icon: "sliders" }} active={moreActive} ios={ios} onClick={() => setSheet(true)} />
      </nav>

      {/* tapped-timeframe expanded chart */}
      {tfModal && <TfChartModal ctx={ctx} tf={tfModal} onClose={() => setTfModal(null)} />}

      {/* more sheet */}
      {sheet && (
        <div className="sgs-sheet-scrim" onClick={() => setSheet(false)}>
          <div className="sgs-sheet" onClick={(e) => e.stopPropagation()} style={{ paddingBottom: ios ? 34 : 18 }}>
            <div className="sgs-sheet-grab" />
            <div className="sgs-label" style={{ padding: "0 4px 10px" }}>DAHA FAZLA</div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "var(--gap)" }}>
              {NAV_MORE.map((m) => (
                <button key={m.id} className="sgs-sheet-item" onClick={() => ctx.setRoute(m.id)}
                  style={{ borderColor: route === m.id ? "var(--accent)" : "var(--border)" }}>
                  <span style={{ color: route === m.id ? "var(--accent)" : "var(--muted)", display: "flex" }}>{SGSIcons[m.icon]({ size: 20 })}</span>
                  <span style={{ color: "var(--text)", fontWeight: 600, fontSize: "calc(13px * var(--fs))" }}>{m.label}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function NavCell({ n, active, ios, onClick }) {
  return (
    <button className="sgs-navcell" onClick={onClick} data-active={active}>
      <span className="sgs-navcell-icon" style={{
        color: active ? (ios ? "var(--accent)" : "var(--chip-fg)") : "var(--muted)",
        background: (!ios && active) ? "var(--accent)" : "transparent",
      }}>{SGSIcons[n.icon]({ size: 21 })}</span>
      <span style={{ fontSize: "calc(9.5px * var(--fs))", color: active ? "var(--accent)" : "var(--muted)", fontWeight: active ? 700 : 500, letterSpacing: 0.2 }}>{n.label}</span>
    </button>
  );
}

window.AppShell = AppShell;
