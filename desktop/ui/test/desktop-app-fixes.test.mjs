/* ============================================================================
   Regression tests for the desktop-app.jsx fix wave:

     1. Command palette: rendering never runs an item action (Ctrl+K opens and stays).
     2. Palette "kıyas" / annotation helpers: bare tickers become XUSDT, sub-cent
        annotation prices survive (the old 1/6 grid zeroed them).
     3. Theme storage: a blocked localStorage never blanks the app during first render.
     4. TARA: a finished scan with zero survivors says so; the counter reads symbols.
     5. KARŞIT KANIT: the "ranking" header is claimed only when scores exist.
     6. LOG / settings literals go through the TR/EN catalog.

   The bundle is compiled the same way as v3.test.mjs (esbuild, React prelude).
   Runs under `node --test`.
   ========================================================================== */
import { test } from "node:test";
import assert from "node:assert/strict";
import esbuild from "esbuild";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const UI_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const APP = join(UI_ROOT, "src", "app");

let _seq = 0;
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
  const file = join(dir, `fixes-${++_seq}-${Date.now()}.mjs`);
  writeFileSync(file, out.outputFiles[0].text);
  await import(pathToFileURL(file).href);
  return globalThis.DIVE_APP;
}

/* ── 1. palette: no action runs during render ─────────────────────────────── */
test("command palette: rendering never runs an item action (Ctrl+K opens and stays put)", async () => {
  const app = await loadBundle();
  let closed = 0;
  const went = [];
  const html = renderToStaticMarkup(React.createElement(app.CommandPalette, {
    open: true,
    onClose: () => { closed++; },
    onGo: (v) => went.push(`go:${v}`),
    onPickSymbol: (s) => went.push(`sym:${s}`),
    onScan: () => went.push("scan"),
    onEvidence: () => went.push("evidence"),
    onCompare: () => went.push("compare"),
    onTheme: (t) => went.push(`theme:${t}`),
    onLang: (c) => went.push(`lang:${c}`),
  }));
  assert.equal(closed, 0, "onClose must not run while rendering the rows");
  assert.deepEqual(went, [], "no item run may fire during render");
  assert.ok(html.includes("pitem"), "palette rows are rendered");
});

/* ── 2. helpers ──────────────────────────────────────────────────────────── */
test("kıyas tokens become USDT-M symbols; blank tokens are dropped", async () => {
  const app = await loadBundle();
  assert.equal(app.toUsdtSym("btc"), "BTCUSDT");
  assert.equal(app.toUsdtSym(" ETHUSDT "), "ETHUSDT", "already-suffixed symbols are kept");
  assert.equal(app.toUsdtSym(""), "", "blank input yields no symbol");
  assert.equal(app.toUsdtSym(undefined), "");
});

test("annotation prices keep sub-cent values instead of snapping to a 1/6 grid", async () => {
  const app = await loadBundle();
  assert.equal(app.annPrice(0.0123), 0.0123, "sub-cent price was zeroed by the old grid");
  assert.equal(app.annPrice(0.42), 0.42, "0.42 was shifted to 0.5 by the old grid");
  assert.equal(app.annPrice(68240.5), 68240.5);
  assert.ok(app.annPrice(0.0123) > 0, "stored price must stay drawable (p > 0)");
});

/* ── 3. theme storage ────────────────────────────────────────────────────── */
test("theme persistence: blocked storage falls back and the shell still renders", async () => {
  const app = await loadBundle();
  globalThis.localStorage = {
    getItem() { throw new Error("blocked"); },
    setItem() { throw new Error("blocked"); },
  };
  try {
    assert.equal(app.readTheme(), "phosphor", "blocked read falls back to the default theme");
    assert.doesNotThrow(() => app.saveTheme("amber"), "blocked write is swallowed");
    const html = renderToStaticMarkup(React.createElement(app.App));
    assert.ok(html.includes("TARA"), "the app shell renders with storage blocked");
  } finally {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
  }
});

/* ── 4. TARA empty states + counter ──────────────────────────────────────── */
test("TARA: a finished scan with zero survivors says so; the counter reads symbols", async () => {
  const app = await loadBundle();
  window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 50, universeCount: 250 };
  const done = renderToStaticMarkup(React.createElement(app.Scanner, { onPick() {}, onCompare() {}, job: null, prog: null }));
  assert.ok(done.includes("Hayatta kalan yok"), "zero-survivor outcome is named");
  assert.ok(!done.includes("Tarama çalışıyor"), "no endless spinner once the universe is known");
  assert.ok(done.includes("50/250"), "counter shows symbols over universe, not TF verdicts");

  window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 0, universeCount: 0 };
  const pending = renderToStaticMarkup(React.createElement(app.Scanner, { onPick() {}, onCompare() {}, job: null, prog: null }));
  assert.ok(pending.includes("Tarama çalışıyor"), "no scan body yet still shows the spinner");
  window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 0, universeCount: 0 };
});

