/* ============================================================================
   DIVE INTO CRYPTO — DESKTOP · "DEPTH TERMINAL"  (self-contained React app)
   Reads the live backend via window.DIVE / SGS_DATA_MAP / SGS_SCAN (data.js).
   Falls back to an embedded demo market when the backend is unreachable, so the
   UI renders standalone. Themed via [data-theme] on <html>.
   ========================================================================== */
const { useState, useEffect, useRef, useCallback } = React;

/* ── indicator → family (57 indicators grouped for the panel table) ──────── */
const FAMILY = {
  ema_cross:"TREND", sma_cross:"TREND", macd:"TREND", ichimoku:"TREND", psar:"TREND",
  adx_di:"TREND", supertrend:"TREND", vortex:"TREND", aroon_oscillator:"TREND",
  schaff_trend_cycle:"TREND", trix:"TREND", kst:"TREND", coppock_curve:"TREND",
  kalman_trend:"TREND", donchian_breakout:"TREND", keltner_breakout:"TREND", elder_ray:"TREND",
  roc:"MOMENTUM", awesome_oscillator:"MOMENTUM", relative_vigor_index:"MOMENTUM",
  cmo:"MOMENTUM", tsi:"MOMENTUM", qstick:"MOMENTUM",
  rsi:"OSCILLATOR", stochastic:"OSCILLATOR", williams_r:"OSCILLATOR", cci:"OSCILLATOR",
  connors_rsi:"OSCILLATOR", stoch_rsi:"OSCILLATOR", ultimate_oscillator:"OSCILLATOR",
  fisher_transform:"OSCILLATOR", wavetrend:"OSCILLATOR",
  obv:"VOLUME", mfi:"VOLUME", cmf:"VOLUME", vwap:"VOLUME", chaikin_oscillator:"VOLUME",
  klinger_oscillator:"VOLUME", accum_dist_line:"VOLUME", force_index:"VOLUME", vwma_cross:"VOLUME",
  bollinger:"VOLATILITY", bollinger_percent_b:"VOLATILITY", squeeze:"VOLATILITY",
  choppiness:"VOLATILITY", atr_filter:"VOLATILITY", atr_percentile:"VOLATILITY",
  hist_vol_percentile:"VOLATILITY", mass_index:"VOLATILITY", range_expansion:"VOLATILITY",
  hurst:"REGIME", balance_of_power:"PRESSURE",
  zscore_reversion:"STATISTICAL", linreg_slope:"STATISTICAL",
  half_life_reversion:"STATISTICAL", rolling_sharpe:"STATISTICAL",
};
const FAM_ORDER = ["TREND","MOMENTUM","OSCILLATOR","VOLUME","VOLATILITY","REGIME","PRESSURE","STATISTICAL"];

/* ── helpers ─────────────────────────────────────────────────────────────── */
const TFS = ["1m","3m","5m","15m","30m","1h","2h","4h","6h","8h","12h","1d"];
const cls = (s="") => s.includes("BUY") ? "b" : s.includes("SELL") ? "s" : "n";
const mcls = (s) => ({STRONG_BUY:"sb",BUY:"b",STRONG_SELL:"ss",SELL:"s",NEUTRAL:"n"}[s] || "n");
const shortSig = (s="") => s.replace("STRONG_","S-").replace("_"," ");
const fmt = (v) => (window.sgsFmtPrice ? window.sgsFmtPrice(v) : v);
const kfmt = (v) => Math.abs(v) >= 1000 ? (v/1000).toFixed(1)+"K" : Math.round(v||0);
const num = (v, d=1) => (v==null || isNaN(v)) ? "—" : Number(v).toFixed(d);
const sgn = (x) => x>0?1:x<0?-1:0;

const ICONS = {
  scan:"M11 4a7 7 0 100 14 7 7 0 000-14zM16 16l5 5",
  panel:"M3 4h18v16H3zM3 9h18M9 9v11",
  oi:"M4 18V9M9 18V5M14 18v-6M19 18v-9",
  signal:"M3 12h4l3-8 4 16 3-8h4",
  leader:"M4 20V10M10 20V4M16 20v-8M22 20h-1M3 20h19",
  logs:"M4 6h16M4 12h16M4 18h10",
  gear:"M12 9a3 3 0 100 6 3 3 0 000-6zM12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2 2M16.4 16.4l2 2M18.4 5.6l-2 2M7.6 16.4l-2 2",
  search:"M10 3a6 6 0 100 12 6 6 0 000-12zM15 15l5 5",
  refresh:"M20 11a8 8 0 10-1.5 5M20 5v6h-6",
};
const Svg = ({d,vb="0 0 24 24"}) => <svg viewBox={vb}>{(Array.isArray(d)?d:[d]).map((p,i)=><path key={i} d={p}/>)}</svg>;

