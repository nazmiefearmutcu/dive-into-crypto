/* ============================================================================
   Tests for the v0.3 (backend 0.3.0) UI wave:

     1. Wilson hit-rate display formatter (sgsHitLabel) — "57% [45–89] · n=214",
        gated → "yetersiz örnek (n=9 < 20)", n<5 → "—".
     2. Gate-state logic (sgsGateState) mirroring the backend's stats gates.
     3. Cone envelope path math (sgsConeEnvelope) — log-normal expected move.
     4. Replay grid sort/aggregate (sgsReplaySummary).
     5. Preset JSON validation (sgsValidatePreset) — invalid rejected, never coerced.
     6. Portfolio P&L math + localStorage adapter (dive_portfolio_v1).
     7. Hash routes registered (#/compare, #/map, #/portfolio, #/structure …).
     8. New data.js adapters follow the honesty contract: a failed fetch NEVER
        populates its SGS_* global; claims POST carries a JSON body.

   data.js is imported directly; the bundle is compiled through the same esbuild
   pipeline as demo-mode.test.mjs. Runs under `node --test`.
   ========================================================================== */
import { test } from "node:test";
import assert from "node:assert/strict";
import esbuild from "esbuild";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const UI_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const APP = join(UI_ROOT, "src", "app");

/* ── shared loader: data.js against a window alias (real file, no re-impl) ── */
async function loadData(store = new Map()) {
  globalThis.window = globalThis;
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
  };
  await import(pathToFileURL(join(APP, "data.js")).href);
  return globalThis.window;
}

/* ── 1. Wilson display formatter ─────────────────────────────────────────── */
test("sgsHitLabel renders the Wilson interval, gated and suppressed states", async () => {
  await loadData();
  // healthy: "57% [45–89] · n=214"
  assert.equal(
    window.sgsHitLabel({ n: 214, hit_rate: 0.57, wilson_lo: 0.45, wilson_hi: 0.89 }),
    "57% [45–89] · n=214",
  );
  // rounding: lo/hi round to integer percents independently
  assert.equal(
    window.sgsHitLabel({ n: 60, hit_rate: 0.5714, wilson_lo: 0.4524, wilson_hi: 0.6871 }),
    "57% [45–69] · n=60",
  );
  // small-value mode: point or bound < 1% → one decimal everywhere, so 0.5%
  // never collapses into "1%" (or 0.4% into "0%") via Math.round
  assert.equal(
    window.sgsHitLabel({ n: 200, hit_rate: 0.005, wilson_lo: 0.001, wilson_hi: 0.032 }),
    "0.5% [0.1–3.2] · n=200",
  );
  assert.equal(
    window.sgsHitLabel({ n: 150, hit_rate: 0.004, wilson_lo: 0.001, wilson_hi: 0.021 }),
    "0.4% [0.1–2.1] · n=150",
  );
  assert.equal(window.sgsHitLabel({ n: 200, hit_rate: 0.005 }), "0.5% · n=200");
  // a small BOUND alone also triggers one-decimal mode (bounds formatted
  // independently from their own values)
  assert.equal(
    window.sgsHitLabel({ n: 30, hit_rate: 0.40, wilson_lo: 0.001, wilson_hi: 0.70 }),
    "40% [0.1–70] · n=30",
  );
  // gated: no numbers, an honest small-sample warning
  assert.equal(
    window.sgsHitLabel({ n: 9, hit_rate: 0.6, wilson_lo: 0.3, wilson_hi: 0.85 }),
    "yetersiz örnek (n=9 < 20)",
  );
  // suppressed: n<5 or no stats at all → "—", never a fake percentage
  assert.equal(window.sgsHitLabel({ n: 4 }), "—");
  assert.equal(window.sgsHitLabel({ n: 0 }), "—");
  assert.equal(window.sgsHitLabel(null), "—");
  assert.equal(window.sgsHitLabel({ n: 30, hit_rate: null }), "—");
});

/* ── 2. gate-state logic mirrors the backend gates ───────────────────────── */
test("sgsGateState: none <5, gated <20, ok at 20+", async () => {
  await loadData();
  assert.equal(window.sgsGateState({ n: 4 }), "none");
  assert.equal(window.sgsGateState({ n: 5 }), "gated");
  assert.equal(window.sgsGateState({ n: 19 }), "gated");
  assert.equal(window.sgsGateState({ n: 20 }), "ok");
  assert.equal(window.sgsGateState({ n: 214 }), "ok");
  assert.equal(window.sgsGateState(null), "none");
  assert.equal(window.sgsGateState({}), "none");
});

