/* ============================================================================
   Dive Into Crypto — Desktop · data adapter
   Replaces the old mock generator. Pulls REAL data from the local backend
   (Crypcodile-fed) and exposes it through the same globals the screens read:
   SGS_DATA / SGS_DATA_MAP / SGS_GAINERS / SGS_LOSERS / SGS_LOGS / sgsFmtPrice /
   sgsFmtBig, plus a thin SGS_QUANT shim whose runScan() returns the backend's
   server-computed scan (the canonical, Android-parity engine — not a client copy).
   ========================================================================== */

window.SGS_TF = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"];
window.SGS_DATA = [];
window.SGS_DATA_MAP = {};
window.SGS_GAINERS = [];
window.SGS_LOSERS = [];
window.SGS_LOGS = [];
window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 0, universeCount: 0 };

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

/* ── SGS_QUANT shim — the heavy lifting is server-side (canonical engine) ──── */
window.SGS_QUANT = {
  // Return a NEW array reference each tick so TaramaScreen's useMemo([ticked])
  // re-runs and picks up the latest server scan (no client perturbation).
  liveTick: (data) => (Array.isArray(data) ? data.slice() : data),
  runScan: () => window.SGS_SCAN,      // server-computed scan result (set by DIVE.scan)
};

/* ── backend client (the UI is served by the backend → same origin) ───────── */
const API = "";  // same-origin

async function _get(path) {
  const r = await fetch(API + path, { headers: { Accept: "application/json" } });
  if (!r.ok) throw new Error(`${path} → ${r.status}`);
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
    const survivors = (res.survivors || []).map(_wrapRow);
    survivors.forEach((x, i) => (x.rank = i + 1));
    const eliminated = (res.eliminated || []).map(_wrapRow);
    [...(res.survivors || []), ...(res.eliminated || [])].forEach((r) => { window.SGS_DATA_MAP[r.s] = r; });
    window.SGS_SCAN = { survivors, eliminated, scanned: res.scanned || 0, universeCount: res.universeCount || 0 };
    _notify();
    return window.SGS_SCAN;
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