function Mark(){return(
  <svg width="38" height="38" viewBox="0 0 38 38"><g fill="none" stroke="var(--accent)" strokeWidth="1.5">
    <path d="M19 3 L34 19 L19 35 L4 19 Z"/><path d="M19 10 L27 19 L19 28 L11 19 Z" stroke="var(--accent-dim)"/>
    <circle cx="19" cy="19" r="2.4" fill="var(--accent)" stroke="none"/></g></svg>);}

function Vu({conf,dir}){const n=Math.round((conf||0)/10);
  return <span className="vu">{Array.from({length:10},(_,i)=>
    <i key={i} className={i<n?"on "+(dir<0?"dn":""):""}/>)}</span>;}

function Heat({multiTf}){
  const arr = (multiTf&&multiTf.length) ? multiTf : TFS.map(tf=>({tf,signal:"NEUTRAL",confidence:0}));
  return <span className="heat">{arr.map(m=>{
    const d = sgn(m.signal?.includes?.("BUY")?1:m.signal?.includes?.("SELL")?-1:(m.score||0));
    const lab = String(m.tf).replace("m","").replace("h","H").replace("d","D");
    return <b key={m.tf} className={d>0?"u":d<0?"d":"z"} title={`${m.tf} ${m.confidence||0}%`}>{lab}</b>;
  })}</span>;}

function Pill({sig}){const c=cls(sig);return <span className={"pill "+c}><span className="g"/>{shortSig(sig)}</span>;}

function Gauge({name,score,cap}){const p=Math.max(-100,Math.min(100,score||0));const pos=p>=0;
  return <div className="gauge">
    <div className="gt"><span className="gn">{name}</span>
      <span className="gv" style={{color:pos?"var(--up)":"var(--down)"}}>{p>0?"+":""}{p.toFixed(1)}</span></div>
    <div className="bar"><span className="mid"/><span className={"fill "+(pos?"pos":"neg")} style={{width:Math.abs(p)/2+"%"}}/></div>
    {cap && <div className="cap">{cap}</div>}
  </div>;}

/* ── SCANNER ─────────────────────────────────────────────────────────────── */
function Scanner({onPick}){
  const scan = window.SGS_SCAN || {survivors:[]};
  const rows = scan.survivors || [];
  return <>
    <div className="vhead"><span className="kicker">MANUEL TARAMA</span><h1>Piyasa Süpürmesi</h1>
      <div className="meta">TÜM BINANCE USDT-M · 12 TF<br/>KONSENSÜS · 57 İNDİKATÖR + 3 OVERLAY</div></div>
    <div className="panel"><div className="ph"><span className="tick">▸</span>SIRALANMIŞ SONUÇLAR
      <span className="rt">{rows.length} HAYATTA KALAN · {scan.scanned||0}/{scan.universeCount||0} TARANDI</span></div>
      <div className="pb" style={{padding:0}}>
        {rows.length===0
          ? <div className="state" style={{height:220}}><div className="spin"/><div>Tarama çalışıyor…</div></div>
          : <table className="rank"><thead><tr>
              <th>#</th><th>SEMBOL</th><th>KARAR</th><th className="r">FİYAT</th><th>GÜVEN</th>
              <th className="r">PUAN</th><th className="r">UYUM</th><th>12 ZAMAN DİLİMİ</th>
            </tr></thead><tbody>{rows.map((w,i)=>{
              const d=w.d||w; const dir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
              const hit=(d.multiTf||[]).filter(m=>sgn(m.signal?.includes("BUY")?1:m.signal?.includes("SELL")?-1:0)===dir).length;
              const score=w.score||d.netNss||d.quantBias||0;
              return <tr key={d.s} onClick={()=>onPick(d.s)}>
                <td className="rk">{String(i+1).padStart(2,"0")}</td>
                <td><div className="sym">{d.s.replace("USDT","")}<small>{d.name||d.s}</small></div></td>
                <td><Pill sig={d.finalSignal||"NEUTRAL"}/></td>
                <td className="r"><span className="px">${fmt(d.price)}</span>
                  <span className={"chg "+(d.ch>=0?"up":"dn")} style={{display:"block",fontSize:10}}>{d.ch>=0?"+":""}{num(d.ch,2)}%</span></td>
                <td><Vu conf={d.confidence} dir={dir}/></td>
                <td className="r score">{kfmt(score)}</td>
                <td className="r" style={{color:hit>=10?"var(--up)":"var(--warn)",fontFamily:"var(--font-d)",fontWeight:700,fontSize:10}}>{hit}/12</td>
                <td><Heat multiTf={d.multiTf}/></td>
              </tr>;})}</tbody></table>}
      </div></div>
  </>;}

