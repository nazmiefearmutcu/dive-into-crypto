/* ============================================================================
   DIVE INTO CRYPTO — DESKTOP · "DEPTH TERMINAL"  (self-contained React app)
   Reads the live backend via window.DIVE / SGS_DATA_MAP / SGS_SCAN (data.js).

   When the backend is unreachable the UI renders an explicit "data source
   unavailable" state. It NEVER falls back to fabricated data: the embedded demo
   market (mock.js) is manual-only, and while it is active a permanent banner plus
   a per-value DEMO marker make every fabricated number impossible to mistake for
   a live quote. Mid-session staleness (failed poll or >90s without a successful
   fetch) raises its own dismissible banner — mock data plays no part in it.
   Themed via [data-theme] on <html>.
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

/* ── demo-mode surfacing ─────────────────────────────────────────────────────
   mock.js fabricates prices/verdicts wholesale. It is manual-only, but if it IS
   on, nothing fabricated may reach the screen unlabelled: a fixed banner spans
   the viewport and every fabricated value carries an inline DEMO marker, so a
   fabricated verdict cannot be screenshotted without the warning in frame. */
const isDemo = () => !!(typeof window!=="undefined" && window.DIVE_DEMO && window.DIVE_DEMO.active);

function DemoBanner(){
  if(!isDemo()) return null;
  return <div className="demo-banner" role="alert" data-testid="demo-banner">
    <b>DEMO DATA — NOT LIVE MARKET DATA</b>
    <span>Ekrandaki her fiyat, karar ve skor UYDURMADIR · every price, verdict and score on screen is FABRICATED</span>
  </div>;
}

/* Inline marker attached to an individual fabricated value. */
function DemoMark({row}){
  if(!(row && row._demo)) return null;
  return <span className="demo-mark" title="Fabricated demo value — not live market data">DEMO</span>;
}

function DataSourceDown({error,onRetry}){
  return <div className="state src-down" role="alert" data-testid="source-unavailable">
    <div className="sd-title">VERİ KAYNAĞI KULLANILAMIYOR · DATA SOURCE UNAVAILABLE</div>
    <div className="sd-body">
      Yerel arka uca ya da Binance USDT-M public API'sine ulaşılamadı. Hiçbir sayı gösterilmiyor —
      bu uygulama veri yokken veri uydurmaz.<br/>
      Could not reach the local backend or the Binance USDT-M public API. No numbers are shown:
      this app does not synthesise data it does not have.
      <br/><br/>
      Binance market data is geo-restricted in some regions (Türkiye included). That is a network
      condition, not an app bug.
    </div>
    {error && <div className="sd-err">{String(error)}</div>}
    <button className="cta" onClick={onRetry}>TEKRAR DENE · RETRY</button>
  </div>;
}

/* Mid-session honesty: the LIVE feed went quiet. Distinct from demo mode — this
   never involves mock data; it only reports that real polls stopped succeeding. */
function StaleBanner({stale,onRetry,onDismiss}){
  if(!stale || stale.hidden) return null;
  return <div className="stale-banner" role="alert" data-testid="stale-banner">
    <b>VERİ GÜNCEL DEĞİL · DATA STALE</b>
    <span>— {stale.reason}</span>
    <span className="sgrow"/>
    <button className="sbtn" onClick={onRetry}>TEKRAR DENE · RETRY</button>
    <button className="sbtn" aria-label="Uyarıyı kapat · Dismiss stale-data warning" onClick={onDismiss}>✕</button>
  </div>;
}

/* Boot the symbol universe. Extracted so the no-fabrication guarantee is directly
   testable: on failure this returns an error and MUST NOT touch window.DIVE_MOCK. */
async function bootUniverse(api){
  try{
    const u = await api.universe(60);
    const top = (u||[]).slice(0,8).map(r=>r.s);
    return { ok:true, symbol: top[0] || null, error:null };
  }catch(e){
    return { ok:false, symbol:null, error: (e && e.message) ? e.message : String(e) };
  }
}