/* ── 3. cone envelope math (chart overlay projection) ────────────────────── */
test("sgsConeEnvelope computes the log-normal expected-move band symmetrically", async () => {
  await loadData();
  const env = window.sgsConeEnvelope(100, 0.01, 24, 1);
  const drift = 0.01 * Math.sqrt(24);
  assert.ok(Math.abs(env.up - (Math.exp(drift) - 1)) < 1e-12, "upper bound = e^(+z·σ√h) − 1");
  assert.ok(Math.abs(env.down - (Math.exp(-drift) - 1)) < 1e-12, "lower bound = e^(−z·σ√h) − 1");
  // 2σ doubles the drift (not the exponent argument linearly — verify exactly)
  const env2 = window.sgsConeEnvelope(100, 0.01, 24, 2);
  assert.ok(Math.abs(env2.up - (Math.exp(2 * drift) - 1)) < 1e-12);
  // zero sigma → a degenerate but honest band on the last close
  const flat = window.sgsConeEnvelope(100, 0, 24, 1);
  assert.equal(flat.up, 0);
  assert.equal(flat.down, 0);
  // dirty input → null (never a fabricated band)
  assert.equal(window.sgsConeEnvelope(0, 0.01, 24), null);
  assert.equal(window.sgsConeEnvelope(100, -1, 24), null);
  assert.equal(window.sgsConeEnvelope(100, 0.01, NaN), null);
  assert.equal(window.sgsConeEnvelope(null, 0.01, 24), null);
});

/* ── 4. replay grid sort/aggregate ───────────────────────────────────────── */
test("sgsReplaySummary sorts directional cells by hit-rate and filters honestly", async () => {
  await loadData();
  const grid = [
    { buy: 1.1, strong: 2.5, conflict: 1.5, hit_rate: 0.62, n_directional: 40 },
    { buy: 1.1, strong: 2.5, conflict: 2, hit_rate: null, n_directional: 30 },   // no direction → dropped
    { buy: 0.9, strong: 2.5, conflict: 1.5, hit_rate: 0.55, n_directional: 40 },
    { buy: 1.2, strong: 3, conflict: 2, hit_rate: 0.62, n_directional: 10 },     // tie on rate, lower n
    { buy: 0.8, strong: 2, conflict: 1, hit_rate: 0.40, n_directional: 5 },      // below minN in strict mode
  ];
  const all = window.sgsReplaySummary(grid, 0);
  assert.equal(all.count, 4, "null hit-rate cell is dropped, never imputed");
  assert.equal(all.best.hit_rate, 0.62);
  assert.equal(all.best.n_directional, 40, "ties break by n_directional desc");
  assert.equal(all.worst.hit_rate, 0.40);
  const strict = window.sgsReplaySummary(grid, 10);
  assert.equal(strict.count, 3, "cells below minN are excluded");
  assert.equal(window.sgsReplaySummary(null).count, 0);
  assert.equal(window.sgsReplaySummary([]).best, null);
});

/* ── 5. preset validation — strict, invalid rejected ─────────────────────── */
test("sgsValidatePreset accepts the exact shape and rejects everything else", async () => {
  await loadData();
  const ok = { name: "tümü-250", universeLimit: 250, sort: { k: "score", dir: -1 } };
  assert.deepEqual(window.sgsValidatePreset(ok), { name: "tümü-250", universeLimit: 250, sort: { k: "score", dir: -1 } });
  assert.deepEqual(window.sgsValidatePreset({ name: "x", universeLimit: 24, sort: null }),
    { name: "x", universeLimit: 24, sort: null });
  // invalids → null, never silently coerced
  assert.equal(window.sgsValidatePreset(null), null);
  assert.equal(window.sgsValidatePreset([ok]), null, "arrays are not presets");
  assert.equal(window.sgsValidatePreset("preset"), null);
  assert.equal(window.sgsValidatePreset({ ...ok, universeLimit: 5 }), null, "below floor");
  assert.equal(window.sgsValidatePreset({ ...ok, universeLimit: 501 }), null, "above ceiling");
  assert.equal(window.sgsValidatePreset({ ...ok, universeLimit: 24.5 }), null, "must be an integer");
  assert.equal(window.sgsValidatePreset({ ...ok, universeLimit: "250" }), null, "no numeric strings");
  assert.equal(window.sgsValidatePreset({ ...ok, sort: { k: "hack", dir: -1 } }), null, "unknown sort key");
  assert.equal(window.sgsValidatePreset({ ...ok, sort: { k: "score", dir: 0 } }), null, "dir must be ±1");
  assert.equal(window.sgsValidatePreset({ ...ok, name: "" }), null);
  assert.equal(window.sgsValidatePreset({ ...ok, name: 42 }), null);
  assert.equal(window.sgsValidatePreset({ ...ok, name: "x".repeat(25) }), null, "name cap 24");
});

