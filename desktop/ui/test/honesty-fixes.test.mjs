/* ============================================================================
   Honesty fixes in the desktop UI (review round, g-desktop-ui):

     1. A failed backend call is explained in words: HTTP 451 (or region_blocked) is the
        only region-block text; HTTP 403 is a separate, retryable "forbidden" state; the
        raw "path → status" string never reaches the screen.
     2. The HTTP status and the structured error code survive the data layer (ApiError).
     3. A view whose own fetch failed shows the reason and a retry, not a spinner.
     4. Demo mode: a symbol outside the fixture gets an explicit state, not a spinner.
     5. The PLANLAMA strip is illustrative ATR levels, labels routed through the catalog.
     6. The scanner caption stays "paused" while the deep-scan hold is in force.
     7. The scan "action" token (LONG / SHORT / NONE, plus legacy AL / SAT / BEKLE) maps to
        direction-only labels.
     8. The claim-form placeholder carries no win-rate figure.

   Loads the real data.js / i18n.js / desktop-app.jsx through esbuild, like the other
   UI suites. Runs under `node --test`.
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
  delete globalThis.DIVE_DEMO;
  const dir = join(UI_ROOT, "test", ".tmp");
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `honesty-${++_seq}-${Date.now()}.mjs`);
  writeFileSync(file, out.outputFiles[0].text);
  await import(pathToFileURL(file).href);
  return globalThis.DIVE_APP;
}

const app = await loadBundle();
const h = (el) => renderToStaticMarkup(el);

/* ── 1. failure wording ──────────────────────────────────────────────────── */
test("HTTP 451 (or region_blocked) is the region text; 403 is a separate forbidden state", () => {
  const region = app.failureText({ message: "/api/universe?limit=60 → 451", status: 451 });
  assert.equal(region.kind, "region");
  assert.equal(app.failureText("/api/symbol/BTCUSDT → 451").kind, "region", "a bare message is parsed too");
  assert.equal(app.failureText({ message: "x", status: null, code: "region_blocked" }).kind, "region");

  const forbidden = app.failureText({ message: "/api/scan → 403", status: 403 });
  assert.equal(forbidden.kind, "forbidden", "403 is not a region block");
  assert.notEqual(forbidden.text, region.text);
  assert.match(forbidden.text, /regional restriction|bölgesel bir kısıtlama/);
});

test("region text quotes no code; the generic text carries the code only in parentheses at the end", () => {
  const region = app.failureText({ status: 451 }).text;
  assert.ok(!/\d{3}/.test(region), `region text must not quote a code: ${region}`);
  const generic = app.failureText({ message: "/api/scan → 500", status: 500 });
  assert.equal(generic.kind, "generic");
  assert.match(generic.text, /\(HTTP 500\)$/, "code only as a trailing parenthetical");
  assert.ok(!generic.text.includes("→"), "no raw path → status text");
  const timeout = app.failureText("/api/symbol/X → timeout 90s");
  assert.equal(timeout.kind, "generic");
  assert.ok(!/HTTP/.test(timeout.text), "a timeout names no HTTP status");
});

test("the data-source screen shows the explained reason and a retry, never the raw path", () => {
  const html451 = h(React.createElement(app.DataSourceDown, { error: { message: "/api/universe?limit=60 → 451", status: 451 }, onRetry() {} }));
  assert.ok(/bölgesel kısıtlama|regional restriction/.test(html451));
  assert.ok(!/→|\b451\b/.test(html451), "raw text must not reach the screen");
  assert.ok(html451.includes("TEKRAR DENE"), "retry offered");

  const html403 = h(React.createElement(app.DataSourceDown, { error: { message: "/api/universe → 403", status: 403 }, onRetry() {} }));
  assert.ok(html403.includes('data-kind="forbidden"'), "403 renders the forbidden state");
  assert.ok(html403.includes("TEKRAR DENE"), "forbidden still offers a retry");
});

test("bootUniverse keeps the HTTP status and the error code for the screen", async () => {
  const e451 = Object.assign(new Error("/api/universe?limit=60 → 451"), { status: 451 });
  const r = await app.bootUniverse({ universe: async () => { throw e451; } });
  assert.equal(r.ok, false);
  assert.equal(r.status, 451);
  assert.match(r.error, /451/, "the raw message is still available for debugging");
});

/* ── 2. data layer keeps the status and the structured code ──────────────── */
test("a non-2xx answer becomes an ApiError with its status and error code", async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: false, status: 451, json: async () => ({ error: "region_blocked" }) });
  try {
    await assert.rejects(window.DIVE.universe(60), (e) => {
      assert.equal(e.status, 451);
      assert.equal(e.code, "region_blocked");
      assert.match(e.message, /→ 451/, "message keeps the raw form for debugging");
      return true;
    });
    globalThis.fetch = async () => ({ ok: false, status: 403, json: async () => { throw new Error("opaque"); } });
    await assert.rejects(window.DIVE.universe(60), (e) => e.status === 403 && e.code === null);
  } finally {
    globalThis.fetch = realFetch;
  }
});

