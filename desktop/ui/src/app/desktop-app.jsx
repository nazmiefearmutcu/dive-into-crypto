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

/* ── indicator → family (60 indicators grouped for the panel table) ──────── */
const FAMILY = {
  ema_cross:"TREND", sma_cross:"TREND", macd:"TREND", ichimoku:"TREND", psar:"TREND",
  adx_di:"TREND", supertrend:"TREND", vortex:"TREND", aroon_oscillator:"TREND",
  schaff_trend_cycle:"TREND", trix:"TREND", kst:"TREND", coppock_curve:"TREND",
  kalman_trend:"TREND", donchian_breakout:"TREND", keltner_breakout:"TREND", elder_ray:"TREND", dpo:"TREND",
  engulfing:"PRICE ACTION", liquidity_sweep:"PRICE ACTION", pivot_structure:"PRICE ACTION",
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
/* 24h change. A missing value (the ticker failed) is UNAVAILABLE: it renders "—" with no
   sign and no green/red class, never "+—%" or a sign inferred from null. */
const hasChg = (v) => v!=null && Number.isFinite(Number(v));
const chgCls = (v) => hasChg(v) ? (Number(v)>=0?"up":"dn") : "";
const chgTxt = (v) => hasChg(v) ? (Number(v)>=0?"+":"")+num(v,2)+"%" : "—";
const chgArrow = (v) => hasChg(v) ? (Number(v)>=0?"▲":"▼")+" "+num(Math.abs(v),2)+"%" : "—";
/* Annotation price: 6 significant digits keeps sub-cent prices (a fixed 1/6 grid
   zeroed them and shifted 0.1–5 by up to 1/12). */
const annPrice = (p) => Number(Number(p).toPrecision(6));
/* Bare ticker → Binance USDT-M symbol (the backend only resolves the suffixed form). */
const toUsdtSym = (t) => { const s=String(t||"").trim().toUpperCase(); return !s ? "" : (s.endsWith("USDT") ? s : s+"USDT"); };

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
  compare:"M3 3h10v10H3zM11 11h10v10H11z",
  map:"M3 3h18v18H3zM9 3v18M15 3v18M3 9h18M3 15h18",
  wallet:"M3 7h18v12H3zM3 7l3-4h12l3 4M15 13h4",
  structure:"M9 3h6v4H9zM3 17h6v4H3zM15 17h6v4h-6zM12 7v4M6 17v-3h12v3",
  cmd:"M4 6h16M4 12h10M4 18h7",
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

/* Scan rows carry a direction-only "action" token: LONG / SHORT / NONE. The legacy
   AL / SAT / BEKLE values are still mapped while the backend transition lands. */
const ACTION_LABEL_KEY={LONG:"act_long",SHORT:"act_short",NONE:"act_none",AL:"act_long",SAT:"act_short",BEKLE:"act_none"};
function actionLabel(tok){ const k=ACTION_LABEL_KEY[String(tok||"").toUpperCase()]; return k?L(k):"—"; }

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

/* ── failure wording ─────────────────────────────────────────────────────────
   A failed backend call is explained in words. The raw "path → status" text never
   reaches the screen: a numeric HTTP code may only appear in parentheses at the end
   of the generic sentence. Only HTTP 451 (or an explicit region_blocked code) is a
   region block; HTTP 403 is a separate, non-transient "forbidden" state. Both offer
   a retry. */
const statusFromText=(t)=>{ const m=/→\s*(\d{3})\b/.exec(String(t||"")); return m?Number(m[1]):null; };
function failureOf(err){
  if(err==null) return {status:null,code:null,message:""};
  if(typeof err==="string") return {status:statusFromText(err),code:null,message:err};
  const message=String(err.message||"");
  const st=(err.status!=null&&Number.isFinite(Number(err.status)))?Number(err.status):null;
  return {status:st||statusFromText(message),code:err.code||null,message};
}
function failureText(err){
  const {status,code}=failureOf(err);
  if(status===451||code==="region_blocked") return {kind:"region",text:L("err_region")};
  if(status===403||code==="forbidden") return {kind:"forbidden",text:L("err_forbidden")};
  return {kind:"generic",text:L("err_generic")+(status?` (HTTP ${status})`:"")};
}

function DataSourceDown({error,onRetry}){
  const f=failureText(error);
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
    {error && <div className="sd-err" data-kind={f.kind}>{f.text}</div>}
    <button className="cta" onClick={onRetry}>TEKRAR DENE · RETRY</button>
  </div>;
}

/* A view whose own fetch failed says so, with a retry, instead of spinning forever. */
function FetchFail({err,onRetry}){
  return <div className="state" role="alert" data-testid="view-fetch-error">
    <div>{err.text}</div>
    <button className="cta" onClick={onRetry}>{L("sp_retry")}</button>
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
    console.debug("[dive] universe boot failed:", e);   // raw detail for debugging only
    const f=failureOf(e);
    return { ok:false, symbol:null, error: f.message||String(e), status:f.status, code:f.code };
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

/* ── v0.3 shared honesty widgets ───────────────────────────────────────────── */
/* Wilson-interval hit-rate display. "57% [45–89] · n=214" when healthy,
   grayed "yetersiz örnek (n=9 < 20)" when gated, "—" below n=5. The math lives
   in data.js (sgsHitLabel / sgsGateState) — pure and unit-tested. */
function HitLabel({stats,title}){
  const gate=window.sgsGateState?window.sgsGateState(stats):"ok";
  const label=window.sgsHitLabel?window.sgsHitLabel(stats):"—";
  if(!stats||gate==="none") return <span className="hitlabel none" title={title||"örneklem yok — hiçbir oran gösterilmez"}>—</span>;
  if(gate==="gated") return <span className="hitlabel gated" title="örneklem küçük — bu oran gürültülü">{label}</span>;
  return <span className="hitlabel" title={title}>{label}</span>;
}
/* Stability pip-row: k pips, majority share lit. agree_frac==null → all dim. */
function Pips({agree,k=8,max=8}){
  const n=Math.max(1,Math.min(max,k||max));
  const on=agree==null?0:Math.round(agree*n);
  return <span className="pips" title={agree==null?"yönlü kayıt yok — uyum tanımsız":`uyum %${Math.round(agree*100)} · k=${k||"—"}`}>
    {Array.from({length:n},(_,i)=><i key={i} className={i<on?"on":""}/>)}{agree==null?<em>—</em>:null}</span>;
}
const isoDay=(iso)=>{ try{ return iso?String(iso).slice(0,10):"—"; }catch(e){ return "—"; } };
const isoShort=(iso)=>{ try{ return iso?String(iso).slice(0,16).replace("T"," "):"—"; }catch(e){ return "—"; } };
/* Lazy shared fetches (cached by their SGS_* globals; failures stay silent —
   the views render honest "—" states, never placeholders-with-numbers). */
function ensureEvidence(horizon){
  if(!window.SGS_EVIDENCE && window.DIVE && typeof window.DIVE.evidence==="function")
    window.DIVE.evidence(horizon||"4h").catch(()=>{});
}
function ensureStability(){
  if(!window.SGS_STABILITY && window.DIVE && typeof window.DIVE.stability==="function")
    window.DIVE.stability().catch(()=>{});
}
function stabilityFor(sym){
  const rows=window.SGS_STABILITY;
  if(!Array.isArray(rows)||!sym) return null;
  return rows.find(r=>r&&r.s===sym)||null;
}
const TIER_LABEL={WEAK:"ZAYIF",MODERATE:"ORTA",STRONG:"GÜÇLÜ"};
function TierBadge({tier}){
  if(!tier||tier==="NONE") return null;
  const cls= tier==="STRONG"?"bad": tier==="MODERATE"?"hot":"";
  return <span className={"tag "+cls} title={`balina uyumsuzluğu seviyesi: ${tier}`}>DİVERJANS {TIER_LABEL[tier]||tier}</span>;
}
function ClusterChip({row}){
  const a=row&&row.cluster_agreement;
  if(a==null) return null;
  return <span className="tag cl-agree" title="küme üyelerinin aynı yöndeki payı — sektör teyidi">S%{Math.round(a*100)}</span>;
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
    <div className="ph"><span className="tick">▸</span>{L("sp_title")}
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
        {failed&&<button className="cta" onClick={onRetry}>{L("sp_retry")}</button>}
        {finished&&resultErr&&<button className="cta" onClick={onRetryResult}>{L("sp_reload")}</button>}
        <button className="cta" style={finished&&!resultErr?{background:"transparent",color:"var(--dim)",border:"1px solid var(--line2)"}:undefined}
          onClick={onCancel}>{finished?(resultErr?L("sp_cancel"):L("sp_back")):(failed?L("sp_cancel"):L("sp_detach"))}</button>
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
/* Localized column labels — read L() at render time so a language switch
   re-renders without remount (SORT_COLS labels stay the TR fallback). */
const scanColLabel=(k)=>({sym:L("scan_col_sym"),verdict:L("scan_col_verdict"),
  ch:L("scan_col_ch"),conf:L("scan_col_conf"),score:L("scan_col_score")}[k]
  ||(SORT_COLS.find(c=>c.k===k)||{}).label||"");
const UNIVERSE_CHIPS=[24,100,250,500];
const PRESETS_KEY="dive_presets_v1";

/* Presets persist {universeLimit, sort} under dive_presets_v1. Every row is
   re-validated on load (strict, data.js sgsValidatePreset) — corrupt entries are
   dropped with an honest count, never applied half-secretly. */
function loadPresets(){
  try{
    const raw=JSON.parse(localStorage.getItem(PRESETS_KEY)||"[]");
    if(!Array.isArray(raw)) return {presets:[],dropped:0};
    let dropped=0;
    const presets=raw.map(p=>{ const v=window.sgsValidatePreset?window.sgsValidatePreset(p):p; if(!v)dropped++; return v; })
      .filter(Boolean);
    return {presets,dropped};
  }catch(e){ return {presets:[],dropped:0}; }
}
function savePresets(list){
  try{ localStorage.setItem(PRESETS_KEY,JSON.stringify(list)); return true; }catch(e){ return false; }
}

function Scanner({onPick,onCompare,job,prog,jobErr,onAsyncStart,onAsyncCancel,onRetryResult,held,fetchFail,onRetry}){
  const scan = window.SGS_SCAN || {survivors:[]};
  const [uLimit,setULimit]=useState(250);
  const [sort,setSort]=useState(null);   // null = server rank order
  const [sel,setSel]=useState(()=>new Set());
  const bootPresets=useRef(loadPresets());
  const [presets,setPresets]=useState(bootPresets.current.presets);
  const [presetMsg,setPresetMsg]=useState(bootPresets.current.dropped
    ? `${bootPresets.current.dropped} geçersiz ön ayar yüklenirken atlandı` : null);
  const [presetName,setPresetName]=useState("");
  const [renameIdx,setRenameIdx]=useState(null);
  const busy=!!(job&&!job.done);
  // SGS_SCAN.scanned is the fully-assembled SYMBOL count (data.js maps it from scannedCount),
  // the same unit as universeCount — not the 12-per-symbol timeframe-verdict total
  const scannedSyms=scan.scanned||0;
  const hasCluster=(scan.survivors||[]).some(w=>{const d=w.d||w;return d&&d.cluster_id!=null;});
  useEffect(()=>{if(hasCluster)ensureStructure();},[hasCluster]);  // cluster labels, fetched once + cached
  useEffect(()=>{ensureStability();},[]);                          // pips column (cached global; failures → "—")
  const toggleSort=(k)=>setSort(s=> s&&s.k===k ? {k,dir:-s.dir} : {k,dir:-1});
  const toggleSel=(s)=>setSel(prev=>{const n=new Set(prev); n.has(s)?n.delete(s):n.add(s); return n;});
  /* preset actions — validation is strict; invalid input becomes a message,
     never a half-applied scan */
  const presetSave=()=>{ const name=presetName.trim();
    if(!name){ setPresetMsg("ön ayar adı boş — kaydedilmedi"); return; }
    const v=window.sgsValidatePreset?window.sgsValidatePreset({name,universeLimit:uLimit,sort}):{name,universeLimit:uLimit,sort};
    if(!v){ setPresetMsg("geçersiz ön ayar — kaydedilmedi"); return; }
    /* rename: the renamed entry takes the new name and any OTHER entry already
       carrying it is dropped — same de-duplication as a fresh save */
    const next= renameIdx!=null ? presets.flatMap((p,i)=> i===renameIdx ? [v] : (p.name===v.name ? [] : [p]))
                                : [...presets.filter(p=>p.name!==v.name),v];
    if(savePresets(next)){ setPresets(next); setPresetMsg(null); setPresetName(""); setRenameIdx(null); }
    else setPresetMsg("ön ayar kaydedilemedi (depolama yazılamadı)");
  };
  const presetApply=(p)=>{ setULimit(p.universeLimit); setSort(p.sort||null);
    onAsyncStart&&onAsyncStart(p.universeLimit);   // existing async scan path
  };
  const presetDelete=(i)=>{ const next=presets.filter((_,j)=>j!==i);
    if(savePresets(next)){ setPresets(next); setRenameIdx(null); } };
  const presetExport=()=>{ try{
      const blob=new Blob([JSON.stringify(presets,null,2)],{type:"application/json"});
      const a=document.createElement("a"); a.href=URL.createObjectURL(blob);
      a.download="dive_presets_v1.json"; a.click(); URL.revokeObjectURL(a.href);
    }catch(e){ setPresetMsg("dışa aktarma başarısız"); } };
  const presetImport=async(file)=>{ if(!file)return;
    try{
      const raw=JSON.parse(await file.text());
      const list=Array.isArray(raw)?raw:[raw];
      let ok=0,bad=0; const next=[...presets];
      list.forEach(p=>{ const v=window.sgsValidatePreset?window.sgsValidatePreset(p):null;
        if(v){ ok++; const i=next.findIndex(x=>x.name===v.name); i>=0?next[i]=v:next.push(v); }
        else bad++; });
      /* the import only counts once storage actually accepted the list */
      if(!savePresets(next)){ setPresetMsg("ön ayar kaydedilemedi (depolama yazılamadı)"); return; }
      setPresets(next);
      setPresetMsg(bad?`${ok} ön ayar alındı · ${bad} geçersiz JSON reddedildi`:`${ok} ön ayar alındı`);
    }catch(e){ setPresetMsg(`geçersiz JSON — içe aktarma reddedildi (${(e&&e.message)||e})`); }
  };
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
    <div className="vhead"><span className="kicker">{L("scan_kicker")}</span><h1>{L("scan_h1")}</h1>
      <div className="meta">{L("scan_meta1")}<br/>{L("scan_meta2")}</div></div>
    <div className="scanbar">
      <span className="label">{L("scan_universe")}</span>
      {UNIVERSE_CHIPS.map(n=><button key={n} className={"chip"+(uLimit===n?" on":"")} disabled={busy}
        aria-pressed={uLimit===n} onClick={()=>setULimit(n)} title={`${n} sembollük tam evren taraması (async)`}>{n}</button>)}
      <button className="cta" disabled={busy} onClick={()=>onAsyncStart&&onAsyncStart(uLimit)}
        title="Tam evreni arka planda tarar; ilerleme paneliyle izlenir">{L("scan_deep")}</button>
      <button className={"cta compare"+(sel.size>=2?"":" off")} disabled={sel.size<2}
        onClick={()=>{onCompare&&onCompare([...sel]); setSel(new Set());}}
        title="Seçili sembolleri yan yana senkron karşılaştır">{L("scan_compare")} ({sel.size})</button>
      <span className="sbcap">{held?(busy?L("scan_cap_busy"):L("scan_cap_held")):L("scan_cap_idle")}</span>
    </div>
    <div className="scanbar presets">
      <span className="label">{L("scan_presets")}</span>
      {presets.length===0 && <span className="sbcap" style={{marginLeft:0}}>kayıtlı ön ayar yok — mevcut evren+sıralamayı Kaydet ile sakla</span>}
      {presets.map((p,i)=><span key={p.name} className="preset">
        <button className="chip" aria-pressed="false" disabled={busy} onClick={()=>presetApply(p)}
          title={`${p.universeLimit} evren · sıralama: ${p.sort?p.sort.k+" "+(p.sort.dir>0?"▲":"▼"):"sunucu sırası"}`}>{p.name}</button>
        <button className="pxbtn" aria-label={`${p.name} adını değiştir`} onClick={()=>{setRenameIdx(i);setPresetName(p.name);}}>✎</button>
        <button className="pxbtn" aria-label={`${p.name} ön ayarını sil`} onClick={()=>presetDelete(i)}>✕</button>
      </span>)}
      <input className="pname" value={presetName} maxLength={24} placeholder={renameIdx!=null?"yeni ad…":"ön ayar adı…"}
        onChange={e=>setPresetName(e.target.value)}
        onKeyDown={e=>{if(e.key==="Enter"){e.preventDefault();presetSave();}}}/>
      <button className="chip" onClick={presetSave}>{renameIdx!=null?L("scan_rename"):L("scan_save")}</button>
      <button className="chip" onClick={presetExport} title="ön ayarları JSON olarak indir">{L("scan_export")}</button>
      <label className="chip file" title="JSON ön ayar dosyası içe aktar (geçersizler reddedilir)">{L("scan_import")}
        <input type="file" accept="application/json,.json" style={{display:"none"}}
          onChange={e=>{presetImport(e.target.files&&e.target.files[0]); e.target.value="";}}/></label>
      {presetMsg&&<span className="sbmsg" role="status">{presetMsg}</span>}
    </div>
    {job&&!job.dismissed&&<ScanProgressPanel prog={prog} startErr={jobErr} resultErr={job&&job.resultErr}
      onRetry={()=>onAsyncStart&&onAsyncStart(uLimit)} onCancel={onAsyncCancel} onRetryResult={onRetryResult}/>}
    <div className="panel"><div className="ph"><span className="tick">▸</span>{L("scan_results")}
      <span className="rt">{rows.length} {L("scan_survivors")} · {scannedSyms}/{scan.universeCount||0} {L("scan_scanned")}</span></div>
      <div className="pb" style={{padding:0}}>
        {rows.length===0
          ? (scan.universeCount>0
              /* a finished scan with no survivors is an outcome, not a running scan */
              ? <div className="state" style={{height:120}}><div>{L("scan_none")}</div></div>
              : fetchFail
                ? <FetchFail err={fetchFail} onRetry={onRetry}/>
                : <div className="state" style={{height:220}}><div className="spin"/><div>{L("scan_running")}</div></div>)
          : <table className="rank"><thead><tr>
              <th scope="col" aria-label="KIYAS seçimi"></th>
              <th scope="col">#</th>
              {SORT_COLS.map(c=>{ const on=sort&&sort.k===c.k;
                return <th key={c.k} scope="col" className={c.r?"r":""}
                  aria-sort={on?(sort.dir>0?"ascending":"descending"):undefined}>
                  <button className="thbtn" onClick={()=>toggleSort(c.k)}
                    aria-label={`${scanColLabel(c.k)} sütununa göre sırala`}>{scanColLabel(c.k)}{on?(sort.dir>0?" ▲":" ▼"):""}</button></th>;})}
              <th scope="col" className="r">{L("scan_agree")}</th><th scope="col" className="r">{L("scan_stability")}</th><th scope="col">{L("scan_12tf")}</th>
            </tr></thead><tbody>{rows.map(({w,i})=>{
              const d=w.d||w; const dir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
              const hit=(d.multiTf||[]).filter(m=>sgn(m.signal?.includes("BUY")?1:m.signal?.includes("SELL")?-1:0)===dir).length;
              const score=w.score||d.netNss||d.quantBias||0;
              const stab=stabilityFor(d.s);
              return <tr key={d.s} tabIndex={0} onClick={()=>onPick(d.s)}
                onKeyDown={(e)=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();onPick(d.s);}}}
                aria-label={`${d.s} panelini aç`}>
                <td className="ck" onClick={(e)=>e.stopPropagation()}>
                  <input type="checkbox" aria-label={`${d.s} KIYAS'a ekle`} checked={sel.has(d.s)}
                    onChange={()=>toggleSel(d.s)}/></td>
                <td className="rk">{String(i+1).padStart(2,"0")}</td>
                <td><div className="sym">{d.s.replace("USDT","")}<small>{d.name||d.s}</small></div></td>
                <td title={d.action?actionLabel(d.action):undefined}><Pill sig={d.finalSignal||"NEUTRAL"}/><DemoMark row={d}/></td>
                <td className="r"><span className="px">${fmt(d.price)}</span><DemoMark row={d}/>
                  <span className={"chg "+chgCls(d.ch)} style={{display:"block",fontSize:10}}>{chgTxt(d.ch)}</span></td>
                <td><Vu conf={d.confidence} dir={dir}/></td>
                <td className="r score">{kfmt(score)}</td>
                <td className="r beta">{betaRho(d.beta,d.corr_btc)||"—"}
                  {d.cluster_id!=null&&<span className="tag cluster" title={clusterTitle(d.cluster_id)}>K{d.cluster_id}</span>}
                  <ClusterChip row={d}/></td>
                <td className="r" style={{color:hit>=10?"var(--up)":"var(--warn)",fontFamily:"var(--font-d)",fontWeight:700,fontSize:10}}>{hit}/12</td>
                <td className="r"><span className="stabcell">
                  {stab?<Pips agree={stab.agree_frac} k={stab.k}/>:<span style={{color:"var(--faint)"}}>—</span>}
                  <TierBadge tier={d.divergence_tier}/></span></td>
                <td><Heat multiTf={d.multiTf}/></td>
              </tr>;})}</tbody></table>}
      </div></div>
  </>;}

/* ── PANEL candlestick chart — plain <canvas>, DPR-aware. Candles come straight
   from the symbol payload's `candles` field (primary TF, last 120 bars). EMA20 /
   EMA50 + Bollinger(20,2) are COMPUTED IN JS from those candles (data.js
   sgsEma / sgsBollinger) — report-only overlays, they never touch the verdict.
   v0.3: optional ±1σ/±2σ 24h cone overlay (backend `cone`, projected forward
   from the last close) + user-drawn S/R annotation lines (localStorage-only,
   engine-blind). */
const CH_H=320, CH_PAD={l:6,r:58,t:10,b:22}, CH_VOL_FRAC=0.16, CONE_W_FRAC=0.2;

function CandleChart({candles,tf,cone,annotations,annMode,onChartClick}){
  const wrapRef=useRef(null),cvsRef=useRef(null),domainRef=useRef(null);
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
    (annotations||[]).forEach(a=>{const p=Number(a&&a.price);if(isFinite(p)&&p>0){lo=Math.min(lo,p);hi=Math.max(hi,p);}});
    // cone projection extends the y-domain honestly (it is a real expectation band)
    const last=closes[closes.length-1];
    const cone1=cone&&window.sgsConeEnvelope?window.sgsConeEnvelope(last,cone.sigma_1h,24,1):null;
    const cone2=cone&&window.sgsConeEnvelope?window.sgsConeEnvelope(last,cone.sigma_1h,24,2):null;
    [[cone1,"up"],[cone1,"down"],[cone2,"up"],[cone2,"down"]].forEach(([env,k])=>{
      if(env&&isFinite(env[k])){const v=last*(1+env[k]);lo=Math.min(lo,v);hi=Math.max(hi,v);}});
    if(!isFinite(lo)||!isFinite(hi))return;
    if(hi===lo){hi*=1.001;lo*=0.999;}
    const padp=(hi-lo)*0.05;lo-=padp;hi+=padp;
    domainRef.current={lo,hi};   // exact painted domain — click→price inverts it
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
    // cone overlay: ±1σ/±2σ 24h envelope projected forward from the LAST CLOSE —
    // a wedge over the right CONE_W_FRAC of the plot (the future has no bars)
    if(cone1&&cone2){
      const x0=X(list.length-1),x1=W-padR;
      const wedge=Math.max(24,(x1-x0)*CONE_W_FRAC*(list.length>1?list.length/(list.length-1):1));
      const band=(env,alpha,strokeCol)=>{
        if(!env)return;
        ctx.beginPath();ctx.moveTo(x0,Y(last));
        for(let s=1;s<=12;s++){const fr=s/12,v=last*(1+env.up*fr);ctx.lineTo(Math.min(x1,x0+wedge*fr),Y(v));}
        for(let s=12;s>=1;s--){const fr=s/12,v=last*(1+env.down*fr);ctx.lineTo(Math.min(x1,x0+wedge*fr),Y(v));}
        ctx.closePath();ctx.globalAlpha=alpha;ctx.fillStyle=C.acc;ctx.fill();
        ctx.globalAlpha=Math.min(1,alpha*3);ctx.strokeStyle=strokeCol;ctx.stroke();ctx.globalAlpha=1;};
      band(cone2,0.05,C.dim);   // ±2σ first (behind)
      band(cone1,0.09,C.acc);   // ±1σ on top
      ctx.fillStyle=C.dim;ctx.fillText("±1σ/±2σ 24s koni",Math.max(padL,x1-wedge-70),padT+9);
    }
    // user annotations — horizontal S/R lines (user ink; never engine data)
    (annotations||[]).forEach(a=>{const p=Number(a&&a.price);if(!isFinite(p)||p<=0)return;
      const y=Math.round(Y(p))+0.5;
      ctx.setLineDash([2,2]);ctx.strokeStyle=C.warn;ctx.beginPath();
      ctx.moveTo(padL,y);ctx.lineTo(W-padR,y);ctx.stroke();ctx.setLineDash([]);
      const lab=`Ç${fmt(p)}${a.note?` · ${String(a.note).slice(0,18)}`:""}`;
      ctx.fillStyle=C.warn;ctx.fillText(lab,padL+3,y-3);});
    // last price line + tag
    const yL=Y(last);
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
  const onClick=(e)=>{ if(!annMode||!onChartClick)return;
    const cvs=cvsRef.current;if(!cvs)return;
    const dom=domainRef.current;if(!dom)return;   // exact domain the painter used
    const r=cvs.getBoundingClientRect();
    const plotH=box.h-CH_PAD.t-CH_PAD.b-Math.round((box.h-CH_PAD.t-CH_PAD.b)*CH_VOL_FRAC)-6;
    const y=e.clientY-r.top;
    const price=dom.hi-((y-CH_PAD.t)/plotH)*(dom.hi-dom.lo);
    if(isFinite(price)&&price>0)onChartClick(price);};
  const hc=hover!=null?list[hover]:null;
  const hUp=hc?Number(hc.c)>=Number(hc.o):false;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>GRAFİK · {tf||"1H"} MUM
      <span className="rt">{list.length} BAR{list.length>=20?" · EMA/BB 20. BARDAN İTİBAREN":""}</span></div>
    <div className="ch-legend">
      <span><i style={{background:"var(--info)"}}/>EMA20</span>
      <span><i style={{background:"var(--warn)"}}/>EMA50</span>
      <span><i style={{background:"var(--accent)",opacity:.5}}/>BOLLINGER(20,2)</span>
      {cone&&<span><i style={{background:"var(--accent)",opacity:.25}}/>KONİ ±1σ/±2σ</span>}
      {(annotations||[]).length>0&&<span><i style={{background:"var(--warn)",opacity:.8}}/>ÇİZİMLER</span>}
      <span style={{marginLeft:"auto",color:"var(--dim)"}}>SON {fmt(closes[closes.length-1])}</span>
    </div>
    <div className={"chartbox"+(annMode?" ann-on":"")} ref={wrapRef}>
      <canvas ref={cvsRef} role="img" aria-label={`${tf||"1H"} mum grafiği · ${list.length} bar`}
        onMouseMove={onMove} onMouseLeave={()=>setHover(null)} onClick={onClick}
        style={annMode?{cursor:"copy"}:undefined}/>
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

/* ── PANEL depth cards (v0.3) — every block degrades to an honest "—" ──────── */
/* regime keys → catalog; an unknown regime is shown raw */
const FUNDING_REGIME_KEY={long_crowding:"fr_long",extreme_long_crowding:"fr_ext_long",
  short_crowding:"fr_short",extreme_short_crowding:"fr_ext_short",balanced:"fr_balanced"};
const fundingRegimeLabel=(reg)=>FUNDING_REGIME_KEY[reg]?L(FUNDING_REGIME_KEY[reg]):(reg==="unavailable"?"—":reg);

function FundingCard({fl}){
  const base=fl&&fl.seconds_to_funding!=null?fl.seconds_to_funding:null;
  const [t,setT]=useState(base);
  const stamp=useRef(Date.now());
  useEffect(()=>{ setT(base); stamp.current=Date.now(); },[base]);
  useEffect(()=>{ if(base==null)return;   // server snapshot → local honest countdown
    const id=setInterval(()=>setT(Math.max(0,base-(Date.now()-stamp.current)/1000)),1000);
    return()=>clearInterval(id); },[base]);
  if(!fl||fl.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("card_funding")}</div>
      <div className="pb"><Stat k={L("card_funding")} v="—"
        sub={fl?L("unav"):L("no_block","funding_lens")}/></div></div>;
  const cd=t==null?"—":`${String(Math.floor(t/3600)).padStart(2,"0")}:${String(Math.floor((t%3600)/60)).padStart(2,"0")}:${String(Math.floor(t%60)).padStart(2,"0")}`;
  const reg=fl.regime||"unavailable";
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("card_funding")}
    <span className="rt">{L("fund_rt")}</span></div>
    <div className="pb" style={{padding:0}}>
      <div className="cdrow"><span className="cd">{cd}</span>
        <span className="cdcap">{L("fund_next")}</span></div>
      <Stat k={L("fund_pred")} v={fPct(fl.predicted_funding)}/>
      <Stat k={L("fund_last")} v={fPct(fl.last_settled)}/>
      <Stat k={L("fund_apr")} v={fl.apr!=null?(fl.apr>=0?"+":"")+(fl.apr*100).toFixed(1)+"%":null}/>
      <div className="tagrow" style={{paddingTop:9}}>
        <span className={"tag "+(reg.includes("extreme")?"hot":reg==="unavailable"?"":reg!=="balanced"?"good":"")}>
          {L("fund_regime")} {fundingRegimeLabel(reg)}</span></div>
    </div></div>;
}

function BasisCard({basis}){
  if(!basis||basis.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("card_basis")}</div>
      <div className="pb"><Stat k={L("card_basis")} v="—"
        sub={basis?L("unav"):L("no_block","basis")}/></div></div>;
  const cur=basis.curve||{};
  const vals=[["PERP",cur.perp],["CQ",cur.cq],["NQ",cur.nq]]
    .map(([k,v])=>[k,v==null?null:Math.max(-2000,Math.min(2000,v))]);
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("card_basis")}
    <span className="rt">{L("basis_rt")}</span></div>
    <div className="pb" style={{padding:0}}>
      <div className="cdrow"><span className="cd" style={{fontSize:20}}>{basis.basis_bps!=null?(basis.basis_bps>0?"+":"")+num(basis.basis_bps,1):"—"}</span>
        <span className="cdcap">{L("basis_bps")}</span></div>
      <Stat k={L("basis_z")} v={basis.zscore!=null?num(basis.zscore,2):null}/>
      <Stat k={L("basis_ann")} v={basis.ann_funding!=null?(basis.ann_funding>0?"+":"")+num(basis.ann_funding,1)+" bps":null}/>
      <div className="bcurve" role="img" aria-label={L("basis_aria")}>
        {vals.map(([k,v])=><div key={k} className="bcol">
          <span className="bv">{v==null?"—":(v>0?"+":"")+Math.round(v)}</span>
          <span className="bbar"><i style={v==null?{}:{height:Math.min(50,Math.abs(v)/2000*50)+"%",
            background:v>0?"var(--up)":v<0?"var(--down)":"var(--neutral)"}}/></span>
          <span className="bk">{k}</span></div>)}
      </div>
      <div className="tagrow" style={{paddingTop:9}}>
        <span className="tag">{String(basis.label||"—").toUpperCase()}</span>
        {basis.partial&&<span className="tag hot" title={L("basis_partial_t")}>{L("basis_partial")}</span>}
      </div>
    </div></div>;
}

