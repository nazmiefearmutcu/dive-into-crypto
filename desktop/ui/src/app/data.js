/* ============================================================================
   Dive Into Crypto — Desktop · data adapter
   Pulls REAL data from the local backend (Crypcodile-fed) and exposes it through
   the globals the screens read: SGS_DATA / SGS_DATA_MAP / SGS_GAINERS /
   SGS_LOSERS / SGS_LOGS / sgsFmtPrice / sgsFmtBig. The scan itself is the
   backend's server-computed result (the canonical, Android-parity engine).
   ========================================================================== */

window.SGS_TF = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"];
window.SGS_DATA = [];
window.SGS_DATA_MAP = {};
window.SGS_GAINERS = [];
window.SGS_LOSERS = [];
window.SGS_LOGS = [];
window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 0, universeCount: 0 };
/* Wave-2 globals — populated ONLY on a successful fetch, never on failure
   (same honesty contract as SGS_DATA / SGS_SCAN above). */
window.SGS_EVIDENCE = null;       // GET /api/evidence body (engine self-grading summary)
window.SGS_STRUCTURE = null;      // GET /api/structure body (beta/corr clusters; fetched once, cached)
window.SGS_SCAN_PROGRESS = null;  // GET /api/scan/progress body for the tracked async scan
/* v0.3 globals — same contract: a failed fetch never populates them. */
window.SGS_PULSE = null;          // GET /api/pulse rows (watch-list ticker)
window.SGS_MACRO = null;          // GET /api/macro body (F&G / stablecoin / defillama)
window.SGS_OPTIONS = null;        // GET /api/options body (Deribit DVOL slice)
window.SGS_STABILITY = null;      // GET /api/evidence/stability rows
window.SGS_DECISIONS = null;      // GET /api/evidence/decisions rows (archive reader)
window.SGS_REPLAY = null;         // POST /api/evidence/replay body (counterfactual grid)
window.SGS_IC = null;             // GET /api/evidence/ic body (per-indicator IC)
window.SGS_CLAIMS = null;         // GET /api/claims body {claims, generated_at}

/* ── formatters (unchanged from the prototype) ───────────────────────────── */
function sgsFmtPrice(v) {
  if (v == null || isNaN(v)) return "—";
  if (v >= 1000) return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (v >= 1) return v.toFixed(3);
  return v.toFixed(4);
}
function sgsFmtBig(v) {
  if (v == null || isNaN(v)) return "—";
  if (v >= 1e9) return (v / 1e9).toFixed(2) + "B";
  if (v >= 1e6) return (v / 1e6).toFixed(2) + "M";
  if (v >= 1e3) return (v / 1e3).toFixed(2) + "K";
  return v.toFixed(0);
}
window.sgsFmtPrice = sgsFmtPrice;
window.sgsFmtBig = sgsFmtBig;

/* ── pure indicator math (computed in the UI from the backend's raw candles) ──
   Report-only overlays for the panel candlestick chart. No verdict, weight or
   scan result ever reads these — they only draw. Missing/dirty input yields
   honest nulls, never zeros. */
function sgsEma(values, period) {
  const n = Array.isArray(values) ? values.length : 0;
  const out = new Array(n).fill(null);
  if (!n || !(period >= 1) || n < period) return out;
  let sum = 0;
  for (let i = 0; i < period; i++) {
    const v = Number(values[i]);
    if (!isFinite(v)) return out;            // dirty seed window → all nulls
    sum += v;
  }
  let ema = sum / period;                    // SMA seed
  out[period - 1] = ema;
  const k = 2 / (period + 1);
  for (let i = period; i < n; i++) {
    const v = Number(values[i]);
    if (!isFinite(v)) continue;              // gap stays null, EMA carries on
    ema += k * (v - ema);
    out[i] = ema;
  }
  return out;
}
function sgsBollinger(values, period = 20, mult = 2) {
  const n = Array.isArray(values) ? values.length : 0;
  const out = new Array(n).fill(null);
  if (!n || !(period >= 2)) return out;
  for (let i = period - 1; i < n; i++) {
    let s = 0, ok = true;
    for (let j = i - period + 1; j <= i; j++) {
      const v = Number(values[j]);
      if (!isFinite(v)) { ok = false; break; }
      s += v;
    }
    if (!ok) continue;
    const mid = s / period;
    let vs = 0;
    for (let j = i - period + 1; j <= i; j++) { const d = Number(values[j]) - mid; vs += d * d; }
    const sd = Math.sqrt(vs / period);       // population stddev over the window
    out[i] = { mid, up: mid + mult * sd, lo: mid - mult * sd };
  }
  return out;
}
window.sgsEma = sgsEma;
window.sgsBollinger = sgsBollinger;