/* ── 6. portfolio P&L math + localStorage adapter ────────────────────────── */
test("portfolio: signed P&L math, strict row validation, corrupt storage dropped", async () => {
  const store = new Map();
  await loadData(store);
  const P = window.DIVE_PORTFOLIO;
  // P&L: long gains when price rises, short when it falls; missing price → null
  assert.ok(Math.abs(P.pnl({ s: "BTCUSDT", entry: 100, size: 1, direction: "long" }, 110) - 10) < 1e-9);
  assert.ok(Math.abs(P.pnl({ s: "BTCUSDT", entry: 100, size: 2, direction: "short" }, 90) - 10) < 1e-9,
    "short P&L% is size-independent by design");
  assert.ok(Math.abs(P.pnl({ s: "BTCUSDT", entry: 100, size: 1, direction: "short" }, 110) + 10) < 1e-9);
  assert.equal(P.pnl({ s: "BTCUSDT", entry: 100, size: 1, direction: "long" }, null), null, "no mark → no fake P&L");
  assert.equal(P.pnl({ s: "BTCUSDT", entry: 100, size: 1, direction: "long" }, 0), null);
  // validation: every field must be real
  assert.equal(P.validate({ s: "BTCUSDT", entry: 100, size: 1, direction: "long" })?.s, "BTCUSDT");
  assert.equal(P.validate({ s: "btc", entry: 100, size: 1, direction: "long" }), null, "uppercase symbol only");
  assert.equal(P.validate({ s: "BTCUSDT", entry: -1, size: 1, direction: "long" }), null);
  assert.equal(P.validate({ s: "BTCUSDT", entry: 100, size: 0, direction: "long" }), null);
  assert.equal(P.validate({ s: "BTCUSDT", entry: 100, size: 1, direction: " sideways" }), null);
  // storage roundtrip + corrupt entries dropped honestly (never thrown, never kept)
  store.set(P.KEY, JSON.stringify([
    { s: "BTCUSDT", entry: 100, size: 0.5, direction: "long", note: "kor", id: "a1" },
    { garbage: true },
    { s: "ETHUSDT", entry: "x", size: 1, direction: "long" },
  ]));
  const rows = P.load();
  assert.equal(rows.length, 1, "corrupt rows are dropped, not repaired");
  assert.equal(rows[0].id, "a1");
  store.set(P.KEY, "not json at all{{{");
  assert.deepEqual(P.load(), [], "unparseable storage → empty ledger, not a crash");
  // save → load roundtrip
  assert.equal(P.save([{ s: "SOLUSDT", entry: 171.4, size: 3, direction: "short", note: "" }]), true);
  assert.equal(JSON.parse(store.get(P.KEY))[0].s, "SOLUSDT");
});

/* ── 7. hash routes registered for every new view ────────────────────────── */
async function loadBundle() {
  const FILES = ["data.js", "mock.js", "i18n.js", "desktop-app.jsx"];
  const prelude =
    "import React from 'react';\n" +
    "const ReactDOM = { createRoot: () => ({ render(){} }) };\n" +
    "globalThis.React = React; globalThis.ReactDOM = ReactDOM;\n";
  const contents =
    prelude +
    FILES.map((f) => `\n/* ==== ${f} ==== */\n` + readFileSync(join(APP, f), "utf8")).join("\n");
  const out = await esbuild.build({
    stdin: { contents, loader: "jsx", resolveDir: UI_ROOT },
    bundle: true, format: "esm", write: false, target: ["es2020"],
    jsx: "transform", jsxFactory: "React.createElement", jsxFragment: "React.Fragment",
    external: ["react", "react-dom"], logLevel: "silent",
  });
  globalThis.window = globalThis;
  globalThis.document = { documentElement: { setAttribute() {}, getAttribute: () => null }, getElementById: () => null };
  globalThis.location = { hash: "" };
  globalThis.localStorage = { getItem: () => null, setItem: () => {} };
  delete globalThis.DIVE_APP;
  const dir = join(UI_ROOT, "test", ".tmp");
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `v3-${Date.now()}.mjs`);
  writeFileSync(file, out.outputFiles[0].text);
  await import(pathToFileURL(file).href);
  return globalThis.DIVE_APP;
}
test("hash routes exist for the new views and viewFromHash resolves both ways", async () => {
  const app = await loadBundle();
  for (const hash of ["compare", "map", "portfolio", "structure", "scan", "panel", "flow", "signal", "evidence", "log", "settings"])
    assert.ok(app.VIEW_HASH[hash] || Object.values(app.VIEW_HASH).includes(hash), `route ${hash} must be registered`);
  for (const [hash, view] of [["compare", "compare"], ["map", "map"], ["portfolio", "portfolio"], ["structure", "structure"]]) {
    globalThis.location.hash = `#/${hash}`;
    assert.equal(app.viewFromHash(), view, `#/${hash} must resolve to ${view}`);
  }
  globalThis.location.hash = "#/nonsense";
  assert.equal(app.viewFromHash(), null, "unknown hashes resolve to no view (default stays)");
  // rail quick keys cover the 7 canonical views
  assert.deepEqual(app.KEY_VIEWS, ["scan", "panel", "flow", "sig", "evidence", "logs", "settings"]);
  // the fuzzy scorer matches subsequences and rejects misses
  assert.ok(app.fzScore("btc", "sembol · BTC · BITCOIN") > 0);
  assert.equal(app.fzScore("zzz", "sembol · BTC"), -1);
});