function CascadeCard({cascade}){
  if(!cascade)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("card_cascade")}</div>
      <div className="pb"><Stat k={L("card_cascade")} v="—" sub={L("casc_none")}/></div></div>;
  if(cascade.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("card_cascade")}</div>
      <div className="pb"><Stat k={L("card_cascade")} v="—" sub={cascade.score!=null
        ?L("casc_below",cascade.score):L("unav")}/></div></div>;
  const sc=Math.max(0,Math.min(100,cascade.score||0));
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("card_cascade")}
    <span className="rt">{cascade.since_min!=null?L("min_ago",Math.round(cascade.since_min)):"—"}</span></div>
    <div className="pb" style={{padding:0}}>
      <Gauge name={L("casc_gauge")} score={sc-(100-sc)} cap={`${cascade.direction==="long_flush"?"LONG FLUSH":cascade.direction==="short_flush"?"SHORT FLUSH":String(cascade.direction||"—").toUpperCase()} · 0–100`}/>
      <div className="tagrow" style={{paddingTop:9}}>
        {cascade.proxy&&<span className="tag hot" title={L("casc_proxy_t")}>{L("casc_proxy")}</span>}
      </div>
    </div></div>;
}

function BookCard({book}){
  if(!book||book.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("book_title")}</div>
      <div className="pb"><Stat k={L("book_k")} v="—"
        sub={book?L("unav"):L("no_block","book")}/></div></div>;
  const bands=book.imbalance||{};
  const age=book.ts?Math.max(0,Math.round((Date.now()-Number(book.ts))/1000)):null;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("book_title")}
    <span className="rt">{age!=null?L("sec_ago",age):"TS —"}</span></div>
    <div className="pb" style={{padding:0}}>
      <div className="cdrow"><span className="cd" style={{fontSize:18}}>{window.sgsFmtBig?window.sgsFmtBig(book.notional_1pct||0):"—"}</span>
        <span className="cdcap">{L("book_notional")}</span></div>
      {Object.entries(bands).map(([band,v])=>
        <div key={band} className="imbrow">
          <span className="ib">±{band}</span>
          <span className="ibar">{v==null?<em>—</em>:<>
            <i className={v>0?"l":"l off"} style={{width:v>0?Math.abs(v)*50+"%":"0%"}}/>
            <i className={v<0?"r":"r off"} style={{width:v<0?Math.abs(v)*50+"%":"0%"}}/></>}</span>
          <span className="iv">{v==null?"—":(v>0?"+":"")+ (v*100).toFixed(1)+"%"}</span>
        </div>)}
      <div className="gcap">{L("book_cap",fmt(book.mid))}</div>
    </div></div>;
}

function LsTermCard({ls}){
  if(!ls||!ls.periods||!Object.keys(ls.periods).length)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("ls_title")}</div>
      <div className="pb"><Stat k="TERM" v="—" sub={ls?L("ls_no_periods"):L("no_block","ls_term")}/></div></div>;
  const periods=Object.keys(ls.periods);
  const fams=["glob","acc","pos","taker"];
  const FAM_KEY={glob:"ls_glob",acc:"ls_acc",pos:"ls_pos",taker:"ls_taker"};
  const cell=(p,v)=> v==null ? <td key={p} className="lc dim">—</td>
    : <td key={p} className={"lc "+(v>1.05?"u":v<0.95?"d":"n")}>{num(v,2)}</td>;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("ls_title")}
    <span className="rt">{ls.cells_unavailable!=null?L("ls_cells",ls.cells_unavailable):L("ls_grid",periods.length)}</span></div>
    <div className="pb" style={{padding:0}}>
      <table className="lstable"><thead><tr><th/> {periods.map(p=><th key={p}>{p.toUpperCase()}</th>)}</tr></thead>
      <tbody>{fams.map(f=><tr key={f}><td className="lf">{L(FAM_KEY[f])}</td>
        {periods.map(p=>cell(p,(ls.periods[p]||{})[f]))}</tr>)}</tbody></table>
      <div className="gcap">{L("ls_cap")}</div>
    </div></div>;
}

function SpotPerpCard({sp}){
  const LEAD_KEY={spot:"spp_lead_spot",perp:"spp_lead_perp",mixed:"spp_lead_mixed"};
  if(!sp||sp.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>SPOT–PERP</div>
      <div className="pb"><Stat k="SPOT–PERP" v="—" sub={sp
        ?(sp.unavailable==="no_spot_market"?L("spp_perp_only"):L("unav"))
        :L("no_block","spot_perp")}/></div></div>;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>SPOT–PERP
    <span className="rt">{L("spp_window")}</span></div>
    <div className="pb" style={{padding:0}}>
      <Stat k={L("spp_premium")} v={sp.premium_pct!=null?(sp.premium_pct>0?"+":"")+num(sp.premium_pct,3)+"%":null}/>
      <Stat k={L("spp_ret")} v={sp.ret_spread_48h!=null?(sp.ret_spread_48h>0?"+":"")+(sp.ret_spread_48h*100).toFixed(2)+"%":null}/>
      <div className="tagrow" style={{paddingTop:9}}>
        <span className={"tag "+(sp.lead==="spot"?"good":sp.lead==="perp"?"hot":"")}>{LEAD_KEY[sp.lead]?L(LEAD_KEY[sp.lead]):L("spp_lead_none")}</span>
      </div>
      <div className="gcap">{L("spp_cap")}</div>
    </div></div>;
}

function PlanningStrip({planning}){
  if(!planning||planning.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("plan_title")}</div>
      <div className="pb"><div className="stat"><span className="k">{L("plan_k")}</span>
        <span className="v">— {planning?L("unav"):L("no_block","planning")}</span></div></div></div>;
  const env=planning.envelope||{};
  const cell=(k,v,cls="")=><div key={k} className={"plcell "+cls}>
    <span className="k">{k}</span><span className="v">{v==null?"—":"±"+num(v,2)+"%"}</span></div>;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("plan_title_geo")}
    <span className="rt">{L("plan_rt")}</span></div>
    <div className="plstrip">
      {cell(L("plan_sl"),planning.sl_distance_pct)}
      {cell(L("plan_tp1r"),planning.tp_1r_pct)}
      {cell(L("plan_tp2r"),planning.tp_2r_pct)}
      {cell(L("plan_tp3r"),planning.tp_3r_pct)}
      {cell(L("plan_env1"),env.h1)}
      {cell(L("plan_env4"),env.h4)}
      {cell(L("plan_env24"),env.h24)}
    </div>
    <div className="gcap">{L("plan_note")}</div>
  </div>;
}