/* ── v0.3 shared display + gate math (pure; exported for tests and views) ────
   Mirrors the backend's stats gates (evidence.py): n < 5 → the stat does not
   exist on screen ("—"); n < 20 → values shown but flagged "gated"; proportions
   carry the backend's Wilson 95% interval. */
const GATE_SUPPRESS_N = 5;
const GATE_FLAG_N = 20;

function sgsGateState(stats) {
  const n = stats && stats.n;
  if (n == null || isNaN(Number(n)) || Number(n) < GATE_SUPPRESS_N) return "none";
  if (Number(n) < GATE_FLAG_N) return "gated";
  return "ok";
}
/* "57% [45–89] · n=214" · gated → L("sgs_gate_small") · n<5 → "—"
   Small-value mode: when the point estimate or either Wilson bound is below 1%,
   every number in the label renders with one decimal (each computed from its own
   value) so 0.5% never collapses into "1%" or "0%" via integer rounding. */
function sgsHitLabel(stats) {
  const s = stats || {};
  const gate = sgsGateState(s);
  if (gate === "none") return "—";
  if (gate === "gated") {
    // data.js loads before i18n.js in the bundle → guard L and keep TR verbatim
    return (typeof window.L === "function")
      ? window.L("sgs_gate_small", s.n, GATE_FLAG_N)
      : `yetersiz örnek (n=${s.n} < ${GATE_FLAG_N})`;
  }
  const hr = s.hit_rate == null ? NaN : Number(s.hit_rate);
  if (isNaN(hr)) return "—";
  const lo = s.wilson_lo == null ? null : Number(s.wilson_lo);
  const hi = s.wilson_hi == null ? null : Number(s.wilson_hi);
  const small = [hr, lo, hi].some((v) => v != null && isFinite(v) && Math.abs(v * 100) < 1);
  /* One decimal in small mode (each value from its own number); integer form
     stays for two-digit magnitudes where the decimal adds no signal. */
  const fmt = (v) => {
    const p = v * 100;
    return (small && Math.abs(p) < 10) ? p.toFixed(1) : String(Math.round(p));
  };
  const iv = lo != null && hi != null ? ` [${fmt(lo)}\u2013${fmt(hi)}]` : "";
  return `${fmt(hr)}%${iv} · n=${s.n}`;
}
/* Log-normal expected-move multipliers for the chart cone overlay:
   P·exp(±z·σ_1h·√hours) − 1. Non-finite input → null (never a fake band). */
function sgsConeEnvelope(close, sigma1h, hours, z = 1) {
  const c = Number(close), s = Number(sigma1h), h = Number(hours);
  if (!isFinite(c) || c <= 0 || !isFinite(s) || s < 0 || !isFinite(h) || h < 0) return null;
  const drift = z * s * Math.sqrt(h);
  return { up: Math.exp(drift) - 1, down: Math.exp(-drift) - 1 };
}
/* Replay grid → deterministic ordering/summary for the heatmap table
   (directional cells only; honest null hit-rates dropped, never imputed). */
function sgsReplaySummary(grid, minN = 1) {
  const cells = (Array.isArray(grid) ? grid : [])
    .filter((c) => c && typeof c === "object" && c.hit_rate != null && (c.n_directional || 0) >= minN);
  const sorted = cells.slice().sort((a, b) =>
    (b.hit_rate - a.hit_rate) || ((b.n_directional || 0) - (a.n_directional || 0)));
  return {
    count: cells.length,
    best: sorted[0] || null,
    worst: sorted[sorted.length - 1] || null,
    sorted,
  };
}
/* Preset validation — STRICT. Anything malformed is rejected (null), never
   coerced: an invalid preset must surface as an honest error, not silently
   become a different scan. Shape: {name, universeLimit, sort}. */
