/* ============================================================================
   Tests for the TR/EN catalog (src/app/i18n.js) and its consumers:

     1. Every literal L("key") used by the UI exists in BOTH catalogs
        (a missing EN key silently shows the Turkish string to English users).
     2. <html lang> follows the active language, from first paint onward.
     3. The divergence sentence on signal cards is translated at read time, so a
        TR/EN switch changes it without waiting for the next poll.

   data.js and i18n.js are plain scripts that attach to `window`; they are imported
   with a window alias, so the real files run. Runs under `node --test`.
   ========================================================================== */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { pathToFileURL, fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const UI_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const APP = join(UI_ROOT, "src", "app");

/* Shared environment: English is stored BEFORE the scripts load, so the first-paint
   language sync is observable. */
globalThis.window = globalThis;
const store = new Map([["dive_lang", "en"]]);
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)); },
};
globalThis.document = { documentElement: { lang: "tr", setAttribute() {}, getAttribute: () => null } };
await import(pathToFileURL(join(APP, "data.js")).href);
await import(pathToFileURL(join(APP, "i18n.js")).href);

/* ── 1. catalog parity for every literal key the UI reads ────────────────── */
test("every literal L() key used by the UI exists in both TR and EN", () => {
  const sources = ["data.js", "mock.js", "i18n.js", "desktop-app.jsx"].map((f) => readFileSync(join(APP, f), "utf8"));
  // complete literal keys only: L("key") or L("key", ...). Concatenated keys such as
  // L("nav_" + id) are dynamic and are covered by the prefix checks below.
  const used = new Set();
  for (const src of sources)
    for (const m of src.matchAll(/\bL\(\s*["']([A-Za-z0-9_]+)["']\s*[,)]/g)) used.add(m[1]);
  assert.ok(used.size > 50, `expected to find the UI's L() keys, found ${used.size}`);

  // dynamic keys: the rail's nav ids and the evidence window chips build these at run time
  for (const id of ["scan", "panel", "flow", "sig", "evidence", "compare", "map", "portfolio", "structure", "logs", "settings"]) {
    assert.ok(window.DIVE_I18N.en["nav_" + id] != null, `nav_${id} missing in EN`);
    assert.ok(window.DIVE_I18N.tr["nav_" + id] != null, `nav_${id} missing in TR`);
  }
  for (const k of ["7d", "30d", "all"]) {
    assert.ok(window.DIVE_I18N.en["ev_win_" + k] != null, `ev_win_${k} missing in EN`);
    assert.ok(window.DIVE_I18N.tr["ev_win_" + k] != null, `ev_win_${k} missing in TR`);
  }

  const missingEn = [...used].filter((k) => window.DIVE_I18N.en[k] == null);
  const missingTr = [...used].filter((k) => window.DIVE_I18N.tr[k] == null);
  assert.deepEqual(missingEn, [], "keys used by the UI but absent from the EN catalog");
  assert.deepEqual(missingTr, [], "keys used by the UI but absent from the TR catalog");
});

test("the portfolio direction header is translated in EN", () => {
  assert.equal(window.DIVE_I18N.en.pf_dir, "DIRECTION");
  assert.equal(window.DIVE_I18N.tr.pf_dir, "YÖN");
});

/* ── 2. <html lang> ──────────────────────────────────────────────────────── */
test("document language follows the stored language on load and on every switch", () => {
  assert.equal(document.documentElement.lang, "en", "first paint must already declare EN");
  window.setDiveLang("tr");
  assert.equal(document.documentElement.lang, "tr");
  assert.equal(window.diveLang(), "tr");
  window.setDiveLang("en");
  assert.equal(document.documentElement.lang, "en");
  window.setDiveLang("xx");   // unknown codes normalise to TR, like the rest of i18n
  assert.equal(document.documentElement.lang, "tr");
  window.setDiveLang("en");
});

/* ── 3. divergence sentence is read at render time ───────────────────────── */
test("divergence reason follows the active language without a data refresh", () => {
  const adverse = window.sgsWrapScanRow({ s: "SOLUSDT", whaleRegime: "adverse", netNss: 1, divergence: { score: -61.2 } });
  const confirm = window.sgsWrapScanRow({ s: "BTCUSDT", whaleRegime: "confirm", netNss: 1, divergence: { score: 58.4 } });
  const neutral = window.sgsWrapScanRow({ s: "ETHUSDT", whaleRegime: "neutral", netNss: 1, divergence: { score: 12 } });

  assert.equal(store.get("dive_lang"), "en");
  assert.equal(adverse.div.reason, "Whale flow against the indicator direction (WF -61.2).");
  assert.equal(confirm.div.reason, "Whale flow confirms the indicator direction (WF +58.4).");
  assert.equal(neutral.div.reason, "No notable whale divergence.");

  window.setDiveLang("tr");
  assert.equal(adverse.div.reason, "İndikatör yönüne karşı balina akışı (WF -61.2).");
  assert.equal(confirm.div.reason, "Balina akışı indikatör yönünü teyit ediyor (WF +58.4).");
  assert.equal(neutral.div.reason, "Belirgin balina uyumsuzluğu yok.");
  window.setDiveLang("en");
});