/* ── 9. smoke renders — the new views mount against contract-shaped payloads
      and the honest states are visible in the markup ────────────────────────── */
function mkCandles(n = 30) {
  return Array.from({ length: n }, (_, i) => ({ t: 1726000000000 + i * 3600e3, o: 100 + i, h: 102 + i, l: 99 + i, c: 101 + i, v: 10 + i }));
}
function mkSymbol(over = {}) {
  return {
    s: "BTCUSDT", name: "BITCOIN", price: 68240.5, ch: 2.14, finalSignal: "STRONG_BUY", confidence: 84,
    risk: "DÜŞÜK", netNss: 1820, quantBias: 42.1, whaleRegime: "confirm",
    multiTf: ["1h", "4h", "1d"].map((tf) => ({ tf, signal: "BUY", confidence: 70 })),
    indicators: [
      { name: "rsi", signal: "BUY", score: 0.5, weight: 1.2, weighted_score: 0.6, value: 62 },
      { name: "macd", signal: "SELL", score: -0.4, weight: 1, weighted_score: -0.4, value: -3.2 },
    ],
    divergence: { score: 58, tf: "4h", coverage: 3 }, divergence_tier: "MODERATE",
    microstructure: { score: 53, active: 2, label: "BUY", signals: [] },
    regime: { regime: "TREND", adx: 31, chop: 29, adaptive_score: 0.9 },
    mtfConfluence: { score: 71, direction: 1, gate: true, htf_agree: 0.86, label: "STRONG" },
    candles: mkCandles(), ...over,
  };
}
const OK_STATS = { n: 214, hit_rate: 0.57, avg_forward: 0.004, median_forward: 0.003, wilson_lo: 0.45, wilson_hi: 0.89, gated: false };
const GATED_STATS = { n: 9, hit_rate: 0.6, avg_forward: 0.002, median_forward: 0.001, wilson_lo: 0.3, wilson_hi: 0.85, gated: true };
const NONE_STATS = { n: 3 };
const CAL_BIN = (b, mc, hr, n) => ({ bucket: b, mean_conf: mc, hit_rate: hr, wilson_lo: hr == null ? null : hr - 0.05, wilson_hi: hr == null ? null : hr + 0.05, n });
const mkEvidence = () => ({
  horizon: "4h", archived_count: 30, gradable_count: 30, graded_count: 30, coverage: 1, stale: false,
  by_verdict: { LONG: OK_STATS, SHORT: GATED_STATS, NEUTRAL: NONE_STATS },
  by_confidence: [CAL_BIN("0-25", null, null, 0), CAL_BIN("25-50", 35.2, 0.4, 214), CAL_BIN("50-75", null, null, 0), CAL_BIN("75-100", null, null, 0)],
  by_indicator: { rsi: { n: 214, agree: OK_STATS, disagree: GATED_STATS }, macd: { n: 60, agree: OK_STATS, disagree: GATED_STATS } },
  calibration: { ece: 0.0431, bins: [CAL_BIN("0-25", null, null, 0), CAL_BIN("25-50", 35.2, 0.4, 214), CAL_BIN("50-75", null, null, 0), CAL_BIN("75-100", null, null, 0)] },
  brier: { score: 0.213, ref: 0.247, skill: 0.14, n: 214 },
  baselines: { coin_mean: 0, coin_sd: 0.012, p_value: 0.013, observed_mean: 0.004,
    momentum_hit_rate: 0.54, momentum_n: 214, bnh_forward_mean: 0.0031, permutations: 1000, seed: 42 },
  windows: {
    "7d": { LONG: OK_STATS, SHORT: GATED_STATS, NEUTRAL: NONE_STATS },
    "30d": { LONG: OK_STATS, SHORT: GATED_STATS, NEUTRAL: NONE_STATS },
    all: { LONG: OK_STATS, SHORT: GATED_STATS, NEUTRAL: NONE_STATS },
  },
  by_regime: { note: "multiple comparisons — descriptive only, not a tested hypothesis", buckets: { TREND: OK_STATS, RANGE: GATED_STATS } },
  by_session: { note: "multiple comparisons — descriptive only, not a tested hypothesis", buckets: { "0-8": OK_STATS } },
  by_funding_proximity: { note: "multiple comparisons — descriptive only, not a tested hypothesis", buckets: { within_1h: OK_STATS } },
  by_divergence_tier: { note: "multiple comparisons — descriptive only, not a tested hypothesis", buckets: { MODERATE: OK_STATS } },
  provenance: {
    first_ts: "2026-08-01T00:00:00Z", last_ts: "2026-09-12T00:00:00Z", graded_count: 214, failed_grades: 3,
    engine_version: "0.3.0", window_note: "archive 2026-08-01 → 2026-09-12; horizon 4h; slices are descriptive (multiple comparisons)",
  },
  generated_at: "t", engine_version: "0.3.0",
});

