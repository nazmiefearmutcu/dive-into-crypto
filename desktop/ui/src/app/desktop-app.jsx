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
const pct2 = (v) => (v==null || isNaN(v)) ? null : ((v>=0?"+":"")+(v*100).toFixed(2)+"%");
const hitPct = (v) => (v==null || isNaN(v)) ? "—" : Math.round(v*100)+"%";
const betaRho = (b,c) => (b==null && c==null) ? null : `β ${num(b,2)} · ρ ${num(c,2)}`;

const ICONS = {
  scan:"M11 4a7 7 0 100 14 7 7 0 000-14zM16 16l5 5",
  panel:"M3 4h18v16H3zM3 9h18M9 9v11",
  oi:"M4 18V9M9 18V5M14 18v-6M19 18v-9",
  signal:"M3 12h4l3-8 4 16 3-8h4",
  leader:"M4 20V10M10 20V4M16 20v-8M22 20h-1M3 20h19",
  ev:"M9 11l2 2 4-4M5 4h14v16H5zM5 4h14M9 4V2h6v2M5 20h14",
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

/* ── market structure (GET /api/structure → SGS_STRUCTURE, fetched once) ──── */
function ensureStructure(){
  if(!window.SGS_STRUCTURE && window.DIVE && typeof window.DIVE.structure==="function")
    window.DIVE.structure().catch(()=>{});   // failure stays honest: lookups render "—"
}
function structureFind(sym){
  const st=window.SGS_STRUCTURE; if(!st||!sym) return null;
  for(const c of (st.clusters||[])){
    const m=(c.members||[]).find(x=>x.s===sym);
    if(m) return {...m,_cluster:c};
  }
  return (st.unclustered||[]).find(x=>x.s===sym)||null;
}
function clusterTitle(id){
  const st=window.SGS_STRUCTURE;
  if(st){const c=(st.clusters||[]).find(x=>x.cluster_id===id);
    if(c) return `Küme ${id} · ${c.label} · ${c.size} sembol`;}
  return st?`Küme #${id} · etiket yok`:`Küme #${id} · yapı verisi yükleniyor`;
}

/* ── async scan progress (App polls /api/scan/progress every 2 s) ───────────
   ETA is animated locally from the server's measured eta_seconds — a real
   completion-rate measurement, never a fabricated countdown. */
const PHASE_TR={starting:"BAŞLIYOR",universe:"EVREN DERLENİYOR",phase1_coarse:"AŞAMA 1 · KABA ELEME",
  phase2_detail:"AŞAMA 2 · DERİN ANALİZ",divergence:"BALİNA UYUMSUZLUĞU",structure:"PİYASA YAPISI",
  done:"TAMAMLANDI",error:"HATA"};

function ScanProgressPanel({prog,startErr,resultErr,onRetry,onCancel,onRetryResult}){
  const etaRef=useRef(null);
  const [,tick]=useState(0);
  useEffect(()=>{const id=setInterval(()=>tick(t=>t+1),500);return()=>clearInterval(id);},[]);
  useEffect(()=>{etaRef.current=(prog&&prog.eta_seconds!=null)?Date.now()+prog.eta_seconds*1000:null;},[prog]);
  const remain=etaRef.current!=null?Math.max(0,etaRef.current-Date.now()):null;
  const etaLab=remain==null?"—":`${Math.floor(remain/60000)}dk ${String(Math.floor((remain%60000)/1000)).padStart(2,"0")}sn`;
  const failed=!!startErr||(prog&&prog.status==="error");
  const finished=!!(prog&&prog.status==="done");
  const running=!!(prog&&prog.status==="running");
  const pct=prog&&prog.total?Math.min(100,Math.round((prog.completed||0)/prog.total*100)):(finished?100:0);
  const summary=finished&&prog.summary;
  return <div className="panel async" data-testid="scan-progress">
    <div className="ph"><span className="tick">▸</span>DERİN TARAMA · İZLEME
      <span className="rt">{prog&&prog.scan_id?String(prog.scan_id).slice(0,8):"ASYNC"}</span></div>
    <div className="prog">
      {failed
        ? <div className="perr">DERİN TARAMA HATASI · {startErr||(prog&&prog.error)||"bilinmeyen hata"}</div>
        : <>
            <div className="prow">
              <span className="phase">{PHASE_TR[(prog&&prog.phase)||"starting"]||String((prog&&prog.phase)||"—").toUpperCase()}</span>
              <span className="pnum">{prog?`${prog.completed||0}/${prog.total||0}`:"—"}</span>
              {running&&<span className="peta" title="kalan süre — taramanın son 15 sn'deki gerçek tamamlama hızından">KALAN {etaLab}</span>}
              {finished&&<span className="peta">{summary?`${summary.survivors} hayatta kalan · ${summary.eliminated} elendi · ${((summary.duration_ms||0)/1000).toFixed(1)} sn`:"bitti"}</span>}
            </div>
            <div className="pbar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
              <i style={{width:pct+"%"}}/></div>
          </>}
      {finished&&resultErr&&<div className="perr">SONUÇ YÜKLENEMEDİ · {resultErr}</div>}
      <div className="pbtns">
        {failed&&<button className="cta" onClick={onRetry}>TEKRAR DENE</button>}
        {finished&&resultErr&&<button className="cta" onClick={onRetryResult}>SONUCU YENİDEN YÜKLE</button>}
        <button className="cta" style={finished&&!resultErr?{background:"transparent",color:"var(--dim)",border:"1px solid var(--line2)"}:undefined}
          onClick={onCancel}>{finished&&!resultErr?"SENKRON TARAMAYA DÖN":"VAZGEÇ"}</button>
      </div>
      <div className="pcap">İlerleme 2 sn'de bir /api/scan/progress'tan okunur. Evren: {prog&&prog.meta?prog.meta.universe_limit:"—"} sembol ·
        bittiğinde sonuç /api/scan/result'tan yüklenip normal sonuç tablosunda görünür.</div>
    </div>
  </div>;
}

/* ── SCANNER ─────────────────────────────────────────────────────────────── */
const VERDICT_RANK={STRONG_BUY:2,BUY:1,NEUTRAL:0,SELL:-1,STRONG_SELL:-2};
const SORT_COLS=[
  {k:"sym",   label:"SEMBOL",  get:(d,w)=>String(d.s||"")},
  {k:"verdict",label:"KARAR",  get:(d)=>VERDICT_RANK[d.finalSignal]??0},
  {k:"ch",    label:"FİYAT·24S",get:(d)=>d.ch, r:true},
  {k:"conf",  label:"GÜVEN",   get:(d)=>d.confidence},
  {k:"score", label:"PUAN",    get:(d,w)=>w.score||d.netNss||d.quantBias||0, r:true},
  {k:"beta",  label:"β / ρ",   get:(d)=>d.beta, r:true},   // compact "β 1.24 · ρ 0.71"; sorts by beta
];
const UNIVERSE_CHIPS=[24,100,250,500];

function Scanner({onPick,job,prog,jobErr,onAsyncStart,onAsyncCancel,onRetryResult}){
  const scan = window.SGS_SCAN || {survivors:[]};
  const [uLimit,setULimit]=useState(250);
  const [sort,setSort]=useState(null);   // null = server rank order
  const busy=!!(job&&!job.done);
  const hasCluster=(scan.survivors||[]).some(w=>{const d=w.d||w;return d&&d.cluster_id!=null;});
  useEffect(()=>{if(hasCluster)ensureStructure();},[hasCluster]);  // cluster labels, fetched once + cached
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
    <div className="scanbar">
      <span className="label">EVREN</span>
      {UNIVERSE_CHIPS.map(n=><button key={n} className={"chip"+(uLimit===n?" on":"")} disabled={busy}
        aria-pressed={uLimit===n} onClick={()=>setULimit(n)} title={`${n} sembollük tam evren taraması (async)`}>{n}</button>)}
      <button className="cta" disabled={busy} onClick={()=>onAsyncStart&&onAsyncStart(uLimit)}
        title="Tam evreni arka planda tarar; ilerleme paneliyle izlenir">DERİN TARAMA (async)</button>
      <span className="sbcap">{busy
        ?"arka plan taraması çalışıyor — senkron otomatik tarama duraklatıldı"
        :"varsayılan: senkron otomatik tarama · 15 sembol / 24 evren"}</span>
    </div>
    {job&&<ScanProgressPanel prog={prog} startErr={jobErr} resultErr={job&&job.resultErr}
      onRetry={()=>onAsyncStart&&onAsyncStart(uLimit)} onCancel={onAsyncCancel} onRetryResult={onRetryResult}/>}
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
                <td className="r beta">{betaRho(d.beta,d.corr_btc)||"—"}
                  {d.cluster_id!=null&&<span className="tag cluster" title={clusterTitle(d.cluster_id)}>K{d.cluster_id}</span>}</td>
                <td className="r" style={{color:hit>=10?"var(--up)":"var(--warn)",fontFamily:"var(--font-d)",fontWeight:700,fontSize:10}}>{hit}/12</td>
                <td><Heat multiTf={d.multiTf}/></td>
              </tr>;})}</tbody></table>}
      </div></div>
  </>;}