/* ── PANEL ───────────────────────────────────────────────────────────────── */
function IndicatorTable({indicators=[]}){
  const groups={}; indicators.forEach(x=>{const f=FAMILY[x.name]||"OTHER";(groups[f]=groups[f]||[]).push(x);});
  const order=[...FAM_ORDER.filter(f=>groups[f]),...Object.keys(groups).filter(f=>!FAM_ORDER.includes(f))];
  return <table className="itbl"><tbody>{order.map(f=>[
    <tr className="fam" key={f}><td colSpan={3}>{f}</td></tr>,
    ...groups[f].map(x=><tr key={x.name}>
      <td className="nm">{x.name}</td>
      <td className="rv">{x.value!=null?num(x.value,x.value>100?1:3):"—"}</td>
      <td className="sg"><span className={"mini "+mcls(x.signal)}>{shortSig(x.signal)}</span></td>
    </tr>)
  ])}</tbody></table>;}

function Panel({sym}){
  const d = (window.SGS_DATA_MAP||{})[sym];
  if(!d || !d.multiTf || !d.multiTf.length)
    return <div className="state"><div className="spin"/><div>{String(sym||"").replace("USDT","")} verisi çekiliyor…</div></div>;
  const c=cls(d.finalSignal); const dir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
  const score=d.netNss||d.quantBias||0;
  const wr=d.whaleRegime; const ms=d.microstructure||{signals:[]}; const rg=d.regime||{}; const mtf=d.mtfConfluence||{};
  const wrtag = wr==="confirm" ? <span className="tag good">BALİNA: TEYİT</span>
    : wr==="adverse" ? <span className="tag bad">BALİNA: KARŞIT</span> : <span className="tag">BALİNA: NÖTR</span>;
  return <>
    <div className="vhead"><span className="kicker">SEMBOL DERİNLİK</span><h1>{d.s.replace("USDT","")} · {d.name||d.s}</h1>
      <div className="meta">1H BİRİNCİL · 12 TF KONSENSÜS<br/>RİSK: {d.risk||"—"}</div></div>
    <div className="grid2">
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>KONSENSÜS KARARI</div>
          <div className="price-row"><span className="p">${fmt(d.price)}</span>
            <span className={"c "+(d.ch>=0?"up":"dn")}>{d.ch>=0?"▲":"▼"} {num(Math.abs(d.ch||0),2)}%</span>
            <span style={{marginLeft:"auto"}}><Heat multiTf={d.multiTf}/></span></div>
          <div className="verdict">
            <div className="big"><div className="vlabel">NİHAİ SİNYAL</div>
              <div className={"vsig "+c}>{shortSig(d.finalSignal||"NEUTRAL").replace("S-","GÜÇLÜ ")}</div>
              <div className="vsub"><Vu conf={d.confidence} dir={dir}/> güven %{d.confidence||0}</div></div>
            <div className="side">
              <div className="cell"><div className="k">GÜVEN</div><div className="v">{d.confidence||0}<small>%</small></div></div>
              <div className="cell"><div className="k">PUAN</div><div className="v" style={{color:"var(--warn)"}}>{kfmt(score)}</div></div>
            </div></div>
          <div className="reason"><b>Tez.</b> {d.reason||"—"}</div>
          <div className="tagrow">{wrtag}
            <span className={"tag "+(rg.regime==="TREND"?"good":rg.regime==="RANGE"?"hot":"")}>REJİM: {rg.regime||"—"}</span>
            {rg.adx!=null && <span className="tag">ADX {num(rg.adx)}</span>}
            {rg.chop!=null && <span className="tag">CHOP {num(rg.chop)}</span>}
            <span className={"tag "+(mtf.gate?"good":"")}>MTF {mtf.gate?"✓ KAPI":"✕"} {mtf.htf_agree!=null?Math.round(mtf.htf_agree*100)+"%":""}</span></div>
        </div>
        <div className="panel mute"><div className="ph"><span className="tick">▸</span>İNDİKATÖR TABLOSU · {(d.indicators||[]).length} SATIR</div>
          <div className="pb" style={{padding:0}}><IndicatorTable indicators={d.indicators}/></div></div>
      </div>
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>FUTURES MİKROYAPI</div>
          <div className="pb" style={{padding:0}}>
            <Gauge name="BÜTÜN DEMET" score={ms.score} cap={`${ms.active||0} sinyal · ${ms.label||"—"}`}/>
            {(ms.signals||[]).map(s=><Gauge key={s.name} name={String(s.name).replace(/_/g," ").toUpperCase()} score={(s.score||0)*100}/>)}
          </div></div>
        <div className="panel mute"><div className="ph"><span className="tick">▸</span>REJİM & KONFLUENS</div>
          <div className="pb" style={{padding:0}}>
            <Gauge name="REJİM AĞIRLIKLI SKOR" score={(rg.adaptive_score||0)*50} cap={`${rg.regime||"—"} · adx ${num(rg.adx)} · chop ${num(rg.chop)}`}/>
            <Gauge name="MTF KONFLUENS" score={mtf.score} cap={`üst-TF uyum ${mtf.htf_agree!=null?Math.round(mtf.htf_agree*100):0}% · ${mtf.gate?"KAPI AÇIK":"kapı kapalı"}`}/>
            <Gauge name="BALİNA UYUMSUZLUK" score={d.divergence?.score} cap={`en iyi ${d.divergence?.tf||"—"} · kapsam ${d.divergence?.coverage||0}/3`}/>
          </div></div>
      </div>
    </div>
  </>;}

