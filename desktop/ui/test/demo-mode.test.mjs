/* ============================================================================
   Regression tests for the fabricated-data guarantee.

   History: on boot, a failed universe fetch used to silently call window.DIVE_MOCK(),
   filling the UI with invented prices, verdicts and confidence scores with nothing
   on screen saying so. Binance is geo-restricted in some regions, so that path fired
   routinely. These tests pin the two properties that make it impossible to recur:

     1. A failed fetch NEVER populates mock data.
     2. If demo mode IS on (manually), the DEMO banner is rendered.

   Runs the real source through the real esbuild pipeline — no re-implementation.
   Usage: npm test   (node --test, Node 18+)
   ========================================================================== */
import { test } from "node:test";
import assert from "node:assert/strict";
import esbuild from "esbuild";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import { mkdirSync, writeFileSync } from "node:fs";

const UI_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const APP = join(UI_ROOT, "src", "app");

/* Minimal DOM stub. renderToStaticMarkup does not run effects, so we only need
   what module top-level code and useState initialisers touch. */
function makeDom() {
  const htmlAttrs = {};
  return {
    documentElement: {
      setAttribute: (k, v) => { htmlAttrs[k] = v; },
      getAttribute: (k) => (k in htmlAttrs ? htmlAttrs[k] : null),
    },
    getElementById: () => null,   // no #root → the bundle must not try to mount
    _attrs: htmlAttrs,
  };
}

/* Build the same file set as build.mjs, but with a server-side React prelude.
   Returns a fresh module namespace with fresh globals on every call. */
let _seq = 0;
async function loadApp() {
  const FILES = ["data.js", "mock.js", "desktop-app.jsx"];
  const { readFileSync } = await import("node:fs");
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

  // Fresh globals per load so state cannot leak between tests.
  globalThis.window = globalThis;
  globalThis.document = makeDom();
  globalThis.location = { hash: "" };
  globalThis.localStorage = { getItem: () => null, setItem: () => {} };
  delete globalThis.DIVE_DEMO;
  delete globalThis.DIVE_MOCK;
  delete globalThis.DIVE_APP;

  // Written inside the project so bare `react` resolves against ui/node_modules.
  const dir = join(UI_ROOT, "test", ".tmp");
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `app-${++_seq}.mjs`);
  writeFileSync(file, out.outputFiles[0].text);
  await import(pathToFileURL(file).href);
  return globalThis.DIVE_APP;
}

/* ── 1. a failed fetch must NOT produce fabricated data ──────────────────── */
test("failed universe fetch does not populate mock data", async () => {
  const app = await loadApp();

  let mockCalls = 0;
  const realMock = window.DIVE_MOCK;
  assert.equal(typeof realMock, "function", "DIVE_MOCK should still exist as a manual demo hook");
  window.DIVE_MOCK = () => { mockCalls++; realMock(); };

  const failing = { universe: async () => { throw new Error("fetch failed: /api/universe → 451"); } };
  const res = await app.bootUniverse(failing);

  assert.equal(res.ok, false, "a failed fetch must be reported as a failure");
  assert.match(res.error, /451/, "the real error must be surfaced, not swallowed");

  // The actual regression: nothing fabricated may reach the globals the UI reads.
  assert.equal(mockCalls, 0, "boot must never invoke DIVE_MOCK on fetch failure");
  assert.equal(app.isDemo(), false, "demo mode must stay off after a failed fetch");
  assert.deepEqual(window.SGS_DATA, [], "SGS_DATA must stay empty");
  assert.deepEqual(Object.keys(window.SGS_DATA_MAP), [], "SGS_DATA_MAP must stay empty");
  assert.deepEqual(window.SGS_SCAN.survivors, [], "no fabricated scan survivors");
  assert.equal(document.documentElement.getAttribute("data-demo"), null, "no demo attribute");

  // And specifically: no fabricated BTC price may appear anywhere.
  assert.equal(window.SGS_DATA_MAP.BTCUSDT, undefined);
});

