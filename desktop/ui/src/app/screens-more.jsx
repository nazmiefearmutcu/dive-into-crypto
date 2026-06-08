/* ============================================================================
   SGS — ScreenTitle + secondary screens: Sinyal, Lider, Ağ Günlüğü
   ========================================================================== */

function ScreenTitle({ title, sub }) {
  return (
    <div style={{ marginBottom: "var(--gap)" }}>
      <h1 style={{ margin: 0, fontFamily: "var(--font-display)", fontWeight: 800, fontSize: "calc(23px * var(--fs) * var(--hero-mul))", color: "var(--text)", letterSpacing: 0.2, lineHeight: 1.1 }}>{title}</h1>
      {sub && <div style={{ color: "var(--muted)", fontSize: "calc(11.5px * var(--fs))", marginTop: 3 }}>{sub}</div>}
    </div>
  );
}

/* ── Sinyal · 15 indicator table ──────────────────────────────────────────── */
function SinyalScreen({ ctx }) {
  const d = SGS_DATA_MAP[ctx.symbol];
  const buy = d.indicators.filter((i) => i.signal.includes("BUY")).length;
  const sell = d.indicators.filter((i) => i.signal.includes("SELL")).length;
  const neutral = 15 - buy - sell;
  return (
    <div className="sgs-screen">
      <ScreenTitle title="Sinyal" sub="15 indikatör · ayrıntılı tablo" />
      <div className="sgs-hscroll" style={{ marginBottom: "var(--gap)" }}>
        {ctx.favorites.map((sy) => <Chip key={sy} active={sy === ctx.symbol} onClick={() => ctx.setSymbol(sy)}>{sy.replace("USDT", "")}</Chip>)}
      </div>

      <Card title={`${d.s} · İNDİKATÖR ÖZETİ`}>
        <div style={{ display: "flex", gap: "var(--pad)", alignItems: "center" }}>
          <div style={{ flex: 1 }}>
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
              <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))" }}><b style={{ color: "var(--up)" }}>{buy} AL</b></span>
              <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))", color: "var(--muted)" }}>{neutral} NÖTR</span>
              <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))" }}><b style={{ color: "var(--down)" }}>{sell} SAT</b></span>
            </div>
            <div style={{ display: "flex", height: 10, borderRadius: 99, overflow: "hidden", background: "var(--elev2)" }}>
              <div style={{ width: `${(buy / 15) * 100}%`, background: "var(--up)" }} />
              <div style={{ width: `${(neutral / 15) * 100}%`, background: "var(--dim)" }} />
              <div style={{ width: `${(sell / 15) * 100}%`, background: "var(--down)" }} />
            </div>
          </div>
          <SignalBadge signal={d.finalSignal} size="lg" />
        </div>
      </Card>

      <Card title="İNDİKATÖRLER" pad={false}>
        <div className="sgs-table-head">
          <span style={{ flex: 1 }}>İNDİKATÖR</span>
          <span style={{ width: 64, textAlign: "right" }}>DEĞER</span>
          <span style={{ width: 48, textAlign: "center" }}>AĞIRLIK</span>
          <span style={{ width: 74, textAlign: "right" }}>SİNYAL</span>
        </div>
        {d.indicators.map((ind, i) => (
          <div key={ind.name} className="sgs-table-row" style={{ borderTop: i ? "1px solid var(--border)" : "none" }}>
            <span style={{ flex: 1, color: "var(--text)", fontSize: "calc(12.5px * var(--fs))", fontWeight: 600 }}>{ind.name}</span>
            <span style={{ width: 64, textAlign: "right", fontFamily: "var(--font-mono)", fontSize: "calc(11.5px * var(--fs))", color: "var(--muted)" }}>{ind.value.toFixed(1)}</span>
            <span style={{ width: 48, textAlign: "center", fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))", color: "var(--dim)" }}>×{ind.weight}</span>
            <span style={{ width: 74, textAlign: "right" }}><SignalBadge signal={ind.signal} size="sm" /></span>
          </div>
        ))}
      </Card>
    </div>
  );
}