/* ── simple screens ──────────────────────────────────────────────────────── */
function Flow({sym}){ return <Panel sym={sym}/>; }
function Signal({sym}){ return <Panel sym={sym}/>; }
function Logs(){
  const logs=window.SGS_LOGS||[];
  return <>
    <div className="vhead"><span className="kicker">AĞ GÜNLÜĞÜ</span><h1>Bağlantı</h1>
      <div className="meta">BINANCE USDT-M · PUBLIC REST/WS</div></div>
    <div className="panel mute"><div className="ph"><span className="tick">▸</span>SON İSTEKLER</div>
      <div className="pb" style={{padding:0}}>
        {logs.length===0 ? <div className="reason">Günlük boş.</div> :
         <table className="itbl"><tbody>{logs.slice(0,60).map((l,i)=>
           <tr key={i}><td className="nm" style={{color:"var(--dim)"}}>{l.t||l.time||""}</td>
             <td className="rv" style={{textAlign:"left"}}>{l.msg||l.message||JSON.stringify(l)}</td></tr>)}</tbody></table>}
      </div></div>
  </>;}
function Settings({theme,setTheme}){
  const T=[["phosphor","Phosphor"],["amber","Amber"],["ice","Ice"],["paper","Paper (light)"]];
  return <>
    <div className="vhead"><span className="kicker">AYARLAR</span><h1>Görünüm</h1>
      <div className="meta">DEPTH TERMINAL · v0.2.0</div></div>
    <div className="panel"><div className="ph"><span className="tick">▸</span>TEMA</div>
      <div className="pb" style={{display:"flex",gap:10,flexWrap:"wrap"}}>
        {T.map(([id,label])=><button key={id} className="cta" style={{background:id===theme?"var(--accent)":"transparent",
          color:id===theme?"var(--accent-ink)":"var(--dim)",border:"1px solid var(--line2)"}}
          onClick={()=>setTheme(id)}>{label}</button>)}
      </div></div>
    <div className="panel mute"><div className="ph"><span className="tick">▸</span>MOTOR</div>
      <div className="pb reason">57 indikatör · 12 zaman dilimi · konsensüs + balina-uyumsuzluk filtresi + 3 overlay
        (futures-mikroyapı, rejim-adaptif ağırlık, çoklu-TF konfluens). Yalnızca public Binance verisi.</div></div>
  </>;}

/* ── shell + data orchestration ──────────────────────────────────────────── */
const NAV=[
  {id:"scan",label:"TARA",icon:"scan"},{id:"panel",label:"PANEL",icon:"panel"},
  {id:"flow",label:"OI·L/S",icon:"oi"},{id:"sig",label:"SİNYAL",icon:"signal"},
  {id:"logs",label:"LOG",icon:"logs"},
];
const THEMES=[["phosphor","#39ff9e"],["amber","#ffb02e"],["ice","#59c6ff"],["paper","#c2410c"]];
const SYMBOL_VIEWS=new Set(["panel","flow","sig"]);

