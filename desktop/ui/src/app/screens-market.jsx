/* ============================================================================
   SGS — market screens: Panel, OI·L/S (Pozisyon), Tarama (Scanner)
   ========================================================================== */

/* ── shared hero: active symbol + price + change + action ─────────────────── */
function SymbolHero({ d }) {
  const upDir = d.ch >= 0;
  return (
    <div className="sgs-hero">
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 10 }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ fontFamily: "var(--font-display)", fontWeight: 800, fontSize: "calc(20px * var(--fs) * var(--hero-mul))", color: "var(--text)", letterSpacing: 0.3 }}>{d.s.replace("USDT", "")}</span>
            <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--dim)", border: "1px solid var(--border)", padding: "1px 5px", borderRadius: "var(--radius-sm)" }}>USDT-M PERP</span>
          </div>
          <div style={{ color: "var(--muted)", fontSize: "calc(11px * var(--fs))", marginTop: 2 }}>{d.name}</div>
        </div>
        <div style={{ textAlign: "right" }}>
          <div style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(22px * var(--fs) * var(--hero-mul))", color: "var(--text)", lineHeight: 1.05 }}>
            ${sgsFmtPrice(d.price)}
          </div>
          <div style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(12px * var(--fs))", color: upDir ? "var(--up)" : "var(--down)", marginTop: 2 }}>
            {upDir ? "▲" : "▼"} {upDir ? "+" : ""}{d.ch.toFixed(2)}% · 24s
          </div>
        </div>
      </div>
    </div>
  );
}

/* ── 12-TF consensus grid ─────────────────────────────────────────────────── */
function LiveTfGrid({ multiTf, onSelect }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: "calc(var(--gap) * 0.7)" }}>
      {multiTf.map((m) => {
        const col = sigColor(m.signal);
        return (
          <button key={m.tf} onClick={() => onSelect && onSelect(m.tf)} className="sgs-tf-cell" style={{
            background: "var(--bg2)", border: "1px solid var(--border)", borderRadius: "var(--radius-sm)",
            padding: "7px 8px", display: "flex", flexDirection: "column", gap: 4, position: "relative", overflow: "hidden",
            cursor: "pointer", textAlign: "left",
          }}>
            <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: 3, background: col }} />
            <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--muted)", fontWeight: 700, paddingLeft: 4, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              {m.tf}
              <span style={{ color: "var(--dim)", display: "flex" }}>{SGSIcons.search({ size: 10 })}</span>
            </span>
            <div style={{ paddingLeft: 4 }}><SignalBadge signal={m.signal} size="sm" /></div>
            <div style={{ paddingLeft: 4, display: "flex", alignItems: "center", gap: 4 }}>
              <div style={{ flex: 1, height: 3, borderRadius: 3, background: "var(--elev2)", overflow: "hidden" }}>
                <div style={{ width: `${m.confidence}%`, height: "100%", background: col }} />
              </div>
              <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(9px * var(--fs))", color: "var(--dim)" }}>{m.confidence}</span>
            </div>
          </button>
        );
      })}
    </div>
  );
}