/* KARŞIT KANIT — top-3 indicators standing AGAINST the verdict, ranked by
   |score×weight|, each with its historical disagree hit-rate from the evidence
   archive (gated rendering). Correlation captioned as non-causation. */
function OpposingEvidence({d,evHorizon}){
  useEffect(()=>{ ensureEvidence(evHorizon); },[evHorizon]);
  const dir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
  /* rank key = |weighted_score| (score×weight from the engine); |score| when only the raw
     score ships; 0 when neither is present (those rows keep table order). */
  const absOr0=(v)=>(v!=null&&Number.isFinite(Number(v)))?Math.abs(Number(v)):0;
  const cands=(d.indicators||[]).map(x=>{
    const xd=x.signal?.includes("BUY")?1:x.signal?.includes("SELL")?-1:0;
    const hasScore=x.weighted_score!=null||x.score!=null;
    const wsc=x.weighted_score!=null?absOr0(x.weighted_score):absOr0(x.score);
    return {name:x.name,signal:x.signal,value:x.value,wsc,xd,hasScore};
  }).filter(x=>dir!==0&&x.xd!==0&&x.xd!==dir);
  const rows=cands.slice().sort((a,b)=>b.wsc-a.wsc).slice(0,3);
  const byInd=(window.SGS_EVIDENCE&&window.SGS_EVIDENCE.by_indicator)||null;
  // the ranking header is shown only when the candidate rows carry score fields; without
  // them there is no |score| to rank by and the header must not claim a ranking
  const scored=cands.some(x=>x.hasScore);
  if(!rows.length)
    return <div className="oppose"><div className="op-h">{L("op_title")}</div>
      <div className="stat"><span className="k">{L("op_none_k")}</span><span className="v">{L("op_none_v")}</span></div></div>;
  return <div className="oppose">
    <div className="op-h">{L("op_title")} <span className="rt">{scored?L("op_scored"):L("op_unscored")}</span></div>
    {rows.map(x=>{
      const hist=byInd&&byInd[x.name]&&byInd[x.name].disagree;
      return <div key={x.name} className="op-row">
        <span className="op-name">{String(x.name).replace(/_/g," ")}</span>
        <span className={"mini "+mcls(x.signal)}>{shortSig(x.signal)}</span>
        <span className="op-val">{x.value!=null?num(x.value,x.value>100?1:3):"—"}</span>
        <span className="op-hist" title={L("op_hist_t")}>
          {hist?<HitLabel stats={hist}/>:<span className="hitlabel none" title={L("op_no_arch")}>{L("op_rel_dash")}</span>}</span>
      </div>;})}
    <div className="gcap">{L("op_caption")}
    </div>
  </div>;
}

/* TF-MATRİS — collapsed 12-TF strip of verdict chips. Clicking a column moves
   ONLY the chart to that TF (verdict stays on the 1h primary; labelled so). */
function TfMatrix({multiTf,activeTf,onPickTf}){
  const [open,setOpen]=useState(false);
  const arr=(multiTf&&multiTf.length)?multiTf:TFS.map(tf=>({tf,signal:"NEUTRAL",confidence:0}));
  return <div className="panel mute tfmatrix">
    <button className="ph asbtn" aria-expanded={open} onClick={()=>setOpen(o=>!o)}>
      <span className="tick">▸</span>TF-MATRİS · {arr.length} ZAMAN DİLİMİ
      <span className="rt">{open?"KAPAT":"AÇ"} · SÜTUN → GRAFİK O TF'E GEÇER</span></button>
    {open&&<div className="pb">
      <div className="tfgrid" role="list">
        {arr.map(m=>{
          const c=cls(m.signal); const act=String(m.tf)===String(activeTf);
          return <button key={m.tf} role="listitem" className={"tfcol"+(act?" on":"")}
            aria-pressed={act} aria-label={`${m.tf} grafiğini yükle`} title={`${m.tf} · ${m.confidence||0}%`}
            onClick={()=>onPickTf&&onPickTf(String(m.tf))}>
            <span className="tflab">{String(m.tf).toUpperCase()}</span>
            <span className={"tfchip "+c}>{m.signal?.includes("BUY")?"▲":m.signal?.includes("SELL")?"▼":"•"}</span>
            <span className="tfconf">{m.confidence||0}</span>
          </button>;})}
      </div>
      <div className="gcap">karar her zaman 1H birincil TF'den okunur · sütun seçimi yalnızca grafik mumlarını değiştirir</div>
    </div>}
  </div>;
}

/* Replay scrub — ghost verdict card at T. Archive gaps are honest: no records →
   an explicit empty state, and the scrub never interpolates between records.
   /api/evidence/decisions returns the NEWEST `limit` records of a symbol in ascending
   order, so a single request anchors the scrub on the newest record. A shorter result
   just gives a shorter scrub. The main chart is not touched (no per-step rebuild). */
const REPLAY_MAX=500;
function ReplayScrub({sym}){
  const [rows,setRows]=useState(null);
  const [err,setErr]=useState(null);
  const [idx,setIdx]=useState(0);
  useEffect(()=>{ let live=true;
    setRows(null); setErr(null);   // a symbol change must not show the previous symbol's records
    (async()=>{
      try{
        const page=await window.DIVE.decisions({symbol:sym,limit:REPLAY_MAX});
        if(!live)return;
        const arr=(Array.isArray(page)?page:[]).filter(r=>r&&Number.isFinite(Number(r.ts)))
          .sort((a,b)=>Number(a.ts)-Number(b.ts)).slice(-REPLAY_MAX);   // ascending: last = newest
        setRows(arr); setIdx(Math.max(0,arr.length-1));                // anchor on the newest record
      }catch(e){ if(live){ setErr((e&&e.message)||String(e)); setRows([]); } }
    })();
    return()=>{live=false;};
  },[sym]);
  const rec=rows&&rows[idx];
  if(err) return <div className="panel mute"><div className="pb"><div className="perr">{L("rs_err",err)}</div></div></div>;
  if(!rows) return <div className="panel mute"><div className="pb"><div className="state" style={{height:80}}><div className="spin"/></div></div></div>;
  if(!rows.length)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("rs_chip")}</div>
      <div className="pb"><div className="stat"><span className="k">{L("rs_k")}</span>
        <span className="v">{L("rs_empty",String(sym||"").replace("USDT",""))}</span></div></div></div>;
  return <div className="panel replay">
    <div className="ph"><span className="tick">▸</span>{L("rs_title")}
      <span className="rt">{L("rs_rt",rows.length,REPLAY_MAX)}</span></div>
    <div className="pb">
      <input type="range" className="scrub" min={0} max={rows.length-1} value={idx}
        aria-label={L("rs_aria")} onChange={e=>setIdx(Number(e.target.value))}/>
      {rec?<div className="ghost">
        <div className="g-when">{new Date(Number(rec.ts)).toLocaleString()} <span className="g-gap">T</span></div>
        <div className="g-verdict"><Pill sig={rec.verdict||"NEUTRAL"}/>
          <span className="g-conf">{L("rs_conf",rec.confidence!=null?rec.confidence:"—")}</span>
          <span className="g-price">${fmt(rec.price)}</span></div>
        <div className="g-meta">{[L("rs_score",rec.net_score!=null?num(rec.net_score,1):"—"),
          L("rs_tfagree",rec.tf_agreement!=null?Math.round(rec.tf_agreement*100)+"%":"—"),
          rec.divergence_tier?L("rs_div",rec.divergence_tier):null,
          L("rs_static")].filter(Boolean).join(" · ")}</div>
      </div>:null}
      <div className="gcap">{L("rs_gap_cap")}</div>
    </div></div>;
}

/* ── PANEL ───────────────────────────────────────────────────────────────── */
/* A symbol with no payload yet: its own fetch failure (with retry), the demo fixture's
   limit (a symbol outside the fixture never loads in demo mode), or a spinner while the
   request is still in flight. */
function SymbolLoading({sym,fetchFail,onRetry}){
  if(fetchFail) return <FetchFail err={fetchFail} onRetry={onRetry}/>;
  if(isDemo()) return <div className="state"><div>{L("demo_no_symbol")}</div></div>;
  return <div className="state"><div className="spin"/><div>{String(sym||"").replace("USDT","")} verisi çekiliyor…</div></div>;
}

function Panel({sym,evHorizon,fetchFail,onRetry}){
  const d = (window.SGS_DATA_MAP||{})[sym];
  const [chartTf,setChartTf]=useState("1h");
  const [tfObj,setTfObj]=useState(null);       // chart-only payload (commit:false)
  const [tfErr,setTfErr]=useState(null);
  const [annOn,setAnnOn]=useState(false);
  const [anns,setAnns]=useState(()=>(sym&&window.sgsAnnotationsLoad)?window.sgsAnnotationsLoad(sym):[]);
  const [rpOn,setRpOn]=useState(false);        // replay scrub toggle
  const [annMsg,setAnnMsg]=useState(null);     // storage failure on annotation save
  const reqSeq=useRef(0);
  useEffect(()=>{  // reset chart-local state when the symbol changes
    ++reqSeq.current;   // an in-flight TF response belongs to the previous symbol: drop it
    setChartTf("1h"); setTfObj(null); setTfErr(null);
    setAnns((sym&&window.sgsAnnotationsLoad)?window.sgsAnnotationsLoad(sym):[]);
    setAnnOn(false); setRpOn(false); setAnnMsg(null);
  },[sym]);
  const pickTf=async(tf)=>{
    setChartTf(tf); setTfErr(null);
    if(tf==="1h"){ ++reqSeq.current; setTfObj(null); return; }   // also drops any in-flight non-1h response
    const s=++reqSeq.current;
    try{ const obj=await window.DIVE.symbol(sym,{tf,commit:false});
      if(s===reqSeq.current) setTfObj(obj&&obj.candles&&obj.candles.length?obj:null); }
    catch(e){ if(s===reqSeq.current){ setTfObj(null); setTfErr(failureText(e).text); } }
  };
  /* annotations: local state follows the store only when the store accepted the write */
  const commitAnns=(next)=>{
    /* every caller (chart click, note edit, delete) lands here: the store's boolean decides
       whether the local list follows, and a refusal is shown as a localized message */
    const saved=!!(window.sgsAnnotationsSave&&window.sgsAnnotationsSave(sym,next));
    if(!saved){ setAnnMsg(L("ann_save_fail")); return; }
    setAnns(next); setAnnMsg(null);
  };
  const addAnn=(price)=>{ commitAnns([...anns,{price:annPrice(price),note:"",ts:Date.now()}]); };
  const updAnn=(i,note)=>{ commitAnns(anns.map((a,j)=>j===i?{...a,note}:a)); };
  const delAnn=(i)=>{ commitAnns(anns.filter((_,j)=>j!==i)); };
  if(!d || !d.multiTf || !d.multiTf.length) return <SymbolLoading sym={sym} fetchFail={fetchFail} onRetry={onRetry}/>;
  const c=cls(d.finalSignal); const dir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
  const score=d.netNss||d.quantBias||0;
  const wr=d.whaleRegime; const ms=d.microstructure||{signals:[]}; const rg=d.regime||{}; const mtf=d.mtfConfluence||{};
  const chartCandles= tfObj?tfObj.candles:d.candles;
  const chartTfLabel= tfObj?chartTf.toUpperCase():"1H";
  const wrtag = wr==="confirm" ? <span className="tag good">BALİNA: TEYİT</span>
    : wr==="adverse" ? <span className="tag bad">BALİNA: KARŞIT</span> : <span className="tag">BALİNA: NÖTR</span>;
  return <>
    <div className="vhead"><span className="kicker">{L("panel_kicker")}</span><h1>{d.s.replace("USDT","")} · {d.name||d.s}</h1>
      <div className="meta">{L("panel_meta1")}<br/>{L("panel_risk")} {d.risk||"—"}</div></div>
    <CandleChart candles={chartCandles} tf={chartTfLabel} cone={d.cone}
      annotations={anns} annMode={annOn} onChartClick={addAnn}/>
    {tfErr&&<div className="panel mute"><div className="pb"><div className="perr">TF YÜKLENEMEDİ · {tfErr}</div></div></div>}
    <div className="chartools">
      <button className={"chip"+(annOn?" on":"")} aria-pressed={annOn}
        onClick={()=>setAnnOn(o=>!o)} title="grafikte tıkla → yatay S/R çizgisi ekler">ÇİZİM</button>
      <button className={"chip"+(rpOn?" on":"")} aria-pressed={rpOn}
        onClick={()=>setRpOn(o=>!o)} title={L("rs_chip_t")}>{L("rs_chip")}</button>
      <span className="sbcap">{annOn?"grafikte tıkla → çizgi eklenir (cihazında saklanır · motorla ilgisiz)":""}</span>
    </div>
    {annMsg&&<div className="perr" role="status">{annMsg}</div>}
    {annOn&&<div className="panel mute"><div className="ph"><span className="tick">▸</span>ÇİZİMLER
        <span className="rt">{anns.length} ÇİZGİ · CİHAZDA SAKLANIR</span></div>
      <div className="pb" style={{padding:0}}>
        {anns.length===0&&<div className="stat"><span className="k">ÇİZGİ</span>
          <span className="v">— henüz çizgi yok · grafikte tıkla</span></div>}
        {anns.map((a,i)=><div key={i} className="annrow">
          <span className="apx">{fmt(a.price)}</span>
          <input value={a.note||""} maxLength={120} placeholder="not (opsiyonel)…"
            aria-label={`${fmt(a.price)} çizgisi notu`}
            onChange={e=>updAnn(i,e.target.value)}/>
          <button className="pxbtn" aria-label={`${fmt(a.price)} çizgisini sil`} onClick={()=>delAnn(i)}>✕</button>
        </div>)}
        <div className="gcap">kullanıcı çizimi · motorla ilgisiz · yalnız bu cihazın deposunda (localStorage)</div>
      </div></div>}
    {rpOn&&<ReplayScrub sym={sym}/>}
    <PlanningStrip planning={d.planning}/>
    <div className="grid2">
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>{L("panel_consensus")}</div>
          <div className="price-row"><span className="p">${fmt(d.price)}</span><DemoMark row={d}/>
            <span className={"c "+chgCls(d.ch)}>{chgArrow(d.ch)}</span>
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
          <OpposingEvidence d={d} evHorizon={evHorizon}/>
          <div className="tagrow">{wrtag}
            <TierBadge tier={d.divergence_tier}/>
            <span className={"tag "+(rg.regime==="TREND"?"good":rg.regime==="RANGE"?"hot":"")}>REJİM: {rg.regime||"—"}</span>
            {rg.adx!=null && <span className="tag">ADX {num(rg.adx)}</span>}
            {rg.chop!=null && <span className="tag">CHOP {num(rg.chop)}</span>}
            <span className={"tag "+(mtf.gate?"good":"")}>MTF {mtf.gate?"✓ KAPI":"✕"} {mtf.htf_agree!=null?Math.round(mtf.htf_agree*100)+"%":""}</span></div>
        </div>
        <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("panel_indtable")} · {(d.indicators||[]).length} SATIR</div>
          <div className="pb" style={{padding:0}}><IndicatorTable indicators={d.indicators}/></div></div>
      </div>
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>{L("panel_micro")}</div>
          <div className="pb" style={{padding:0}}>
            <Gauge name="BÜTÜN DEMET" score={ms.score} cap={`${ms.active||0} sinyal · ${ms.label||"—"}`}/>
            {(ms.signals||[]).map(s=><Gauge key={s.name} name={String(s.name).replace(/_/g," ").toUpperCase()} score={(s.score||0)*100}/>)}
          </div></div>
        <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("panel_regime")}</div>
          <div className="pb" style={{padding:0}}>
            <Gauge name="REJİM AĞIRLIKLI SKOR" score={(rg.adaptive_score||0)*50} cap={`${rg.regime||"—"} · adx ${num(rg.adx)} · chop ${num(rg.chop)}`}/>
            <Gauge name="MTF KONFLUENS" score={mtf.score} cap={`üst-TF uyum ${mtf.htf_agree!=null?Math.round(mtf.htf_agree*100):0}% · ${mtf.gate?"KAPI AÇIK":"kapı kapalı"}`}/>
            <Gauge name="BALİNA UYUMSUZLUK" score={d.divergence?.score} cap={`en iyi ${d.divergence?.tf||"—"} · kapsam ${d.divergence?.coverage||0}/3`}/>
            {d.cone&&<div className="gcap" style={{paddingTop:8}}>KONİ: 24s ±%{num((d.cone.env_24h&&d.cone.env_24h.up||0)*100,1)} /
              ±%{num(Math.abs((d.cone.env_24h&&d.cone.env_24h.down)||0)*100,1)} (1σ)
              {d.cone.percentile!=null?` · son 24s hamle arşivin %${num(d.cone.percentile,0)}'lik diliminde`:" · dilim —"}</div>}
          </div></div>
        <FundingCard fl={d.funding_lens}/>
        <BasisCard basis={d.basis}/>
      </div>
    </div>
    <div className="grid4">
      <CascadeCard cascade={d.cascade}/>
      <BookCard book={d.book}/>
      <LsTermCard ls={d.ls_term}/>
      <SpotPerpCard sp={d.spot_perp}/>
    </div>
    <TfMatrix multiTf={d.multiTf} activeTf={chartTf} onPickTf={pickTf}/>
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
        <span className={"chg "+chgCls(r.ch)}>{chgTxt(r.ch)}</span>
      </button>)}
  </div>;
  return <div className="panel mute"><div className="ph"><span className="tick">▸</span>24S LİDERLERİ
    <span className="rt">HACİM SIRALI · KLIK → SEMBOL</span></div>
    <div className="pb leaders">{col("YÜKSELENLER",window.SGS_GAINERS)}{col("DÜŞENLER",window.SGS_LOSERS)}</div></div>;
}

/* CVD card — rolling cumulative volume delta from the symbol payload's `cvd`
   field (public aggTrades). An unavailable window shows the localized "unavailable" label and
   never a zero; the backend's raw reason string is not rendered. */