test("KANIT v2 renders Wilson labels, gated warnings and every v2 panel", async () => {
  const app = await loadBundle();
  const { renderToStaticMarkup } = await import("react-dom/server");
  window.SGS_EVIDENCE = mkEvidence();
  const html = renderToStaticMarkup(React.createElement(app.Evidence, { horizon: "4h", setHorizon: () => {} }));
  assert.ok(html.includes("57% [45–89] · n=214"), `healthy Wilson label expected, got: ${html.slice(0, 400)}`);
  assert.ok(html.includes("yetersiz örnek (n=9 &lt; 20)") || html.includes("yetersiz örnek (n=9 < 20)"),
    "gated warning must be visible");
  assert.ok(html.includes("PROVENANCE"), "provenance line");
  assert.ok(html.includes("GÜVENİLİRLİK DİYAGRAMI"), "reliability diagram panel");
  assert.ok(html.includes("0.0431"), "ECE badge value");
  assert.ok(html.includes("BRIER"), "brier panel");
  assert.ok(html.includes("TABANLAR · BASELINES"), "baselines panel");
  assert.ok(html.includes("1000 permütasyon"), "permutation caption");
  // baselines contract keys: B&H row reads bnh_forward_mean (renders a value,
  // never "—" when the backend ships it) and momentum_hit_rate renders too
  assert.ok(/B&(amp;)?H İLERİ<\/span><b[^>]*>\+0\.31%<\/b>/.test(html),
    "B&H row renders the bnh_forward_mean value (dead buyhold_forward key fixed)");
  assert.ok(/MOMENTUM TABANI<\/span><b[^>]*>54%<\/b>/.test(html), "momentum hit-rate renders from the contract key");
  // axis labels: magnitude + explicit sign prefix, never a double sign (−+0.30%)
  assert.ok(html.includes("−3.60%") && html.includes("+3.60%"),
    "baselines axis ends render −span/+span with a single sign each");
  // legacy-key fallback: an older payload shipping only buyhold_forward still renders
  const legacy = mkEvidence();
  legacy.baselines = { ...legacy.baselines, bnh_forward_mean: null, buyhold_forward: 0.0031 };
  window.SGS_EVIDENCE = legacy;
  const legacyHtml = renderToStaticMarkup(React.createElement(app.Evidence, { horizon: "4h", setHorizon: () => {} }));
  assert.ok(/B&(amp;)?H İLERİ<\/span><b[^>]*>\+0\.31%<\/b>/.test(legacyHtml), "legacy buyhold_forward key still renders");
  window.SGS_EVIDENCE = mkEvidence();
  assert.ok(html.includes("KAYITLI İDDİALAR"), "claims panel");
  assert.ok(html.includes("İDDİA KAYDET"), "claim registration form");
  assert.ok(html.includes("KARARLILIK"), "stability panel");
  assert.ok(html.includes("İNDİKATÖR IC"), "IC panel");
  assert.ok(html.includes("AĞIRLIK ÖNERİSİ ÜRET"), "suggest-weights button");
  assert.ok(html.includes("asla otomatik uygulanmaz"), "report-only disclaimer");
  assert.ok(html.includes("REPLAY · TERSİNE-EŞİK IZGARASI"), "replay panel");
  assert.ok(html.includes("DİLİMLER"), "slice tabs");
  assert.ok(html.includes("multiple comparisons"), "slice multiple-comparisons note");
  assert.ok(html.includes("7G") && html.includes("30G") && html.includes("TÜMÜ"), "windows strip chips");
  // by_indicator disagree stats render through the gated formatter too
  assert.ok(html.includes("KARŞIT İSABET"), "disagree column present");
  // empty-archive honesty path still renders
  window.SGS_EVIDENCE = { archived_count: 0 };
  const empty = renderToStaticMarkup(React.createElement(app.Evidence, { horizon: "4h", setHorizon: () => {} }));
  assert.ok(empty.includes("HENÜZ ARŞİV YOK"), "empty archive state");
});