/* ── 2. a successful fetch still works (guard against over-correcting) ───── */
test("successful universe fetch boots normally without demo mode", async () => {
  const app = await loadApp();
  const ok = { universe: async () => [{ s: "ETHUSDT" }, { s: "BTCUSDT" }] };
  const res = await app.bootUniverse(ok);

  assert.equal(res.ok, true);
  assert.equal(res.symbol, "ETHUSDT");
  assert.equal(app.isDemo(), false, "a healthy boot must not flip demo mode on");
});

/* ── 3. when demo mode IS on, the banner renders ─────────────────────────── */
test("DEMO banner renders whenever mock mode is on", async () => {
  const app = await loadApp();
  const { renderToStaticMarkup } = await import("react-dom/server");

  // Off by default: no banner, no marker.
  assert.equal(app.isDemo(), false);
  assert.equal(renderToStaticMarkup(React.createElement(app.DemoBanner)), "");
  const cleanShell = renderToStaticMarkup(React.createElement(app.App));
  assert.ok(!cleanShell.includes("demo-banner"), "no banner when demo mode is off");
  assert.ok(!cleanShell.includes("NOT LIVE MARKET DATA"), "no warning text when demo mode is off");

  // Manual activation — the only supported way in.
  window.DIVE_MOCK();
  assert.equal(app.isDemo(), true, "DIVE_MOCK must flip the demo flag");
  assert.equal(document.documentElement.getAttribute("data-demo"), "1");

  const html = renderToStaticMarkup(React.createElement(app.App));
  assert.ok(html.includes('data-testid="demo-banner"'), "banner must be in the tree");
  assert.ok(html.includes("DEMO DATA — NOT LIVE MARKET DATA"), "banner must state the data is not live");
  assert.ok(html.includes('role="alert"'), "banner must be announced to assistive tech");
  assert.ok(html.includes("is-demo"), "shell must carry the demo modifier class");
});

/* ── 4. every fabricated value carries its own marker ────────────────────── */
test("fabricated rows are marked individually, not just globally", async () => {
  const app = await loadApp();
  const { renderToStaticMarkup } = await import("react-dom/server");

  window.DIVE_MOCK();

  // Every fabricated symbol object must be self-identifying.
  const rows = Object.values(window.SGS_DATA_MAP);
  assert.ok(rows.length > 0, "demo mode should have populated rows");
  for (const r of rows) assert.equal(r._demo, true, `${r.s} must be flagged _demo`);

  // The scanner table renders a per-row marker next to the fabricated verdict.
  const scan = renderToStaticMarkup(React.createElement(app.App));
  assert.ok(scan.includes("demo-mark"), "scanner rows must carry inline DEMO markers");
  const markers = scan.match(/demo-mark/g) || [];
  assert.ok(markers.length >= rows.length,
    `expected at least one marker per fabricated row (${rows.length}), got ${markers.length}`);

  // A marker is never emitted for a live row.
  assert.equal(renderToStaticMarkup(React.createElement(app.DemoMark, { row: { s: "BTCUSDT" } })), "");
});

/* ── 5. the failure path renders the unavailable state, not a fake market ── */
test("data-source-unavailable state names the failure instead of inventing data", async () => {
  const app = await loadApp();
  const { renderToStaticMarkup } = await import("react-dom/server");

  const html = renderToStaticMarkup(
    React.createElement(app.DataSourceDown, { error: "/api/universe → 451", onRetry: () => {} }),
  );
  assert.ok(html.includes('data-testid="source-unavailable"'));
  assert.ok(html.includes("DATA SOURCE UNAVAILABLE"), "must say the source is unavailable");
  assert.ok(html.includes("451"), "must surface the underlying error");
  assert.ok(!/68240|STRONG_BUY/.test(html), "must not contain any fabricated market value");
});