/* ── expanded big chart for a tapped timeframe ────────────────────────────── */
function TfChartModal({ ctx, tf, onClose }) {
  const d = SGS_DATA_MAP[ctx.symbol];
  const m = d.multiTf.find((x) => x.tf === tf) || d.multiTf[0];
  const col = sigColor(m.signal);
  // a per-tf slice count so each timeframe feels distinct
  const countByTf = { "1m": 80, "3m": 74, "5m": 70, "15m": 64, "30m": 58, "1h": 54, "2h": 48, "4h": 44, "6h": 40, "8h": 36, "12h": 32, "1d": 28 };
  const types = [{ v: "candle", l: "Mum" }, { v: "line", l: "Çizgi" }, { v: "area", l: "Alan" }, { v: "heikin", l: "Heikin" }];
  const [type, setType] = React.useState(ctx.t.chartType);
  const last = d.candles[d.candles.length - 1];
  return (
    <div className="sgs-sheet-scrim" style={{ alignItems: "center", justifyContent: "center", padding: "var(--pad)" }} onClick={onClose}>
      <div className="sgs-card sgs-glass" onClick={(e) => e.stopPropagation()} style={{ width: "100%", maxWidth: 380 }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "var(--pad) var(--pad) 0" }}>
          <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
            <span style={{ fontFamily: "var(--font-display)", fontWeight: 800, fontSize: "calc(17px * var(--fs))", color: "var(--text)" }}>{d.s.replace("USDT", "")}</span>
            <span style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(12px * var(--fs))", color: "var(--accent)", border: "1px solid var(--accent-line)", borderRadius: "var(--radius-sm)", padding: "1px 7px" }}>{tf}</span>
          </div>
          <button className="sgs-icon-btn" onClick={onClose}>{SGSIcons.close({ size: 16 })}</button>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "8px var(--pad) 0" }}>
          <SignalBadge signal={m.signal} size="lg" />
          <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))", color: "var(--muted)" }}>güven <b style={{ color: col }}>{m.confidence}%</b></span>
          <div style={{ flex: 1 }} />
          <span style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(14px * var(--fs))", color: "var(--text)" }}>${sgsFmtPrice(d.price)}</span>
        </div>
        <div style={{ padding: "10px var(--pad) var(--pad)" }}>
          <div style={{ marginBottom: 8 }}><Seg full options={types} value={type} onChange={setType} /></div>
          <SGSChart candles={d.candles} type={type} height={260} count={countByTf[tf] || 56} />
          <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: 6, marginTop: 10 }}>
            {[["A", last.o], ["Y", last.h], ["D", last.l], ["K", last.c]].map(([k, v], i) => (
              <div key={i} style={{ background: "var(--bg2)", border: "1px solid var(--border)", borderRadius: "var(--radius-sm)", padding: "6px 8px" }}>
                <div className="sgs-label">{["AÇILIŞ", "YÜKSEK", "DÜŞÜK", "KAPANIŞ"][i]}</div>
                <div style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(11px * var(--fs))", color: i === 1 ? "var(--up)" : i === 2 ? "var(--down)" : "var(--text)", marginTop: 2 }}>{sgsFmtPrice(v)}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ── confidence ring ──────────────────────────────────────────────────────── */
function ConfRing({ value, color, size = 78 }) {
  const r = (size - 10) / 2, c = 2 * Math.PI * r;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ flexShrink: 0 }}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--elev2)" strokeWidth="6" />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth="6" strokeLinecap="round"
        strokeDasharray={c} strokeDashoffset={c * (1 - value / 100)} transform={`rotate(-90 ${size / 2} ${size / 2})`} style={{ transition: "stroke-dashoffset .6s" }} />
      <text x="50%" y="48%" textAnchor="middle" dominantBaseline="middle" fill="var(--text)" style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: size * 0.26 }}>{value}</text>
      <text x="50%" y="66%" textAnchor="middle" dominantBaseline="middle" fill="var(--dim)" style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: size * 0.12 }}>GÜVEN</text>
    </svg>
  );
}