/* ── Lider · gainers / losers ─────────────────────────────────────────────── */
function LiderScreen({ ctx }) {
  const [tab, setTab] = React.useState("gain");
  const list = tab === "gain" ? SGS_GAINERS : SGS_LOSERS;
  return (
    <div className="sgs-screen">
      <ScreenTitle title="Lider" sub="24 saat yükselen / düşen" />
      <Seg full options={[{ v: "gain", l: "▲ Yükselenler" }, { v: "lose", l: "▼ Düşenler" }]} value={tab} onChange={setTab} />
      <div style={{ height: "var(--gap)" }} />
      <div style={{ display: "flex", flexDirection: "column", gap: "var(--gap)" }}>
        {list.map((d, i) => (
          <button key={d.s} className="sgs-result" onClick={() => { ctx.setSymbol(d.s); ctx.setRoute("panel"); }}>
            <span style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(13px * var(--fs))", color: i < 3 ? "var(--accent)" : "var(--dim)", width: 20, flexShrink: 0 }}>{i + 1}</span>
            <div style={{ flex: 1, minWidth: 0, textAlign: "left" }}>
              <div style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(13px * var(--fs))", color: "var(--text)" }}>{d.s.replace("USDT", "")}</div>
              <div style={{ color: "var(--muted)", fontSize: "calc(10.5px * var(--fs))" }}>{d.name}</div>
            </div>
            <div style={{ width: 60, flexShrink: 0 }}><Sparkline values={d.candles.slice(-24).map((c) => c.c)} color={d.ch >= 0 ? "var(--up)" : "var(--down)"} height={28} /></div>
            <div style={{ textAlign: "right", flexShrink: 0, minWidth: 70 }}>
              <div style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(12px * var(--fs))", color: "var(--text)" }}>${sgsFmtPrice(d.price)}</div>
              <div style={{
                fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(11px * var(--fs))",
                color: d.ch >= 0 ? "var(--up)" : "var(--down)",
                background: d.ch >= 0 ? "var(--up-soft)" : "var(--down-soft)",
                borderRadius: "var(--radius-sm)", padding: "1px 6px", marginTop: 2, display: "inline-block",
              }}>{d.ch >= 0 ? "+" : ""}{d.ch.toFixed(2)}%</div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}

/* ── Ağ Günlüğü · logs ────────────────────────────────────────────────────── */
function LogsScreen() {
  const statusColor = (s) => s === 200 || s === 101 ? "var(--up)" : s === 429 ? "var(--warn)" : "var(--down)";
  return (
    <div className="sgs-screen">
      <ScreenTitle title="Ağ Günlüğü" sub="Canlı HTTP / WS aktivitesi" />
      <Card title="İSTEK AKIŞI" right={<span style={{ display: "flex", alignItems: "center", gap: 5, fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--up)" }}><i className="sgs-pulse" style={{ width: 7, height: 7, borderRadius: 7, background: "var(--up)" }} /> CANLI</span>} pad={false}>
        {SGS_LOGS.map((l, i) => (
          <div key={i} className="sgs-log-row" style={{ borderTop: i ? "1px solid var(--border)" : "none" }}>
            <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--dim)", flexShrink: 0 }}>{l.t}</span>
            <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10.5px * var(--fs))", color: statusColor(l.s), fontWeight: 700, width: 30, flexShrink: 0 }}>{l.s}</span>
            <span style={{ flex: 1, fontFamily: "var(--font-mono)", fontSize: "calc(10.5px * var(--fs))", color: "var(--muted)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{l.m}</span>
            <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--dim)", flexShrink: 0 }}>{l.ms}ms</span>
          </div>
        ))}
      </Card>
      <Card title="OTURUM">
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: "var(--pad)" }}>
          <Stat label="İSTEK" value="1.284" dotColor="var(--accent)" />
          <Stat label="HATA" value="3" valueColor="var(--down)" dotColor="var(--down)" />
          <Stat label="AĞIRLIK" value="480/2400" dotColor="var(--warn)" />
        </div>
      </Card>
    </div>
  );
}

Object.assign(window, { ScreenTitle, SinyalScreen, LiderScreen, LogsScreen });