function CvdCard({cvd}){
  if(!cvd||cvd.unavailable!=null)
    return <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("cvd_title")}</div>
      <div className="pb"><Stat k="CVD" v="—"
        sub={cvd?L("unav"):L("no_cvd")}/></div></div>;
  const total=(cvd.buy_vol||0)+(cvd.sell_vol||0);
  const bp=total>0?(cvd.buy_vol||0)/total*100:50;
  const series=cvd.delta_series||[];
  const diffs=series.slice(1).map((v,i)=>v-(series[i]||0));
  const maxD=diffs.reduce((m,v)=>Math.max(m,Math.abs(v)),0)||1;
  const big=window.sgsFmtBig||String;
  const col=cvd.cvd>0?"var(--up)":cvd.cvd<0?"var(--down)":"var(--neutral)";
  const cvdWin=cvd.window_seconds!=null?L("cvd_window",Math.round(cvd.window_seconds/60)):L("cvd_window_none");
  return <div className="panel cvd"><div className="ph"><span className="tick">▸</span>{L("cvd_title")}
    <span className="rt">{cvd.window_trades!=null?L("cvd_trades",cvd.window_trades)+" · ":""}{cvdWin}</span></div>
    <div className="pb" style={{padding:0}}>
      <div className="cvd-big" style={{color:col}}>
        {cvd.cvd>0?"+":cvd.cvd<0?"-":""}{big(Math.abs(cvd.cvd||0))}<small> CVD</small></div>
      <div className="split">
        <div className="splitbar" role="img" aria-label={L("cvd_aria",bp.toFixed(1),(100-bp).toFixed(1))}>
          <span className="b" style={{width:bp+"%"}}/><span className="s" style={{width:(100-bp)+"%"}}/></div>
        <div className="splitlab">
          <span style={{color:"var(--up)"}}>{L("cvd_buy",big(cvd.buy_vol||0),bp.toFixed(1))}</span>
          <span style={{color:"var(--down)"}}>{L("cvd_sell",(100-bp).toFixed(1),big(cvd.sell_vol||0))}</span></div>
      </div>
      {diffs.length
        ? <div className="cvdh" title={L("cvd_bars_t")}>
            {diffs.map((v,i)=><i key={i} className={v>=0?"u":"d"} style={{height:Math.max(8,Math.abs(v)/maxD*100)+"%"}}/>)}</div>
        : <div className="stat"><span className="k">{L("cvd_series")}</span><span className="v">—</span></div>}
      <div className="gcap">{L("cvd_cap",cvd.window_seconds!=null?Math.round(cvd.window_seconds/60):"—",series.length)}</div>
    </div></div>;
}

/* ── OI · L/S — positioning & futures microstructure for the selected symbol ──
   Data: GET /api/symbol → microstructure{score,label,active,signals[{name,score,
   reason,weight}]}, series{oi,funding,taker,glob,pos,acc}, divergence, cvd. */
function Flow({sym,onSelect,fetchFail,onRetry}){
  useEffect(()=>{ensureStructure();},[]);   // BTC beta/corr lookup (fetched once, cached)
  const d=(window.SGS_DATA_MAP||{})[sym];
  if(!d || !d.multiTf || !d.multiTf.length) return <SymbolLoading sym={sym} fetchFail={fetchFail} onRetry={onRetry}/>;
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
            <div className="gcap">{L("ls_cap")}</div>
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
    {rows.length===0&&scan.universeCount>0
      ? <div className="state"><div>{L("scan_none")}</div></div>
      : rows.length===0
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
              <span className={"chg "+chgCls(d.ch)}>{chgArrow(d.ch)}</span>
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
   it NEVER refits weights or thresholds. Missing stats render "—". v0.3 adds
   Wilson intervals with gates, the reliability diagram, ECE/Brier badges,
   baselines, windows, slices, provenance, claims, stability, IC + weight
   suggestions and the counterfactual replay grid. */
const EV_HORIZONS=["1h","4h","24h"];
const EV_WINDOWS=[["7d","7G"],["30d","30G"],["all","TÜMÜ"]];

function EvCard({label,cls,dir,stats}){
  const s=stats||{};
  const gate=window.sgsGateState?window.sgsGateState(s):"ok";
  return <div className="evcard">
    <div className="evh"><span className={"pill "+cls}><span className="g"/>{label}</span>
      <span className="evn">{L("ev_karar",s.n||0)}</span></div>
    <Vu conf={Math.round((s.hit_rate||0)*100)} dir={dir}/>
    <div className="evrow"><span>{L("ev_hit")}</span><b><HitLabel stats={s}/></b></div>
    <div className="evrow"><span>{L("ev_avg_fwd")}</span>
      <b className={sgn(s.avg_forward)>0?"up":sgn(s.avg_forward)<0?"dn":""}>{pct2(s.avg_forward)||"—"}</b></div>
    <div className="evrow"><span>{L("ev_med_fwd")}</span>
      <b className={sgn(s.median_forward)>0?"up":sgn(s.median_forward)<0?"dn":""}>{pct2(s.median_forward)||"—"}</b></div>
    {gate==="gated"&&<div className="gcap" style={{padding:0}}>{L("ev_gated")}</div>}
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
      <td className="r"><HitLabel stats={r.a}/></td>
      <td className="r"><HitLabel stats={r.d}/></td></tr>)}</tbody></table>;
}

/* Reliability diagram — inline SVG. Diagonal = perfect calibration; each bin
   plots mean confidence vs realized hit-rate with its Wilson whiskers. Empty
   bins are drawn as faint dashes at the baseline (honest absence, not 0.5). */
function ReliabilityDiagram({calibration}){
  const bins=(calibration&&calibration.bins)||[];
  const W=300,H=196,X0=38,X1=284,Y0=14,Y1=154;
  const px=(v)=>X0+(X1-X0)*v, py=(v)=>Y1-(Y1-Y0)*v;
  return <div className="reldiag">
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="güven güvenilirlik diyagramı">
      {[0,0.25,0.5,0.75,1].map(v=><g key={v}>
        <line x1={px(v)} y1={Y1} x2={px(v)} y2={Y0} className="rd-grid"/>
        <line x1={X0} y1={py(v)} x2={X1} y2={py(v)} className="rd-grid"/>
        <text x={px(v)} y={Y1+12} className="rd-lab" textAnchor="middle">{Math.round(v*100)}</text>
        <text x={X0-6} y={py(v)+3} className="rd-lab" textAnchor="end">{Math.round(v*100)}</text>
      </g>)}
      <line x1={X0} y1={Y1} x2={X1} y2={Y0} className="rd-diag"/>
      <text x={(X0+X1)/2+8} y={(Y0+Y1)/2-6} className="rd-diaglab" textAnchor="middle">mükemmel kalibrasyon</text>
      {bins.map(b=>{
        if(b.mean_conf==null||b.hit_rate==null){
          /* Empty bins: dash at the bucket's CENTER ("75-100" → 0.875), not its
             lower bound — honest absence plotted where the bin actually lives. */
          const m=/^(\d+)\s*-\s*(\d+)$/.exec(String(b.bucket||""));
          const c=(m&&Number(m[2])>Number(m[1]))?((Number(m[1])+Number(m[2]))/2)/100:(parseInt(b.bucket,10)/100||0);
          const cx=px(c);
          return <line key={b.bucket} x1={cx} y1={Y1} x2={cx} y2={Y1-4} className="rd-empty"/>;
        }
        const x=px(b.mean_conf/100),y=py(b.hit_rate);
        return <g key={b.bucket}>
          {b.wilson_hi!=null&&<line x1={x} y1={py(b.wilson_hi)} x2={x} y2={py(b.wilson_lo==null?b.wilson_hi:b.wilson_lo)} className="rd-whisk"/>}
          <circle cx={x} cy={y} r={3.2} className="rd-dot"><title>{`güven ${b.bucket} · isabet %${Math.round(b.hit_rate*100)} · n=${b.n}`}</title></circle>
          <text x={x} y={Y1+12} className="rd-lab sm" textAnchor="middle">{b.bucket}</text>
        </g>;})}
    </svg>
  </div>;
}

/* BASELINES — the observed mean forward against the coin-flip null model
   (analytic sd band + seeded permutation p-value). Ghost bars, honest dashes. */
function BaselinesPanel({baselines}){
  const b=baselines||{};
  if(b.coin_mean==null&&b.observed_mean==null)
    return <div className="stat"><span className="k">TABAN</span>
      <span className="v">— notalanmış ileri getiri yok (yazı-tura tabanı üretilemez)</span></div>;
  const sd=b.coin_sd||0;
  const span=Math.max(3*sd,Math.abs(b.observed_mean||0)*1.2,1e-6);
  const posOf=(v)=>50+(v/span)*50;   // percent position on a −span..+span track
  const mag=(v)=>(v==null||isNaN(v))?null:(Math.abs(v)*100).toFixed(2)+"%";   // sign-free magnitude (labels add their own −/+)
  /* Contract (evidence.py baselines): momentum_hit_rate + momentum_n + bnh_forward_mean. */
  const momentum=b.momentum_hit_rate!=null?b.momentum_hit_rate:null;
  const bh=b.bnh_forward_mean!=null?b.bnh_forward_mean:(b.buyhold_forward!=null?b.buyhold_forward:null);   // legacy-key fallback
  return <div className="baseln">
    <div className="btrack" role="img" aria-label={`gözlenen ortalama ${pct2(b.observed_mean)||"—"} · yazı-tura sıfır ±${pct2(sd)||"—"}`}>
      <span className="bz band" style={{left:posOf(-sd)+"%",width:(posOf(sd)-posOf(-sd))+"%"}}/>
      <span className="bz mid"/>
      {b.observed_mean!=null&&<span className="bz obs" style={{left:Math.max(0,Math.min(100,posOf(b.observed_mean)))+"%"}}
        title={`gözlenen ortalama ${pct2(b.observed_mean)}`}/>}
    </div>
    <div className="blab"><span>−{mag(span)}</span><span>yazı-tura ±1σ {pct2(sd)||"—"}</span><span>+{mag(span)}</span></div>
    <div className="evrow"><span>GÖZLENEN ORT. İLERİ</span>
      <b className={sgn(b.observed_mean)>0?"up":sgn(b.observed_mean)<0?"dn":""}>{pct2(b.observed_mean)||"—"}</b></div>
    <div className="evrow"><span>P-DEĞERİ (permütasyon)</span><b>{b.p_value!=null?b.p_value:"—"}</b></div>
    <div className="evrow"><span>MOMENTUM TABANI</span><b>{momentum!=null?hitPct(momentum):"—"}</b></div>
    <div className="evrow"><span>B&amp;H İLERİ</span><b className={sgn(bh)>0?"up":sgn(bh)<0?"dn":""}>{pct2(bh)||"—"}</b></div>
    <div className="gcap">{b.p_value!=null
      ?`p=${b.p_value} · ${b.permutations} permütasyon · seed ${b.seed} (deterministik) — yazı-tura yönü gözlenen ortalamayı geçemezse edge şüpheli`
      :"yazı-tura tabanı: rastgele yön işaretleri altında ortalama 0, sd=√(Σf²)/n"}</div>
  </div>;
}

/* KAYITLI İDDİALAR — pre-registered claims with equal-weight status pills. */
const CLAIM_STATUS_CLS={PENDING:"",CONFIRMED:"good",REFUTED:"bad"};
const CLAIM_STATUS_KEY={PENDING:"claim_pending",CONFIRMED:"claim_confirmed",REFUTED:"claim_refuted"};
function ClaimsPanel({horizon}){
  const claims=((window.SGS_CLAIMS&&window.SGS_CLAIMS.claims)||[]);
  useEffect(()=>{ if(window.DIVE&&typeof window.DIVE.claims==="function") window.DIVE.claims().catch(()=>{}); },[]);
  const [txt,setTxt]=useState("");
  const [metric,setMetric]=useState("hit_rate");
  const [verdict,setVerdict]=useState("ALL");   // "ALL" = no verdict filter (internal sentinel)
  const [ch,setCh]=useState(horizon);
  const [minN,setMinN]=useState("20");
  const [op,setOp]=useState(">=");
  const [thr,setThr]=useState("0.55");
  const [busy,setBusy]=useState(false);
  const [msg,setMsg]=useState(null);
  useEffect(()=>{ setCh(horizon); },[horizon]);
  const slug=(s)=>s.toLowerCase()
    .replace(/ğ/g,"g").replace(/ü/g,"u").replace(/ş/g,"s").replace(/ı/g,"i").replace(/ö/g,"o").replace(/ç/g,"c")
    .replace(/[^a-z0-9]+/g,"-").replace(/^-+|-+$/g,"").slice(0,32)||"iddia";
  const submit=async(e)=>{
    e.preventDefault(); if(busy)return;
    const claimText=txt.trim();
    if(!claimText){ setMsg({ok:false,text:L("claim_empty")}); return; }
    const mn=parseInt(minN,10);
    const tv=parseFloat(thr);
    if(!isFinite(mn)||mn<1){ setMsg({ok:false,text:L("claim_minn")}); return; }
    if(!isFinite(tv)){ setMsg({ok:false,text:L("claim_thr_num")}); return; }
    /* hit_rate is a FRACTION (fmtVal shows it ×100): 55 would register hit_rate ≥ 55, which can
       never be met, and the registry is immutable */
    if(metric==="hit_rate"&&(tv<0||tv>1)){ setMsg({ok:false,text:L("claim_thr_range")}); return; }
    const now=new Date();
    const stamp=now.toISOString().replace(/[-:]/g,"").replace(/\..+$/,"");
    const claim={
      claim_id:`ui-${slug(claimText)}-${stamp}`,
      claim:claimText,
      metric,
      filter: verdict==="ALL"?{}:{verdict},
      horizon:ch,
      min_n:mn,
      threshold:{op,value:tv},
      registered_at:now.toISOString().replace(/\.\d+Z$/,"Z"),
      engine_version:(window.SGS_EVIDENCE&&window.SGS_EVIDENCE.engine_version)||"ui-unknown",
    };
    setBusy(true); setMsg(null);
    try{
      await window.DIVE.registerClaim(claim);
      setMsg({ok:true,text:L("claim_saved",claim.claim_id.slice(0,40))});
      setTxt("");
    }catch(err){ setMsg({ok:false,text:L("claim_rejected",(err&&err.message)||err)}); }
    finally{ setBusy(false); }
  };
  const fmtVal=(c)=> c.value==null?"—":(c.metric==="hit_rate"?Math.round(c.value*100)+"%":pct2(c.value)||"—");
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("claim_title")}
    <span className="rt">{L("claim_rt",claims.length)}</span></div>
    <div className="pb" style={{padding:0}}>
      {claims.length===0&&<div className="stat"><span className="k">{L("claim_k")}</span>
        <span className="v">{L("claim_none")}</span></div>}
      <table className="rank evtab claims">{claims.length>0&&<thead><tr>
        <th scope="col">{L("claim_h_status")}</th><th scope="col">{L("claim_h_claim")}</th><th scope="col" className="r">N</th>
        <th scope="col" className="r">{L("claim_h_value")}</th><th scope="col" className="r">{L("claim_h_thr")}</th><th scope="col" className="r">{L("claim_h_reg")}</th>
      </tr></thead>}
      <tbody>{claims.map(cl=><tr key={cl.claim_id}>
        <td><span className={"tag "+(CLAIM_STATUS_CLS[cl.status]||"")}>{CLAIM_STATUS_KEY[cl.status]?L(CLAIM_STATUS_KEY[cl.status]):cl.status}</span></td>
        <td className="l ctxt" title={cl.claim_id}>{cl.claim}
          <small>{cl.metric} · {cl.horizon} · {(cl.filter&&Object.entries(cl.filter).map(([k,v])=>`${k}:${v}`).join(", "))||L("claim_nofilter")}</small></td>
        <td className="r">{cl.n}</td>
        <td className="r">{fmtVal(cl)}</td>
        <td className="r">{cl.threshold?`${cl.threshold.op} ${cl.threshold.value}`:"—"}</td>
        <td className="r">{isoDay(cl.registered_at)}</td>
      </tr>)}</tbody></table>
      <form className="claimform" onSubmit={submit}>
        <span className="label">{L("claim_form")}</span>
        <input className="wide" value={txt} maxLength={200} placeholder={L("claim_ph")}
          onChange={e=>setTxt(e.target.value)}/>
        <select value={metric} onChange={e=>setMetric(e.target.value)} aria-label={L("claim_a_metric")}>
          <option value="hit_rate">hit_rate</option><option value="avg_forward">avg_forward</option></select>
        <select value={verdict} onChange={e=>setVerdict(e.target.value)} aria-label={L("claim_a_filter")}>
          <option value="ALL">{L("claim_all")}</option><option value="LONG">LONG</option>
          <option value="SHORT">SHORT</option><option value="NEUTRAL">{L("ev_neutral")}</option></select>
        <select value={ch} onChange={e=>setCh(e.target.value)} aria-label={L("claim_a_horizon")}>
          {EV_HORIZONS.map(h=><option key={h} value={h}>{h}</option>)}</select>
        <input className="num" type="number" min="1" step="1" value={minN} aria-label={L("claim_a_minn")}
          title={L("claim_minn_t")} onChange={e=>setMinN(e.target.value)}/>
        <select value={op} onChange={e=>setOp(e.target.value)} aria-label={L("claim_a_op")}>
          <option value=">=">≥</option><option value="<=">≤</option></select>
        <input className="num" type="number" step="0.01" value={thr} aria-label={L("claim_a_thr")}
          min={metric==="hit_rate"?0:undefined} max={metric==="hit_rate"?1:undefined}
          title={L("claim_a_thr")} onChange={e=>setThr(e.target.value)}/>
        <button className="cta" disabled={busy}>{busy?L("claim_busy"):L("claim_form")}</button>
        {msg&&<span className={"sbmsg"+(msg.ok?"":" bad")} role="status">{msg.text}</span>}
      </form>
      <div className="gcap">{L("claim_cap")}</div>
    </div></div>;
}

/* KARARLILIK — per-symbol verdict self-agreement from /api/evidence/stability. */
function StabilityPanel({onPick}){
  useEffect(()=>{ ensureStability(); },[]);
  const rows=Array.isArray(window.SGS_STABILITY)?window.SGS_STABILITY:null;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>KARARLILIK
    <span className="rt">SON 8 ARŞİV KAYDI · ÇOĞUNLUK UYUMU</span></div>
    <div className="pb" style={{padding:0}}>
      {!rows&&<div className="stat"><span className="k">STABİLİTE</span><span className="v">— kararlılık arşivi okunuyor…</span></div>}
      {rows&&rows.length===0&&<div className="stat"><span className="k">STABİLİTE</span>
        <span className="v">— arşivde sembol kaydı yok</span></div>}
      {rows&&rows.slice(0,24).map(r=><div key={r.s} className="stabrow" tabIndex={0} role="button"
        onClick={()=>onPick&&onPick(r.s)} onKeyDown={e=>{if(e.key==="Enter")onPick&&onPick(r.s);}}>
        <span className="ssym">{String(r.s||"").replace("USDT","")}</span>
        <Pips agree={r.agree_frac} k={r.k}/>
        <span className="sval">{r.agree_frac!=null?"%"+Math.round(r.agree_frac*100):"—"}</span>
        <span className="smeta">k={r.k} · aralık {r.median_gap_min!=null?num(r.median_gap_min,0)+"dk":"—"}</span>
      </div>)}
      <div className="gcap">uyum = son k kayıt içinde çoğunluk yönünde kalanların payı · yönlü kayıt yoksa tanımsız (—)</div>
    </div></div>;
}