test("reliability diagram: empty-bin dash sits at the bucket center, not its lower bound", async () => {
  const app = await loadBundle();
  const { renderToStaticMarkup } = await import("react-dom/server");
  const html = renderToStaticMarkup(React.createElement(app.ReliabilityDiagram, {
    calibration: { bins: [{ bucket: "75-100", mean_conf: null, hit_rate: null, n: 0 }] },
  }));
  // center of 75–100 = 87.5 → px = 38 + (284−38)·0.875 = 253.25
  // (the old bug plotted at the lower bound: 75 → 222.5 + 4 nudge)
  assert.ok(html.includes('x1="253.25"') && html.includes('x2="253.25"'),
    `dash must sit at the bucket center (x=253.25), got: ${html}`);
  assert.ok(html.includes('class="rd-empty"'), "absence dash keeps its honest-empty class");
});

test("gated-sample label is routed through L(): TR default verbatim, EN translated", async () => {
  const app = await loadBundle();
  const { renderToStaticMarkup } = await import("react-dom/server");
  window.SGS_EVIDENCE = mkEvidence();
  const tr = renderToStaticMarkup(React.createElement(app.Evidence, { horizon: "4h", setHorizon: () => {} }));
  assert.ok(tr.includes("yetersiz örnek (n=9 &lt; 20)") || tr.includes("yetersiz örnek (n=9 < 20)"),
    "TR stays the verbatim default");
  globalThis.localStorage = { getItem: (k) => (k === "dive_lang" ? "en" : null), setItem: () => {} };
  const en = renderToStaticMarkup(React.createElement(app.Evidence, { horizon: "4h", setHorizon: () => {} }));
  assert.ok(en.includes("insufficient sample (n=9 &lt; 20)") || en.includes("insufficient sample (n=9 < 20)"),
    `EN label expected, got: ${en.slice(0, 400)}`);
  globalThis.localStorage = { getItem: () => null, setItem: () => {} };
});

test("PANEL renders the v0.3 depth cards and every unavailable block stays honest", async () => {
  const app = await loadBundle();
  const { renderToStaticMarkup } = await import("react-dom/server");
  window.SGS_EVIDENCE = mkEvidence();
  window.SGS_STABILITY = [{ s: "BTCUSDT", agree_frac: 0.875, k: 8, median_gap_min: 30, last_ts: 1 }];
  window.SGS_DATA_MAP.BTCUSDT = mkSymbol({
    planning: { sl_distance_pct: 1.2, tp_1r_pct: 0.8, tp_2r_pct: 1.6, tp_3r_pct: 2.4, envelope: { h1: 0.8, h4: 1.6, h24: 3.9 }, note: "informational geometry only" },
    cone: { sigma_1h: 0.008, env_24h: { up: 0.048, down: -0.046 }, env_48h: { up: 0.068, down: -0.064 }, percentile: 72.2 },
    funding_lens: { predicted_funding: 0.0001, last_settled: 0.00008, apr: 0.263, seconds_to_funding: 3600, regime: "extreme_long_crowding" },
    basis: { basis_bps: 2.3, zscore: 0.4, ann_funding: 26, label: "premium", curve: { perp: 2.3, cq: 12, nq: null }, partial: true },
    cascade: { score: 63.1, direction: "long_flush", since_min: 120.5, proxy: true },
    book: { imbalance: { "0.5%": 0.2, "1%": -0.1, "2%": 0.05 }, notional_1pct: 1234567, mid: 68240, ts: Date.now() },
    ls_term: { periods: { "5m": { glob: 1.2, acc: 0.9, pos: 1.05, taker: null }, "1h": { glob: 0.8, acc: 1.1, pos: null, taker: 0.95 } }, cells_unavailable: 2 },
    spot_perp: { premium_pct: 0.02, ret_spread_48h: 0.004, lead: "spot" },
  });
  const html = renderToStaticMarkup(React.createElement(app.Panel, { sym: "BTCUSDT", evHorizon: "4h" }));
  for (const marker of ["PLANLAMA", "TP 2R", "KARŞIT KANIT", "macd", "ilişki, nedensellik değildir",
    "FONLAMA", "01:00:00", "BASİS", "PARSİNEL", "KASKAD", "VEKİL/PROXY", "EMİR DEFTERİ", "L/S TERM",
    "SPOT–PERP", "SPOT ÖNDE", "TF-MATRİS", "DİVERJANS ORTA", "KONİ", "%72"])
    assert.ok(html.includes(marker), `panel must render "${marker}"`);
  assert.ok(html.includes("yetersiz örnek (n=9"), "opposing indicator's disagree hit-rate is gated-rendered");

  // every depth block missing → honest dashes, no fabricated numbers
  const poor = mkSymbol({ s: "POORUSDT", planning: { unavailable: "atr_missing" }, finalSignal: "NEUTRAL",
    indicators: [], funding_lens: { unavailable: "x" }, basis: { unavailable: "y" }, book: { unavailable: "book_too_thin" } });
  window.SGS_DATA_MAP.POORUSDT = poor;
  const html2 = renderToStaticMarkup(React.createElement(app.Panel, { sym: "POORUSDT", evHorizon: "4h" }));
  assert.ok(html2.includes("kullanılamıyor · atr_missing"), "planning unavailable");
  assert.ok(html2.includes("FONLAMA"), "funding card present even when unavailable");
  assert.ok(!html2.includes("VEKİL/PROXY"), "no proxy tag without cascade data");
});