function Gauge({name,score,cap}){const p=Math.max(-100,Math.min(100,score||0));const pos=p>=0;
  return <div className="gauge">
    <div className="gt"><span className="gn">{name}</span>
      <span className="gv" style={{color:pos?"var(--up)":"var(--down)"}}>{p>0?"+":""}{p.toFixed(1)}</span></div>
    <div className="bar"><span className="mid"/><span className={"fill "+(pos?"pos":"neg")} style={{width:Math.abs(p)/2+"%"}}/></div>
    {cap && <div className="cap">{cap}</div>}
  </div>;}

/* ── SCANNER ─────────────────────────────────────────────────────────────── */
const VERDICT_RANK={STRONG_BUY:2,BUY:1,NEUTRAL:0,SELL:-1,STRONG_SELL:-2};
const SORT_COLS=[
  {k:"sym",   label:"SEMBOL",  get:(d,w)=>String(d.s||"")},
  {k:"verdict",label:"KARAR",  get:(d)=>VERDICT_RANK[d.finalSignal]??0},
  {k:"ch",    label:"FİYAT·24S",get:(d)=>d.ch, r:true},
  {k:"conf",  label:"GÜVEN",   get:(d)=>d.confidence},
  {k:"score", label:"PUAN",    get:(d,w)=>w.score||d.netNss||d.quantBias||0, r:true},
];
function Scanner({onPick}){
  const scan = window.SGS_SCAN || {survivors:[]};
  const [sort,setSort]=useState(null);   // null = server rank order
  const toggleSort=(k)=>setSort(s=> s&&s.k===k ? {k,dir:-s.dir} : {k,dir:-1});
  let rows=(scan.survivors||[]).map((w,i)=>({w,i}));
  if(sort){ const def=SORT_COLS.find(c=>c.k===sort.k);
    rows=rows.slice().sort((a,b)=>{
      const da=a.w.d||a.w, db=b.w.d||b.w;
      const va=def.get(da,a.w), vb=def.get(db,b.w);
      const c=typeof va==="string" ? String(va).localeCompare(String(vb))
                                   : ((va==null||isNaN(va))?-Infinity:va)-((vb==null||isNaN(vb))?-Infinity:vb);
      return c*sort.dir || a.i-b.i; });   // stable: ties keep server rank
  }
  return <>
    <div className="vhead"><span className="kicker">MANUEL TARAMA</span><h1>Piyasa Süpürmesi</h1>
      <div className="meta">TÜM BINANCE USDT-M · 12 TF<br/>KONSENSÜS · 57 İNDİKATÖR + 3 OVERLAY</div></div>
    <div className="panel"><div className="ph"><span className="tick">▸</span>SIRALANMIŞ SONUÇLAR
      <span className="rt">{rows.length} HAYATTA KALAN · {scan.scanned||0}/{scan.universeCount||0} TARANDI</span></div>
      <div className="pb" style={{padding:0}}>
        {rows.length===0
          ? <div className="state" style={{height:220}}><div className="spin"/><div>Tarama çalışıyor…</div></div>
          : <table className="rank"><thead><tr>
              <th scope="col">#</th>
              {SORT_COLS.map(c=>{ const on=sort&&sort.k===c.k;
                return <th key={c.k} scope="col" className={c.r?"r":""}
                  aria-sort={on?(sort.dir>0?"ascending":"descending"):undefined}>
                  <button className="thbtn" onClick={()=>toggleSort(c.k)}
                    aria-label={`${c.label} sütununa göre sırala`}>{c.label}{on?(sort.dir>0?" ▲":" ▼"):""}</button></th>;})}
              <th scope="col" className="r">UYUM</th><th scope="col">12 ZAMAN DİLİMİ</th>
            </tr></thead><tbody>{rows.map(({w,i})=>{
              const d=w.d||w; const dir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
              const hit=(d.multiTf||[]).filter(m=>sgn(m.signal?.includes("BUY")?1:m.signal?.includes("SELL")?-1:0)===dir).length;
              const score=w.score||d.netNss||d.quantBias||0;
              return <tr key={d.s} tabIndex={0} onClick={()=>onPick(d.s)}
                onKeyDown={(e)=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();onPick(d.s);}}}
                aria-label={`${d.s} panelini aç`}>
                <td className="rk">{String(i+1).padStart(2,"0")}</td>
                <td><div className="sym">{d.s.replace("USDT","")}<small>{d.name||d.s}</small></div></td>
                <td><Pill sig={d.finalSignal||"NEUTRAL"}/><DemoMark row={d}/></td>
                <td className="r"><span className="px">${fmt(d.price)}</span><DemoMark row={d}/>
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
          <div className="price-row"><span className="p">${fmt(d.price)}</span><DemoMark row={d}/>
            <span className={"c "+(d.ch>=0?"up":"dn")}>{d.ch>=0?"▲":"▼"} {num(Math.abs(d.ch||0),2)}%</span>
            <span style={{marginLeft:"auto"}}><Heat multiTf={d.multiTf}/></span></div>
          <div className="verdict">
            <div className="big"><div className="vlabel">NİHAİ SİNYAL <DemoMark row={d}/></div>
              <div className={"vsig "+c}>{shortSig(d.finalSignal||"NEUTRAL").replace("S-","GÜÇLÜ ")}</div>
              <div className="vsub"><Vu conf={d.confidence} dir={dir}/> güven %{d.confidence||0}</div></div>
            <div className="side">
              <div className="cell"><div className="k">GÜVEN</div><div className="v">{d.confidence||0}<small>%</small></div></div>
              <div className="cell"><div className="k">PUAN</div><div className="v" style={{color:"var(--warn)"}}>{kfmt(score)}</div></div>
            </div></div>
          <div className="reason"><b>Tez.</b> <DemoMark row={d}/> {d.reason||"—"}</div>
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

/* ── series stats (real transforms over the backend's raw series; no invention) */
const sLast=(a)=>Array.isArray(a)&&a.length?a[a.length-1]:null;
const sFirst=(a)=>Array.isArray(a)&&a.length?a[0]:null;
const sMean=(a)=>Array.isArray(a)&&a.length?a.reduce((x,y)=>x+(+y||0),0)/a.length:null;
const sDelta=(a)=>{const f=sFirst(a),l=sLast(a);return (f==null||l==null||!f)?null:(l-f)/Math.abs(f)*100;};
const fPct=(v,d=4)=>(v==null||isNaN(v))?"—":((v>=0?"+":"")+(v*100).toFixed(d)+"%");
const MS_STATE=(v)=> v==null?null : v>0.55?"STRONG_BUY" : v>0.05?"BUY" : v<-0.55?"STRONG_SELL" : v<-0.05?"SELL" : "NEUTRAL";
const MS_LABEL={oi_price_divergence:"OI·FİYAT UYUMSUZLUĞU",oi_breakout_confirm:"OI KIRILIM TEYİDİ",
  funding_fade:"FONLAMA TERSİNE",taker_aggression:"TAKER SALDIRGANLIĞI",
  ls_crowding_fade:"KALABALIK TERSİNE",smart_dumb_spread:"AKILLI–EMU FARKI"};

/* One labelled stat row. A missing field renders an explicit "—", never a guess. */
function Stat({k,v,sub}){return <div className="stat"><span className="k">{k}</span>
  <span className="v">{v==null||v===""?"—":v}{sub?<small className="sub"> {sub}</small>:null}</span></div>;}

/* Microstructure overlay signal → one row: state pill · name · strength · reason. */
function MsigRow({s}){
  const state=MS_STATE(s.score); const c=mcls(state);
  return <div className="msig">
    <span className={"mini "+c}>{state?shortSig(state).replace("S-","G-"):"—"}</span>
    <span className="msname">{MS_LABEL[s.name]||String(s.name||"?").replace(/_/g," ").toUpperCase()}</span>
    <Vu conf={Math.round(Math.abs(s.score||0)*100)} dir={sgn(s.score||0)}/>
    <span className="msval">{s.score==null?"—":(s.score>0?"+":"")+Math.round(s.score*100)}</span>
    <span className="msreason">{s.reason||"—"}</span>
  </div>;}

/* 24s gainers/losers rail (GET /api/leaders → SGS_GAINERS / SGS_LOSERS). */
function LeadersRail({onSelect}){
  const col=(title,rows)=> <div className="lcol">
    <div className="lhead">{title}</div>
    {!(rows||[]).length ? <div className="stat"><span className="k">VERİ</span><span className="v">—</span></div>
      : rows.map(r=><button key={r.s} className="leader" onClick={()=>onSelect(r.s)} title={r.s}>
        <span className="lsym">{String(r.s||"").replace("USDT","")}</span>
        <span className="lpx">${fmt(r.price)}</span>
        <span className={"chg "+(r.ch>=0?"up":"dn")}>{r.ch>=0?"+":""}{num(r.ch,2)}%</span>
      </button>)}
  </div>;
  return <div className="panel mute"><div className="ph"><span className="tick">▸</span>24S LİDERLERİ
    <span className="rt">HACİM SIRALI · KLIK → SEMBOL</span></div>
    <div className="pb leaders">{col("YÜKSELENLER",window.SGS_GAINERS)}{col("DÜŞENLER",window.SGS_LOSERS)}</div></div>;
}

/* ── OI · L/S — positioning & futures microstructure for the selected symbol ──
   Data: GET /api/symbol → microstructure{score,label,active,signals[{name,score,
   reason,weight}]}, series{oi,funding,taker,glob,pos,acc}, divergence. */
function Flow({sym,onSelect}){
  const d=(window.SGS_DATA_MAP||{})[sym];
  if(!d || !d.multiTf || !d.multiTf.length)
    return <div className="state"><div className="spin"/><div>{String(sym||"").replace("USDT","")} verisi çekiliyor…</div></div>;
  const ms=d.microstructure||{signals:[]}; const ser=d.series||{};
  const oi=ser.oi,fu=ser.funding,tk=ser.taker,gl=ser.glob,ps=ser.pos,ac=ser.acc;
  return <>
    <div className="vhead"><span className="kicker">AÇIK POZİSYON · MİKROYAPI</span>
      <h1>{d.s.replace("USDT","")} · OI / L-S</h1>
      <div className="meta">5M × 48 PENCERE<br/>BINANCE FUTURES DATA</div></div>
    <div className="grid2">
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>MİKROYAPI SİNYALLERİ
          <span className="rt">{ms.active||0} AKTİF · {ms.label||"—"}</span></div>
          <div className="pb" style={{padding:0}}>
            <Gauge name="BÜTÜN DEMET" score={ms.score} cap={`${ms.active||0}/6 sinyal veriye sahip`}/>
            {(ms.signals||[]).map(s=><MsigRow key={s.name} s={s}/>)}
            {!(ms.signals||[]).length &&
              <div className="stat"><span className="k">SİNYAL</span><span className="v">— mikroyapı sinyali yok (seri verisi yetersiz)</span></div>}
          </div></div>
        <LeadersRail onSelect={onSelect}/>
      </div>
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>AÇIK POZİSYON & FONLAMA</div>
          <div className="pb" style={{padding:0}}>
            <Stat k="AÇIK POZİSYON (OI)" v={sLast(oi)!=null?window.sgsFmtBig(sLast(oi)):null} sub="kontrat"/>
            <Stat k="OI Δ PENCERE" v={sDelta(oi)==null?null:(sDelta(oi)>0?"+":"")+num(sDelta(oi),2)+"%"}/>
            <Stat k="SON FONLAMA" v={fPct(sLast(fu))}/>
            <Stat k="ORT. FONLAMA" v={fPct(sMean(fu))}/>
            <Stat k="TAKER AL/SAT" v={sLast(tk)==null?null:num(sLast(tk),3)} sub={sMean(tk)!=null?("ort "+num(sMean(tk),3)):null}/>
            <div className="gcap">OI = taker açık pozisyon (kontrat) · fonlama periyodik oran · taker 1.0 = dengeli</div>
          </div></div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>LONG / SHORT</div>
          <div className="pb" style={{padding:0}}>
            <Stat k="PERAKENDE HESAP L/S" v={sLast(gl)==null?null:num(sLast(gl),3)} sub={sMean(gl)!=null?("ort "+num(sMean(gl),3)):null}/>
            <Stat k="ÜST-TRADER POZİSYON L/S" v={sLast(ps)==null?null:num(sLast(ps),3)} sub={sMean(ps)!=null?("ort "+num(sMean(ps),3)):null}/>
            <Stat k="ÜST-TRADER HESAP L/S" v={sLast(ac)==null?null:num(sLast(ac),3)}/>
            <div className="gcap">≥1 long baskın · ≤1 short baskın · kalabalık ucu tersine okunur (fade)</div>
          </div></div>
        <div className="panel mute"><div className="ph"><span className="tick">▸</span>BALİNA UYUMSUZLUĞU</div>
          <div className="pb" style={{padding:0}}>
            <Gauge name="WF SKORU" score={d.divergence?.score} cap={`en iyi ${d.divergence?.tf||"—"} · kapsam ${d.divergence?.coverage||0}/3`}/>
          </div></div>
      </div>
    </div>
  </>;
}

/* ── SİNYAL — the scan's survivors as cards. Click → PANEL for that symbol. ──
   Data: GET /api/scan → survivors (full contract + netNss + whale divergence). */
function Signal({onPick}){
  const scan=window.SGS_SCAN||{survivors:[]};
  const rows=scan.survivors||[];
  return <>
    <div className="vhead"><span className="kicker">SİNYAL KARTLARI</span><h1>Kısa Liste</h1>
      <div className="meta">TARAMADAN SAĞ KALANLAR<br/>KART → PANELE GİDER</div></div>
    {rows.length===0
      ? <div className="state src-down">
          <div className="sd-title">HENÜZ TARAMA YOK · NO SCAN YET</div>
          <div className="sd-body">Bu görünüm /api/scan sonucunu gösterir. TARA görünümü ilk taramayı
            otomatik çalıştırır; burada yeniden çalıştırabilirsin.</div>
          <button className="cta" onClick={()=>window.DIVE?.scan?.(15,24).catch(()=>{})}>TARAMAYI ÇALIŞTIR · RUN SCAN</button>
        </div>
      : <div className="sigrail">{rows.map((w,i)=>{
          const d=w.d||w;
          const dir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
          const hit=(d.multiTf||[]).filter(m=>sgn(m.signal?.includes("BUY")?1:m.signal?.includes("SELL")?-1:0)===dir).length;
          const wr=d.whaleRegime;
          return <button key={d.s} className="sigcard" onClick={()=>onPick(d.s)}>
            <span className="sc-top"><span className="rk">{String(i+1).padStart(2,"0")}</span>
              <span className="sym">{d.s.replace("USDT","")}</span>
              <Pill sig={d.finalSignal||"NEUTRAL"}/><DemoMark row={d}/></span>
            <span className="sc-price"><span className="px">${fmt(d.price)}</span><DemoMark row={d}/>
              <span className={"chg "+(d.ch>=0?"up":"dn")}>{d.ch>=0?"▲":"▼"} {num(Math.abs(d.ch||0),2)}%</span>
              <span className="sc-score" title="netNss">{kfmt(w.score||d.netNss||d.quantBias||0)}</span></span>
            <span className="sc-conf"><Vu conf={d.confidence} dir={dir}/> %{d.confidence||0}</span>
            <Heat multiTf={d.multiTf}/>
            <span className="sc-meta">
              <span className={"tag "+(wr==="confirm"?"good":wr==="adverse"?"bad":"")}>BALİNA: {wr==="confirm"?"TEYİT":wr==="adverse"?"KARŞIT":"NÖTR"}</span>
              <span className="tag">RİSK: {d.risk||"—"}</span>
              <span className="tag">UYUM {hit}/12</span></span>
            <span className="sc-reason">{(w.div&&w.div.reason)||d.reason||"—"}</span>
          </button>;})}
      </div>}
  </>;
}

/* ── simple screens ──────────────────────────────────────────────────────── */
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
             <td className="rv" style={{textAlign:"left"}}>{l.msg||l.m||l.message||JSON.stringify(l)}</td></tr>)}</tbody></table>}
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