/* İNDİKATÖR IC — Spearman IC per indicator + weight suggestions (report-only). */
function IcPanel({horizon}){
  useEffect(()=>{ if(window.DIVE&&typeof window.DIVE.ic==="function") window.DIVE.ic(horizon).catch(()=>{}); },[horizon]);
  const ic=window.SGS_IC;
  const [busy,setBusy]=useState(false);
  const [sug,setSug]=useState(null);     // {h, out}: tagged with the horizon it was generated for
  const [err,setErr]=useState(null);
  const gen=async()=>{
    if(busy)return; setBusy(true); setErr(null);
    try{ const out=await window.DIVE.suggestWeights(horizon); setSug({h:horizon,out}); }
    catch(e){ setErr((e&&e.message)||String(e)); }
    finally{ setBusy(false); }
  };
  // a suggestion table never sits beside another horizon's IC table
  const sugOut=(sug&&sug.h===horizon)?sug.out:null;
  const rows=ic?Object.entries(ic.indicators||{})
    .map(([name,v])=>({name,ic:v&&v.ic,ir:v&&v.ic_ir,n:(v&&v.n)||0,
      rank:(v&&typeof v.ic==="number")?Math.abs(v.ic)*((v&&v.n)||0):0}))
    .sort((a,b)=>b.rank-a.rank).slice(0,15):null;
  const sugRows=sugOut?Object.entries(sugOut.suggestions||{})
    .map(([name,v])=>({name,...v,capped: v.suggested!=null&&v.shipped!=null
      && v.suggested>=v.shipped*(sugOut.cap_multiple||3)-1e-9}))
    .sort((a,b)=>Math.abs(b.suggested-b.shipped)-Math.abs(a.suggested-a.shipped)):null;
  return <div className="panel"><div className="ph"><span className="tick">▸</span>İNDİKATÖR IC · SPEARMAN
    <span className="rt">{ic?`${ic.n} OLAY · |IC|·N İLK 15`:"OKUNUYOR…"}</span></div>
    <div className="pb" style={{padding:0}}>
      {!rows&&<div className="stat"><span className="k">IC</span><span className="v">— IC tablosu okunuyor…</span></div>}
      {rows&&rows.length===0&&<div className="stat"><span className="k">IC</span>
        <span className="v">— notalanmış olay yok · IC üretilemez</span></div>}
      {rows&&rows.length>0&&<table className="rank evtab"><thead><tr>
        <th scope="col">İNDİKATÖR</th><th scope="col" className="r">IC</th><th scope="col" className="r">IC-IR</th>
        <th scope="col" className="r">N</th></tr></thead>
        <tbody>{rows.map(r=><tr key={r.name}>
          <td className="l">{r.name}</td>
          <td className="r">{r.ic==="n/a"?"n/a":(typeof r.ic==="number"?(r.ic>0?"+":"")+r.ic:"—")}</td>
          <td className="r">{r.ir==null?"—":(r.ir>0?"+":"")+r.ir}</td>
          <td className="r">{r.n}</td></tr>)}</tbody></table>}
      <div className="pbtns" style={{padding:"10px 13px"}}>
        <button className="cta" disabled={busy} onClick={gen}>{busy?"ÜRETİLİYOR…":"AĞIRLIK ÖNERİSİ ÜRET"}</button>
        {err&&<span className="sbmsg bad">ÖNERİ BAŞARISIZ · {err}</span>}
      </div>
      {sugRows&&<table className="rank evtab"><thead><tr>
        <th scope="col">İNDİKATÖR</th><th scope="col" className="r">MEVCUT</th><th scope="col" className="r">ÖNERİLEN</th>
        <th scope="col" className="r">IC</th><th scope="col" className="r">TAVAN</th></tr></thead>
        <tbody>{sugRows.map(r=><tr key={r.name}>
          <td className="l">{r.name}</td>
          <td className="r">{r.shipped}</td>
          <td className="r" style={{color:r.suggested>r.shipped?"var(--up)":r.suggested<r.shipped?"var(--down)":undefined}}>{r.suggested}</td>
          <td className="r">{r.ic==="n/a"?"n/a":r.ic==null?"—":r.ic}</td>
          <td className="r">{r.capped?<span className="tag hot">3× TAVAN</span>:""}</td></tr>)}</tbody></table>}
      <div className="gcap">DISCLAIMER: rapor-only · asla otomatik uygulanmaz · {sugOut?sugOut.disclaimer||"öneriler runtime/weight_suggestions.json'a yazılır; motor asla okumaz":""}</div>
    </div></div>;
}

/* REPLAY — counterfactual threshold grid (POST, report-only). Heatmap table:
   hit-rate colored by delta_vs_shipped; shipped cell highlighted. */
function ReplayPanel({horizon}){
  const [busy,setBusy]=useState(false);
  const [err,setErr]=useState(null);
  const rp=window.SGS_REPLAY&&window.SGS_REPLAY.horizon===horizon?window.SGS_REPLAY:null;
  const run=async()=>{
    if(busy)return; setBusy(true); setErr(null);
    try{ await window.DIVE.replay(horizon); }
    catch(e){ setErr((e&&e.message)||String(e)); }
    finally{ setBusy(false); }
  };
  const grid=rp?window.sgsReplaySummary?window.sgsReplaySummary(rp.grid,0).sorted:(rp.grid||[]):[];
  const isShipped=(c)=>rp&&rp.shipped_cell&&c.buy===rp.shipped_cell.buy
    &&c.strong===rp.shipped_cell.strong&&c.conflict===rp.shipped_cell.conflict;
  const deltaCol=(d)=> d==null?"var(--dim)":d>0.02?"var(--up)":d<-0.02?"var(--down)":"var(--ink)";
  return <div className="panel"><div className="ph"><span className="tick">▸</span>{L("rp_title")}
    <span className="rt">{rp?L("rp_rt",rp.events.replayable,rp.folds,rp.bootstrap.n,rp.bootstrap.seed):"REPORT-ONLY"}</span></div>
    <div className="pb" style={{padding:0}}>
      {!rp&&<div className="stat"><span className="k">{L("rp_k")}</span>
        <span className="v">{busy?L("rp_busy"):L("rp_empty")}</span></div>}
      <div className="pbtns" style={{padding:"10px 13px"}}>
        <button className="cta" disabled={busy} onClick={run}>{busy?L("rp_running"):L("rp_run")}</button>
        {err&&<span className="sbmsg bad">{L("rp_fail",err)}</span>}
      </div>
      {rp&&<>
        <div className="scanbar" style={{margin:"0 13px 10px"}}>
          <span className="sbcap" style={{marginLeft:0, textAlign:"left"}}>
            {L("rp_skipped",rp.events.skipped_weights_hash,rp.events.skipped_no_signals,
              rp.shipped_cell.buy,rp.shipped_cell.strong,rp.shipped_cell.conflict)}</span>
        </div>
        <div style={{maxHeight:340,overflow:"auto"}}>
        <table className="rank evtab replaygrid"><thead><tr>
          <th scope="col">BUY</th><th scope="col">STRONG</th><th scope="col">CONFLICT</th>
          <th scope="col" className="r">{L("rp_h_n")}</th><th scope="col" className="r">{L("rp_h_hit")}</th>
          <th scope="col" className="r">Δ SHIPPED</th><th scope="col" className="r">BOOT WIN%</th>
          <th scope="col" className="r">{L("rp_h_fold")}</th></tr></thead>
        <tbody>{grid.map((cell,i)=>{
          const ship=isShipped(cell);
          const bg=cell.delta_vs_shipped==null?"transparent"
            :`color-mix(in srgb, ${cell.delta_vs_shipped>=0?"var(--up)":"var(--down)"} ${Math.min(28,Math.abs(cell.delta_vs_shipped)*220)}%, transparent)`;
          return <tr key={i} className={ship?"shipped":""} style={ship?{outline:"1px solid var(--accent)"}:{}}>
            <td>{cell.buy}</td><td>{cell.strong}</td><td>{cell.conflict}</td>
            <td className="r">{cell.n_directional}</td>
            <td className="r" style={{background:bg}}>{cell.hit_rate!=null?Math.round(cell.hit_rate*100)+"%":"—"}</td>
            <td className="r" style={{color:deltaCol(cell.delta_vs_shipped)}}>
              {cell.delta_vs_shipped!=null?(cell.delta_vs_shipped>0?"+":"")+(cell.delta_vs_shipped*100).toFixed(1)+"%":"—"}</td>
            <td className="r">{cell.bootstrap_win_vs_shipped!=null?Math.round(cell.bootstrap_win_vs_shipped*100)+"%":"—"}</td>
            <td className="r">{cell.fold_spread!=null?(cell.fold_spread*100).toFixed(1)+"%":"—"}</td>
          </tr>;})}</tbody></table></div>
        <div className="gcap">{L("rp_cap",rp.folds)}</div>
      </>}
    </div></div>;
}

function ProvenanceLine({prov,horizon}){
  if(!prov) return null;
  return <div className="provline" title={prov.window_note||""}>
    <span className="pl-k">PROVENANCE</span>
    <span>{isoDay(prov.first_ts)} → {isoDay(prov.last_ts)}</span>
    <span>· notalanmış {prov.graded_count}</span>
    <span>· başarısız nota {prov.failed_grades}</span>
    <span>· {prov.engine_version}</span>
    <span>· ufuk {horizon}</span>
    <span className="pl-note">{prov.window_note||""}</span>
  </div>;
}

function SliceTabs({ev}){
  const SLICES=[["regime","REJİM","by_regime"],["session","SEANS","by_session"],
    ["funding","FUNDING","by_funding_proximity"],["divergence","DİVERJANS","by_divergence_tier"]];
  const [tab,setTab]=useState("regime");
  const def=SLICES.find(s=>s[0]===tab);
  const slice=ev&&ev[def[2]];
  const buckets=(slice&&slice.buckets)||{};
  const keys=Object.keys(buckets);
  return <div className="panel"><div className="ph"><span className="tick">▸</span>DİLİMLER
    <span className="rt">TANIMSAL · ÇOKLU KARŞILAŞTIRMA</span></div>
    <div className="pb" style={{padding:0}}>
      <div className="scanbar" style={{margin:"10px 13px",border:0,padding:0,background:"transparent"}}>
        {SLICES.map(([id,label])=><button key={id} className={"chip"+(tab===id?" on":"")}
          aria-pressed={tab===id} onClick={()=>setTab(id)}>{label}</button>)}
      </div>
      {keys.length===0
        ? <div className="stat"><span className="k">DİLİM</span><span className="v">— bu dilim için notalanmış karar yok</span></div>
        : <table className="rank evtab"><thead><tr>
            <th scope="col">KOVA</th><th scope="col" className="r">İSABET</th>
            <th scope="col" className="r">ORT. İLERİ</th><th scope="col" className="r">MEDYAN</th><th scope="col" className="r">N</th></tr></thead>
          <tbody>{keys.map(k=>{const b=buckets[k]||{};return <tr key={k}>
            <td className="l">{k}</td>
            <td className="r"><HitLabel stats={b}/></td>
            <td className="r">{pct2(b.avg_forward)||"—"}</td>
            <td className="r">{pct2(b.median_forward)||"—"}</td>
            <td className="r">{b.n||0}</td></tr>;})}</tbody></table>}
      {slice&&slice.note&&<div className="gcap">{slice.note}</div>}
    </div></div>;
}

function Evidence({horizon,setHorizon,fetchFail,onRetry}){
  const evAny=window.SGS_EVIDENCE;
  /* only a summary for the SELECTED horizon may render (mirror of the replay guard): the
     previous horizon's numbers must never sit under the new chip */
  const ev=(evAny&&evAny.horizon===horizon)?evAny:null;
  const [gradingH,setGradingH]=useState(null);   // per-horizon busy flag
  const [winKey,setWinKey]=useState("30d");
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
  const chips=EV_HORIZONS.map(h=><button key={h} className={"chip"+(h===horizon?" on":"")}
    aria-pressed={h===horizon} disabled={!!gradingH} onClick={()=>setHorizon(h)}
    title={`${h} ufku için öz-derecelendirme`}>{h.toUpperCase()}</button>);
  // while the selected horizon's summary is not loaded the chips stay usable
  if(!ev)
    return <><div className="scanbar">{chips}</div>
      {fetchFail?<FetchFail err={fetchFail} onRetry={onRetry}/>:<div className="state"><div className="spin"/><div>{L("ev_loading")}</div></div>}</>;
  const rem=Math.max((ev.gradable_count||0)-(ev.graded_count||0),0);
  const winStats=(ev.windows&&(ev.windows[winKey]&&ev.windows[winKey]))||ev.by_verdict;
  const winFallback=!(ev.windows&&ev.windows[winKey]);
  const cal=ev.calibration;
  const br=ev.brier||{};
  return <>
    <div className="vhead"><span className="kicker">{L("ev_kicker")}</span><h1>{L("ev_h1")}</h1>
      <div className="meta">{L("ev_meta")}<br/>{ev.engine_version||"—"} · {ev.generated_at||"—"}</div></div>
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
    <ProvenanceLine prov={ev.provenance} horizon={horizon}/>
    <div className="scanbar">
      <span className="label">{L("ev_horizon")}</span>
      {chips}
      <button className="cta" disabled={!!gradingH} onClick={doGrade}>
        {gradingH?`${L("ev_grading")} (${gradingH.toUpperCase()})…`:`${L("ev_grade_now")} (${horizon.toUpperCase()})`}</button>
      {msg&&<span className={"sbmsg"+(msg.ok?"":" bad")}>{msg.text}</span>}
      <span className="sbcap">ARŞİV {ev.archived_count} · NOTALANMIŞ {ev.graded_count}/{ev.gradable_count} · KAPAMA {hitPct(ev.coverage)}</span>
    </div>
    {ev.stale&&<div className="panel mute"><div className="pb"><div className="reason" style={{padding:"4px 2px"}}>
      <b>Dürüstlük notu:</b> {rem} olgunlaşmış karar henüz notalanmadı (kapama {hitPct(ev.coverage)}).
      "ŞİMDİ NOTALA" istek başına 40 sembol geriye doldurur ve özet verir.</div></div></div>}
    <div className="scanbar">
      <span className="label">{L("ev_window")}</span>
      {EV_WINDOWS.map(([k])=><button key={k} className={"chip"+(winKey===k?" on":"")}
        aria-pressed={winKey===k} onClick={()=>setWinKey(k)}
        title={`${k} penceresinin karar kırılımı`}>{L("ev_win_"+k)}</button>)}
      <span className="sbcap">{winFallback?"pencere verisi yok — tüm arşiv gösteriliyor":"pencere seçimi karar kartlarını besler"}</span>
    </div>
    <div className="evcards">
      <EvCard label="LONG" cls="b" dir={1} stats={winStats&&winStats.LONG}/>
      <EvCard label="SHORT" cls="s" dir={-1} stats={winStats&&winStats.SHORT}/>
      <EvCard label={L("ev_neutral")} cls="n" dir={0} stats={winStats&&winStats.NEUTRAL}/>
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
                <td className="r"><HitLabel stats={b}/></td>
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
    <div className="grid2">
      <div className="panel"><div className="ph"><span className="tick">▸</span>GÜVENİLİRLİK DİYAGRAMI
        <span className="rt">ECE <b className={"ecebadge"}>{cal?cal.ece!=null?cal.ece:"—":"—"}</b></span></div>
        <div className="pb">
          {cal?<ReliabilityDiagram calibration={cal}/>
            :<div className="stat"><span className="k">DİYAGRAM</span><span className="v">— kalibrasyon verisi yok</span></div>}
          <div className="gcap">nokta = kova ortalama güveni × gerçekleşen isabet · bıyıklar Wilson %95 ·
            köşegenin altı = aşırı güvenli · boş kovalar çizgi yok</div>
        </div></div>
      <div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>BRIER
          <span className="rt">YÖN KALİTESİ</span></div>
          <div className="pb" style={{padding:0}}>
            <div className="cdrow"><span className="cd">{br.score!=null?br.score:"—"}</span>
              <span className="cdcap">BRIER SKORU (0=mükemmel)</span></div>
            <Stat k="SKILL (tabana göre)" v={br.skill!=null?(br.skill>0?"+":"")+br.skill:null}
              sub={br.ref!=null?`ref ${br.ref}`:"taban —"}/>
            <Stat k="N" v={br.n!=null?br.n:null}/>
            <div className="gcap">skill = 1 − score/ref · pozitifse güven dağılımı yazı-tura tabanından iyisi</div>
          </div></div>
        <div className="panel"><div className="ph"><span className="tick">▸</span>TABANLAR · BASELINES</div>
          <div className="pb" style={{padding:0}}>
            <BaselinesPanel baselines={ev.baselines}/>
          </div></div>
      </div>
    </div>
    <SliceTabs ev={ev}/>
    <div className="grid2">
      <ClaimsPanel horizon={horizon}/>
      <StabilityPanel/>
    </div>
    <IcPanel horizon={horizon}/>
    <ReplayPanel horizon={horizon}/>
      </>}
  </>;
}

/* ── KIYAS (#/compare) — 2-4 symbols in synchronized columns ────────────────
   Mini candle charts share the time axis (aligned by index from the END — the
   honest alignment: every column's latest bar is "now"). One failing symbol
   renders its own error; other columns are unaffected. */