test("HARİTA/PORTFÖY/YAPI render honest tiles, ledger and cluster explorer", async () => {
  const app = await loadBundle();
  const { renderToStaticMarkup } = await import("react-dom/server");
  // HARİTA (TARAMA default): tiles from scan survivors
  window.SGS_SCAN = { survivors: [{ d: mkSymbol(), rank: 1 }], eliminated: [], scanned: 60, universeCount: 437 };
  window.SGS_DATA = [{ s: "ETHUSDT", name: "E", price: 3, ch: 1, quote_volume: 9 }];
  const map = renderToStaticMarkup(React.createElement(app.MapView, { onPick: () => {} }));
  assert.ok(map.includes("TARAMA") && map.includes("EVREN"), "mode chips");
  assert.ok(map.includes("BTC"), "survivor tile");
  // PORTFÖY: rows from localStorage render with honest totals
  globalThis.localStorage = {
    _m: new Map(),
    getItem(k) { return this._m.has(k) ? this._m.get(k) : null; },
    setItem(k, v) { this._m.set(k, String(v)); },
  };
  globalThis.localStorage.setItem("dive_portfolio_v1", JSON.stringify([
    { id: "p1", s: "BTCUSDT", entry: 67000, size: 1, direction: "long", note: "" },
    { id: "p2", s: "NOPEUSDT", entry: 5, size: 2, direction: "short", note: "" },
  ]));
  window.SGS_DATA_MAP.BTCUSDT = mkSymbol();   // live price for BTC only → NOPE excluded from totals
  const pf = renderToStaticMarkup(React.createElement(app.Portfolio, null));
  assert.ok(pf.includes("giriş fiyatı cihazında saklanır · borsa bağlantısı yok"), "local-only caption");
  assert.ok(pf.includes("+1.85%"), `live P&L expected, got: ${pf.slice(0, 200)}`);
  assert.ok(pf.includes("1 tam satır") && pf.includes("1 eksik satır toplam dışı"), "honest totals with exclusion note");
  // YAPI: cluster explorer with verification badge + unclustered tail
  window.SGS_STRUCTURE = {
    generated_at: "t", count: 5, btc_symbol: "BTCUSDT", window_bars: 500, cluster_threshold: 0.7,
    clusters: [{ cluster_id: 1, label: "SOL", size: 3, index_verified: true, verified_name: "Solana Ecosystem",
      members: [{ s: "SOLUSDT", beta: 1.2, corr_btc: 0.8 }, { s: "RNDRUSDT", beta: 1.4, corr_btc: 0.75 }] }],
    unclustered: [{ s: "BTCUSDT", beta: 1, corr_btc: 1 }],
    unavailable: ["PEPEUSDT"],
  };
  const st = renderToStaticMarkup(React.createElement(app.StructureView, { onPick: () => {} }));
  assert.ok(st.includes("İNDEKS DOĞRULANDI"), "verified badge");
  assert.ok(st.includes("Solana Ecosystem"), "verified name");
  assert.ok(st.includes("kümelenmemiş"), "unclustered tail");
  assert.ok(st.includes("PEPE"), "unavailable symbols named, never substituted");
  assert.ok(st.includes("β-ort"), "avg beta header");
});