const VIEW_IDS=["scan","panel","flow","sig","logs","settings"];
function App(){
  const [view,setView]=useState(()=>{const h=(location.hash||"").replace(/^#\/?/,"").split("/")[0];return VIEW_IDS.includes(h)?h:"scan";});
  const [sym,setSym]=useState(null);
  const [theme,setThemeState]=useState(()=>localStorage.getItem("dive_theme")||"phosphor");
  const [q,setQ]=useState("");
  const [clock,setClock]=useState("");
  const [,force]=useState(0);
  const setTheme=(t)=>{setThemeState(t);localStorage.setItem("dive_theme",t);document.documentElement.setAttribute("data-theme",t);};

  useEffect(()=>{document.documentElement.setAttribute("data-theme",theme);},[]);
  useEffect(()=>{window.__diveOnData=()=>force(v=>v+1);return()=>{window.__diveOnData=null;};},[]);
  useEffect(()=>{const id=setInterval(()=>setClock(new Date().toTimeString().slice(0,8)),1000);
    setClock(new Date().toTimeString().slice(0,8));return()=>clearInterval(id);},[]);

  // boot: universe → first symbol; mock fallback if backend down
  useEffect(()=>{(async()=>{
    try{
      const u=await window.DIVE.universe(60);
      const top=(u||[]).slice(0,8).map(r=>r.s);
      setSym(s=>s||top[0]||"BTCUSDT");
    }catch(e){
      if(window.DIVE_MOCK) window.DIVE_MOCK();
      setSym(s=>s||"BTCUSDT"); force(v=>v+1);
    }
  })();},[]);

  useEffect(()=>{ if(sym&&SYMBOL_VIEWS.has(view)) window.DIVE?.symbol?.(sym).catch(()=>{}); },[sym,view]);
  useEffect(()=>{ let live=true; const tick=async()=>{ if(!live)return;
    try{ if(view==="scan") await window.DIVE.scan(15,24);}catch{}
    try{ await window.DIVE.logs?.(); }catch{} };
    tick(); const id=setInterval(tick, view==="scan"?20000:15000); return()=>{live=false;clearInterval(id);}; },[view]);

  const refresh=()=>{ if(view==="scan") window.DIVE?.scan?.(15,24).catch(()=>{});
    else if(sym) window.DIVE?.symbol?.(sym).catch(()=>{}); window.DIVE?.logs?.().catch(()=>{}); };
  const submit=(e)=>{e.preventDefault();let s=q.trim().toUpperCase();if(!s)return;if(!s.endsWith("USDT"))s+="USDT";
    setSym(s); if(!SYMBOL_VIEWS.has(view)) setView("panel"); setQ("");};
  const pick=(s)=>{setSym(s);setView("panel");};

  let body;
  if(view==="scan") body=<Scanner onPick={pick}/>;
  else if(view==="panel") body=<Panel sym={sym}/>;
  else if(view==="flow") body=<Flow sym={sym}/>;
  else if(view==="sig") body=<Signal sym={sym}/>;
  else if(view==="logs") body=<Logs/>;
  else body=<Settings theme={theme} setTheme={setTheme}/>;

  return <div className="app">
    <div className="strip">
      <span className="live"><span className="dot"/>CANLI</span><span className="seg">│</span>
      <span>BINANCE USDT-M · PERP</span><span className="seg">│</span>
      <span><b>57</b> İNDİKATÖR</span><span className="seg">│</span><span><b>12</b> TF</span>
      <span className="spacer"/>
      <span>{view==="scan"?"TARAMA":(sym||"—").replace("USDT","")}</span><span className="seg">│</span>
      <form onSubmit={submit}><Svg d={ICONS.search}/><input value={q} onChange={e=>setQ(e.target.value)} placeholder="Sembol ara (örn. SOL)…"/></form>
      <span className="clk">{clock}</span><span className="seg">│</span>
      <div className="themes">{THEMES.map(([id,c])=><button key={id} className={id===theme?"on":""} style={{background:c}} title={id} onClick={()=>setTheme(id)}/>)}</div>
      <button className="iconbtn" title="Yenile" onClick={refresh}><Svg d={ICONS.refresh}/></button>
    </div>
    <nav className="rail">
      <div className="mark"><Mark/></div>
      {NAV.map(n=><button key={n.id} className={"rail-btn"+(view===n.id?" on":"")} onClick={()=>setView(n.id)}><Svg d={ICONS[n.icon]}/>{n.label}</button>)}
      <div className="grow"/>
      <button className={"rail-btn"+(view==="settings"?" on":"")} onClick={()=>setView("settings")}><Svg d={ICONS.gear}/>AYAR</button>
    </nav>
    <main className="stage" key={view+"|"+sym}>{body}</main>
  </div>;
}

ReactDOM.createRoot(document.getElementById("root")).render(<App/>);