/* ── 3. a failed view fetch is an error state, not a spinner ─────────────── */
test("TARA with no rows and a failed fetch shows the reason and a retry, not the spinner", () => {
  window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 0, universeCount: 0 };
  const failHtml = h(React.createElement(app.Scanner, {
    onPick() {}, onCompare() {}, job: null, prog: null, jobErr: null,
    fetchFail: { view: "scan", text: "tarama: Veri kaynağı isteğe yanıt vermedi (HTTP 500)" }, onRetry() {},
  }));
  assert.ok(failHtml.includes('data-testid="view-fetch-error"'));
  assert.ok(failHtml.includes("(HTTP 500)"));
  assert.ok(failHtml.includes("TEKRAR DENE"));
  assert.ok(!failHtml.includes("spin"), "no spinner once the fetch has failed");

  const loadingHtml = h(React.createElement(app.Scanner, {
    onPick() {}, onCompare() {}, job: null, prog: null, jobErr: null, fetchFail: null, onRetry() {},
  }));
  assert.ok(loadingHtml.includes("spin"), "still loading → spinner");
});

test("FetchFail names the reason and offers a retry", () => {
  const html = h(React.createElement(app.FetchFail, { err: { text: "Kanıt arşivi okunamadı" }, onRetry() {} }));
  assert.ok(html.includes("Kanıt arşivi okunamadı"));
  assert.ok(html.includes("TEKRAR DENE"));
});

/* ── 4. demo fixture limit and loading state for symbol views ────────────── */
test("a symbol outside the demo fixture gets an explicit state, not a spinner", () => {
  window.DIVE_DEMO = { active: true };
  try {
    const demoHtml = h(React.createElement(app.SymbolLoading, { sym: "XYZUSDT", fetchFail: null, onRetry() {} }));
    assert.ok(demoHtml.includes("demo verisinde yok"), "demo fixture limit is named");
    assert.ok(!demoHtml.includes("spin"));
  } finally {
    delete window.DIVE_DEMO;
  }
  const liveHtml = h(React.createElement(app.SymbolLoading, { sym: "XYZUSDT", fetchFail: null, onRetry() {} }));
  assert.ok(liveHtml.includes("spin") && liveHtml.includes("verisi çekiliyor"), "live → loading spinner");
  const failed = h(React.createElement(app.SymbolLoading, { sym: "XYZUSDT", fetchFail: { text: "x" }, onRetry() {} }));
  assert.ok(failed.includes('data-testid="view-fetch-error"'), "a failed fetch wins over the spinner");
});

/* ── 5. PLANLAMA strip: illustrative ATR levels, no SL / TP vocabulary ───── */
test("PLANLAMA shows illustrative ATR levels with the disclaimer and no SL/TP labels", () => {
  const html = h(React.createElement(app.PlanningStrip, {
    planning: {
      sl_distance_pct: 1.5, tp_1r_pct: 1.5, tp_2r_pct: 3, tp_3r_pct: 4.5,
      envelope: { h1: 0.4, h4: 0.9, h24: 2.1 }, note: "informational geometry only — no order semantics",
    },
  }));
  assert.ok(html.includes("ATR 1× SEVİYE") && html.includes("ATR 2× SEVİYE") && html.includes("ATR 3× SEVİYE"));
  assert.ok(html.includes("1.5×ATR%"), "header states the basis");
  assert.ok(html.includes("yalnızca bilgi") && html.includes("emir kurulmaz"), "Android-matching disclaimer line");
  assert.ok(!/\bSL\b|\bTP\b|take-profit|stop/i.test(html), "no SL/TP/stop vocabulary");
  assert.ok(!html.includes("no order semantics"), "the backend note is not shown raw; the catalog line is");
});

/* ── 6. caption tracks the deep-scan hold ────────────────────────────────── */
test("the scanner caption stays paused while the deep-scan hold is in force", () => {
  window.SGS_SCAN = { survivors: [], eliminated: [], scanned: 0, universeCount: 0 };
  const base = { onPick() {}, onCompare() {}, prog: null, jobErr: null, onRetry() {}, fetchFail: null };
  const finished = h(React.createElement(app.Scanner, { ...base, job: { scanId: "s1", done: true, dismissed: true }, held: true }));
  assert.ok(finished.includes("arka plan taraması bitti"), "finished-but-held says the sync scan is paused");
  assert.ok(!finished.includes("varsayılan: senkron otomatik tarama"), "must not claim the sync scan runs");

  const idle = h(React.createElement(app.Scanner, { ...base, job: null, held: false }));
  assert.ok(idle.includes("varsayılan: senkron otomatik tarama"), "released → default caption");
});

/* ── 7. scan action tokens map to direction-only labels ──────────────────── */
test("actionLabel maps LONG / SHORT / NONE (and legacy AL / SAT / BEKLE) to bias labels", () => {
  assert.equal(app.actionLabel("LONG"), "LONG eğilim");
  assert.equal(app.actionLabel("SHORT"), "SHORT eğilim");
  assert.equal(app.actionLabel("NONE"), "Belirgin eğilim yok");
  assert.equal(app.actionLabel("AL"), "LONG eğilim", "legacy token still maps during the transition");
  assert.equal(app.actionLabel("SAT"), "SHORT eğilim");
  assert.equal(app.actionLabel("BEKLE"), "Belirgin eğilim yok");
  assert.equal(app.actionLabel(undefined), "—");
  assert.equal(app.actionLabel("BUY"), "—", "unknown tokens render a dash, never raw text");
});

/* ── 8. claim placeholder carries no win-rate figure ─────────────────────── */
test("the claim-form placeholders name a template, not a hit-rate claim", () => {
  const tr = window.DIVE_I18N.tr.claim_ph;
  const en = window.DIVE_I18N.en.claim_ph;
  for (const ph of [tr, en]) {
    assert.ok(!/%\s*\d|\b55\b|STRONG_BUY|hit|isabet/i.test(ph), `no figure or verdict in: ${ph}`);
  }
  assert.ok(tr.includes("<eşik>") && en.includes("<threshold>"), "template placeholders present");
});