/* ── 10. new adapters: honest globals on failure, JSON body on claims POST ── */
test("v0.3 adapters: failed fetches never populate globals; claims POSTs JSON", async () => {
  const w = await loadData();
  for (const g of ["SGS_PULSE", "SGS_MACRO", "SGS_OPTIONS", "SGS_STABILITY", "SGS_DECISIONS", "SGS_REPLAY", "SGS_IC", "SGS_CLAIMS"])
    assert.equal(w[g], null, `${g} starts null`);
  for (const m of ["pulse", "macro", "options", "stability", "decisions", "replay", "ic", "suggestWeights", "claims", "registerClaim"])
    assert.equal(typeof w.DIVE[m], "function", `DIVE.${m} must exist`);

  // 8a. every failing fetch leaves its global untouched
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: false, status: 502, json: async () => ({ error: "x" }) });
  try {
    await assert.rejects(w.DIVE.pulse(["BTC"]), /502/);
    await assert.rejects(w.DIVE.macro(), /502/);
    await assert.rejects(w.DIVE.options(), /502/);
    await assert.rejects(w.DIVE.stability(), /502/);
    await assert.rejects(w.DIVE.decisions({ symbol: "BTC" }), /502/);
    await assert.rejects(w.DIVE.replay("4h"), /502/);
    await assert.rejects(w.DIVE.ic("4h"), /502/);
    await assert.rejects(w.DIVE.claims(), /502/);
    await assert.rejects(w.DIVE.registerClaim({ claim_id: "x" }), /502/);
    for (const g of ["SGS_PULSE", "SGS_MACRO", "SGS_OPTIONS", "SGS_STABILITY", "SGS_DECISIONS", "SGS_REPLAY", "SGS_IC", "SGS_CLAIMS"])
      assert.equal(w[g], null, `${g} must stay null after failure`);
  } finally {
    globalThis.fetch = realFetch;
  }

  // 8b. successful fetches populate; claims POST carries the JSON body; symbol
  //     commits by default and can be read without overwriting the map.
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    const u = String(url);
    if (u.includes("/api/pulse")) return ok([{ s: "BTCUSDT", price: 1, ch: 2, funding_rate: 0.0001, next_funding_time_ms: 1, oi_delta_pct: 3 }]);
    if (u.includes("/api/macro")) return ok({ fng: { value: 40 } });
    if (u.includes("/api/options")) return ok({ BTC: { dvol_level: 41.2 }, ETH: { unavailable: "deribit_unreachable" }, generated_at: "t" });
    if (u.includes("/api/evidence/stability")) return ok([{ s: "BTCUSDT", agree_frac: 0.75, k: 8 }]);
    if (u.includes("/api/evidence/decisions")) return ok([{ ts: 1, verdict: "BUY" }]);
    if (u.includes("/api/evidence/replay")) return ok({ report_only: true, grid: [] });
    if (u.includes("/api/evidence/ic")) return ok({ indicators: {} });
    if (u.includes("/api/claims")) {
      if ((init && init.method) === "POST") return ok({ registered: true, file: "x.yaml" }, 201);
      return ok({ claims: [{ claim_id: "c1", status: "PENDING" }], generated_at: "t" });
    }
    if (u.includes("/api/symbol/BTCUSDT")) return ok({ s: "BTCUSDT", price: 9, candles: [{ t: 1, o: 1, h: 1, l: 1, c: 1 }] });
    return ok({});
    function ok(body, status = 200) { return { ok: true, status, json: async () => body }; }
  };
  try {
    await w.DIVE.pulse(["BTC", "ETH"]);
    assert.deepEqual(w.SGS_PULSE.map(r => r.s), ["BTCUSDT"]);
    await w.DIVE.macro();
    assert.equal(w.SGS_MACRO.fng.value, 40);
    await w.DIVE.options();
    assert.equal(w.SGS_OPTIONS.BTC.dvol_level, 41.2);
    await w.DIVE.stability();
    assert.equal(w.SGS_STABILITY[0].agree_frac, 0.75);
    await w.DIVE.decisions({ symbol: "BTCUSDT", limit: 500 });
    assert.ok(calls.at(-1).url.includes("symbol=BTCUSDT") && calls.at(-1).url.includes("limit=500"));
    assert.equal(w.SGS_DECISIONS[0].verdict, "BUY");
    await w.DIVE.replay("4h");
    assert.equal(w.SGS_REPLAY.report_only, true);
    await w.DIVE.ic("4h");
    assert.deepEqual(w.SGS_IC, { indicators: {} });

    const claim = { claim_id: "ui-x-1", claim: "x", metric: "hit_rate", filter: {},
      horizon: "4h", min_n: 20, threshold: { op: ">=", value: 0.55 },
      registered_at: "2026-09-12T00:00:00Z", engine_version: "t" };
    await w.DIVE.registerClaim(claim);
    const post = calls.filter(c => (c.init && c.init.method) === "POST").find(c => c.url.includes("/api/claims"));
    assert.ok(post, "claims registration must POST /api/claims");
    assert.equal(post.init.headers["Content-Type"], "application/json");
    assert.deepEqual(JSON.parse(post.init.body), claim, "the claim body must travel verbatim");
    assert.equal(w.SGS_CLAIMS.claims[0].claim_id, "c1", "the list refresh follows the registration");

    // symbol commit:false → payload returned, canonical map untouched
    w.SGS_DATA_MAP.BTCUSDT = { s: "BTCUSDT", price: 42 };
    const view = await w.DIVE.symbol("BTCUSDT", { tf: "4h", commit: false });
    assert.equal(view.price, 9);
    assert.equal(w.SGS_DATA_MAP.BTCUSDT.price, 42, "chart-only fetch must not clobber the map");
    const committed = await w.DIVE.symbol("BTCUSDT");
    assert.equal(w.SGS_DATA_MAP.BTCUSDT.price, 9, "default commit updates the map");
    assert.ok(calls.filter(c => c.url.includes("tf=4h")).length === 1, "tf param is forwarded");
  } finally {
    globalThis.fetch = realFetch;
  }
});