function PanelScreen({ ctx }) {
  const d = SGS_DATA_MAP[ctx.symbol];
  const tfOpts = ["15m", "1h", "4h", "1d"];
  const col = sigColor(d.finalSignal);
  const favs = ctx.favorites;
  return (
    <div className="sgs-screen">
      <ScreenTitle title="Aktif Coin" sub="12 zaman dilimi konsensüs" />

      {/* symbol switcher */}
      <div className="sgs-hscroll" style={{ marginBottom: "var(--gap)" }}>
        {favs.map((s) => (
          <Chip key={s} active={s === ctx.symbol} onClick={() => ctx.setSymbol(s)}>{s.replace("USDT", "")}</Chip>
        ))}
      </div>

      <SymbolHero d={d} />

      <Card title="Fiyat Grafiği" right={<Seg options={tfOpts} value={ctx.tf} onChange={ctx.setTf} />}>
        <SGSChart candles={d.candles} type={ctx.t.chartType} height={170} count={ctx.t.density === "compact" ? 70 : 56} />
      </Card>

      <Card title={`${d.s} · 12 ZAMAN DİLİMİ KONSENSÜS`}>
        <LiveTfGrid multiTf={d.multiTf} onSelect={ctx.openTfChart} />
      </Card>

      {/* Son Karar */}
      <Card title="SON KARAR" right={<SignalBadge signal={d.finalSignal} size="lg" />}>
        <div style={{ display: "flex", gap: "var(--pad)", alignItems: "center" }}>
          <ConfRing value={d.confidence} color={col} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: "flex", gap: 6, marginBottom: 8 }}>
              <ActionTag action={d.action} col={col} />
              <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--dim)", alignSelf: "center" }}>15 indikatör · ağırlıklı</span>
            </div>
            <p style={{ margin: 0, color: "var(--muted)", fontSize: "calc(12px * var(--fs))", lineHeight: 1.5, textWrap: "pretty" }}>{d.reason}</p>
          </div>
        </div>
        <div style={{ marginTop: "var(--pad)" }}>
          <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
            <span className="sgs-label">SİNYAL DAĞILIMI</span>
            <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10.5px * var(--fs))", color: "var(--muted)" }}>
              <b style={{ color: "var(--up)" }}>{d.buy} AL</b> · <b style={{ color: "var(--muted)" }}>{d.neutral} NÖTR</b> · <b style={{ color: "var(--down)" }}>{d.sell} SAT</b>
            </span>
          </div>
          <div style={{ display: "flex", height: 10, borderRadius: 99, overflow: "hidden", background: "var(--elev2)" }}>
            <div style={{ width: `${(d.buy / 12) * 100}%`, background: "var(--up)" }} />
            <div style={{ width: `${(d.neutral / 12) * 100}%`, background: "var(--dim)" }} />
            <div style={{ width: `${(d.sell / 12) * 100}%`, background: "var(--down)" }} />
          </div>
        </div>
      </Card>
    </div>
  );
}

function ActionTag({ action, col }) {
  return (
    <span style={{
      fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(13px * var(--fs))",
      color: "var(--chip-fg)", background: col, padding: "3px 12px", borderRadius: "var(--radius-sm)", letterSpacing: 1,
    }}>{action}</span>
  );
}