const PRESET_SORT_KEYS = ["sym", "verdict", "ch", "conf", "score", "beta"];
function sgsValidatePreset(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const ul = raw.universeLimit;
  if (typeof ul !== "number" || !isFinite(ul) || !Number.isInteger(ul) || ul < 10 || ul > 500) return null;
  let sort = null;
  if (raw.sort != null) {
    if (typeof raw.sort !== "object" || Array.isArray(raw.sort)) return null;
    if (!PRESET_SORT_KEYS.includes(raw.sort.k)) return null;
    if (raw.sort.dir !== 1 && raw.sort.dir !== -1) return null;
    sort = { k: raw.sort.k, dir: raw.sort.dir };
  }
  const name = raw.name;
  if (typeof name !== "string" || !name.trim() || name.length > 24) return null;
  return { name: name.trim(), universeLimit: ul, sort };
}
/* ── local-only portfolio (dive_portfolio_v1) — the device is the ledger ─────
   No exchange connection exists; entries live and die in localStorage. */
const PORTFOLIO_KEY = "dive_portfolio_v1";
function sgsValidatePosition(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  if (typeof raw.s !== "string" || !/^[A-Z0-9]{2,20}$/.test(raw.s)) return null;
  const entry = Number(raw.entry);
  if (!isFinite(entry) || entry <= 0) return null;
  const size = Number(raw.size);
  if (!isFinite(size) || size <= 0) return null;
  const direction = raw.direction === "short" ? "short" : raw.direction === "long" ? "long" : null;
  if (!direction) return null;
  return {
    id: typeof raw.id === "string" && raw.id ? raw.id : `${raw.s}-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`,
    s: raw.s, entry, size, direction,
    note: typeof raw.note === "string" ? raw.note.slice(0, 120) : "",
  };
}
/* Signed P&L% of a position at the current mark; no price → null (never 0). */
function sgsPositionPnl(pos, price) {
  if (!pos || !pos.entry || pos.entry <= 0) return null;
  const p = Number(price);
  if (!isFinite(p) || p <= 0) return null;
  const sign = pos.direction === "short" ? -1 : 1;
  return sign * ((p - pos.entry) / pos.entry) * 100;
}
function sgsPortfolioLoad() {
  try {
    const raw = JSON.parse(localStorage.getItem(PORTFOLIO_KEY) || "[]");
    if (!Array.isArray(raw)) return [];
    return raw.map(sgsValidatePosition).filter(Boolean);   // corrupt rows dropped honestly
  } catch (e) { return []; }
}
function sgsPortfolioSave(rows) {
  try { localStorage.setItem(PORTFOLIO_KEY, JSON.stringify(Array.isArray(rows) ? rows : [])); return true; }
  catch (e) { return false; }
}
window.DIVE_PORTFOLIO = {
  KEY: PORTFOLIO_KEY, load: sgsPortfolioLoad, save: sgsPortfolioSave,
  validate: sgsValidatePosition, pnl: sgsPositionPnl,
};
/* ── local-only chart annotations (per symbol) — user ink, engine-blind ────── */
const ANNOTATIONS_KEY = "dive_annotations_v1";
function sgsAnnotationsLoad(sym) {
  try {
    const all = JSON.parse(localStorage.getItem(ANNOTATIONS_KEY) || "{}");
    const list = all && typeof all === "object" ? all[sym] : null;
    if (!Array.isArray(list)) return [];
    return list.filter((a) => a && isFinite(Number(a.price)) && Number(a.price) > 0)
      .map((a) => ({ price: Number(a.price), note: typeof a.note === "string" ? a.note.slice(0, 120) : "", ts: Number(a.ts) || 0 }));
  } catch (e) { return []; }
}
function sgsAnnotationsSave(sym, list) {
  try {
    const all = JSON.parse(localStorage.getItem(ANNOTATIONS_KEY) || "{}");
    if (!all || typeof all !== "object" || Array.isArray(all)) return false;
    all[sym] = Array.isArray(list) ? list : [];
    localStorage.setItem(ANNOTATIONS_KEY, JSON.stringify(all));
    return true;
  } catch (e) { return false; }
}
window.sgsAnnotationsLoad = sgsAnnotationsLoad;
window.sgsAnnotationsSave = sgsAnnotationsSave;
window.sgsGateState = sgsGateState;
window.sgsHitLabel = sgsHitLabel;
window.sgsConeEnvelope = sgsConeEnvelope;
window.sgsReplaySummary = sgsReplaySummary;
window.sgsValidatePreset = sgsValidatePreset;
window.SGS_PRESET_SORT_KEYS = PRESET_SORT_KEYS;

/* ── backend client (the UI is served by the backend → same origin) ───────── */
const API = "";  // same-origin

async function _get(path) {
  const r = await fetch(API + path, { headers: { Accept: "application/json" } });
  if (!r.ok) throw new Error(`${path} → ${r.status}`);
  return r.json();
}

