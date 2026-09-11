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

/* ── backend client (the UI is served by the backend → same origin) ───────── */
const API = "";  // same-origin

async function _get(path) {
  const r = await fetch(API + path, { headers: { Accept: "application/json" } });
  if (!r.ok) throw new Error(`${path} → ${r.status}`);
  return r.json();
}

async function _post(path) {
  const r = await fetch(API + path, { method: "POST", headers: { Accept: "application/json" } });
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
  async symbol(sym) {
    const obj = await _get(`/api/symbol/${sym}`);
    if (obj && obj.s) { window.SGS_DATA_MAP[obj.s] = obj; _notify(); }
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