/* ── PANEL candlestick chart — plain <canvas>, DPR-aware. Candles come straight
   from the symbol payload's `candles` field (primary TF, last 120 bars). EMA20 /
   EMA50 + Bollinger(20,2) are COMPUTED IN JS from those candles (data.js
   sgsEma / sgsBollinger) — report-only overlays, they never touch the verdict. */
const CH_H=320, CH_PAD={l:6,r:58,t:10,b:22}, CH_VOL_FRAC=0.16;

function CandleChart({candles,tf}){
  const wrapRef=useRef(null),cvsRef=useRef(null);
  const [box,setBox]=useState({w:0,h:CH_H});
  const [hover,setHover]=useState(null);
  const list=(candles||[]).filter(c=>c&&["o","h","l","c"].every(k=>isFinite(Number(c[k]))));
  useEffect(()=>{  // measure + keep crisp on resize
    const el=wrapRef.current;if(!el)return;
    const m=()=>setBox({w:el.clientWidth||0,h:CH_H});
    m();
    if(typeof ResizeObserver!=="undefined"){const ro=new ResizeObserver(m);ro.observe(el);return()=>ro.disconnect();}
    window.addEventListener("resize",m);return()=>window.removeEventListener("resize",m);
  },[]);
  const closes=list.map(c=>Number(c.c));
  const ema20=window.sgsEma?window.sgsEma(closes,20):closes.map(()=>null);
  const ema50=window.sgsEma?window.sgsEma(closes,50):closes.map(()=>null);
  const bb=window.sgsBollinger?window.sgsBollinger(closes,20,2):closes.map(()=>null);
  useEffect(()=>{  // redraw on every render (data, size or hover changed)
    const cvs=cvsRef.current;if(!cvs||!box.w||!list.length)return;
    const W=box.w,H=box.h;
    const dpr=Math.max(1,(typeof window!=="undefined"&&window.devicePixelRatio)||1);
    cvs.width=Math.round(W*dpr);cvs.height=Math.round(H*dpr);
    cvs.style.width=W+"px";cvs.style.height=H+"px";
    const ctx=cvs.getContext&&cvs.getContext("2d");if(!ctx)return;
    ctx.setTransform(dpr,0,0,dpr,0,0);
    const cssv=(n,fb)=>{try{const v=getComputedStyle(document.documentElement).getPropertyValue(n).trim();return v||fb;}catch(e){return fb;}};
    const C={up:cssv("--up","#4dffa6"),dn:cssv("--down","#ff5c6c"),line:cssv("--line","#182029"),
      dim:cssv("--faint","#3a454d"),acc:cssv("--accent","#39ff9e"),accInk:cssv("--accent-ink","#04140c"),
      info:cssv("--info","#57d4ff"),warn:cssv("--warn","#ffc23d")};
    const padL=CH_PAD.l,padR=CH_PAD.r,padT=CH_PAD.t;
    const volH=Math.round((H-padT-CH_PAD.b)*CH_VOL_FRAC);
    const plotW=W-padL-padR,plotH=H-padT-CH_PAD.b-volH-6;
    let lo=Infinity,hi=-Infinity;
    list.forEach((c,i)=>{lo=Math.min(lo,Number(c.l));hi=Math.max(hi,Number(c.h));
      const b=bb[i];if(b){lo=Math.min(lo,b.lo);hi=Math.max(hi,b.up);}});
    if(!isFinite(lo)||!isFinite(hi))return;
    if(hi===lo){hi*=1.001;lo*=0.999;}
    const padp=(hi-lo)*0.05;lo-=padp;hi+=padp;
    const slot=plotW/list.length,cw=Math.max(1,Math.min(13,slot*0.62));
    const X=(i)=>padL+(i+0.5)*slot,Y=(p)=>padT+(hi-p)/(hi-lo)*plotH;
    ctx.clearRect(0,0,W,H);
    ctx.font="9px ui-monospace,monospace";ctx.lineWidth=1;
    for(let g=0;g<=4;g++){const p=hi-(hi-lo)*g/4,y=Math.round(Y(p))+0.5;
      ctx.strokeStyle=C.line;ctx.beginPath();ctx.moveTo(padL,y);ctx.lineTo(W-padR,y);ctx.stroke();
      ctx.fillStyle=C.dim;ctx.fillText(fmt(p),W-padR+6,y+3);}
    [0,Math.floor((list.length-1)/3),Math.floor(2*(list.length-1)/3),list.length-1].forEach(i=>{
      const t=new Date(Number(list[i].t||0)/1e6);
      const lab=`${String(t.getHours()).padStart(2,"0")}:${String(t.getMinutes()).padStart(2,"0")}`;
      ctx.fillStyle=C.dim;ctx.fillText(lab,Math.min(Math.max(X(i)-13,padL),Math.max(padL,W-padR-26)),H-8);});
    // Bollinger band fill (only where the 20-bar window is full — honest gap)
    ctx.beginPath();let st=false;
    for(let i=0;i<list.length;i++){if(!bb[i])continue;const x=X(i),y=Y(bb[i].up);st?ctx.lineTo(x,y):(ctx.moveTo(x,y),st=true);}
    for(let i=list.length-1;i>=0;i--){if(!bb[i])continue;ctx.lineTo(X(i),Y(bb[i].lo));}
    if(st){ctx.closePath();ctx.globalAlpha=0.07;ctx.fillStyle=C.acc;ctx.fill();
      ctx.globalAlpha=0.25;ctx.strokeStyle=C.acc;ctx.stroke();ctx.globalAlpha=1;}
    // volume
    const vmax=list.reduce((m,c)=>Math.max(m,Number(c.v)||0),0)||1;
    const vy0=padT+plotH+6;
    list.forEach((c,i)=>{const v=Number(c.v)||0;if(!v)return;
      const h=Math.max(1,v/vmax*volH);
      ctx.globalAlpha=0.45;ctx.fillStyle=Number(c.c)>=Number(c.o)?C.up:C.dn;
      ctx.fillRect(X(i)-cw/2,vy0+volH-h,cw,h);});
    ctx.globalAlpha=1;
    // candles
    list.forEach((c,i)=>{const x=X(i),up=Number(c.c)>=Number(c.o),col=up?C.up:C.dn;
      ctx.strokeStyle=col;ctx.fillStyle=col;
      ctx.beginPath();ctx.moveTo(x,Y(Number(c.h)));ctx.lineTo(x,Y(Number(c.l)));ctx.stroke();
      const yO=Y(Number(c.o)),yC=Y(Number(c.c));
      ctx.fillRect(x-cw/2,Math.min(yO,yC),cw,Math.max(1,Math.abs(yC-yO)));});
    // overlay lines
    const line=(arr,col)=>{ctx.strokeStyle=col;ctx.lineWidth=1.4;ctx.beginPath();let s2=false;
      arr.forEach((v,i)=>{if(v==null)return;const x=X(i),y=Y(v);s2?ctx.lineTo(x,y):(ctx.moveTo(x,y),s2=true);});
      if(s2)ctx.stroke();ctx.lineWidth=1;};
    line(ema20,C.info);line(ema50,C.warn);
    // last price line + tag
    const last=closes[closes.length-1],yL=Y(last);
    ctx.setLineDash([4,3]);ctx.strokeStyle=C.acc;
    ctx.beginPath();ctx.moveTo(padL,yL);ctx.lineTo(W-padR,yL);ctx.stroke();ctx.setLineDash([]);
    ctx.fillStyle=C.acc;ctx.fillRect(W-padR+2,yL-8,padR-4,16);
    ctx.fillStyle=C.accInk;ctx.font="bold 9px ui-monospace,monospace";ctx.fillText(fmt(last),W-padR+6,yL+3);
    // crosshair
    if(hover!=null&&list[hover]){const c=list[hover],x=X(hover);
      ctx.setLineDash([3,3]);ctx.strokeStyle=C.dim;
      ctx.beginPath();ctx.moveTo(x,padT);ctx.lineTo(x,vy0+volH);ctx.stroke();
      ctx.beginPath();ctx.moveTo(padL,Y(Number(c.c)));ctx.lineTo(W-padR,Y(Number(c.c)));ctx.stroke();
      ctx.setLineDash([]);}
  });
  if(!list.length)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>GRAFİK · {tf||"1H"} MUM</div>
      <div className="pb"><div className="stat"><span className="k">MUM</span>
        <span className="v">— sembol verisinde mum yok · no candles in payload</span></div></div></div>;
  const onMove=(e)=>{const cvs=cvsRef.current;if(!cvs||!list.length)return;
    const r=cvs.getBoundingClientRect();
    const slot=(box.w-CH_PAD.l-CH_PAD.r)/list.length;
    const i=Math.floor(((e.clientX-r.left)-CH_PAD.l)/slot);
    setHover(Math.max(0,Math.min(list.length-1,i)));};
  const hc=hover!=null?list[hover]:null;
  const hUp=hc?Number(hc.c)>=Number(hc.o):false;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>GRAFİK · {tf||"1H"} MUM
      <span className="rt">{list.length} BAR{list.length>=20?" · EMA/BB 20. BARDAN İTİBAREN":""}</span></div>
    <div className="ch-legend">
      <span><i style={{background:"var(--info)"}}/>EMA20</span>
      <span><i style={{background:"var(--warn)"}}/>EMA50</span>
      <span><i style={{background:"var(--accent)",opacity:.5}}/>BOLLINGER(20,2)</span>
      <span style={{marginLeft:"auto",color:"var(--dim)"}}>SON {fmt(closes[closes.length-1])}</span>
    </div>
    <div className="chartbox" ref={wrapRef}>
      <canvas ref={cvsRef} role="img" aria-label={`${tf||"1H"} mum grafiği · ${list.length} bar`}
        onMouseMove={onMove} onMouseLeave={()=>setHover(null)}/>
      {hc?<div className="ch-read" data-testid="chart-ohlc">
        <span className="t">{new Date(Number(hc.t||0)/1e6).toLocaleString()}</span>
        <span>O <b>{fmt(Number(hc.o))}</b></span><span>H <b>{fmt(Number(hc.h))}</b></span>
        <span>L <b>{fmt(Number(hc.l))}</b></span>
        <span className={hUp?"up":"dn"}>C <b>{fmt(Number(hc.c))}</b></span>
        <span>V <b>{window.sgsFmtBig?window.sgsFmtBig(Number(hc.v)||0):"—"}</b></span>
      </div>:null}
    </div>
  </div>;
}

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
    <CandleChart candles={d.candles} tf="1H"/>
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