/* ── OI · L/S ─────────────────────────────────────────────────────────────── */
function QuantGauge({ score }) {
  const txt = score >= 60 ? "GÜÇLÜ BOĞA" : score >= 20 ? "BOĞA EĞİLİMİ" : score > -20 ? "DENGELİ / NÖTR" : score > -60 ? "AYI EĞİLİMİ" : "GÜÇLÜ AYI";
  const col = score >= 20 ? "var(--up)" : score <= -20 ? "var(--down)" : "var(--muted)";
  const pos = ((score + 100) / 200) * 100;
  return (
    <div style={{ background: `color-mix(in srgb, ${col} 8%, transparent)`, border: `1px solid color-mix(in srgb, ${col} 22%, transparent)`, borderRadius: "var(--radius)", padding: "var(--pad)" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div>
          <div className="sgs-label">KANTİTATİF PİYASA YÖNÜ · QUANT BIAS</div>
          <div style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(15px * var(--fs))", color: col, marginTop: 3 }}>{txt}</div>
        </div>
        <div style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(22px * var(--fs))", color: col }}>{score >= 0 ? "+" : ""}{score.toFixed(1)}%</div>
      </div>
      <div style={{ position: "relative", height: 7, marginTop: 12, borderRadius: 99, background: "linear-gradient(90deg, var(--down-soft), var(--elev2) 50%, var(--up-soft))" }}>
        <div style={{ position: "absolute", top: "50%", left: "50%", width: 1, height: 11, transform: "translate(-50%,-50%)", background: "var(--border-strong)" }} />
        <div style={{ position: "absolute", top: "50%", left: `${pos}%`, width: 12, height: 12, borderRadius: 99, transform: "translate(-50%,-50%)", background: col, boxShadow: `0 0 0 3px var(--bg2)` }} />
      </div>
    </div>
  );
}

function PositionsScreen({ ctx }) {
  const d = SGS_DATA_MAP[ctx.symbol];
  const s = d.series;
  const periods = ["5m", "15m", "1h", "4h", "1d"];
  const last = (arr) => arr[arr.length - 1];
  const delta = (arr) => { const p = arr[arr.length - 2] || arr[arr.length - 1]; return p ? ((last(arr) - p) / p) * 100 : 0; };
  const netTaker = s.oi.map((v, i) => v * 0.0009 * (s.taker[i] - 1));
  const accLong = (last(s.acc) / (1 + last(s.acc))) * 100;
  const posLong = (last(s.pos) / (1 + last(s.pos))) * 100;

  const seriesRows = [
    { label: "AÇIK İLGİ (OI)", arr: s.oi, color: "#06B6D4", fmt: (v) => "$" + sgsFmtBig(v) },
    { label: "GLOBAL L/S", arr: s.glob, color: "#00BFA5", fmt: (v) => v.toFixed(2) },
    { label: "HESAP L/S", arr: s.acc, color: "#F97316", fmt: (v) => v.toFixed(2) },
    { label: "BALİNA L/S", arr: s.pos, color: "#8B5CF6", fmt: (v) => v.toFixed(2) },
    { label: "TAKER L/S", arr: s.taker, color: "var(--accent)", fmt: (v) => v.toFixed(2) },
    { label: "NET TAKER HACMİ", arr: netTaker, color: "#2979FF", fmt: (v) => "$" + sgsFmtBig(Math.abs(v)) },
    { label: "FONLAMA ORANI", arr: s.funding, color: "var(--warn)", fmt: (v) => (v * 100).toFixed(4) + "%" },
    { label: "QUANT BIAS", arr: s.bias, color: d.quantBias >= 0 ? "var(--up)" : "var(--down)", fmt: (v) => (v >= 0 ? "+" : "") + v.toFixed(1) + "%" },
  ];

  return (
    <div className="sgs-screen">
      <ScreenTitle title="OI · L/S" sub="Açık ilgi & long/short oranları" />
      <div className="sgs-hscroll" style={{ marginBottom: "var(--gap)" }}>
        {ctx.favorites.map((sy) => <Chip key={sy} active={sy === ctx.symbol} onClick={() => ctx.setSymbol(sy)}>{sy.replace("USDT", "")}</Chip>)}
      </div>

      <Card title={`${d.s} · PERİYOT`} right={<Seg options={periods} value={ctx.period} onChange={ctx.setPeriod} />}>
        <QuantGauge score={d.quantBias} />
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "var(--pad)", marginTop: "var(--pad)" }}>
          <Stat label="FİYAT" value={"$" + sgsFmtPrice(d.price)} delta={d.ch} dotColor="var(--up)" />
          <Stat label="AÇIK İLGİ" value={"$" + sgsFmtBig(last(s.oi))} delta={delta(s.oi)} dotColor="#06B6D4" />
          <Stat label="QUANT BIAS" value={(d.quantBias >= 0 ? "+" : "") + d.quantBias.toFixed(1) + "%"} valueColor={d.quantBias >= 0 ? "var(--up)" : "var(--down)"} dotColor={d.quantBias >= 0 ? "var(--up)" : "var(--down)"} />
          <Stat label="BALİNA L/S" value={last(s.pos).toFixed(2)} delta={delta(s.pos)} dotColor="#8B5CF6" />
          <Stat label="HESAP L/S" value={last(s.acc).toFixed(2)} delta={delta(s.acc)} dotColor="#F97316" />
          <Stat label="GLOBAL L/S" value={last(s.glob).toFixed(2)} delta={delta(s.glob)} dotColor="#00BFA5" />
          <Stat label="TAKER L/S" value={last(s.taker).toFixed(2)} delta={delta(s.taker)} dotColor="var(--accent)" />
          <Stat label="NET TAKER HACMİ" value={"$" + sgsFmtBig(Math.abs(last(netTaker)))} delta={delta(netTaker)} dotColor="#2979FF" />
          <Stat label="FONLAMA ORANI" value={(last(s.funding) * 100).toFixed(4) + "%"} dotColor="var(--warn)" valueColor={last(s.funding) >= 0 ? "var(--up)" : "var(--down)"} />
        </div>
      </Card>

      <Card title="SERİLER · SON 48 NOKTA">
        <div style={{ display: "flex", flexDirection: "column", gap: "calc(var(--gap) * 1.2)" }}>
          {seriesRows.map((row) => (
            <div key={row.label}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 4 }}>
                <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
                  <i style={{ width: 6, height: 6, borderRadius: 6, background: row.color }} />
                  <span className="sgs-label">{row.label}</span>
                </span>
                <span style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(12px * var(--fs))", color: "var(--text)" }}>{row.fmt(last(row.arr))}</span>
              </div>
              <Sparkline values={row.arr} color={row.color} height={30} />
            </div>
          ))}
        </div>
      </Card>

      <Card title="L/S DAĞILIM DETAYLARI">
        <LSRow label="Balina L/S (Pozisyon ağırlıklı)" longPct={posLong} />
        <div style={{ height: "var(--gap)" }} />
        <LSRow label="Hesap L/S (Kullanıcı sayısı)" longPct={accLong} />
      </Card>
    </div>
  );
}