function MiniCandles({candles,hoverIdx,onHover,err}){
  const wrapRef=useRef(null),cvsRef=useRef(null);
  const [w,setW]=useState(0);
  const list=(candles||[]).filter(c=>c&&["o","h","l","c"].every(k=>isFinite(Number(c[k]))));
  useEffect(()=>{const el=wrapRef.current;if(!el)return;
    const m=()=>setW(el.clientWidth||0);
    m();
    if(typeof ResizeObserver!=="undefined"){const ro=new ResizeObserver(m);ro.observe(el);return()=>ro.disconnect();}
    return()=>{};},[]);
  useEffect(()=>{
    const cvs=cvsRef.current;if(!cvs||!w||!list.length)return;
    const H=120,dpr=Math.max(1,(window.devicePixelRatio)||1);
    cvs.width=Math.round(w*dpr);cvs.height=Math.round(H*dpr);
    cvs.style.width=w+"px";cvs.style.height=H+"px";
    const ctx=cvs.getContext("2d");if(!ctx)return;
    ctx.setTransform(dpr,0,0,dpr,0,0);
    let lo=Infinity,hi=-Infinity;
    list.forEach(c=>{lo=Math.min(lo,Number(c.l));hi=Math.max(hi,Number(c.h));});
    if(!isFinite(lo)||!isFinite(hi))return;
    if(hi===lo){hi*=1.001;lo*=0.999;}
    const pad=(hi-lo)*0.06;lo-=pad;hi+=pad;
    const slot=w/list.length,cw=Math.max(1,Math.min(11,slot*0.6));
    const X=(i)=>(i+0.5)*slot,Y=(p)=>(hi-p)/(hi-lo)*(H-14);
    ctx.clearRect(0,0,w,H);
    const cs=getComputedStyle(document.documentElement);
    const up=cs.getPropertyValue("--up").trim()||"#4dffa6",dn=cs.getPropertyValue("--down").trim()||"#ff5c6c";
    list.forEach((c,i)=>{const col=Number(c.c)>=Number(c.o)?up:dn;
      ctx.strokeStyle=col;ctx.fillStyle=col;
      ctx.beginPath();ctx.moveTo(X(i),Y(Number(c.h)));ctx.lineTo(X(i),Y(Number(c.l)));ctx.stroke();
      const yO=Y(Number(c.o)),yC=Y(Number(c.c));
      ctx.fillRect(X(i)-cw/2,Math.min(yO,yC),cw,Math.max(1,Math.abs(yC-yO)));});
    if(hoverIdx!=null){const i=list.length-1-hoverIdx;
      if(i>=0&&i<list.length){ctx.setLineDash([3,3]);ctx.strokeStyle="rgba(255,255,255,.35)";
        ctx.beginPath();ctx.moveTo(X(i),0);ctx.lineTo(X(i),H-14);ctx.stroke();ctx.setLineDash([]);}}
    const last=list[list.length-1];
    ctx.fillStyle=Number(last.c)>=Number(last.o)?up:dn;
    ctx.font="bold 9px ui-monospace,monospace";
    ctx.fillText(fmt(Number(last.c)),2,H-3);
  },[w,list.length,hoverIdx,list]);
  return <div className="minichart" ref={wrapRef}
    onMouseMove={(e)=>{ if(!onHover||!list.length)return;
      const r=e.currentTarget.getBoundingClientRect();
      const slot=r.width/list.length;
      const i=Math.max(0,Math.min(list.length-1,Math.floor((e.clientX-r.left)/slot)));
      onHover(list.length-1-i); }}
    onMouseLeave={()=>onHover&&onHover(null)}>
    {err
      ? <div className="mini-err">— {err}</div>
      : list.length?<canvas ref={cvsRef} role="img" aria-label={`mini grafik · ${list.length} bar`}/>
      : <div className="mini-err">— mum yok</div>}
  </div>;
}

function Compare({symbols,onPick}){
  const [cols,setCols]=useState({});   // {SYM: {obj}|{err}}
  const [hov,setHov]=useState(null);
  const [add,setAdd]=useState("");
  const list=Array.isArray(symbols)?symbols.filter(Boolean):[];
  useEffect(()=>{
    list.forEach(s=>{
      if(cols[s]||cols[s]===null)return;   // null = in-flight
      setCols(c=>({...c,[s]:null}));
      window.DIVE.symbol(s,{commit:false})
        .then(obj=>setCols(c=>({...c,[s]:obj&&obj.s?{...obj,_at:Date.now()}:{err:L("cmp_invalid")}})))
        .catch(e=>setCols(c=>({...c,[s]:{err:(e&&e.message)||String(e)}})));
    });
  },[list.join("|")]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(()=>{ ensureStructure(); },[]);   // β/ρ lookups
  /* freshness: a KIYAS column is a one-time snapshot per symbol (not polled); the caption
     shows the age of the OLDEST loaded column, like MAP and STRUCTURE */
  const fetchedAts=list.map(s=>cols[s]&&cols[s]._at).filter(Boolean);
  const oldestAt=fetchedAts.length?Math.min(...fetchedAts):null;
  const addSym=(e)=>{ e.preventDefault();
    let s=add.trim().toUpperCase(); if(!s)return; if(!s.endsWith("USDT"))s+="USDT";
    if(list.includes(s)){ setAdd(""); return; }
    if(window.__diveCompareAdd)window.__diveCompareAdd(s);
    setAdd(""); };
  return <>
    <div className="vhead"><span className="kicker">{L("cmp_kicker")}</span><h1>{L("cmp_h1")}</h1>
      <div className="meta">{L("cmp_meta1")}<br/>{L("cmp_meta2")}</div></div>
    <form className="scanbar" onSubmit={addSym}>
      <span className="label">{L("cmp_add")}</span>
      <input className="pname wide" value={add} onChange={e=>setAdd(e.target.value)} placeholder="örn. SOL…" aria-label="kıyas için sembol ekle"/>
      <span className="sbcap">imleç bir kolonda gezinirken hepsinde aynı bar vurgulanır</span>
      <span className="sbcap">{oldestAt==null?L("map_age_none"):L("map_age",Math.max(0,Math.round((Date.now()-oldestAt)/60000)))}</span>
    </form>
    {list.length===0
      ? <div className="state"><div>— kıyas listesi boş · TARA tablosundaki onay kutularıyla ya da yukarıdan sembol ekle</div></div>
      : <div className="cmpgrid">{list.map(s=>{
          const col=cols[s];
          const d=col&&col.s?col:null;
          const err=col&&col.err;
          const sm=structureFind(s);
          const cvd=d&&d.cvd&&!d.cvd.unavailable?d.cvd.cvd:null;
          const dir=d?sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0):0;
          return <div key={s} className="panel cmpcol">
            <div className="ph"><span className="tick">▸</span>{s.replace("USDT","")}
              <span className="rt">{d?`${d.multiTf?d.multiTf.length:0} TF`:"—"}</span></div>
            <div className="pb">
              {d?<Pill sig={d.finalSignal||"NEUTRAL"}/>:<span className="pill n"><span className="g"/>—</span>}
              <DemoMark row={d}/>
              {err&&<div className="perr">YÜKLENEMEDİ · {err}</div>}
              {!d&&!err&&<div className="stat"><span className="k">VERİ</span><span className="v">çekiliyor…</span></div>}
              {d&&<>
                <div className="cmp-px"><span className="px">${fmt(d.price)}</span>
                  <span className={"chg "+chgCls(d.ch)}>{chgTxt(d.ch)}</span>
                  <span style={{marginLeft:"auto"}}><Vu conf={d.confidence} dir={dir}/></span></div>
                <MiniCandles candles={d.candles} hoverIdx={hov} onHover={setHov}/>
                <div className="cmp-row"><span className="k">12 TF</span><Heat multiTf={d.multiTf}/></div>
                <div className="cmp-row"><span className="k">β / ρ</span>
                  <span className="v">{sm&&(sm.beta!=null||sm.corr_btc!=null)?betaRho(sm.beta,sm.corr_btc):"—"}</span></div>
                <div className="cmp-row"><span className="k">CVD YÖNÜ</span>
                  <span className={"v "+(cvd>0?"up":cvd<0?"dn":"")}>{cvd==null?"—":cvd>0?"▲ alım":"▼ satım"}</span></div>
                <div className="cmp-row"><span className="k">{L("cmp_conf")}</span><span className="v">%{d.confidence||0} · puan {kfmt(d.netNss||d.quantBias||0)}</span></div>
                <button className="cta" style={{width:"100%",justifyContent:"center",marginTop:8}}
                  onClick={()=>onPick&&onPick(s)}>{L("cmp_open")}</button>
              </>}
            </div></div>;})}
      </div>}
  </>;
}

/* ── HARİTA (#/map) — verdict tile mosaic ──────────────────────────────────── */
function MapView({onPick,snapAgeMin}){
  const [mode,setMode]=useState("TARAMA");
  const [clusterMode,setClusterMode]=useState(false);
  const scan=window.SGS_SCAN||{survivors:[]};
  const universe=window.SGS_DATA||[];
  useEffect(()=>{ if(clusterMode)ensureStructure(); },[clusterMode]);
  const tiles = mode==="TARAMA"
    ? (scan.survivors||[]).map((w,i)=>{const d=w.d||w;return{s:d.s,d,rank:i+1};})
    : universe.map(u=>({s:u.s,d:u,rank:null}));
  const clusterOf=(s)=>{ if(!clusterMode)return null;
    const st=window.SGS_STRUCTURE; if(!st)return null;
    for(const c of (st.clusters||[])){ if((c.members||[]).some(m=>m.s===s))return c; }
    return null; };
  return <>
    <div className="vhead"><span className="kicker">{L("map_kicker")}</span><h1>{L("map_h1")}</h1>
      <div className="meta">{L("map_meta")}<br/>{mode==="TARAMA"?"BOYUT=SIRALAMA":"EVREN · KARAR YOK"}</div></div>
    <div className="scanbar">
      <button className={"chip"+(mode==="TARAMA"?" on":"")} aria-pressed={mode==="TARAMA"} onClick={()=>setMode("TARAMA")}>{L("map_mode_scan")}</button>
      <button className={"chip"+(mode==="EVREN"?" on":"")} aria-pressed={mode==="EVREN"} onClick={()=>setMode("EVREN")}>{L("map_mode_universe")}</button>
      <button className={"chip"+(clusterMode?" on":"")} aria-pressed={clusterMode} onClick={()=>setClusterMode(v=>!v)}
        title="kümeleri konturla (/api/structure)">{L("map_cluster")}</button>
      <span className="sbcap">{mode==="TARAMA"
        ?"karo = taramadan sağ kalan · tıkla → panel"
        :"karar yok · tarama gerekli — evren karoları nötr gridir"}</span>
      {mode==="TARAMA"&&<span className="sbcap">{snapAgeMin==null?L("map_age_none"):L("map_age",snapAgeMin)}</span>}
    </div>
    {tiles.length===0
      ? <div className="state"><div>{mode==="TARAMA"?"— tarama sonucu yok · TARA görünümü ilk taramayı çalıştırır":"— evren yükleniyor…"}</div></div>
      : <div className="mosaic">{tiles.map(t=>{
          const d=t.d||{};
          const vdir=sgn(d.finalSignal?.includes("BUY")?1:d.finalSignal?.includes("SELL")?-1:0);
          const cls2= mode==="EVREN"?"maptile neutral": vdir>0?"maptile up":vdir<0?"maptile dn":"maptile flat";
          const conf=d.confidence||0;
          const op= mode==="EVREN"?0.55:0.4+Math.min(1,conf/100)*0.6;   // intensity = confidence
          const cc=clusterOf(t.s);
          const cl= cc?{outline:`1px solid hsl(${(cc.cluster_id*67)%360} 70% 55%)`,outlineOffset:1}:null;
          const title= mode==="EVREN"
            ? `${t.s} · karar yok · tarama gerekli`
            : `${t.s} · ${d.finalSignal||"NEUTRAL"} · güven ${conf}% · sıra ${t.rank}${cc?` · küme ${cc.cluster_id} (${cc.label})`:""}`;
          return <button key={t.s} className={cls2} style={{opacity:op,...cl}} title={title}
            onClick={()=>onPick&&onPick(t.s)}>
            <span className="mt-sym">{String(t.s).replace("USDT","")}</span>
            {mode==="TARAMA"&&<span className="mt-sig">{d.finalSignal?shortSig(d.finalSignal).replace("S-","S"):"—"}</span>}
            <span className="mt-conf">{mode==="EVREN"?"·":`%${conf}`}</span>
          </button>;})}
      </div>}
  </>;
}

/* ── PORTFÖY (#/portfolio) — local-only positions, live P&L%, honest totals ── */
function Portfolio(){
  const [rows,setRows]=useState(()=>window.DIVE_PORTFOLIO.load());
  const [form,setForm]=useState({s:"",entry:"",size:"",direction:"long",note:""});
  const [editId,setEditId]=useState(null);
  const [msg,setMsg]=useState(null);
  const priceOf=(s)=>{ const m=(window.SGS_DATA_MAP||{})[s];
    if(m&&isFinite(Number(m.price))&&Number(m.price)>0)return Number(m.price);
    const u=(window.SGS_DATA||[]).find(r=>r.s===s);
    return u&&isFinite(Number(u.price))&&Number(u.price)>0?Number(u.price):null; };
  const verdictSignOf=(s)=>{ const m=(window.SGS_DATA_MAP||{})[s];
    const sig=m&&m.finalSignal?m.finalSignal:null;
    if(!sig){ const w=(window.SGS_SCAN&&window.SGS_SCAN.survivors||[]).find(x=>{const d=x.d||x;return d.s===s;});
      return w?sgn(((w.d||w).finalSignal||"").includes("BUY")?1:((w.d||w).finalSignal||"").includes("SELL")?-1:0):0; }
    return sgn(sig.includes("BUY")?1:sig.includes("SELL")?-1:0); };
  const persist=(next)=>{ if(window.DIVE_PORTFOLIO.save(next)) setRows(next);
    else setMsg({ok:false,text:"kaydedilemedi — depolama yazılamadı"}); };
  const submit=(e)=>{ e.preventDefault();
    const s=form.s.trim().toUpperCase(); const sym=!s||s.endsWith("USDT")?s:s+"USDT";
    const raw={ id: editId||undefined, s:sym, entry:parseFloat(form.entry), size:parseFloat(form.size),
      direction:form.direction, note:form.note };
    const v=window.DIVE_PORTFOLIO.validate(raw);
    if(!v){ setMsg({ok:false,text:"geçersiz konum — sembol/giriş/miktar kontrol et · kaydedilmedi"}); return; }
    if(editId){ persist(rows.map(r=>r.id===editId?v:r)); setEditId(null); }
    else persist([...rows,v]);
    setForm({s:"",entry:"",size:"",direction:"long",note:""}); setMsg(null); };
  const startEdit=(r)=>{ setEditId(r.id);
    setForm({s:r.s,entry:String(r.entry),size:String(r.size),direction:r.direction,note:r.note||""}); };
  let complete=0,incomplete=0,notional=0,weighted=0;
  const enriched=rows.map(r=>{ const p=priceOf(r.s); const pnl=window.DIVE_PORTFOLIO.pnl(r,p);
    if(pnl==null)incomplete++; else{ complete++; notional+=r.entry*r.size; weighted+=pnl*r.entry*r.size; }
    const vs=verdictSignOf(r.s); const dsign=r.direction==="short"?-1:1;
    return {...r,pnl,verdictTag: pnl==null?null:(vs===0?"—":vs===dsign?"UYUMLU":"KARŞIT"),verdictVs:vs}; });
  const totalPnl= complete&&notional>0 ? weighted/notional : null;
  return <>
    <div className="vhead"><span className="kicker">{L("pf_kicker")}</span><h1>{L("pf_h1")}</h1>
      <div className="meta">{L("pf_caption")}<br/>dive_portfolio_v1</div></div>
    <form className="scanbar" onSubmit={submit}>
      <span className="label">{editId?L("pf_edit"):L("pf_add")}</span>
      <input className="pname" value={form.s} maxLength={20} placeholder="SEMBOL" aria-label="sembol"
        onChange={e=>setForm(f=>({...f,s:e.target.value}))}/>
      <input className="pname" type="number" step="any" min="0" value={form.entry} placeholder="GİRİŞ" aria-label="giriş fiyatı"
        onChange={e=>setForm(f=>({...f,entry:e.target.value}))}/>
      <input className="pname" type="number" step="any" min="0" value={form.size} placeholder="MİKTAR" aria-label="miktar"
        onChange={e=>setForm(f=>({...f,size:e.target.value}))}/>
      <select value={form.direction} aria-label="yön" onChange={e=>setForm(f=>({...f,direction:e.target.value}))}>
        <option value="long">LONG</option><option value="short">SHORT</option></select>
      <input className="pname wide" value={form.note} maxLength={120} placeholder="not (opsiyonel)…" aria-label="not"
        onChange={e=>setForm(f=>({...f,note:e.target.value}))}/>
      <button className="cta" type="submit">{editId?L("pf_update_btn"):L("pf_add_btn")}</button>
      {editId&&<button className="chip" type="button" onClick={()=>{setEditId(null);setForm({s:"",entry:"",size:"",direction:"long",note:""});}}>{L("pf_cancel")}</button>}
      {msg&&<span className={"sbmsg"+(msg.ok?"":" bad")} role="status">{msg.text}</span>}
    </form>
    <div className="panel"><div className="ph"><span className="tick">▸</span>{L("pf_positions")}
      <span className="rt">{rows.length} {L("pf_rows")} · {complete} {L("pf_live")}</span></div>
      <div className="pb" style={{padding:0}}>
        {rows.length===0
          ? <div className="stat"><span className="k">DEFTER</span>
              <span className="v">— konum yok · yukarıdan ekle · veriler yalnız bu cihazda</span></div>
          : <table className="rank pf"><thead><tr>
              <th scope="col">{L("pf_sym")}</th><th scope="col">{L("pf_dir")}</th><th scope="col" className="r">{L("pf_entry")}</th>
              <th scope="col" className="r">{L("pf_size")}</th><th scope="col" className="r">{L("pf_now")}</th>
              <th scope="col" className="r">{L("pf_pnl")}</th><th scope="col">{L("pf_verdict")}</th><th scope="col" className="r">{L("pf_action")}</th>
            </tr></thead><tbody>{enriched.map(r=><tr key={r.id} style={{cursor:"default"}}>
              <td><div className="sym">{r.s.replace("USDT","")}</div><small>{r.note||" "}</small></td>
              <td><span className={"pill "+(r.direction==="long"?"b":"s")}><span className="g"/>{r.direction==="long"?"LONG":"SHORT"}</span></td>
              <td className="r">{fmt(r.entry)}</td>
              <td className="r">{r.size}</td>
              <td className="r">{r.pnl!=null?fmt(priceOf(r.s)):"—"}</td>
              <td className={"r "+(r.pnl>0?"up":r.pnl<0?"dn":"")} style={{fontWeight:700}}>
                {r.pnl==null?"— (fiyat —)":(r.pnl>0?"+":"")+r.pnl.toFixed(2)+"%"}</td>
              <td>{r.pnl==null?<span className="tag">—</span>
                :<span className={"tag "+(r.verdictTag==="UYUMLU"?"good":r.verdictTag==="KARŞIT"?"bad":"")}>{r.verdictTag}</span>}</td>
              <td className="r"><button className="pxbtn" aria-label={`${r.s} düzenle`} onClick={()=>startEdit(r)}>✎</button>
                <button className="pxbtn" aria-label={`${r.s} sil`}
                  onClick={()=>persist(rows.filter(x=>x.id!==r.id))}>✕</button></td>
            </tr>)}</tbody></table>}
        <div className="gcap">TOPLAM: {complete} tam satır{incomplete?` · ${incomplete} eksik satır toplam dışı (canlı fiyat yok)`:""} ·
          {totalPnl==null?" K/Z toplamı — (tam satır yok)":` ağırlıklı K/Z ${(totalPnl>0?"+":"")+totalPnl.toFixed(2)}% (giriş×miktar ağırlıklı)`} ·
          {" "}uyum etiketi: yön × güncel tarama kararı · yalnızca rapor</div>
      </div></div>
  </>;
}