/* CVD card — rolling cumulative volume delta from the symbol payload's `cvd`
   field (public aggTrades). {unavailable} renders an honest "—", never a zero. */
function CvdCard({cvd}){
  if(!cvd||cvd.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>CVD · KÜMÜLATİF HACİM DELTASI</div>
      <div className="pb"><Stat k="CVD" v="—"
        sub={cvd?`kullanılamıyor · ${cvd.unavailable}`:"sembol verisinde cvd alanı yok"}/></div></div>;
  const total=(cvd.buy_vol||0)+(cvd.sell_vol||0);
  const bp=total>0?(cvd.buy_vol||0)/total*100:50;
  const series=cvd.delta_series||[];
  const diffs=series.slice(1).map((v,i)=>v-(series[i]||0));
  const maxD=diffs.reduce((m,v)=>Math.max(m,Math.abs(v)),0)||1;
  const big=window.sgsFmtBig||String;
  const col=cvd.cvd>0?"var(--up)":cvd.cvd<0?"var(--down)":"var(--neutral)";
  return <div className="panel cvd"><div className="ph"><span className="tick">▸</span>CVD · KÜMÜLATİF HACİM DELTASI
    <span className="rt">{cvd.window_trades!=null?cvd.window_trades+" İŞLEM · ":""}
      {cvd.window_seconds!=null?Math.round(cvd.window_seconds/60)+" DK PENCERE":"PENCERE —"}</span></div>
    <div className="pb" style={{padding:0}}>
      <div className="cvd-big" style={{color:col}}>
        {cvd.cvd>0?"+":cvd.cvd<0?"-":""}{big(Math.abs(cvd.cvd||0))}<small> CVD</small></div>
      <div className="split">
        <div className="splitbar" role="img" aria-label={`alım %${bp.toFixed(1)} · satım %${(100-bp).toFixed(1)}`}>
          <span className="b" style={{width:bp+"%"}}/><span className="s" style={{width:(100-bp)+"%"}}/></div>
        <div className="splitlab">
          <span style={{color:"var(--up)"}}>AL {big(cvd.buy_vol||0)} · %{bp.toFixed(1)}</span>
          <span style={{color:"var(--down)"}}>%{(100-bp).toFixed(1)} SAT {big(cvd.sell_vol||0)}</span></div>
      </div>
      {diffs.length
        ? <div className="cvdh" title="örnek başına delta (delta_series farkı); ≤60 örnek">
            {diffs.map((v,i)=><i key={i} className={v>=0?"u":"d"} style={{height:Math.max(8,Math.abs(v)/maxD*100)+"%"}}/>)}</div>
        : <div className="stat"><span className="k">DELTA SERİSİ</span><span className="v">—</span></div>}
      <div className="gcap">CVD = Σ(alıcı-başlatan − satıcı-başlatan hacim) · son ≤1000 public aggTrade ·
        {" "}{cvd.window_seconds!=null?Math.round(cvd.window_seconds/60):"—"} dk pencere · {series.length} örnek</div>
    </div></div>;
}

/* ── OI · L/S — positioning & futures microstructure for the selected symbol ──
   Data: GET /api/symbol → microstructure{score,label,active,signals[{name,score,
   reason,weight}]}, series{oi,funding,taker,glob,pos,acc}, divergence, cvd. */
function Flow({sym,onSelect}){
  useEffect(()=>{ensureStructure();},[]);   // BTC beta/corr lookup (fetched once, cached)
  const d=(window.SGS_DATA_MAP||{})[sym];
  if(!d || !d.multiTf || !d.multiTf.length)
    return <div className="state"><div className="spin"/><div>{String(sym||"").replace("USDT","")} verisi çekiliyor…</div></div>;
  const ms=d.microstructure||{signals:[]}; const ser=d.series||{};
  const sm=structureFind(d.s);
  const hasBr=sm&&(sm.beta!=null||sm.corr_btc!=null);
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
        <CvdCard cvd={d.cvd}/>
        <LeadersRail onSelect={onSelect}/>
      </div>
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>AÇIK POZİSYON & FONLAMA</div>
          <div className="pb" style={{padding:0}}>
            <Stat k="BTC BETA / KORR" v={hasBr?betaRho(sm.beta,sm.corr_btc):null}
              sub={sm?(sm._cluster?`küme ${sm._cluster.cluster_id} · ${sm._cluster.label}`:"kümelenmemiş"):"yapı verisi yükleniyor / yok"}/>
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

/* ── KANIT — the engine grades itself (GET /api/evidence + POST /grade) ──────
   Report-only doctrine: this dashboard observes hit-rates and forward returns;
   it NEVER refits weights or thresholds. Missing stats render "—". */
const EV_HORIZONS=["1h","4h","24h"];

function EvCard({label,cls,dir,stats}){
  const s=stats||{};
  return <div className="evcard">
    <div className="evh"><span className={"pill "+cls}><span className="g"/>{label}</span>
      <span className="evn">{s.n||0} karar</span></div>
    <Vu conf={Math.round((s.hit_rate||0)*100)} dir={dir}/>
    <div className="evrow"><span>İSABET</span><b>{hitPct(s.hit_rate)}</b></div>
    <div className="evrow"><span>ORT. İLERİ GETİRİ</span>
      <b className={sgn(s.avg_forward)>0?"up":sgn(s.avg_forward)<0?"dn":""}>{pct2(s.avg_forward)||"—"}</b></div>
    <div className="evrow"><span>MEDYAN İLERİ</span>
      <b className={sgn(s.median_forward)>0?"up":sgn(s.median_forward)<0?"dn":""}>{pct2(s.median_forward)||"—"}</b></div>
  </div>;
}

function IndicatorAssoc({byIndicator}){
  const rows=Object.entries(byIndicator||{})
    .map(([name,v])=>{
      const a=v&&v.agree,d=v&&v.disagree;
      const dev=Math.max(a&&a.hit_rate!=null?Math.abs(a.hit_rate-0.5):0,
                         d&&d.hit_rate!=null?Math.abs(d.hit_rate-0.5):0);
      return{name,n:(v&&v.n)||0,a,d,rank:dev*((v&&v.n)||0)};
    })
    .filter(r=>r.a&&r.a.hit_rate!=null&&r.d&&r.d.hit_rate!=null&&r.n>=4)
    .sort((x,y)=>y.rank-x.rank).slice(0,15);
  if(!rows.length)
    return <div className="stat"><span className="k">İLİŞKİ</span>
      <span className="v">— yeterli notalanmış karar yok (n≥4, uyum+karşıt grubu dolu)</span></div>;
  return <table className="rank evtab"><thead><tr>
    <th scope="col">İNDİKATÖR</th><th scope="col" className="r">N</th>
    <th scope="col" className="r">UYUMLU İSABET</th><th scope="col" className="r">KARŞIT İSABET</th></tr></thead>
    <tbody>{rows.map(r=><tr key={r.name}>
      <td className="l">{r.name}</td><td className="r">{r.n}</td>
      <td className="r">{hitPct(r.a.hit_rate)} <small style={{color:"var(--faint)"}}>(n={r.a.n})</small></td>
      <td className="r">{hitPct(r.d.hit_rate)} <small style={{color:"var(--faint)"}}>(n={r.d.n})</small></td></tr>)}</tbody></table>;
}

function Evidence({horizon,setHorizon}){
  const ev=window.SGS_EVIDENCE;
  const [gradingH,setGradingH]=useState(null);   // per-horizon busy flag
  const [msg,setMsg]=useState(null);             // {ok, text} grade result summary
  const doGrade=useCallback(async()=>{
    if(gradingH)return;
    setGradingH(horizon);setMsg(null);
    try{
      const out=await window.DIVE.gradeEvidence(horizon);
      const f=(out&&out.failed)||[];
      const ftxt=f.length?` · BAŞARISIZ ${f.length} (${f.slice(0,3).map(x=>x.symbol).join(", ")}${f.length>3?"…":""})`:"";
      setMsg({ok:true,text:`${String((out&&out.horizon)||horizon).toUpperCase()} · ${out.graded} karar notalandı · ${out.symbols_graded} sembol · kalan ${out.remaining}${ftxt}`});
    }catch(e){setMsg({ok:false,text:"NOTALAMA BAŞARISIZ · "+((e&&e.message)||e)});}
    finally{setGradingH(null);}
  },[gradingH,horizon]);
  if(!ev)
    return <div className="state"><div className="spin"/><div>Kanıt arşivi okunuyor…</div></div>;
  const rem=Math.max((ev.gradable_count||0)-(ev.graded_count||0),0);
  const chips=EV_HORIZONS.map(h=><button key={h} className={"chip"+(h===horizon?" on":"")}
    aria-pressed={h===horizon} disabled={!!gradingH} onClick={()=>setHorizon(h)}
    title={`${h} ufku için öz-derecelendirme`}>{h.toUpperCase()}</button>);
  return <>
    <div className="vhead"><span className="kicker">MOTOR ÖZ-DERECELENDİRME</span><h1>Kanıt Panosu</h1>
      <div className="meta">RAPOR-ONLY · AĞIRLIKLAR OTOMATİK DEĞİŞMEZ<br/>{ev.engine_version||"—"} · {ev.generated_at||"—"}</div></div>
    {ev.archived_count===0
      ? <div className="state src-down">
          <div className="sd-title">HENÜZ ARŞİV YOK · NO ARCHIVE YET</div>
          <div className="sd-body">Henüz arşiv yok — taramalar çalıştıkça kararlar arşivlenir.<br/>
            Her TARA taraması kararlarını arşive yazar; ufuk (1h/4h/24h) olgunlaşınca
            "ŞİMDİ NOTALA" gerçek 1h kline'larla geriye dönük notalama yapar.
            Notalama istek başına 40 semboldür; başarısız semboller listelenir, asla doldurulmaz.</div>
          <div className="scanbar" style={{justifyContent:"center",border:0,background:"transparent"}}>
            {chips}</div>
        </div>
      : <>
    <div className="scanbar">
      <span className="label">UFUK</span>
      {chips}
      <button className="cta" disabled={!!gradingH} onClick={doGrade}>
        {gradingH?`NOTALANIYOR (${gradingH.toUpperCase()})…`:`ŞİMDİ NOTALA (${horizon.toUpperCase()})`}</button>
      {msg&&<span className={"sbmsg"+(msg.ok?"":" bad")}>{msg.text}</span>}
      <span className="sbcap">ARŞİV {ev.archived_count} · NOTALANMIŞ {ev.graded_count}/{ev.gradable_count} · KAPAMA {hitPct(ev.coverage)}</span>
    </div>
    {ev.stale&&<div className="panel mute"><div className="pb"><div className="reason" style={{padding:"4px 2px"}}>
      <b>Dürüstlük notu:</b> {rem} olgunlaşmış karar henüz notalanmadı (kapama {hitPct(ev.coverage)}).
      "ŞİMDİ NOTALA" istek başına 40 sembol geriye doldurur ve özet verir.</div></div></div>}
    <div className="evcards">
      <EvCard label="LONG" cls="b" dir={1} stats={ev.by_verdict&&ev.by_verdict.LONG}/>
      <EvCard label="SHORT" cls="s" dir={-1} stats={ev.by_verdict&&ev.by_verdict.SHORT}/>
      <EvCard label="NÖTR" cls="n" dir={0} stats={ev.by_verdict&&ev.by_verdict.NEUTRAL}/>
    </div>
    <div className="grid2">
      <div className="panel"><div className="ph"><span className="tick">▸</span>GÜVEN KOVASI
        <span className="rt">KARAR ANINDAKİ GÜVEN × SONUÇ</span></div>
        <div className="pb" style={{padding:0}}>
          {(ev.by_confidence||[]).length
            ? <table className="rank evtab"><thead><tr>
                <th scope="col">KOVA</th><th scope="col" className="r">N</th><th scope="col" className="r">İSABET</th>
                <th scope="col" className="r">ORT. İLERİ</th><th scope="col" className="r">MEDYAN</th></tr></thead>
              <tbody>{ev.by_confidence.map(b=><tr key={b.bucket}>
                <td className="l">{b.bucket}</td><td className="r">{b.n||0}</td>
                <td className="r">{hitPct(b.hit_rate)}</td>
                <td className="r">{pct2(b.avg_forward)||"—"}</td>
                <td className="r">{pct2(b.median_forward)||"—"}</td></tr>)}</tbody></table>
            : <div className="stat"><span className="k">KOVA</span><span className="v">— notalanmış karar yok</span></div>}
        </div></div>
      <div className="panel"><div className="ph"><span className="tick">▸</span>İNDİKATÖR İLİŞKİSİ
        <span className="rt">|İSABET−0,5|·N · İLK 15</span></div>
        <div className="pb" style={{padding:0}}>
          <IndicatorAssoc byIndicator={ev.by_indicator}/>
          <div className="gcap">RAPORLAMA — ağırlıklar otomatik değişmez · report-only: bu tablo yalnızca
            gözlemdir; motor ağırlıkları hiçbir koşulda bu sonuçlardan güncellenmez.</div>
        </div></div>
    </div>
      </>}
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
      <div className="meta">DEPTH TERMINAL · v0.3.0</div></div>
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
  {id:"evidence",label:"KANIT",icon:"ev"},{id:"logs",label:"LOG",icon:"logs"},
];
const THEMES=[["phosphor","#39ff9e"],["amber","#ffb02e"],["ice","#59c6ff"],["paper","#c2410c"]];
const SYMBOL_VIEWS=new Set(["panel","flow","sig"]);

/* Two-way hash routing: view ↔ location.hash. Hash names are the public ones. */
const VIEW_HASH={scan:"scan",panel:"panel",flow:"flow",sig:"signal",evidence:"evidence",logs:"log",settings:"settings"};
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
  const [evHorizon,setEvHorizon]=useState("4h");
  const [job,setJob]=useState(null);       // tracked async scan {scanId, done, resultErr?}
  const [jobProg,setJobProg]=useState(null);
  const [jobErr,setJobErr]=useState(null); // async start failure
  const holdRef=useRef(false);             // async owns the TARA table while true
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

  // ── async deep-scan orchestration (survives view switches; the sync path stays
  // the default until "DERİN TARAMA" is pressed) ────────────────────────────────
  const startAsync=useCallback(async(uLimit)=>{
    setJobErr(null);setJobProg(null);
    holdRef.current=true;
    try{
      const r=await window.DIVE.scanAsync({size:15,universeLimit:uLimit||250});
      setJob({scanId:r.scan_id,done:false});
    }catch(e){ holdRef.current=false; setJobErr((e&&e.message)||String(e)); }
  },[]);
  const clearJob=useCallback(()=>{  // back to the default sync auto-scan
    holdRef.current=false;setJob(null);setJobProg(null);
    window.DIVE.scan(15,24).catch(()=>{});
  },[]);
  const retryResult=useCallback(async()=>{
    if(!job||!job.scanId)return;
    setJob(j=>j?{...j,resultErr:null}:j);
    try{ await window.DIVE.scanResult(job.scanId); setJob(j=>j?{...j,resultErr:null}:j); }
    catch(e){ setJob(j=>j?{...j,resultErr:(e&&e.message)||String(e)}:j); }
  },[job&&job.scanId]);
  const jobId=job&&job.scanId,jobDone=job&&job.done;
  useEffect(()=>{  // 2 s progress poll; done → load the full result into SGS_SCAN
    if(!jobId||jobDone)return;
    let live=true;
    const tick=async()=>{
      try{
        const p=await window.DIVE.scanProgress(jobId);
        if(!live)return;
        setJobProg(p);
        if(p.status==="done"){
          try{ await window.DIVE.scanResult(jobId); }
          catch(e){ setJob(j=>j?{...j,resultErr:(e&&e.message)||String(e)}:j); }
          setJob(j=>j?{...j,done:true}:j);
        }else if(p.status==="error"){ setJob(j=>j?{...j,done:true}:j); }
      }catch(e){ /* transient poll failure: keep the last honest snapshot */ }
    };
    tick();
    const id=setInterval(tick,2000);
    return()=>{live=false;clearInterval(id);};
  },[jobId,jobDone]);

  // horizon switch → immediate evidence refetch (poll loop keeps it fresh)
  useEffect(()=>{ if(view==="evidence"&&evHorizon) window.DIVE.evidence(evHorizon).catch(()=>{}); },[view,evHorizon]);

  // One poll loop per view. Every outcome is honest: success stamps freshness and
  // clears the stale banner; any throw names the failing endpoint in the banner.
  const poll=useCallback(async()=>{
    let fail=null;
    try{
      if(view==="scan"){ if(!holdRef.current) await window.DIVE.scan(15,24); }
      else if(view==="evidence"){ if(evHorizon) await window.DIVE.evidence(evHorizon); }
      else if(SYMBOL_VIEWS.has(view)&&sym) await window.DIVE.symbol(sym);
    }catch(e){
      const what=view==="scan"?"tarama":view==="evidence"?"kanıt":(sym||"sembol")+" verisi";
      fail=what+": "+((e&&e.message)||e);
    }
    if(view==="flow"){ try{ await window.DIVE.leaders?.(); }
      catch(e){ fail=fail||"liderler: "+((e&&e.message)||e); } }
    try{ await window.DIVE.logs?.(); }catch(e){ fail=fail||"günlük: "+((e&&e.message)||e); }
    if(fail) setStale(s=> s ? (s.reason===fail?s:{reason:fail,hidden:false}) : {reason:fail});
    else { lastOk.current=Date.now(); setStale(null); }
  },[view,sym,evHorizon]);
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
  else if(view==="scan") body=<Scanner onPick={pick} job={job} prog={jobProg} jobErr={jobErr}
    onAsyncStart={startAsync} onAsyncCancel={clearJob} onRetryResult={retryResult}/>;
  else if(view==="panel") body=<Panel sym={sym}/>;
  else if(view==="flow") body=<Flow sym={sym} onSelect={setSym}/>;
  else if(view==="sig") body=<Signal onPick={pick}/>;
  else if(view==="evidence") body=<Evidence horizon={evHorizon} setHorizon={setEvHorizon}/>;
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
globalThis.DIVE_APP = { App, DemoBanner, DemoMark, DataSourceDown, StaleBanner, Scanner, Flow, Signal,
  Evidence, CandleChart, ScanProgressPanel, bootUniverse, isDemo };