async function _post(path, body) {
  const init = { method: "POST", headers: { Accept: "application/json" } };
  if (body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const r = await fetch(API + path, init);
  if (!r.ok) {
    let detail = "";
    try { const b = await r.json(); if (b && (b.detail || b.error)) detail = ` · ${b.detail || b.error}`; } catch (e) { /* opaque */ }
    throw new Error(`POST ${path} → ${r.status}${detail}`);
  }
  return r.json();
}

function _notify() { if (typeof window.__diveOnData === "function") window.__diveOnData(); }

function _verdict(regime) {
  return regime === "confirm" ? "CONFIRM" : regime === "adverse" ? "ADVERSE" : "NEUTRAL";
}
function _divReason(row) {
  const wf = (row.divergence && row.divergence.score) || 0;
  const wfs = (wf >= 0 ? "+" : "") + wf.toFixed(1);
  if (row.whaleRegime === "adverse") return `İndikatör yönüne karşı balina akışı (WF ${wfs}).`;
  if (row.whaleRegime === "confirm") return `Balina akışı indikatör yönünü teyit ediyor (WF ${wfs}).`;
  return "Belirgin balina uyumsuzluğu yok.";
}
function _wrapRow(row) {
  return {
    d: row,
    score: row.netNss || 0,
    div: {
      verdict: _verdict(row.whaleRegime),
      adverse: !!row._adverse || row.whaleRegime === "adverse",
      wf: (row.divergence && row.divergence.score) || 0,
      reason: _divReason(row),
    },
  };
}

/* Shared absorption of a scan body (sync /api/scan or async /api/scan/result) —
   identical shaping so both paths render through the same table. */
function _absorbScan(res) {
  const survivors = (res.survivors || []).map(_wrapRow);
  survivors.forEach((x, i) => (x.rank = i + 1));
  const eliminated = (res.eliminated || []).map(_wrapRow);
  [...(res.survivors || []), ...(res.eliminated || [])].forEach((r) => { window.SGS_DATA_MAP[r.s] = r; });
  window.SGS_SCAN = { survivors, eliminated, scanned: res.scanned || 0, universeCount: res.universeCount || 0 };
  _notify();
  return window.SGS_SCAN;
}

let _evSeq = 0;  // guards against an older horizon response overwriting a newer one

const DIVE = {
  async universe(limit = 60) {
    const rows = await _get(`/api/universe?limit=${limit}`);
    window.SGS_DATA = rows;  // lightweight {s,name,price,ch,quote_volume}; full objects fetched per symbol
    rows.forEach((r) => {
      if (!window.SGS_DATA_MAP[r.s]) {
        window.SGS_DATA_MAP[r.s] = { ...r, candles: [], multiTf: [], indicators: [], series: {} };
      }
    });
    _notify();
    return rows;
  },
  async symbol(sym, opts = {}) {
    const p = new URLSearchParams();
    if (opts.tf) p.set("tf", String(opts.tf));
    if (opts.endMs != null) p.set("end_ms", String(opts.endMs));
    const qs = p.toString();
    const obj = await _get(`/api/symbol/${encodeURIComponent(sym)}${qs ? "?" + qs : ""}`);
    /* commit:false → chart-only views (TF matrix, replay scrub, compare) get the
       real payload WITHOUT overwriting the canonical map entry the verdict reads. */
    if (opts.commit !== false && obj && obj.s) { window.SGS_DATA_MAP[obj.s] = obj; _notify(); }
    return obj;
  },
  async scan(size = 10, universeLimit = 30) {
    const res = await _get(`/api/scan?size=${size}&universe_limit=${universeLimit}`);
    return _absorbScan(res);
  },
  /* Async full-universe scan: start it, then watch /scan/progress from the UI. */
  async scanAsync({ size = 15, universeLimit = 250, depthTop = 50 } = {}) {
    return _get(`/api/scan?async=1&size=${size}&universe_limit=${universeLimit}&depth_top=${depthTop}`);
  },
  async scanProgress(scanId) {
    const res = await _get(`/api/scan/progress?scan_id=${encodeURIComponent(scanId)}`);
    window.SGS_SCAN_PROGRESS = res;  // set only on success
    _notify();
    return res;
  },
  async scanResult(scanId) {
    const res = await _get(`/api/scan/result?scan_id=${encodeURIComponent(scanId)}`);
    return _absorbScan(res);
  },
  /* Evidence layer — the engine grades itself (report-only). */
  async evidence(horizon = "4h") {
    const seq = ++_evSeq;
    const res = await _get(`/api/evidence?horizon=${encodeURIComponent(horizon)}`);
    if (seq === _evSeq) { window.SGS_EVIDENCE = res; _notify(); }
    return res;
  },
  async gradeEvidence(horizon = "4h") {
    const res = await _post(`/api/evidence/grade?horizon=${encodeURIComponent(horizon)}`);
    if (res && res.summary) { window.SGS_EVIDENCE = res.summary; _notify(); }
    return res;
  },
  /* Market structure (BTC beta / corr / clusters) — fetched once and cached. */
  async structure(limit = 60) {
    const res = await _get(`/api/structure?limit=${limit}`);
    window.SGS_STRUCTURE = res;
    _notify();
    return res;
  },
  /* ── v0.3 adapters — each writes its global ONLY on a successful fetch ───── */
  /* Watch-list ticker: ≤20 symbols, [{s, price, ch, funding_rate,
     next_funding_time_ms, oi_delta_pct}]. Symbols may be passed with or
     without the USDT suffix (the backend normalizes). */
  async pulse(symbols = []) {
    const res = await _get(`/api/pulse?symbols=${encodeURIComponent(symbols.slice(0, 20).join(","))}`);
    window.SGS_PULSE = res;
    _notify();
    return res;
  },
  /* Sentiment/macro backdrop (F&G, stablecoin share, defillama aggregate). */
  async macro() {
    const res = await _get(`/api/macro`);
    window.SGS_MACRO = res;
    _notify();
    return res;
  },
  /* Deribit staged slice — BTC/ETH blocks may each be {unavailable} honestly. */
  async options() {
    const res = await _get(`/api/options`);
    window.SGS_OPTIONS = res;
    _notify();
    return res;
  },
  /* Per-symbol verdict self-agreement over the last 8 archived records. */
  async stability() {
    const res = await _get(`/api/evidence/stability`);
    window.SGS_STABILITY = res;
    _notify();
    return res;
  },
  /* Archive reader: archived verdicts, optionally per symbol in a ts window. */
  async decisions({ symbol, fromMs, toMs, limit } = {}) {
    const p = new URLSearchParams();
    if (symbol) p.set("symbol", symbol);
    if (fromMs != null) p.set("from_ms", String(fromMs));
    if (toMs != null) p.set("to_ms", String(toMs));
    if (limit != null) p.set("limit", String(limit));
    const qs = p.toString();
    const res = await _get(`/api/evidence/decisions${qs ? "?" + qs : ""}`);
    window.SGS_DECISIONS = res;
    _notify();
    return res;
  },
  /* Counterfactual threshold grid — report-only ({report_only:true} body). */
  async replay(horizon = "4h") {
    const res = await _post(`/api/evidence/replay?horizon=${encodeURIComponent(horizon)}`);
    window.SGS_REPLAY = res;
    _notify();
    return res;
  },
  /* Per-indicator Spearman IC table (report-only). */
  async ic(horizon = "4h") {
    const res = await _get(`/api/evidence/ic?horizon=${encodeURIComponent(horizon)}`);
    window.SGS_IC = res;
    _notify();
    return res;
  },
  /* IC-based weight suggestions — written to runtime/weight_suggestions.json,
     NEVER read by the engine. No global: the response is a one-shot report. */
  async suggestWeights(horizon = "4h") {
    return _post(`/api/evidence/suggest-weights?horizon=${encodeURIComponent(horizon)}`);
  },
  /* Registered claims + current evaluation (PENDING/CONFIRMED/REFUTED). */
  async claims() {
    const res = await _get(`/api/claims`);
    window.SGS_CLAIMS = res;
    _notify();
    return res;
  },
  /* Register a NEW claim (immutable registry server-side; 409/422 → throw). */
  async registerClaim(claim) {
    const res = await _post(`/api/claims`, claim);
    try { await DIVE.claims(); } catch (e) { /* list refresh failure surfaces on next poll */ }
    return res;
  },
  async leaders() {
    const res = await _get(`/api/leaders`);
    const fill = (r) => ({ ...r, candles: r.candles || [] });
    window.SGS_GAINERS = (res.gainers || []).map(fill);
    window.SGS_LOSERS = (res.losers || []).map(fill);
    _notify();
    return res;
  },
  async logs() {
    window.SGS_LOGS = await _get(`/api/logs`);
    _notify();
    return window.SGS_LOGS;
  },
  async health() { return _get(`/api/health`); },
};
window.DIVE = DIVE;