/* ── 5. KARŞIT KANIT ranking claim ───────────────────────────────────────── */
test("KARŞIT KANIT claims |SKOR×AĞIRLIK| ranking only when the payload carries scores", async () => {
  const app = await loadBundle();
  const unscored = { finalSignal: "STRONG_BUY",
    indicators: [{ name: "macd", signal: "SELL", weight: 1, value: -3.2 }] };
  const html1 = renderToStaticMarkup(React.createElement(app.OpposingEvidence, { d: unscored, evHorizon: "4h" }));
  assert.ok(html1.includes("macd"), "the opposing row still renders");
  assert.ok(!html1.includes("SKOR×AĞIRLIK"), "no ranking claim without score fields");
  assert.ok(html1.includes("TABLO SIRASI"), "honest table-order label instead");

  const scored = { finalSignal: "STRONG_BUY",
    indicators: [{ name: "macd", signal: "SELL", score: -0.4, weight: 1, weighted_score: -0.4, value: -3.2 }] };
  const html2 = renderToStaticMarkup(React.createElement(app.OpposingEvidence, { d: scored, evHorizon: "4h" }));
  assert.ok(html2.includes("SKOR×AĞIRLIK"), "ranking label is kept when scores exist");
});

/* ── 6. LOG literals through the catalog ─────────────────────────────────── */
test("LOG heading and empty state go through the TR/EN catalog", async () => {
  const app = await loadBundle();
  window.SGS_LOGS = [];
  const tr = renderToStaticMarkup(React.createElement(app.Logs));
  assert.ok(tr.includes("Bağlantı") && tr.includes("Günlük boş."), "TR default stays verbatim");
  globalThis.localStorage = { getItem: (k) => (k === "dive_lang" ? "en" : null), setItem: () => {} };
  try {
    const en = renderToStaticMarkup(React.createElement(app.Logs));
    assert.ok(en.includes("Connection") && en.includes("Log is empty."), "EN catalog is used");
    assert.ok(!en.includes("Günlük boş.") && !en.includes("AĞ GÜNLÜĞÜ"), "no Turkish literal leaks into EN");
  } finally {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    delete window.SGS_LOGS;
  }
});

/* ── 7. a missing 24h change is unavailable, never a signed or colored number ── */
test("a missing 24h change renders as unavailable (—), never as a signed or colored value", async () => {
  const app = await loadBundle();
  const row = (s, ch) => ({ s, finalSignal: "BUY", price: 1, ch, confidence: 50, multiTf: [], indicators: [] });
  window.SGS_SCAN = { survivors: [row("BTCUSDT", null), row("ETHUSDT", undefined), row("SOLUSDT", 2.5)],
    eliminated: [], scanned: 3, universeCount: 3 };
  try {
    const scan = renderToStaticMarkup(React.createElement(app.Scanner, { onPick() {}, onCompare() {}, job: null, prog: null }));
    assert.ok(!scan.includes("+—") && !scan.includes("—%"), "no signed or percent-suffixed placeholder");
    assert.ok(scan.includes('class="chg up"'), "a real positive change keeps its color");
    assert.equal((scan.match(/class="chg "/g) || []).length, 2, "unavailable rows carry no up/dn class");

    window.SGS_SCAN = { survivors: [row("BTCUSDT", null)], eliminated: [], scanned: 1, universeCount: 1 };
    const sig = renderToStaticMarkup(React.createElement(app.Signal, { onPick() {} }));
    assert.ok(sig.includes('class="chg ">—</span>'), "the signal card shows — for a missing change");
    assert.ok(!sig.includes("chg up") && !sig.includes("chg dn"), "no arrow or color for a missing change");
  } finally {
    window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 0, universeCount: 0 };
  }
});