function LSRow({ label, longPct }) {
  const shortPct = 100 - longPct;
  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 5 }}>
        <span style={{ color: "var(--muted)", fontSize: "calc(11px * var(--fs))" }}>{label}</span>
        <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10.5px * var(--fs))" }}>
          <b style={{ color: "var(--up)" }}>L {longPct.toFixed(1)}%</b> · <b style={{ color: "var(--down)" }}>S {shortPct.toFixed(1)}%</b>
        </span>
      </div>
      <Bar2 a={longPct} b={shortPct} h={10} />
    </div>
  );
}

/* ── whale-divergence verdict chip ────────────────────────────────────────── */
function WhaleChip({ verdict, small }) {
  const map = {
    CONFIRM: { c: "var(--up)", bg: "var(--up-soft)", t: "BALİNA ✓" },
    NEUTRAL: { c: "var(--muted)", bg: "var(--elev2)", t: "NÖTR" },
    ADVERSE: { c: "var(--down)", bg: "var(--down-soft)", t: "UYUMSUZ ✕" },
  };
  const m = map[verdict] || map.NEUTRAL;
  return (
    <span style={{
      fontFamily: "var(--font-mono)", fontSize: `calc(${small ? 8.5 : 9}px * var(--fs))`, fontWeight: 700,
      color: m.c, background: m.bg, border: `1px solid color-mix(in srgb, ${m.c} 30%, transparent)`,
      borderRadius: "var(--radius-sm)", padding: "1px 6px", whiteSpace: "nowrap", letterSpacing: 0.3,
    }}>{m.t}</span>
  );
}

/* ── Tarama (scanner) — continuous · whale-divergence elimination · top-N ──── */
const SGS_TABLE_SIZES = [{ v: "5", l: "5" }, { v: "10", l: "10" }, { v: "15", l: "15" }, { v: "20", l: "20" }, { v: "all", l: "Tümü" }];