/* Two-way hash routing: view ↔ location.hash. Hash names are the public ones. */
const VIEW_HASH={scan:"scan",panel:"panel",flow:"flow",sig:"signal",logs:"log",settings:"settings"};
const HASH_VIEW=Object.fromEntries(Object.entries(VIEW_HASH).map(([v,h])=>[h,v]));
const viewFromHash=()=>{const h=(location.hash||"").replace(/^#\/?/,"").split("/")[0];return HASH_VIEW[h]||null;};

const STALE_MS=90000;  // data older than this = honest "stale" banner
function App(){
  const [view,setView]=useState(()=>viewFromHash()||"scan");
  const [sym,setSym]=useState(null);
  const [theme,setThemeState]=useState(()=>localStorage.getItem("dive_theme")||"phosphor");
  const [q,setQ]=useState("");
  const [clock,setClock]=useState("");
  const [bootErr,setBootErr]=useState(null);
  const [stale,setStale]=useState(null);   // {reason,hidden?} — live-feed honesty only
  const lastOk=useRef(0);
  const [,force]=useState(0);
  const demo=isDemo();
  const setTheme=(t)=>{setThemeState(t);localStorage.setItem("dive_theme",t);document.documentElement.setAttribute("data-theme",t);};
  const setRoute=(v)=>{setView(v);try{const h="#/"+(VIEW_HASH[v]||v);if(location.hash!==h)location.hash=h;}catch(e){}};

  useEffect(()=>{document.documentElement.setAttribute("data-theme",theme);},[]);
  useEffect(()=>{window.__diveOnData=()=>force(v=>v+1);return()=>{window.__diveOnData=null;};},[]);
  useEffect(()=>{const id=setInterval(()=>setClock(new Date().toTimeString().slice(0,8)),1000);
    setClock(new Date().toTimeString().slice(0,8));return()=>clearInterval(id);},[]);
  useEffect(()=>{const onHash=()=>{const v=viewFromHash();if(v)setView(cur=>cur===v?cur:v);};
    window.addEventListener("hashchange",onHash);return()=>window.removeEventListener("hashchange",onHash);},[]);

  // boot: universe → first symbol. If the fetch fails we surface an explicit
  // unavailable state — we do NOT fabricate a market to fill the screen.
  const boot=useCallback(async()=>{
    setBootErr(null);
    const r=await bootUniverse(window.DIVE);
    if(r.ok){ setSym(s=>s||r.symbol||"BTCUSDT"); lastOk.current=Date.now(); setStale(null); }
    else{ setBootErr(r.error||"unknown error"); }
    force(v=>v+1);
  },[]);
  useEffect(()=>{ boot(); },[boot]);

  useEffect(()=>{ if(sym&&SYMBOL_VIEWS.has(view)) window.DIVE?.symbol?.(sym).catch(()=>{}); },[sym,view]);

  // One poll loop per view. Every outcome is honest: success stamps freshness and
  // clears the stale banner; any throw names the failing endpoint in the banner.
  const poll=useCallback(async()=>{
    let fail=null;
    try{
      if(view==="scan") await window.DIVE.scan(15,24);
      else if(SYMBOL_VIEWS.has(view)&&sym) await window.DIVE.symbol(sym);
    }catch(e){ fail=(view==="scan"?"tarama":(sym||"sembol")+" verisi")+": "+((e&&e.message)||e); }
    if(view==="flow"){ try{ await window.DIVE.leaders?.(); }
      catch(e){ fail=fail||"liderler: "+((e&&e.message)||e); } }
    try{ await window.DIVE.logs?.(); }catch(e){ fail=fail||"günlük: "+((e&&e.message)||e); }
    if(fail) setStale(s=> s ? (s.reason===fail?s:{reason:fail,hidden:false}) : {reason:fail});
    else { lastOk.current=Date.now(); setStale(null); }
  },[view,sym]);
  useEffect(()=>{ let live=true; const run=async()=>{ if(live) await poll(); };
    run(); const id=setInterval(run,view==="scan"?20000:15000);
    return()=>{live=false;clearInterval(id);}; },[poll]);
  // age watchdog: even without a thrown error, silence > 90s is staleness.
  useEffect(()=>{ const id=setInterval(()=>{ if(!lastOk.current) return;
    if(Date.now()-lastOk.current>STALE_MS)
      setStale(s=> s||{reason:`${Math.round(STALE_MS/1000)} sn'dir başarılı veri çekimi yok · no successful fetch for ${STALE_MS/1000}s`});
  },5000); return()=>clearInterval(id); },[]);

  const retry=()=>poll();
  const dismissStale=()=>setStale(s=>s?{...s,hidden:true}:s);
  const submit=(e)=>{e.preventDefault();let s=q.trim().toUpperCase();if(!s)return;if(!s.endsWith("USDT"))s+="USDT";
    setSym(s); if(!SYMBOL_VIEWS.has(view)) setRoute("panel"); setQ("");};
  const pick=(s)=>{setSym(s);setRoute("panel");};

  // A failed boot with nothing to show renders the unavailable state, not a
  // fabricated market. Settings stays reachable so the app is not a dead end.
  const noData=!!bootErr && !demo && !(window.SGS_DATA||[]).length;

  let body;
  if(noData && view!=="settings") body=<DataSourceDown error={bootErr} onRetry={boot}/>;
  else if(view==="scan") body=<Scanner onPick={pick}/>;
  else if(view==="panel") body=<Panel sym={sym}/>;
  else if(view==="flow") body=<Flow sym={sym} onSelect={setSym}/>;
  else if(view==="sig") body=<Signal onPick={pick}/>;
  else if(view==="logs") body=<Logs/>;
  else body=<Settings theme={theme} setTheme={setTheme}/>;

  return <div className={"app"+(demo?" is-demo":"")+(stale&&!stale.hidden?" has-stale":"")}>
    <DemoBanner/>
    <StaleBanner stale={stale} onRetry={retry} onDismiss={dismissStale}/>
    <div className="strip">
      {demo
        ? <span className="live demo"><span className="dot"/>DEMO</span>
        : <span className="live"><span className="dot"/>CANLI</span>}<span className="seg">│</span>
      <span>BINANCE USDT-M · PERP</span><span className="seg">│</span>
      <span><b>57</b> İNDİKATÖR</span><span className="seg">│</span><span><b>12</b> TF</span>
      <span className="spacer"/>
      <span>{view==="scan"?"TARAMA":(sym||"—").replace("USDT","")}</span><span className="seg">│</span>
      <form onSubmit={submit}><Svg d={ICONS.search}/><input value={q} onChange={e=>setQ(e.target.value)} placeholder="Sembol ara (örn. SOL)…"/></form>
      <span className="clk">{clock}</span><span className="seg">│</span>
      <div className="themes">{THEMES.map(([id,c])=><button key={id} className={id===theme?"on":""} style={{background:c}}
        title={id} aria-label={`Tema: ${id}`} aria-pressed={id===theme} onClick={()=>setTheme(id)}/>)}</div>
      <button className="iconbtn" title="Yenile" aria-label="Yenile · Refresh" onClick={retry}><Svg d={ICONS.refresh}/></button>
    </div>
    <nav className="rail">
      <div className="mark"><Mark/></div>
      {NAV.map(n=><button key={n.id} className={"rail-btn"+(view===n.id?" on":"")}
        aria-current={view===n.id?"page":undefined} onClick={()=>setRoute(n.id)}><Svg d={ICONS[n.icon]}/>{n.label}</button>)}
      <div className="grow"/>
      <button className={"rail-btn"+(view==="settings"?" on":"")} aria-current={view==="settings"?"page":undefined}
        onClick={()=>setRoute("settings")}><Svg d={ICONS.gear}/>AYAR</button>
    </nav>
    <main className="stage" key={view+"|"+sym}>{body}</main>
  </div>;
}

/* Mount only in a real browser document; importing this bundle in a test runner
   must not try to mount. Named pieces are exported for the demo-mode tests. */
const _diveRoot = (typeof document!=="undefined" && document.getElementById) ? document.getElementById("root") : null;
if(_diveRoot) ReactDOM.createRoot(_diveRoot).render(<App/>);
globalThis.DIVE_APP = { App, DemoBanner, DemoMark, DataSourceDown, StaleBanner, Scanner, Flow, Signal, bootUniverse, isDemo };