/* ── YAPI (#/structure) — cluster explorer ────────────────────────────────── */
function StructureView({onPick}){
  const [err,setErr]=useState(null);
  const [busy,setBusy]=useState(false);
  const st=window.SGS_STRUCTURE;
  const load=()=>{ if(busy)return; setBusy(true); setErr(null);
    window.DIVE.structure().catch(e=>setErr((e&&e.message)||String(e))).finally(()=>setBusy(false)); };
  useEffect(()=>{ if(!st)load(); },[]);   // cached global; explicit retry on failure
  const clusters=(st&&st.clusters)||[];
  const avgBeta=(members)=>{ const bs=(members||[]).map(m=>m&&m.beta).filter(v=>typeof v==="number");
    return bs.length?bs.reduce((a,b)=>a+b,0)/bs.length:null; };
  return <>
    <div className="vhead"><span className="kicker">{L("st_kicker")}</span><h1>{L("st_h1")}</h1>
      <div className="meta">{L("st_meta")}<br/>{st?`${st.count} SEMBOL · ${clusters.length} KÜME`:"okunuyor…"}<br/>{L("st_snapshot")}</div></div>
    {err&&<div className="panel mute"><div className="pb"><div className="perr">YAPI YÜKLENEMEDİ · {err}</div>
      <div className="pbtns"><button className="cta" onClick={load}>TEKRAR DENE</button></div></div></div>}
    {!st&&!err&&<div className="state"><div className="spin"/><div>küme yapısı hesaplanıyor…</div></div>}
    {st&&<>
      {clusters.length===0&&<div className="state"><div>— küme yok · veri yetersiz</div></div>}
      {clusters.map(c=><div key={c.cluster_id} className="cluster-g">
        <div className="cl-head">
          <span className="cl-id">K{c.cluster_id}</span>
          <span className="cl-name">{c.verified_name&&c.index_verified?c.verified_name:c.label}</span>
          <span className="tag">{c.size} {L("st_symbols")}</span>
          <span className="tag">{L("st_beta_avg")} {avgBeta(c.members)!=null?num(avgBeta(c.members),2):"—"}</span>
          {c.index_verified
            ? <span className="tag good" title={`endeks adayı doğrulandı: ${c.verified_name||"—"}`}>{L("st_verified")}</span>
            : <span className="tag" title="etiket yalnız en büyük üyeden — endeks doğrulanmadı">{L("st_unverified")}</span>}
        </div>
        <div className="cl-members">{(c.members||[]).map(m=>
          <button key={m.s} className="mtile" onClick={()=>onPick&&onPick(m.s)} title={`${m.s} · β ${num(m.beta,2)} · ρ ${num(m.corr_btc,2)}`}>
            <span className="mt-sym">{String(m.s).replace("USDT","")}</span>
            <span className="mt-beta">β {num(m.beta,2)}</span>
          </button>)}</div>
      </div>)}
      {(st.unclustered||[]).length>0&&<div className="cluster-g tail">
        <div className="cl-head"><span className="cl-id">—</span><span className="cl-name">{L("st_unclustered")}</span>
          <span className="tag">{st.unclustered.length} {L("st_symbols")}</span></div>
        <div className="cl-members">{st.unclustered.map(m=>
          <button key={m.s} className="mtile dim" onClick={()=>onPick&&onPick(m.s)} title={`${m.s} · β ${num(m.beta,2)}`}>
            <span className="mt-sym">{String(m.s).replace("USDT","")}</span>
            <span className="mt-beta">β {num(m.beta,2)}</span>
          </button>)}</div>
      </div>}
      {(st.unavailable||[]).length>0&&<div className="gcap">verisi çıkmayan {st.unavailable.length} sembol listede yok —
        ikame edilmedi: {(st.unavailable||[]).slice(0,8).map(s=>s.replace("USDT","")).join(", ")}{(st.unavailable||[]).length>8?"…":""}</div>}
    </>}
  </>;
}

/* ── PULSE STRIP — pinned active symbol (funding countdown) + scrolling tape ──
   Data: GET /api/pulse (≤20 symbols). A failed fetch leaves SGS_PULSE null and
   the strip degrades to honest dashes — nothing is fabricated to keep it alive. */
function PulseStrip({sym,onPick}){
  const [,tick]=useState(0);
  const tapeSyms=(window.SGS_DATA||[]).slice(0,14).map(r=>r.s).filter(s=>s!==sym);
  const want=useRef("");
  useEffect(()=>{ const id=setInterval(()=>tick(t=>t+1),1000); return()=>clearInterval(id); },[]);
  useEffect(()=>{
    const key=[sym,...tapeSyms].slice(0,20).join(",");
    if(!key)return;
    if(want.current===key && window.SGS_PULSE)return;   // same request already alive
    want.current=key;
    window.DIVE.pulse([sym,...tapeSyms].slice(0,20)).catch(()=>{});
    const id=setInterval(()=>window.DIVE.pulse([sym,...tapeSyms].slice(0,20)).catch(()=>{}),30000);
    return()=>clearInterval(id);
  },[sym,tapeSyms.join("|")]);   // eslint-disable-line react-hooks/exhaustive-deps
  const pulse=Array.isArray(window.SGS_PULSE)?window.SGS_PULSE:null;
  const bySym={}; (pulse||[]).forEach(r=>{ if(r&&r.s)bySym[r.s]=r; });
  const pin=sym?bySym[sym]:null;
  const cd=(pin&&pin.next_funding_time_ms)?Math.max(0,(Number(pin.next_funding_time_ms)-Date.now())/1000):null;
  const cdLab=cd==null?"—":`${String(Math.floor(cd/3600)).padStart(2,"0")}:${String(Math.floor((cd%3600)/60)).padStart(2,"0")}:${String(Math.floor(cd%60)).padStart(2,"0")}`;
  return <div className="pulsestrip" data-testid="pulse-strip">
    <button className="pin" onClick={()=>sym&&onPick&&onPick(sym)} title="aktif sembol · klik → panel">
      <span className="dot"/><span className="ps-sym">{(sym||"—").replace("USDT","")}</span>
      <span className="ps-px">{pin&&pin.price!=null?"$"+fmt(pin.price):"—"}</span>
      {pin&&pin.ch!=null&&<span className={"chg "+(pin.ch>=0?"up":"dn")}>{pin.ch>=0?"+":""}{num(pin.ch,2)}%</span>}
      <span className="ps-cd" title="sonraki fonlamaya kalan (geri sayım canlı)">F{cdLab}</span>
      {pin&&pin.oi_delta_pct!=null&&<span className="ps-oi" title="OI Δ ~2s">OI {pin.oi_delta_pct>0?"+":""}{num(pin.oi_delta_pct,1)}%</span>}
      {!pulse&&<span className="ps-off">pulse —</span>}
    </button>
    <div className="tape" aria-label="hacim şeridi">
      <div className="tape-inner">{[...(window.SGS_DATA||[]).slice(0,14),...(window.SGS_DATA||[]).slice(0,14)].map((r,i)=>{
        const p=bySym[r.s];
        return <button key={r.s+"-"+i} className="titem" tabIndex={i>=14?-1:0} aria-hidden={i>=14}
          onClick={()=>onPick&&onPick(r.s)} title={`${r.s} · klik → panel`}>
          <b>{String(r.s).replace("USDT","")}</b>
          <span>{p&&p.price!=null?"$"+fmt(p.price):"$"+fmt(r.price)}</span>
          <span className={"chg "+chgCls((p&&p.ch!=null)?p.ch:r.ch)}>{chgTxt((p&&p.ch!=null)?p.ch:r.ch)}</span>
        </button>;})}</div>
    </div>
  </div>;
}

/* ── header chips — F&G + DVOL; each disappears honestly when unavailable ──── */
function FngChip({onOpen}){
  const macro=window.SGS_MACRO, fng=macro&&macro.fng;
  if(!fng||fng.unavailable!=null||fng.value==null) return null;
  return <button className="hchip" onClick={onOpen} title={fng.cadence==="daily"?"kadans: günlük":"kadans: "+(fng.cadence||"—")}>
    <span className="hc-k">F&amp;G</span><span className="hc-v">{fng.value}</span>
    <span className="hc-l">{String(fng.classification||"—").toUpperCase()}</span><small>günlük</small>
  </button>;
}
function DvolChip(){
  const o=window.SGS_OPTIONS;
  const pick=["BTC","ETH"].map(k=>o&&o[k]).find(b=>b&&b.unavailable==null&&b.dvol_level!=null);
  if(!pick) return null;   // both unavailable → the chip disappears (honest)
  return <span className="hchip" title={`Deribit DVOL · ${pick.generated_at||""}`}>
    <span className="hc-k">DVOL</span><span className="hc-v">{num(pick.dvol_level,1)}</span>
    {pick.put_call_oi_ratio!=null&&<span className="hc-l">P/C {num(pick.put_call_oi_ratio,2)}</span>}
  </span>;
}
function MacroPopover({onClose}){
  const m=window.SGS_MACRO;
  if(!m) return null;
  const sc=m.stablecoin||{}, dl=m.defillama||{};
  return <div className="macropop" role="dialog" aria-label="makro arka plan">
    <div className="mp-row"><span className="k">STABLE HACİM PAYI</span><span className="v">{sc.stable_volume_share!=null?Math.round(sc.stable_volume_share*100)+"%":"—"}</span></div>
    <div className="mp-row"><span className="k">USDC/USDT</span><span className="v">{sc.usdc_usdt_ratio!=null?num(sc.usdc_usdt_ratio,4):"—"}</span></div>
    <div className="mp-row"><span className="k">STABLE ÇİFT</span><span className="v">{Array.isArray(sc.stable_pairs)?sc.stable_pairs.length:"—"}</span></div>
    <div className="mp-row"><span className="k">STABLE MCAP</span><span className="v">{dl.total_mcap_usd!=null?"$"+window.sgsFmtBig(dl.total_mcap_usd):"—"}</span></div>
    {dl.usdt_share!=null&&<div className="mp-row"><span className="k">USDT PAYI</span><span className="v">{Math.round(dl.usdt_share*100)}%</span></div>}
    {(sc.note||m.generated_at)&&<div className="gcap">{sc.note||""}{m.generated_at?` · ${isoShort(m.generated_at)}`:""}</div>}
    <button className="sbtn" aria-label="makro kapat" onClick={onClose}>✕</button>
  </div>;
}

/* ── COMMAND PALETTE (Ctrl+K) — fuzzy over views / symbols / verbs / themes ── */
function fzScore(q,text){
  if(!q)return 1;
  const t=String(text).toLowerCase(); const s=q.toLowerCase();
  let ti=0,score=0,streak=0;
  for(const ch of s){
    const idx=t.indexOf(ch,ti);
    if(idx<0)return -1;
    score+=1+streak*1.5+(idx===0||/\W|_/.test(t[idx-1]||"")?3:0);
    streak=idx===ti?streak+1:0; ti=idx+1;
  }
  return score - t.length*0.01;
}
function CommandPalette({open,onClose,onGo,onPickSymbol,onScan,onEvidence,onCompare,onTheme,onLang}){
  const [q,setQ]=useState("");
  const [sel,setSel]=useState(0);
  const inputRef=useRef(null);
  useEffect(()=>{ if(open){ setQ(""); setSel(0); setTimeout(()=>inputRef.current&&inputRef.current.focus(),0); } },[open]);
  if(!open)return null;
  const VIEWS=[["scan","pal_scan"],["panel","pal_panel"],["flow","pal_flow"],
    ["sig","pal_sig"],["evidence","pal_evidence"],["compare","pal_compare"],
    ["map","pal_map"],["portfolio","pal_portfolio"],["structure","pal_structure"],
    ["logs","pal_logs"],["settings","pal_settings"]];
  const themes=[["phosphor","tema: phosphor"],["amber","tema: amber"],["ice","tema: ice"],["paper","tema: paper"]];
  const langs=[["tr",L("pal_lang_tr")],["en",L("pal_lang_en")]];
  const verbs=[];
  const m1=q.match(/^tara\s+(\d{1,4})\s*$/i)||q.match(/^derin\s+(\d{1,4})\s*$/i);
  if(m1)verbs.push({label:`→ derin tarama · evren ${m1[1]}`,run:()=>onScan(parseInt(m1[1],10))});
  const m2=q.match(/^kan[ıi]t\s+(\d{1,2}\s*h)\s*$/i);
  if(m2)verbs.push({label:`→ kanıt · ufuk ${m2[1].replace(/\s/g,"")}`,run:()=>onEvidence(m2[1].replace(/\s/g,""))});
  const m3=q.match(/^kiyas\s+(.{1,60})$/i);
  if(m3){ const toks=m3[1].split(/[\s,]+/).map(toUsdtSym).filter(Boolean).slice(0,4);   // bare tickers → XUSDT
    verbs.push({label:`→ kıyas · ${toks.join(" ")}`,run:()=>onCompare(toks)}); }
  const items=[
    ...verbs.map(v=>({kind:"verb",text:v.label,run:v.run})),
    ...VIEWS.map(([id,key])=>({kind:"view",text:L(key),run:()=>onGo(id)})),
    ...(window.SGS_DATA||[]).slice(0,60).map(r=>({kind:"sym",text:`sembol · ${r.s.replace("USDT","")} · ${r.name||""}`,
      run:()=>onPickSymbol(r.s)})),
    ...themes.map(([id,label])=>({kind:"theme",text:label,run:()=>onTheme(id)})),
    ...langs.map(([code,label])=>({kind:"lang",text:label,run:()=>onLang&&onLang(code)})),
  ].map(it=>({...it,score:fzScore(q,it.text)}))
   .filter(it=>it.score>=0)
   .sort((a,b)=>b.score-a.score)
   .slice(0,12);
  const exec=(it)=>{ if(!it)return; onClose(); it.run&&it.run(); };
  const kd=(e)=>{
    if(e.key==="ArrowDown"){e.preventDefault();setSel(s=>Math.min(items.length-1,s+1));}
    else if(e.key==="ArrowUp"){e.preventDefault();setSel(s=>Math.max(0,s-1));}
    else if(e.key==="Enter"){e.preventDefault();exec(items[sel]);}
    else if(e.key==="Escape"){e.preventDefault();onClose();}
  };
  return <div className="palette-wrap" onClick={onClose} role="presentation">
    <div className="palette" role="dialog" aria-label="komut paleti" onClick={e=>e.stopPropagation()}>
      <input ref={inputRef} value={q} placeholder="komut · sembol · görüş · tema… (Ctrl+K)" aria-label="komut girişi"
        onChange={e=>{setQ(e.target.value);setSel(0);}} onKeyDown={kd}/>
      <div className="plist" role="listbox">
        {items.length===0&&<div className="pempty">— eşleşme yok</div>}
        {items.map((it,i)=><button key={it.kind+it.text} role="option" aria-selected={i===sel}
          className={"pitem"+(i===sel?" on":"")}
          onMouseEnter={()=>setSel(i)} onClick={()=>exec(it)}>
          <span className={"pk k-"+it.kind}>{it.kind==="verb"?"EYLEM":it.kind==="view"?"GÖRÜNÜM":it.kind==="sym"?"SEMBOL":it.kind==="lang"?L("pal_kind_lang"):"TEMA"}</span>
          <span className="pt">{it.text}</span>
        </button>)}
      </div>
      <div className="phint">↑↓ gez · Enter çalıştır · Esc kapat · 1-7 görünümler · / arama</div>
    </div>
  </div>;
}

/* ── simple screens ──────────────────────────────────────────────────────── */
function Logs(){
  const logs=window.SGS_LOGS||[];
  return <>
    <div className="vhead"><span className="kicker">{L("log_kicker")}</span><h1>{L("log_h1")}</h1>
      <div className="meta">BINANCE USDT-M · PUBLIC REST/WS</div></div>
    <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("log_recent")}</div>
      <div className="pb" style={{padding:0}}>
        {logs.length===0 ? <div className="reason">{L("log_empty")}</div> :
         <table className="itbl"><tbody>{logs.slice(0,60).map((l,i)=>
           <tr key={i}><td className="nm" style={{color:"var(--dim)"}}>{l.t||l.time||""}</td>
             <td className="rv" style={{textAlign:"left"}}>{l.msg||l.m||l.message||JSON.stringify(l)}</td></tr>)}</tbody></table>}
      </div></div>
  </>;}
function Settings({theme,setTheme,lang,setLang}){
  const T=[["phosphor","Phosphor"],["amber","Amber"],["ice","Ice"],["paper","Paper (light)"]];
  return <>
    <div className="vhead"><span className="kicker">{L("set_kicker")}</span><h1>{L("set_h1")}</h1>
      <div className="meta">DVCRY · v0.3.0</div></div>
    <div className="panel"><div className="ph"><span className="tick">▸</span>{L("set_lang")}</div>
      <div className="pb" style={{display:"flex",gap:10,flexWrap:"wrap"}}>
        {[["tr","TR"],["en","EN"]].map(([code,label])=><button key={code} className="cta" style={{background:code===lang?"var(--accent)":"transparent",
          color:code===lang?"var(--accent-ink)":"var(--dim)",border:"1px solid var(--line2)"}}
          aria-pressed={code===lang} onClick={()=>setLang&&setLang(code)}>{label}</button>)}
      </div></div>
    <div className="panel"><div className="ph"><span className="tick">▸</span>{L("set_theme")}</div>
      <div className="pb" style={{display:"flex",gap:10,flexWrap:"wrap"}}>
        {T.map(([id,label])=><button key={id} className="cta" style={{background:id===theme?"var(--accent)":"transparent",
          color:id===theme?"var(--accent-ink)":"var(--dim)",border:"1px solid var(--line2)"}}
          onClick={()=>setTheme(id)}>{label}</button>)}
      </div></div>
    <div className="panel mute"><div className="ph"><span className="tick">▸</span>{L("set_engine")}</div>
      <div className="pb reason">{L("set_engine_desc")}</div></div>
  </>;}

/* ── shell + data orchestration ──────────────────────────────────────────── */
const NAV=[
  {id:"scan",label:"TARA",icon:"scan"},{id:"panel",label:"PANEL",icon:"panel"},
  {id:"flow",label:"OI·L/S",icon:"oi"},{id:"sig",label:"SİNYAL",icon:"signal"},
  {id:"evidence",label:"KANIT",icon:"ev"},{id:"compare",label:"KIYAS",icon:"compare"},
  {id:"map",label:"HARİTA",icon:"map"},{id:"portfolio",label:"PORTFÖY",icon:"wallet"},
  {id:"structure",label:"YAPI",icon:"structure"},{id:"logs",label:"LOG",icon:"logs"},
];
const THEMES=[["phosphor","#39ff9e"],["amber","#ffb02e"],["ice","#59c6ff"],["paper","#c2410c"]];
const SYMBOL_VIEWS=new Set(["panel","flow","sig"]);

/* Two-way hash routing: view ↔ location.hash. Hash names are the public ones. */
const VIEW_HASH={scan:"scan",panel:"panel",flow:"flow",sig:"signal",evidence:"evidence",
  compare:"compare",map:"map",portfolio:"portfolio",structure:"structure",logs:"log",settings:"settings"};