/* ── 8. KANIT renders only the selected horizon's summary ───────────────────── */
test("KANIT renders only the selected horizon's summary; the other horizon waits with usable chips", async () => {
  const app = await loadBundle();
  window.SGS_EVIDENCE = { horizon: "4h", archived_count: 3, gradable_count: 3, graded_count: 3, coverage: 1,
    stale: false, by_verdict: { LONG: { n: 2, hit_rate: 0.5 } }, by_confidence: [{ bucket: "60-80", n: 2, hit_rate: 0.5 }] };
  try {
    const shown = renderToStaticMarkup(React.createElement(app.Evidence, { horizon: "4h", setHorizon() {} }));
    assert.ok(shown.includes("GÜVEN KOVASI"), "the selected horizon's summary renders");
    const other = renderToStaticMarkup(React.createElement(app.Evidence, { horizon: "1h", setHorizon() {} }));
    assert.ok(!other.includes("GÜVEN KOVASI"), "a 4h summary never renders under the 1h selection");
    assert.ok(other.includes("Kanıt arşivi okunuyor"), "loading state while the 1h summary is pending");
    assert.ok(other.includes("1H") && other.includes("4H"), "horizon chips stay usable while loading");
  } finally {
    window.SGS_EVIDENCE = null;
  }
});

/* ── 9. KARŞIT KANIT ranks by |weighted_score| (|score| fallback) ───────────── */
test("KARŞIT KANIT ranks by |weighted_score|, falls back to |score|, and claims a ranking only with scores", async () => {
  const app = await loadBundle();
  const sell = (name, weighted, score, value) => ({ name, signal: "SELL", value,
    ...(score != null ? { score } : {}), ...(weighted != null ? { weighted_score: weighted } : {}) });
  const bull = (indicators) => ({ finalSignal: "STRONG_BUY", indicators });

  // |weighted| order: cci .6 > macd .2 > rsi .1 > adx .05 — the raw score would order them differently
  const byWeighted = renderToStaticMarkup(React.createElement(app.OpposingEvidence, { d: bull([
    sell("macd", -0.2, -0.9, 1), sell("rsi", -0.1, -0.95, 2), sell("cci", -0.6, -0.1, 3), sell("adx", -0.05, -0.99, 4)]),
    evHorizon: "4h" }));
  assert.ok(byWeighted.indexOf("cci") < byWeighted.indexOf("macd"), "largest |weighted_score| first");
  assert.ok(byWeighted.indexOf("macd") < byWeighted.indexOf("rsi"), "then the next weighted magnitude");
  assert.ok(!byWeighted.includes("adx"), "only the top 3 are shown");
  assert.ok(byWeighted.includes("|SKOR×AĞIRLIK|"), "weighted fields present → ranking header");

  const byScore = renderToStaticMarkup(React.createElement(app.OpposingEvidence, { d: bull([
    sell("alpha", null, -0.2, 1), sell("beta", null, -0.8, 2)]), evHorizon: "4h" }));
  assert.ok(byScore.indexOf("beta") < byScore.indexOf("alpha"), "|score| ranks when weighted_score is absent");
  assert.ok(byScore.includes("SKOR×AĞIRLIK"), "score fields present → ranking header");
});

/* ── 10. card, replay and KIYAS captions go through the catalog ─────────────── */
test("replay and card captions are verbatim in TR and translated in EN; KIYAS has a freshness caption", async () => {
  const app = await loadBundle();
  window.SGS_REPLAY = null;
  const tr = renderToStaticMarkup(React.createElement(app.ReplayPanel, { horizon: "4h" }));
  assert.ok(tr.includes("REPLAY · TERSİNE-EŞİK IZGARASI") && tr.includes("henüz çalıştırılmadı"), "TR verbatim");

  const cmpTr = renderToStaticMarkup(React.createElement(app.Compare, { symbols: [], onPick() {} }));
  assert.ok(cmpTr.includes("henüz çekilmedi"), "KIYAS shows an honest not-fetched-yet age caption");

  globalThis.localStorage = { getItem: (k) => (k === "dive_lang" ? "en" : null), setItem: () => {} };
  try {
    const en = renderToStaticMarkup(React.createElement(app.ReplayPanel, { horizon: "4h" }));
    assert.ok(en.includes("COUNTERFACTUAL THRESHOLD GRID") && en.includes("not run yet"), "EN catalog used");
    assert.ok(!/henüz|TERSİNE|IZGARA/.test(en), "no Turkish replay caption leaks into EN");

    const opp = renderToStaticMarkup(React.createElement(app.OpposingEvidence, { d: {
      finalSignal: "STRONG_BUY", indicators: [{ name: "macd", signal: "SELL", score: -0.4, weighted_score: -0.4, value: 1 }] },
      evHorizon: "4h" }));
    assert.ok(opp.includes("OPPOSING EVIDENCE") && opp.includes("|SCORE×WEIGHT| · TOP 3"), "EN ranking header");
    assert.ok(!opp.includes("KARŞIT"), "no Turkish card title in EN");
  } finally {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    window.SGS_REPLAY = null;
  }
});