function TaramaScreen({ ctx }) {
  const [cont, setCont] = React.useState(true);       // continuous (ardı sıra) mode
  const [running, setRunning] = React.useState(false);
  const [progress, setProgress] = React.useState(0);
  const [cycle, setCycle] = React.useState(0);        // each completed pass bumps this
  const [size, setSize] = React.useState("10");       // table size (top-N) button
  const [sort, setSort] = React.useState("score");    // display order of survivors
  const [showElim, setShowElim] = React.useState(false);

  const timerRef = React.useRef(null);
  const progRef = React.useRef(0);
  const contRef = React.useRef(cont);
  React.useEffect(() => { contRef.current = cont; }, [cont]);

  const sizeN = size === "all" ? "all" : parseInt(size, 10);

  // deterministic per-cycle live snapshot → FULL ranked quant scan (size:'all' so the
  // hysteresis below can re-bucket the whole universe and backfill correctly).
  const ticked = React.useMemo(() => SGS_QUANT.liveTick(SGS_DATA, cycle), [cycle]);
  const scan = React.useMemo(() => SGS_QUANT.runScan(ticked, { size: "all" }), [ticked]);

  // Hysteresis: a coin only leaves the table after it is ADVERSE on TWO consecutive
  // cycles (kills verdict flicker in continuous mode). First sighting seeds itself, so
  // genuine adverse coins are still eliminated on the very first scan.
  const prevVerdict = React.useRef({});
  const debounced = React.useMemo(() => {
    const pv = prevVerdict.current;
    const survivors = [], eliminated = [];
    for (const it of scan.survivors.concat(scan.eliminated)) {
      const sym = it.d.s, now = it.div.verdict;
      const was = (sym in pv) ? pv[sym] : now;
      const effAdverse = it.div.adverse && was === "ADVERSE";
      pv[sym] = now;
      (effAdverse ? eliminated : survivors).push(it);
    }
    survivors.sort((a, b) => b.score - a.score);
    survivors.forEach((it, i) => (it.rank = i + 1));
    const sized = sizeN === "all" ? survivors : survivors.slice(0, sizeN);
    // summary counts describe the WHOLE scan (full survivor population), not just the sliced top-N
    return { sized, eliminated, keptCount: survivors.length, confirmCount: survivors.filter((x) => x.div.verdict === "CONFIRM").length };
  }, [scan, sizeN]);

  const survivors = React.useMemo(() => {
    const xs = debounced.sized.slice();
    if (sort === "conf") xs.sort((a, b) => b.d.confidence - a.d.confidence);
    else if (sort === "ch") xs.sort((a, b) => b.d.ch - a.d.ch);
    else xs.sort((a, b) => b.score - a.score);
    return xs;
  }, [debounced, sort]);

  const stop = React.useCallback(() => {
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
    setRunning(false); progRef.current = 0; setProgress(0);   // idle bar reads as idle, not "stuck mid-load"
  }, []);

  const start = React.useCallback(() => {
    if (timerRef.current) return;                              // idempotent: safe under repeated effect runs
    setRunning(true); progRef.current = 0; setProgress(0);
    timerRef.current = setInterval(() => {
      progRef.current += 12;                                   // fixed deterministic step (~9 ticks/cycle)
      if (progRef.current >= 100) {
        progRef.current = 0;
        setCycle((c) => c + 1);                                // complete a pass → rescan live snapshot
        if (!contRef.current) { stop(); setProgress(100); return; }
      }
      setProgress(progRef.current);
    }, 90);
  }, [stop]);

  // auto-start continuous scanning on mount; clean reset on unmount (StrictMode-safe)
  React.useEffect(() => {
    start();
    return () => { if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; } setRunning(false); };
  }, [start]);

  const elimN = debounced.eliminated.length;
  const tableLabel = size === "all" ? "Tümü" : "İlk " + size;

  return (
    <div className="sgs-screen">
      <ScreenTitle title="Tarama" sub="Sürekli tarama · balina uyumsuzluğu elemesi" />

      {/* control hero */}
      <div className="sgs-scan-hero">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "var(--pad)" }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontFamily: "var(--font-display)", fontWeight: 800, fontSize: "calc(16px * var(--fs))", color: "var(--text)" }}>{scan.scanned} piyasa taraması</div>
            <div style={{ color: "var(--muted)", fontSize: "calc(11px * var(--fs))", marginTop: 2, display: "flex", alignItems: "center", gap: 6 }}>
              {running && <i className="sgs-pulse" style={{ width: 7, height: 7, borderRadius: 7, background: "var(--up)", flexShrink: 0 }} />}
              {running ? `Döngü #${cycle} · ${cont ? "sürekli" : "tek geçiş"}` : `Duraklatıldı · döngü #${cycle}`}
            </div>
          </div>
          <button onClick={running ? stop : start} className="sgs-primary-btn" style={running ? { background: "var(--down)" } : null}
            aria-label={running ? "Taramayı durdur" : "Taramayı başlat"} aria-pressed={running}>
            {(running ? SGSIcons.close : SGSIcons.scan)({ size: 16 })}
            {running ? " DURDUR · %" + Math.round(progress) : " BAŞLAT"}
          </button>
        </div>
        <div style={{ height: 5, borderRadius: 99, background: "var(--elev2)", overflow: "hidden" }}>
          <div style={{ width: `${progress}%`, height: "100%", background: "linear-gradient(90deg, var(--accent), var(--accent2))", transition: "width .12s" }} />
        </div>
        {/* live summary */}
        <div style={{ display: "flex", gap: 8, marginTop: "var(--pad)", flexWrap: "wrap" }}>
          <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--up)" }}>✓ {debounced.confirmCount} teyitli</span>
          <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--muted)" }}>· {debounced.keptCount} kalan</span>
          <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(10px * var(--fs))", color: "var(--down)" }}>· ✕ {elimN} elendi</span>
        </div>
      </div>

      {/* table-size button + continuous toggle */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "var(--gap)", margin: "var(--gap) 0 calc(var(--gap) * 0.6)" }}>
        <div style={{ display: "flex", flexDirection: "column", gap: 4, minWidth: 0 }}>
          <span className="sgs-label">TABLO BOYUTU · {tableLabel}</span>
          <Seg options={SGS_TABLE_SIZES} value={size} onChange={setSize} />
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 4, alignItems: "flex-end" }}>
          <span className="sgs-label">MOD</span>
          <Chip active={cont} onClick={() => setCont((v) => !v)}>{cont ? "SÜREKLİ ●" : "TEK GEÇİŞ"}</Chip>
        </div>
      </div>

      {/* sort row */}
      <div style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", marginBottom: "var(--gap)" }}>
        <Seg options={[{ v: "score", l: "Puan" }, { v: "conf", l: "Güven" }, { v: "ch", l: "Değişim" }]} value={sort} onChange={setSort} />
      </div>

      {/* survivors */}
      <div style={{ display: "flex", flexDirection: "column", gap: "var(--gap)" }}>
        {survivors.map((x, i) => {
          const d = x.d;
          return (
            <button key={d.s} className="sgs-result" onClick={() => { ctx.setSymbol(d.s); ctx.setRoute("panel"); }}
              style={x.div.verdict === "CONFIRM" ? { borderColor: "color-mix(in srgb, var(--up) 28%, var(--border))" } : null}>
              <span style={{ fontFamily: "var(--font-mono)", fontSize: "calc(11px * var(--fs))", color: i < 3 ? "var(--accent)" : "var(--dim)", fontWeight: 800, width: 18, textAlign: "right", flexShrink: 0 }}>{i + 1}</span>
              <div style={{ flex: 1, minWidth: 0, textAlign: "left" }}>
                <div style={{ display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
                  <span style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(13px * var(--fs))", color: "var(--text)" }}>{d.s.replace("USDT", "")}</span>
                  <SignalBadge signal={d.finalSignal} size="sm" />
                  <WhaleChip verdict={x.div.verdict} small />
                </div>
                <div style={{ display: "flex", gap: 2, marginTop: 5 }}>
                  {d.multiTf.map((m) => <i key={m.tf} title={m.tf} style={{ flex: 1, height: 4, borderRadius: 2, background: sigColor(m.signal), opacity: m.signal === "NEUTRAL" ? 0.4 : 1 }} />)}
                </div>
              </div>
              <div style={{ width: 52, flexShrink: 0 }}><Sparkline values={d.candles.slice(-24).map((c) => c.c)} color={d.ch >= 0 ? "var(--up)" : "var(--down)"} height={26} fill={false} /></div>
              <div style={{ textAlign: "right", flexShrink: 0, minWidth: 62 }}>
                <div style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(12px * var(--fs))", color: "var(--text)" }}>${sgsFmtPrice(d.price)}</div>
                <div style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(10px * var(--fs))", color: "var(--warn)" }}>{Math.round(x.score)} P</div>
              </div>
              <span style={{ color: "var(--dim)", display: "flex", flexShrink: 0 }}>{SGSIcons.chevron({ size: 15 })}</span>
            </button>
          );
        })}
        {survivors.length === 0 && (
          <div className="sgs-card sgs-glass" style={{ padding: "var(--pad)", textAlign: "center", color: "var(--muted)", fontSize: "calc(12px * var(--fs))" }}>
            Tüm adaylar balina uyumsuzluğundan elendi — teyitli sinyal bekleniyor.
          </div>
        )}
      </div>

      {/* eliminated strip (collapsible) */}
      {elimN > 0 && (
        <div className="sgs-card sgs-glass" style={{ marginTop: "var(--gap)" }}>
          <button onClick={() => setShowElim((v) => !v)} aria-expanded={showElim} aria-label={`Elenen ${elimN} coini ${showElim ? "gizle" : "göster"}`} style={{ width: "100%", display: "flex", alignItems: "center", justifyContent: "space-between", background: "transparent", border: "none", padding: "var(--pad)", cursor: "pointer" }}>
            <span style={{ display: "flex", alignItems: "center", gap: 7 }}>
              <span style={{ color: "var(--down)", display: "flex" }}>{SGSIcons.close({ size: 13 })}</span>
              <span style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(11px * var(--fs))", color: "var(--text)" }}>BALİNA UYUMSUZLUĞUNDAN ELENENLER · {elimN}</span>
            </span>
            <span style={{ color: "var(--dim)", display: "flex", transform: showElim ? "rotate(90deg)" : "none", transition: "transform .15s" }}>{SGSIcons.chevron({ size: 15 })}</span>
          </button>
          {showElim && (
            <div style={{ padding: "0 var(--pad) var(--pad)" }}>
              {debounced.eliminated.map((x) => (
                <button key={x.d.s} className="sgs-result" onClick={() => { ctx.setSymbol(x.d.s); ctx.setRoute("positions"); }} style={{ marginTop: 6, opacity: 0.92 }}>
                  <div style={{ flex: 1, minWidth: 0, textAlign: "left" }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                      <span style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(12.5px * var(--fs))", color: "var(--text)" }}>{x.d.s.replace("USDT", "")}</span>
                      <SignalBadge signal={x.d.finalSignal} size="sm" />
                      <WhaleChip verdict="ADVERSE" small />
                    </div>
                    <div style={{ color: "var(--muted)", fontSize: "calc(10px * var(--fs))", marginTop: 3, textWrap: "pretty" }}>{x.div.reason}</div>
                  </div>
                  <div style={{ textAlign: "right", flexShrink: 0, minWidth: 54 }}>
                    <div style={{ fontFamily: "var(--font-mono)", fontWeight: 700, fontSize: "calc(10px * var(--fs))", color: "var(--dim)" }}>WF {x.div.wf >= 0 ? "+" : ""}{x.div.wf.toFixed(2)}</div>
                    <div style={{ fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: "calc(10px * var(--fs))", color: "var(--warn)" }}>{Math.round(x.score)} P</div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

Object.assign(window, { PanelScreen, PositionsScreen, TaramaScreen, SymbolHero, LiveTfGrid, TfChartModal });
