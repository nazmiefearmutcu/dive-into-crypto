/* ============================================================================
   Tests for the wave-2 additions in data.js:

     1. sgsEma / sgsBollinger — the pure indicator math behind the panel chart
        overlays (computed in the UI from the backend's raw candles).
     2. The new evidence / structure / async-scan adapter methods follow the
        repo's honesty contract: a failed fetch NEVER populates its SGS_*
        global.

   data.js is a plain script that attaches everything to `window`; importing it
   with a window alias executes the real file — no re-implementation.
   Runs under `node --test` (auto-discovered next to demo-mode.test.mjs).
   ========================================================================== */
import { test } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";

const UI_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

async function loadData() {
  globalThis.window = globalThis;
  await import(pathToFileURL(join(UI_ROOT, "src", "app", "data.js")).href);
  return globalThis.window;
}

/* ── 1. EMA: SMA seed, null warmup, deterministic chain ──────────────────── */
test("sgsEma seeds with the SMA and stays null through the warmup", async () => {
  await loadData();
  const ema = window.sgsEma([1, 2, 3, 4, 5], 3);
  assert.deepEqual(ema.slice(0, 2), [null, null], "no value before the seed window completes");
  assert.equal(ema.length, 5, "output keeps the input length");
  // seed = (1+2+3)/3 = 2; k = 2/4; then 2 + .5*(4-2) = 3; 3 + .5*(5-3) = 4
  assert.equal(ema[2], 2);
  assert.equal(ema[3], 3);
  assert.equal(ema[4], 4);

  // A constant series maps to the same constant once seeded.
  assert.deepEqual(window.sgsEma([7, 7, 7, 7], 2), [null, 7, 7, 7]);

  // Honest edges: too-short input and dirty values stay null — never 0.
  assert.deepEqual(window.sgsEma([1, 2], 5), [null, null]);
  assert.deepEqual(window.sgsEma([1, NaN, 3], 2), [null, null, null]);
});

/* ── 2. Bollinger: population stddev over the trailing window ────────────── */
test("sgsBollinger uses a full trailing window with population stddev", async () => {
  await loadData();
  const bb = window.sgsBollinger([1, 2, 3, 4, 5], 5, 2);
  assert.deepEqual(bb.slice(0, 4), [null, null, null, null], "null until the window is full");
  const b = bb[4];
  // mean = 3; population var = (4+1+0+1+4)/5 = 2; sd = √2
  assert.equal(b.mid, 3);
  assert.ok(Math.abs(b.up - (3 + 2 * Math.sqrt(2))) < 1e-9, "upper band = mid + 2σ");
  assert.ok(Math.abs(b.lo - (3 - 2 * Math.sqrt(2))) < 1e-9, "lower band = mid − 2σ");

  // Zero-variance series collapse onto the mid line (never NaN bands).
  const flat = window.sgsBollinger([5, 5, 5, 5, 5], 5, 2);
  assert.deepEqual(flat[4], { mid: 5, up: 5, lo: 5 });
  assert.deepEqual(window.sgsBollinger([1, 2, 3], 20), [null, null, null], "window longer than data");
});

/* ── 3. the wave-2 adapter surface exists with its honest null globals ───── */
test("DIVE exposes evidence/structure/async-scan methods; globals start empty", async () => {
  await loadData();
  for (const m of ["evidence", "gradeEvidence", "structure", "scanAsync", "scanProgress", "scanResult"])
    assert.equal(typeof window.DIVE[m], "function", `DIVE.${m} must exist`);
  assert.equal(window.SGS_EVIDENCE, null);
  assert.equal(window.SGS_STRUCTURE, null);
  assert.equal(window.SGS_SCAN_PROGRESS, null);
  // Math helpers are exposed for the chart component.
  assert.equal(typeof window.sgsEma, "function");
  assert.equal(typeof window.sgsBollinger, "function");
});

/* ── 4. a failed fetch must NEVER populate the new globals ───────────────── */
test("failed evidence/structure/progress fetches leave their globals untouched", async () => {
  await loadData();
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (_url, init) => ({
    ok: false,
    status: 502,
    json: async () => ({ error: "evidence_unavailable", detail: "boom" }),
    method: (init && init.method) || "GET",
  });
  try {
    await assert.rejects(window.DIVE.evidence("4h"), /502/);
    await assert.rejects(window.DIVE.structure(60), /502/);
    await assert.rejects(window.DIVE.scanProgress("abc"), /502/);
    await assert.rejects(window.DIVE.gradeEvidence("4h"), /502/);
    assert.equal(window.SGS_EVIDENCE, null, "no fabricated evidence summary on failure");
    assert.equal(window.SGS_STRUCTURE, null, "no fabricated structure on failure");
    assert.equal(window.SGS_SCAN_PROGRESS, null, "no fabricated progress on failure");
  } finally {
    globalThis.fetch = realFetch;
  }
});