const HASH_VIEW=Object.fromEntries(Object.entries(VIEW_HASH).map(([v,h])=>[h,v]));
const viewFromHash=()=>{const h=(location.hash||"").replace(/^#\/?/,"").split("/")[0];return HASH_VIEW[h]||null;};
/* 1-7 quick keys (command palette contract); digits skip when typing. */
const KEY_VIEWS=["scan","panel","flow","sig","evidence","logs","settings"];

const STALE_MS=90000;  // data older than this = honest "stale" banner
/* Views whose own market request feeds the freshness clock (and whose Retry
   can refresh them). MAP / STRUCTURE / COMPARE show snapshots instead: they get an
   age caption, not a banner they could never clear. */
const MARKET_VIEWS=new Set(["scan","evidence","panel","flow","sig"]);
/* Theme persistence: storage can be blocked (private mode, site data disabled).
   A blocked read must fall back, never blank the whole app during first render. */
const readTheme=()=>{ try{ return localStorage.getItem("dive_theme")||"phosphor"; }catch(e){ return "phosphor"; } };
const saveTheme=(t)=>{ try{ localStorage.setItem("dive_theme",t); }catch(e){} };
function App(){
  const [view,setView]=useState(()=>viewFromHash()||"scan");
  const [sym,setSym]=useState(null);
  const [theme,setThemeState]=useState(()=>readTheme());
  const [lang,setLangState]=useState(()=>(typeof diveLang==="function")?diveLang():"tr");
  const [q,setQ]=useState("");
  const [clock,setClock]=useState("");
  const [bootErr,setBootErr]=useState(null);
  const [stale,setStale]=useState(null);   // {reason,hidden?} — live-feed honesty only
  const [viewErr,setViewErr]=useState(null); // {view,text}: the current view's own market request failed
  const [evHorizon,setEvHorizon]=useState("4h");
  const [job,setJob]=useState(null);       // tracked async scan {scanId, done, resultErr?, dismissed?}
  const jobRef=useRef(null); jobRef.current=job;   // latest job for callbacks with stable identity
  const [jobProg,setJobProg]=useState(null);
  const [jobErr,setJobErr]=useState(null); // async start failure
  const [cmpSyms,setCmpSyms]=useState([]); // KIYAS selection (2-4)
  const [palOpen,setPalOpen]=useState(false);
  const [macroOpen,setMacroOpen]=useState(false);
  const searchRef=useRef(null);
  const holdRef=useRef(false);             // async owns the TARA table while true
  const pollInFlight=useRef(false);        // a poll tick is skipped while the previous poll() still runs
  const lastOk=useRef(0);
  const [,force]=useState(0);
  const demo=isDemo();
  const setTheme=(t)=>{setThemeState(t);saveTheme(t);document.documentElement.setAttribute("data-theme",t);};
  /* TR/EN language: persist via i18n.js setDiveLang (localStorage "dive_lang")
     and keep local state in sync so every L() call re-renders. */
  const setLang=(code)=>{ setLangState(code); if(typeof setDiveLang==="function") setDiveLang(code); };
  useEffect(()=>{ window.__diveOnLang=()=>{ if(typeof diveLang==="function") setLangState(diveLang()); };
    return()=>{ window.__diveOnLang=null; }; },[]);
  const setRoute=(v)=>{setView(v);try{const h="#/"+(VIEW_HASH[v]||v);if(location.hash!==h)location.hash=h;}catch(e){}};

  useEffect(()=>{document.documentElement.setAttribute("data-theme",theme);},[]);
  useEffect(()=>{window.__diveOnData=()=>force(v=>v+1);return()=>{window.__diveOnData=null;};},[]);
  useEffect(()=>{const id=setInterval(()=>setClock(new Date().toTimeString().slice(0,8)),1000);
    setClock(new Date().toTimeString().slice(0,8));return()=>clearInterval(id);},[]);
  useEffect(()=>{const onHash=()=>{const v=viewFromHash();if(v)setView(cur=>cur===v?cur:v);};
    window.addEventListener("hashchange",onHash);return()=>window.removeEventListener("hashchange",onHash);},[]);

  // macro/options chips: independent failure domains — polled gently, failures
  // leave the chips absent (they never join the stale banner).
  useEffect(()=>{
    window.DIVE.macro?.().catch(()=>{});
    window.DIVE.options?.().catch(()=>{});
    const id=setInterval(()=>{ window.DIVE.macro?.().catch(()=>{}); window.DIVE.options?.().catch(()=>{}); },120000);
    return()=>clearInterval(id);
  },[]);

  // keyboard: Ctrl+K palette · 1-7 views · "/" search focus (never while typing)
  useEffect(()=>{
    const onKey=(e)=>{
      const tag=(e.target&&e.target.tagName)||"";
      const typing= e.target&&(tag==="INPUT"||tag==="TEXTAREA"||tag==="SELECT"||e.target.isContentEditable);
      if((e.ctrlKey||e.metaKey)&&(e.key==="k"||e.key==="K")){ e.preventDefault(); setPalOpen(o=>!o); return; }
      if(typing)return;
      if(e.key==="/"){ e.preventDefault();
        const el=searchRef.current&&searchRef.current.querySelector("input");
        if(el)el.focus(); return; }
      if(e.key>="1"&&e.key<="7"){ const v=KEY_VIEWS[Number(e.key)-1]; if(v)setRoute(v); }
    };
    window.addEventListener("keydown",onKey);
    return()=>window.removeEventListener("keydown",onKey);
  },[]);
  const startCompare=(list)=>{
    const uniq=[...new Set((list||[]).filter(Boolean))].slice(0,4);
    if(uniq.length<2)return;
    setCmpSyms(uniq); setRoute("compare");
  };
  // KIYAS add-sym hook for the compare view's own input
  useEffect(()=>{ window.__diveCompareAdd=(s)=>setCmpSyms(cur=>cur.includes(s)||cur.length>=4?cur:[...cur,s]);
    return()=>{window.__diveCompareAdd=null;}; },[]);

  // boot: universe → first symbol. If the fetch fails we surface an explicit
  // unavailable state — we do NOT fabricate a market to fill the screen.
  const boot=useCallback(async()=>{
    setBootErr(null);
    const r=await bootUniverse(window.DIVE);
    if(r.ok){ setSym(s=>s||r.symbol||"BTCUSDT"); lastOk.current=Date.now(); setStale(null); }
    else{ setBootErr({message:r.error||"unknown error",status:r.status,code:r.code}); }
    force(v=>v+1);
  },[]);
  useEffect(()=>{ boot(); },[boot]);

  useEffect(()=>{ if(sym&&SYMBOL_VIEWS.has(view)) window.DIVE?.symbol?.(sym).catch(()=>{}); },[sym,view]);

  // ── async deep-scan orchestration (survives view switches; the sync path stays
  // the default until "DERİN TARAMA" is pressed) ────────────────────────────────
  const startAsync=useCallback(async(uLimit)=>{
    const cur=jobRef.current;
    if(cur&&!cur.done) return;   // one full-universe run at a time (palette "tara" included)
    setJobErr(null);setJobProg(null);
    holdRef.current=true;
    try{
      const r=await window.DIVE.scanAsync({size:15,universeLimit:uLimit||250});
      setJob({scanId:r.scan_id,done:false});
    }catch(e){   // a failed start is a finished, errored run: the panel shows it and the Scanner unlocks
      holdRef.current=false; setJobErr(failureText(e).text); setJob({scanId:null,done:true});
    }
  },[]);
  /* VAZGEC / back. The server has no cancel route: while the async run is still alive
     we only stop WATCHING (panel hidden, table stays held, no parallel sync scan).
     Once it finishes, the dismissed run releases the table below. */
  const clearJob=useCallback(()=>{
    const cur=jobRef.current;
    if(cur&&!cur.done){ setJob(j=>j?{...j,dismissed:true}:j); return; }
    holdRef.current=false;setJob(null);setJobProg(null);   // back to the default sync auto-scan
    window.DIVE.scan(15,24).catch(()=>{});
  },[]);
  useEffect(()=>{ if(job&&job.dismissed&&job.done) clearJob(); },[job,clearJob]);
  const retryResult=useCallback(async()=>{
    if(!job||!job.scanId)return;
    setJob(j=>j?{...j,resultErr:null}:j);
    try{ await window.DIVE.scanResult(job.scanId); setJob(j=>j?{...j,resultErr:null}:j); }
    catch(e){ setJob(j=>j?{...j,resultErr:failureText(e).text}:j); }
  },[job&&job.scanId]);
  const jobId=job&&job.scanId,jobDone=job&&job.done;
  useEffect(()=>{  // 2 s progress poll; done → load the full result into SGS_SCAN
    if(!jobId||jobDone)return;
    let live=true, misses=0;
    // the poll gave up: say so, and end the run so the Scanner unlocks (Retry / VAZGEC work)
    const giveUp=(msg)=>{ setJobProg(p=>({...(p||{}),status:"error",error:msg})); setJob(j=>j?{...j,done:true}:j); };
    const tick=async()=>{
      try{
        const p=await window.DIVE.scanProgress(jobId);
        if(!live)return;
        misses=0;
        setJobProg(p);
        if(p.status==="done"){
          try{ await window.DIVE.scanResult(jobId); }
          catch(e){ setJob(j=>j?{...j,resultErr:failureText(e).text}:j); }
          setJob(j=>j?{...j,done:true}:j);
        }else if(p.status==="error"){ setJob(j=>j?{...j,done:true}:j); }
      }catch(e){
        if(!live)return;
        const msg=failureText(e).text;
        misses++;
        // 404 = the in-memory scan registry no longer holds this id (backend restarted):
        // definitive. Other failures get a few polls before the run is declared lost.
        if(/→ 404$/.test(msg)) giveUp(L("sp_gone"));
        else if(misses>=3) giveUp(`${L("sp_lost")} · ${msg}`);
      }
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
    let fail=null, ok=false, mktErr=null;   // ok = the view's OWN market request succeeded (only that stamps freshness)
    try{
      if(view==="scan"){ if(!holdRef.current){ await window.DIVE.scan(15,24); ok=true; } }
      else if(view==="evidence"){ if(evHorizon){ await window.DIVE.evidence(evHorizon); ok=true; } }
      else if(SYMBOL_VIEWS.has(view)&&sym){ await window.DIVE.symbol(sym); ok=true; }
    }catch(e){
      const what=view==="scan"?"tarama":view==="evidence"?"kanıt":(sym||"sembol")+" verisi";
      console.debug("[dive] poll failed:", e);   // raw detail for debugging only
      mktErr=failureText(e).text;
      fail=what+": "+mktErr;
    }
    if(view==="flow"){ try{ await window.DIVE.leaders?.(); }
      catch(e){ fail=fail||"liderler: "+failureText(e).text; } }
    // the connection log is not market data: its success says nothing about freshness
    try{ await window.DIVE.logs?.(); }catch(e){ fail=fail||"günlük: "+failureText(e).text; }
    if(mktErr) setViewErr({view,text:mktErr});
    else if(ok) setViewErr(null);
    if(fail) setStale(s=> s ? (s.reason===fail?s:{reason:fail,hidden:false}) : {reason:fail});
    else if(ok){ lastOk.current=Date.now(); setStale(null); setBootErr(null); }
  },[view,sym,evHorizon]);
  useEffect(()=>{ let live=true;
    const run=async()=>{
      if(!live||pollInFlight.current) return;   // previous poll still in flight → skip this tick
      pollInFlight.current=true;
      try{ await poll(); } finally{ pollInFlight.current=false; }
    };
    run(); const id=setInterval(run,view==="scan"?20000:15000);
    return()=>{live=false;clearInterval(id);}; },[poll]);
  // age watchdog: silence > 90s on a view that refreshes its own data is staleness.
  // While an async run owns the TARA table its progress is not a sync feed, so skip it.
  useEffect(()=>{ const id=setInterval(()=>{ if(!lastOk.current||!MARKET_VIEWS.has(view)) return;
    if(view==="scan"&&holdRef.current) return;
    if(Date.now()-lastOk.current>STALE_MS)
      setStale(s=> s||{reason:`${Math.round(STALE_MS/1000)} sn'dir başarılı veri çekimi yok · no successful fetch for ${STALE_MS/1000}s`});
  },5000); return()=>clearInterval(id); },[view]);

  // Retry while the universe never loaded re-runs the boot (the only thing that can fix it)
  const retry=()=>{ if(bootErr) boot(); else poll(); };
  const dismissStale=()=>setStale(s=>s?{...s,hidden:true}:s);
  const submit=(e)=>{e.preventDefault();let s=q.trim().toUpperCase();if(!s)return;if(!s.endsWith("USDT"))s+="USDT";
    setSym(s); if(!SYMBOL_VIEWS.has(view)) setRoute("panel"); setQ("");};
  const pick=(s)=>{setSym(s);setRoute("panel");};

  // A failed boot with nothing to show renders the unavailable state, not a
  // fabricated market. Settings stays reachable so the app is not a dead end.
  const noData=!!bootErr && !demo && !(window.SGS_DATA||[]).length;

  // the stale banner is a market-feed notice: snapshot views (MAP, KIYAS, ...) cannot clear it, so it
  // is held back there and reappears when a market view is opened again
  const bannerOn=!!stale && !stale.hidden && MARKET_VIEWS.has(view);
  const viewFail=viewErr&&viewErr.view===view?viewErr:null;
  let body;
  if(noData && view!=="settings") body=<DataSourceDown error={bootErr} onRetry={boot}/>;
  else if(view==="scan") body=<Scanner onPick={pick} onCompare={startCompare} job={job} prog={jobProg} jobErr={jobErr}
    onAsyncStart={startAsync} onAsyncCancel={clearJob} onRetryResult={retryResult}
    held={holdRef.current} fetchFail={viewFail} onRetry={poll}/>;
  else if(view==="panel") body=<Panel key={sym} sym={sym} evHorizon={evHorizon} fetchFail={viewFail} onRetry={poll}/>;   // per-symbol state resets with the symbol
  else if(view==="flow") body=<Flow sym={sym} onSelect={setSym} fetchFail={viewFail} onRetry={poll}/>;
  else if(view==="sig") body=<Signal onPick={pick}/>;
  else if(view==="evidence") body=<Evidence horizon={evHorizon} setHorizon={setEvHorizon} fetchFail={viewFail} onRetry={poll}/>;
  else if(view==="compare") body=<Compare symbols={cmpSyms} onPick={pick}/>;
  else if(view==="map") body=<MapView onPick={pick} snapAgeMin={lastOk.current?Math.max(0,Math.round((Date.now()-lastOk.current)/60000)):null}/>;
  else if(view==="portfolio") body=<Portfolio/>;
  else if(view==="structure") body=<StructureView onPick={pick}/>;
  else if(view==="logs") body=<Logs/>;
  else body=<Settings theme={theme} setTheme={setTheme} lang={lang} setLang={setLang}/>;

  const fng=window.SGS_MACRO&&window.SGS_MACRO.fng;
  const fngLive=fng&&fng.unavailable==null&&fng.value!=null;

  return <div className={"app"+(demo?" is-demo":"")+(bannerOn?" has-stale":"")}>
    <DemoBanner/>
    <StaleBanner stale={bannerOn?stale:null} onRetry={retry} onDismiss={dismissStale}/>
    <div className="strip">
      {demo
        ? <span className="live demo"><span className="dot"/>DEMO</span>
        : <span className="live"><span className="dot"/>{L("strip_live")}</span>}<span className="seg">│</span>
      <span>BINANCE USDT-M · PERP</span><span className="seg">│</span>
      <span><b>60</b> {L("strip_indicators")}</span><span className="seg">│</span><span><b>12</b> {L("strip_tf")}</span>
      <span className="spacer"/>
      {fngLive&&<FngChip onOpen={()=>setMacroOpen(o=>!o)}/>}
      <DvolChip/>
      {macroOpen&&<MacroPopover onClose={()=>setMacroOpen(false)}/>}
      <span>{view==="scan"?L("strip_scan"):(sym||"—").replace("USDT","")}</span><span className="seg">│</span>
      <form ref={searchRef} onSubmit={submit}><Svg d={ICONS.search}/><input value={q} onChange={e=>setQ(e.target.value)} placeholder={L("search_ph")}/></form>
      <button className="iconbtn cmd" title={L("a11y_palette")} aria-label={L("a11y_palette_open")} onClick={()=>setPalOpen(true)}>
        <Svg d={ICONS.cmd}/><small>K</small></button>
      <span className="clk">{clock}</span><span className="seg">│</span>
      <div className="themes">{THEMES.map(([id,c])=><button key={id} className={id===theme?"on":""} style={{background:c}}
        title={id} aria-label={`Tema: ${id}`} aria-pressed={id===theme} onClick={()=>setTheme(id)}/>)}</div>
      <button className="iconbtn" title={L("a11y_refresh")} aria-label={L("a11y_refresh")} onClick={retry}><Svg d={ICONS.refresh}/></button>
    </div>
    <PulseStrip sym={sym} onPick={(s)=>{setSym(s);}}/>
    <nav className="rail">
      <div className="mark" aria-label="DVCRY"><Mark/><span>DVCRY</span></div>
      {NAV.map(n=><button key={n.id} className={"rail-btn"+(view===n.id?" on":"")}
        aria-current={view===n.id?"page":undefined} onClick={()=>setRoute(n.id)}><Svg d={ICONS[n.icon]}/>{L("nav_"+n.id)}</button>)}
      <div className="grow"/>
      <button className={"rail-btn"+(view==="settings"?" on":"")} aria-current={view==="settings"?"page":undefined}
        onClick={()=>setRoute("settings")}><Svg d={ICONS.gear}/>{L("nav_settings")}</button>
    </nav>
    <main className="stage" key={view}>{body}</main>
    <CommandPalette open={palOpen} onClose={()=>setPalOpen(false)}
      onGo={(v)=>setRoute(v)} onPickSymbol={pick}
      onScan={(limit)=>{setRoute("scan"); startAsync(limit);}}
      onEvidence={(h)=>{ setEvHorizon(h); setRoute("evidence"); }}
      onCompare={startCompare} onTheme={setTheme} onLang={setLang}/>
  </div>;
}

/* Mount only in a real browser document; importing this bundle in a test runner
   must not try to mount. Named pieces are exported for the demo-mode tests. */
const _diveRoot = (typeof document!=="undefined" && document.getElementById) ? document.getElementById("root") : null;
if(_diveRoot) ReactDOM.createRoot(_diveRoot).render(<App/>);
globalThis.DIVE_APP = { App, DemoBanner, DemoMark, DataSourceDown, StaleBanner, Scanner, Flow, Signal,
  Evidence, CandleChart, ScanProgressPanel, bootUniverse, isDemo,
  /* v0.3 surfaces + route map (hash-route registration is pinned by tests) */
  Panel, Compare, MapView, Portfolio, StructureView, PulseStrip, FngChip, DvolChip,
  CommandPalette, HitLabel, ReliabilityDiagram, ReplayPanel, TfMatrix, OpposingEvidence,
  viewFromHash, VIEW_HASH, HASH_VIEW, KEY_VIEWS, fzScore,
  /* fix-wave helpers + screens (pinned by desktop-app-fixes.test.mjs) */
  annPrice, toUsdtSym, readTheme, saveTheme, Logs, Settings,
  failureOf, failureText, FetchFail, SymbolLoading, actionLabel, PlanningStrip };
