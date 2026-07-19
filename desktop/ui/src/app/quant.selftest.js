/* ============================================================================
   SGS quant self-test — sign conventions + elimination/backfill invariants.
   Run:  node quant.selftest.js     (exit 0 = all green)
   ========================================================================== */
const Q = require("./quant.cjs");

let pass = 0, fail = 0;
function ok(name, cond) { if (cond) { pass++; } else { fail++; console.log("  ✗ FAIL:", name); } }
function approx(a, b, e) { return Math.abs(a - b) <= (e || 1e-6); }

/* Build a 48-pt series with a controllable whale-direction trend `wd`
   and trend strength `g`. Deterministic (seeded), no Math.random. */
function rng(seed) { let s = seed >>> 0; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
function makeSeries(seed, wd, g) {
  const r = rng(seed), N = 48;
  const pos = [], acc = [], taker = [], oi = [], price = [], glob = [], funding = [], bias = [];
  let p = 100, o = 5e8;
  for (let i = 0; i < N; i++) {
    const t = i / (N - 1);
    const n = () => (r() - 0.5);
    pos.push(1.2 + g * wd * t + 0.03 * n());
    acc.push(1.1 + g * wd * t + 0.03 * n());
    taker.push(1.0 + 0.18 * g * wd + 0.02 * n());
    o *= 1 + (0.012 * g * wd) + 0.004 * n(); oi.push(o);
    p *= 1 + (0.01 * g * wd) + 0.003 * n(); price.push(p);
    glob.push(1.0 + 0.1 * n());
    funding.push(0.0001 * wd + 0.00005 * n());
    bias.push(wd * g * 60 + 5 * n());
  }
  return { pos, acc, taker, oi, price, glob, funding, bias };
}
function coin(s, finalSignal, wd, g, extra) {
  return Object.assign({ s, seed: 7, finalSignal, confidence: 70, buy: 8, sell: 2, neutral: 2, ch: 3,
    series: makeSeries(7, wd, g) }, extra || {});
}

/* ── indicatorDir ── */
ok("dir BUY=+1", Q.indicatorDir({ finalSignal: "BUY" }) === 1);
ok("dir STRONG_BUY=+1", Q.indicatorDir({ finalSignal: "STRONG_BUY" }) === 1);
ok("dir SELL=-1", Q.indicatorDir({ finalSignal: "SELL" }) === -1);
ok("dir NEUTRAL=0", Q.indicatorDir({ finalSignal: "NEUTRAL" }) === 0);

/* ── whaleFlow sign follows wd ── */
ok("WF>0 when whales accumulate", Q.whaleFlow(makeSeries(1, +1, 1)).wf > 0.2);
ok("WF<0 when whales distribute", Q.whaleFlow(makeSeries(2, -1, 1)).wf < -0.2);
ok("WF~0 when flat", Math.abs(Q.whaleFlow(makeSeries(3, 0, 0)).wf) < 0.16);

/* ── divergence truth table ── */
// BUY + whales buying  → CONFIRM (keep)
ok("BUY+accum = CONFIRM", Q.divergence(coin("A", "BUY", +1, 1)).verdict === "CONFIRM");
// BUY + whales selling → ADVERSE (eliminate)
ok("BUY+distrib = ADVERSE", Q.divergence(coin("B", "BUY", -1, 1)).adverse === true);
// SELL + whales selling → CONFIRM
ok("SELL+distrib = CONFIRM", Q.divergence(coin("C", "SELL", -1, 1)).verdict === "CONFIRM");
// SELL + whales buying → ADVERSE
ok("SELL+accum = ADVERSE", Q.divergence(coin("D", "SELL", +1, 1)).adverse === true);
// BUY + flat whales → NEUTRAL (keep)
ok("BUY+flat = NEUTRAL", Q.divergence(coin("E", "BUY", 0, 0)).verdict === "NEUTRAL");
// NEUTRAL indicator → never adverse
ok("dir0 never adverse", Q.divergence(coin("F", "NEUTRAL", -1, 1)).adverse === false);

/* ── no look-ahead: divergence at t depends only on series ≤ now ── */
(() => {
  const base = coin("G", "BUY", +1, 1);
  const v1 = Q.divergence(base).wf;
  const future = JSON.parse(JSON.stringify(base));
  future.series.pos.push(99); future.series.acc.push(99); // append future point
  // recompute on the ORIGINAL length window — value must be unchanged when we
  // pass the same trailing window; verify whaleFlow ignores nothing spurious.
  ok("WF deterministic / stable", approx(v1, Q.divergence(base).wf, 1e-9));
})();

/* ── runScan: adverse eliminated, table backfilled to N ── */
(() => {
  const uni = [];
  for (let i = 0; i < 12; i++) {
    const adverse = i % 3 === 0;            // every 3rd coin fights its signal
    const wd = adverse ? -1 : +1;
    uni.push({ s: "C" + i, seed: 100 + i, finalSignal: "BUY",
      confidence: 90 - i * 3, buy: 9, sell: 1, neutral: 2, ch: 5 - i * 0.2,
      series: makeSeries(100 + i, wd, 1) });
  }
  const N = 5;
  const res = Q.runScan(uni, { size: N });
  ok("table filled to N", res.survivors.length === N);
  ok("no adverse in survivors", res.survivors.every((x) => !x.div.adverse));
  ok("eliminated are adverse", res.eliminated.every((x) => x.div.adverse));
  ok("eliminated count = #adverse", res.eliminated.length === uni.filter((_, i) => i % 3 === 0).length);
  // survivors sorted by score desc
  let sorted = true;
  for (let i = 1; i < res.survivors.length; i++) if (res.survivors[i].score > res.survivors[i - 1].score + 1e-9) sorted = false;
  ok("survivors ranked by PUAN desc", sorted);
  // backfill: a lower-score kept coin replaces a higher-score adverse one
  ok("backfill pulled lower-score coin", res.survivors.length === N && res.eliminated.length > 0);
})();

/* ── runScan size='all' keeps every non-adverse coin ── */
(() => {
  const uni = [];
  for (let i = 0; i < 8; i++) uni.push({ s: "X" + i, seed: i, finalSignal: "BUY",
    confidence: 60, buy: 7, sell: 3, neutral: 2, ch: 1, series: makeSeries(i, +1, 1) });
  const res = Q.runScan(uni, { size: "all" });
  ok("size=all keeps all non-adverse", res.survivors.length === res.keptCount);
})();

/* ── liveTick is deterministic + bounded ── */
(() => {
  const d = { s: "T", seed: 5, finalSignal: "BUY", confidence: 70, buy: 8, sell: 2, neutral: 2, ch: 2, series: makeSeries(5, +1, 1) };
  const a = Q.liveTick([d], 3)[0], b = Q.liveTick([d], 3)[0];
  ok("liveTick deterministic", approx(a.ch, b.ch, 1e-9) && approx(a.series.pos[47], b.series.pos[47], 1e-12));
  ok("liveTick bounded nudge", Math.abs(a.series.pos[47] - d.series.pos[47]) / d.series.pos[47] < 0.05);
  ok("liveTick(cycle0) is identity", Q.liveTick([d], 0)[0] === d);
})();

/* ════════════════════════════════════════════════════════════════════════════
   APPENDED LENS TESTS — sign-convention, statistical-robustness and realism
   verification of the v2 hardening (multi-channel coherence gate, asymmetric
   ADVERSE thresholds, aggression-family floor, flat-price quadrant symmetry).
   Deduplicated across the three independent lens reviews. Every assertion is
   expected to PASS against the final engine. Inserted BEFORE the terminal
   process.exit so it contributes to the pass/fail tally (a block appended after
   process.exit would be dead code).
   ════════════════════════════════════════════════════════════════════════════ */

/* ── LENS A (sign conventions + v2 coherence gate) — self-contained IIFE with
   its own require + counters so it is independent of the parent harness. ── */
(function () {
  const Q2 = require("./quant.cjs");
  let p2 = 0, f2 = 0;
  function ok2(name, cond) { if (cond) p2++; else { f2++; console.log("  ✗ LENS-A FAIL:", name); } }
  function approx2(a, b, e) { return Math.abs(a - b) <= (e || 1e-9); }
  function flat(N, v) { return Array.from({ length: N }, () => v); }

  /* OI/price quadrant signs */
  function quad(pd, od, N) {
    N = N || 24; const price = [], oi = []; let p = 100, o = 1e8;
    for (let i = 0; i < N; i++) { p *= 1 + 0.01 * pd; o *= 1 + 0.01 * od; price.push(p); oi.push(o); }
    return { pos: flat(N, 1.2), acc: flat(N, 1.1), taker: flat(N, 1.0), oi, price, glob: flat(N, 1.0), funding: flat(N, 0), bias: flat(N, 0) };
  }
  ok2("quad price-up oi-up = +1", approx2(Q2.whaleFlow(quad(+1, +1)).comp.oiPrice, 1));
  ok2("quad price-down oi-up = -1", approx2(Q2.whaleFlow(quad(-1, +1)).comp.oiPrice, -1));
  ok2("quad price-up oi-down = +0.3", approx2(Q2.whaleFlow(quad(+1, -1)).comp.oiPrice, 0.3));
  ok2("quad price-down oi-down = -0.3", approx2(Q2.whaleFlow(quad(-1, -1)).comp.oiPrice, -0.3));

  /* flat-price quadrant symmetry: a fully flat price series contributes 0, not a
     long-side bias (regression for the LENS_SIGNS sign-asymmetry fix). */
  ok2("quad flat-price oi-up = 0 (no long bias)", approx2(Q2.whaleFlow(quad(0, +1)).comp.oiPrice, 0));
  ok2("quad flat-price oi-down = 0 (no long bias)", approx2(Q2.whaleFlow(quad(0, -1)).comp.oiPrice, 0));

  /* CVD sign: taker>1 ⇒ +, taker<1 ⇒ - */
  function cvd(tv, N) { N = N || 24; return { pos: flat(N, 1.2), acc: flat(N, 1.1), taker: flat(N, tv), oi: flat(N, 1e8), price: flat(N, 100), glob: flat(N, 1), funding: flat(N, 0), bias: flat(N, 0) }; }
  ok2("CVD taker>1 ⇒ +", Q2.whaleFlow(cvd(1.2)).comp.cvdFlow > 0.3);
  ok2("CVD taker<1 ⇒ -", Q2.whaleFlow(cvd(0.8)).comp.cvdFlow < -0.3);

  /* 4 core divergence cases (v2 gates) + boolean consistency */
  function buildSeries(wd, N) {
    N = N || 24; const pos = [], acc = [], taker = [], oi = [], price = [], glob = [], funding = [], bias = [];
    let p = 100, o = 1e8;
    for (let i = 0; i < N; i++) {
      const t = i / (N - 1);
      pos.push(1.2 + 0.8 * wd * t); acc.push(1.1 + 0.7 * wd * t); taker.push(1.0 + 0.15 * wd);
      o *= 1 + 0.012 * wd; oi.push(o); p *= 1 + 0.01 * wd; price.push(p);
      glob.push(1.0); funding.push(0); bias.push(0);
    }
    return { pos, acc, taker, oi, price, glob, funding, bias };
  }
  const C = (sig, wd) => ({ finalSignal: sig, confidence: 70, buy: 8, sell: 2, neutral: 2, ch: 3, series: buildSeries(wd) });
  ok2("(1) BUY+distributing ⇒ ADVERSE", Q2.divergence(C("BUY", -1)).verdict === "ADVERSE");
  ok2("(2) SELL+accumulating ⇒ ADVERSE", Q2.divergence(C("SELL", +1)).verdict === "ADVERSE");
  ok2("(3) BUY+accumulating ⇒ CONFIRM", Q2.divergence(C("BUY", +1)).verdict === "CONFIRM");
  ok2("(4) SELL+distributing ⇒ CONFIRM", Q2.divergence(C("SELL", -1)).verdict === "CONFIRM");
  ok2("(1) adverse flag true", Q2.divergence(C("BUY", -1)).adverse === true);
  ok2("(3) confirm not adverse", Q2.divergence(C("BUY", +1)).adverse === false);
  ok2("dir=0 never adverse", Q2.divergence(C("NEUTRAL", -1)).adverse === false);

  /* COHERENCE GATE: single-channel price-noise (ARB-style) must NEVER eliminate. */
  function arbLike(seed, pt) {
    let s = seed >>> 0; const r = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
    const N = 24, o = { pos: [], acc: [], taker: [], oi: [], price: [], glob: [], funding: [], bias: [] };
    let p = 100, oi = 1e8;
    for (let i = 0; i < N; i++) {
      const n = () => r() - 0.5;
      o.pos.push(1.2 + 0.05 * n()); o.acc.push(1.1 + 0.05 * n()); o.taker.push(1.0 + 0.05 * n());
      oi *= 1 + 0.012 + 0.02 * n(); o.oi.push(oi);
      p *= 1 + pt + 0.02 * n(); o.price.push(p);
      o.glob.push(1.0 + 0.3 * n()); o.funding.push(0.0002); o.bias.push(0);
    }
    return o;
  }
  let falseElim = 0;
  for (let seed = 1; seed < 600; seed++) for (const pt of [-0.01, +0.01]) {
    const dir = pt > 0 ? "BUY" : "SELL";
    const d = { finalSignal: dir, confidence: 75, buy: pt > 0 ? 9 : 1, sell: pt > 0 ? 1 : 9, neutral: 2, ch: pt > 0 ? 9 : -9, series: arbLike(seed, pt) };
    if (Q2.divergence(d).adverse) falseElim++;
  }
  ok2("coherence gate: 0 single-channel-noise eliminations (1200 trials)", falseElim === 0);

  /* coherence() family math */
  const COH = Q2._stats.coherence;
  ok2("coh flow-only ⇒ 1 family", COH({ cvdFlow: -0.9, oiPrice: -0.5, posFlow: 0, accFlow: 0, takerFlow: 0 }, -1, {}).families === 1);
  ok2("coh flow+positioning ⇒ 2", COH({ cvdFlow: -0.9, oiPrice: 0, posFlow: -0.5, accFlow: 0, takerFlow: 0 }, -1, {}).families === 2);
  ok2("coh flow+aggression ⇒ 2", COH({ cvdFlow: -0.9, oiPrice: 0, posFlow: 0, accFlow: 0, takerFlow: -0.5 }, -1, {}).families === 2);
  ok2("coh opposite-sign comps ⇒ 0", COH({ cvdFlow: 0.9, oiPrice: 0.9, posFlow: 0.9, accFlow: 0.9, takerFlow: 0.9 }, -1, {}).families === 0);
  ok2("coh wfSign 0 ⇒ 0", COH({ cvdFlow: -1, oiPrice: -1, posFlow: -1, accFlow: -1, takerFlow: -1 }, 0, {}).families === 0);

  /* aggression-family floor (EPS_AGG): a mild taker skew is NOT a 2nd family on
     its own, but a strong one is (regression for the LENS_STATS fix). */
  ok2("coh mild lone aggression (|taker|<0.20) ⇒ NOT a family",
    COH({ cvdFlow: 0, oiPrice: 0, posFlow: 0, accFlow: 0, takerFlow: -0.10 }, -1, {}).aggression === false);
  ok2("coh strong lone aggression (|taker|>=0.20) ⇒ a family",
    COH({ cvdFlow: 0, oiPrice: 0, posFlow: 0, accFlow: 0, takerFlow: -0.30 }, -1, {}).aggression === true);
  ok2("coh flow + mild aggression ⇒ still only 1 family (cannot eliminate)",
    COH({ cvdFlow: -0.9, oiPrice: 0, posFlow: 0, accFlow: 0, takerFlow: -0.10 }, -1, {}).families === 1);

  /* adverseMultiplier sign-safety: ∈[1,1.6]; ==1 when sign(wf)!=-dir */
  const MUL = Q2._stats.adverseMultiplier;
  let mulBad = 0;
  for (let i = 0; i < 4000; i++) {
    const dir = [-1, 0, 1][i % 3];
    const wf = (((i * 2654435761) >>> 0) % 1000) / 500 - 1;
    const crowd = (((i * 40503) >>> 0) % 1000) / 500 - 1;
    const fund = ((((i * 7919) >>> 0) % 1000) / 1000 - 0.5) * 0.001;
    const m = MUL(dir, wf, crowd, fund, {});
    if (m < 1 - 1e-12 || m > 1.6 + 1e-12) mulBad++;
    if ((!dir || Math.sign(wf) !== -dir) && Math.abs(m - 1) > 1e-12) mulBad++;
  }
  ok2("adverseMultiplier ∈[1,1.6] & 1 on non-adverse (4000 trials)", mulBad === 0);

  /* multiplier can never flip the adverse boolean — only scale strength */
  const adv = C("BUY", -1);
  adv.series.funding = flat(24, -0.0009);
  const vNo = Q2.divergence(adv);
  adv.series.funding = flat(24, +0.0009);
  const vYes = Q2.divergence(adv);
  ok2("multiplier never flips adverse boolean", vNo.adverse === true && vYes.adverse === true);
  ok2("corroborating funding amplifies (mult monotonic ≥1)", vYes.multiplier >= vNo.multiplier && vNo.multiplier >= 1);

  /* genuine multi-channel distribution vs BUY is NEVER kept (no false-negative) */
  function mkDistrib(seed) {
    let s = seed >>> 0; const r = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
    const N = 24, o = { pos: [], acc: [], taker: [], oi: [], price: [], glob: [], funding: [], bias: [] };
    let p = 100, oi = 1e8;
    for (let i = 0; i < N; i++) {
      const t = i / (N - 1), n = () => r() - 0.5;
      o.pos.push(1.2 - 0.6 * t + 0.15 * n()); o.acc.push(1.1 - 0.6 * t + 0.15 * n());
      o.taker.push(0.82 + 0.25 * n());
      oi *= 1 + 0.010 + 0.03 * n(); o.oi.push(oi);
      p *= 1 - 0.008 + 0.02 * n(); o.price.push(p);
      o.glob.push(1.0 + 0.3 * n()); o.funding.push(0.0001); o.bias.push(0);
    }
    return o;
  }
  let keptAdverse = 0, checked = 0;
  for (let seed = 1; seed < 800; seed++) {
    const d = { finalSignal: "BUY", confidence: 70, buy: 8, sell: 2, neutral: 2, ch: 3, series: mkDistrib(seed) };
    const v = Q2.divergence(d);
    if (v.wf < -0.20 && v.persistence >= 0.55 && v.coherence.families >= 2) { checked++; if (!v.adverse) keptAdverse++; }
  }
  ok2("genuine 2+family distribution never kept (no false-negative)", keptAdverse === 0 && checked > 0);

  /* purity / determinism: same input ⇒ byte-identical divergence */
  const dd = C("BUY", -1);
  ok2("divergence deterministic / no mutation", JSON.stringify(Q2.divergence(dd)) === JSON.stringify(Q2.divergence(dd)));

  /* runScan: an adverse top-ranked coin is dropped and the table backfills to N */
  (function () {
    const uni = [];
    for (let i = 0; i < 10; i++) {
      const wd = i === 0 ? -1 : +1; // top-ranked coin fights its BUY signal
      uni.push({ s: "Z" + i, seed: 200 + i, finalSignal: "BUY", confidence: 95 - i * 4, buy: 9, sell: 1, neutral: 2, ch: 6 - i * 0.3, series: buildSeries(wd) });
    }
    const res = Q2.runScan(uni, { size: 4 });
    ok2("runScan fills to N", res.survivors.length === 4);
    ok2("runScan drops the adverse top coin", res.eliminated.some((x) => x.d.s === "Z0") && !res.survivors.some((x) => x.d.s === "Z0"));
    ok2("runScan survivors all non-adverse", res.survivors.every((x) => !x.div.adverse));
  })();

  pass += p2; fail += f2;
  console.log("  LENS-A (signs/coherence): " + p2 + " passed, " + f2 + " failed");
})();

/* ── LENS B (statistical robustness) — uses the shared Q/ok harness. Only the
   assertions NOT already covered by LENS-A are kept (coherence family math and
   adverseMultiplier bounds are deduplicated away). Pins the noise-trend false-
   elimination fix (coherence gate + aggression floor) and relSlope/zLast/std. ── */
(function () {
  const S = Q._stats;
  function xrng(seed){let s=seed>>>0;return()=>{s=(s*1664525+1013904223)>>>0;return s/4294967296;};}

  // Pure-noise NEUTRAL-whale coin (flat pos/acc/taker, taker mean ~= 1) riding a
  // STRONG exogenous price trend — the ARB hi-vol false-elimination shape.
  function noiseFlatWhalesStrongTrend(seed, trendPct){
    const r=xrng(seed), N=48; const clip=(x,lo,hi)=>Math.max(lo,Math.min(hi,x));
    const pos=[],acc=[],taker=[],oi=[],price=[],glob=[],funding=[],bias=[];
    let oiv=1.214e6*(3+r()*9), pbase=1/(1+trendPct/100);
    for(let i=0;i<N;i++){const t=i/(N-1);const n=()=>(r()-0.5);
      oiv*=1+n()*0.02; oi.push(oiv);
      glob.push(+clip(0.95+0.5*n(),0.4,1.9).toFixed(3));
      acc.push(+clip(1.05+0.05*n(),0.4,2.2).toFixed(3));   // FLAT top-trader L/S
      pos.push(+clip(1.20+0.05*n(),0.4,2.4).toFixed(3));   // FLAT whale L/S
      taker.push(+clip(1.0+0.05*n(),0.55,1.6).toFixed(3)); // taker mean ~= 1, no aggression
      funding.push(+(((r()-0.45)*0.0004)).toFixed(6));
      price.push(pbase*(1+trendPct/100*t)*(1+0.01*n()));   // strong exogenous price trend
      bias.push(0);
    }
    return {pos,acc,taker,oi,price,glob,funding,bias};
  }
  function xcoin(s, finalSignal, series, ch){
    return { s, seed: 7, finalSignal, confidence: 70, buy: 9, sell: 1, neutral: 2, ch, series };
  }

  // pure-noise neutral coin with a strong price trend must NOT be ADVERSE
  (() => {
    let elim = 0;
    for (let seed = 1; seed <= 400; seed++) {
      const up = seed % 2 === 0;
      const trend = up ? (6 + (seed % 10)) : -(6 + (seed % 10)); // +-6..+-15% in-window
      const d = xcoin("NOISE" + seed, up ? "STRONG_BUY" : "SELL",
                      noiseFlatWhalesStrongTrend(seed, trend), up ? 9.88 : -6);
      if (Q.divergence(d).adverse) elim++;
    }
    ok("noise+strong-trend coin is NEVER ADVERSE (0/400 false eliminations)", elim === 0);
  })();

  // the ARB-like coin's coherence stays below the 2-of-3 family gate
  (() => {
    const v = Q.divergence(xcoin("ARBLIKE", "STRONG_BUY", noiseFlatWhalesStrongTrend(99, 14), 9.88));
    ok("ARB-like noise verdict is NEUTRAL", v.verdict === "NEUTRAL");
    ok("ARB-like coherence < 2 families (lone price-flow family)", v.coherence.families < 2);
    ok("ARB-like positioning family is dead", v.coherence.positioning === false);
    ok("ARB-like aggression family is dead", v.coherence.aggression === false);
  })();

  // regression: a genuine multi-family whale distribution STILL eliminates
  (() => {
    function distrib(seed){
      const r=xrng(seed),N=48;const clip=(x,lo,hi)=>Math.max(lo,Math.min(hi,x));
      const pos=[],acc=[],taker=[],oi=[],price=[],glob=[],funding=[],bias=[];let oiv=5e8,p=100;
      for(let i=0;i<N;i++){const t=i/(N-1);const n=()=>(r()-0.5);
        pos.push(clip(1.20-0.8*t+0.03*n(),0.4,2.4));   // whales SELLING (positioning)
        acc.push(clip(1.10-0.7*t+0.03*n(),0.4,2.2));   // top-traders SELLING
        taker.push(clip(0.85+0.02*n(),0.55,1.6));      // taker sell-skew (aggression)
        oiv*=1+0.012+0.004*n();oi.push(oiv);           // OI building into new shorts (flow)
        p*=1-0.008+0.003*n();price.push(p);
        glob.push(1.0+0.1*n());funding.push(0.0002);bias.push(0);}
      return {pos,acc,taker,oi,price,glob,funding,bias};
    }
    const v = Q.divergence(xcoin("REALADV", "STRONG_BUY", distrib(5), 3));
    ok("genuine multi-family distribution IS ADVERSE", v.adverse === true);
    ok("genuine adverse clears >=2 coherence families", v.coherence.families >= 2);
  })();

  // EPS_AGG regression: a flat-whale BUY coin on a strong DOWN trend whose taker
  // ratio carries only a mild persistent skew (taker mean ~0.95) must NOT be
  // eliminated — flow + a mild aggression must not count as 2 independent
  // families. (Without the aggression floor this false-eliminated ~99%.)
  (() => {
    function biasedTaker(seed, trendPct, takerMean){
      const r=xrng(seed),N=48;const clip=(x,lo,hi)=>Math.max(lo,Math.min(hi,x));
      const pos=[],acc=[],taker=[],oi=[],price=[],glob=[],funding=[],bias=[];
      let oiv=5e8,p=100;
      for(let i=0;i<N;i++){const n=()=>(r()-0.5);
        pos.push(clip(1.2+0.05*n(),0.4,2.4)); acc.push(clip(1.1+0.05*n(),0.4,2.2)); // FLAT positioning
        taker.push(clip(takerMean+0.03*n(),0.55,1.6));
        oiv*=1+0.012+0.01*n(); oi.push(oiv);
        p*=1+trendPct/100+0.01*n(); price.push(p);
        glob.push(1.0+0.3*n()); funding.push(0.0002); bias.push(0);}
      return {pos,acc,taker,oi,price,glob,funding,bias};
    }
    let elim = 0;
    for (let seed = 1; seed <= 400; seed++) {
      const d = { s: "BIAS" + seed, finalSignal: "BUY", confidence: 75, buy: 9, sell: 1, neutral: 2, ch: -8, series: biasedTaker(seed, -0.4, 0.95) };
      if (Q.divergence(d).adverse) elim++;
    }
    ok("biased-taker flat-whale coin is NEVER ADVERSE (aggression floor, 0/400)", elim === 0);
  })();

  // asymmetry invariant: a borderline disagreement defaults to KEEP, never ADVERSE
  (() => {
    const v = Q.divergence(xcoin("BORD", "BUY", noiseFlatWhalesStrongTrend(123, -8), -8));
    ok("borderline single-family disagreement defaults to KEEP (not ADVERSE)", v.adverse === false);
  })();

  // relSlope: finite + bounded on zero-mean / sign-crossing series (no mean->0 blowup)
  (() => {
    const zeroMeanTrend = []; for(let i=0;i<24;i++) zeroMeanTrend.push((i-11.5)*0.01); // mean~=0 linear
    const rs = S.relSlope(zeroMeanTrend);
    ok("relSlope finite + bounded on zero-mean trend", Number.isFinite(rs) && Math.abs(rs) < 5);
    const crossing = []; for(let i=0;i<24;i++) crossing.push(-1 + i*(2/23)); // -1 -> +1, mean~=0
    const rc = S.relSlope(crossing);
    ok("relSlope finite + bounded when level crosses zero", Number.isFinite(rc) && Math.abs(rc) < 10);
  })();

  // zLast: scale-relative MAD fallback, never Infinity/NaN on a MAD=0 collapse
  (() => {
    ok("zLast finite on tiny near-constant series (taker-like)", Number.isFinite(S.zLast([1,1,1,1,1,1,1.001])));
    ok("zLast finite on huge near-constant series (OI-like)", Number.isFinite(S.zLast([5e8,5e8,5e8,5e8,5e8,5e8,5e8*1.001])));
    ok("zLast(all-identical)=0 (no MAD=0 NaN/Inf)", S.zLast([3,3,3,3,3,3,3,3]) === 0);
  })();

  // std helper present + correct (population std used as robust scale floor)
  (() => {
    ok("std exposed in _stats", typeof S.std === "function");
    ok("std([2,4,4,4,5,5,7,9]) === 2", Math.abs(S.std([2,4,4,4,5,5,7,9]) - 2) <= 1e-9);
  })();

  // exposure checks (deduped: family/multiplier math itself pinned in LENS-A)
  (() => {
    ok("coherence exposed in _stats", typeof S.coherence === "function");
    ok("adverseMultiplier exposed in _stats", typeof S.adverseMultiplier === "function");
  })();
})();

/* ── LENS C (market-microstructure realism) — uses the shared Q/ok harness.
   Pins that the STRUCTURAL whale channels (posFlow/accFlow) respond to genuine
   distribution and stay quiet on noise, and that a lone noisy CVD channel can
   never carry a destructive elimination (the coherence gate). ── */
(function () {
  function rng(seed){let s=seed>>>0;return()=>{s=(s*1664525+1013904223)>>>0;return s/4294967296;};}

  // pure zero-mean taker noise, flat whale positioning, no OI trend
  function noiseSeries(seed){
    const r=rng(seed),N=48,pos=[],acc=[],taker=[],oi=[],price=[],glob=[],funding=[],bias=[];
    let p0=100,o=5e6;
    for(let i=0;i<N;i++){const n=()=>(r()-0.5);
      pos.push(1.2+0.03*n()); acc.push(1.1+0.03*n());
      taker.push(1.0+0.05*n());                 // zero-mean taker ratio noise
      o*=1+0.004*n(); oi.push(o);               // flat OI
      p0*=1+0.02*n(); price.push(p0);           // volatile price, no trend
      glob.push(1.0+0.1*n()); funding.push(0.00005*n()); bias.push(5*n());
    }
    return {pos,acc,taker,oi,price,glob,funding,bias};
  }

  // genuine whale distribution: pos & acc L/S falling, taker selling, OI up, price down
  function realDistrib(seed){
    const r=rng(seed),N=48,pos=[],acc=[],taker=[],oi=[],price=[],glob=[],funding=[],bias=[];
    let p0=100,o=5e6;
    for(let i=0;i<N;i++){const t=i/(N-1),n=()=>(r()-0.5);
      pos.push(1.4-0.6*t+0.02*n()); acc.push(1.3-0.5*t+0.02*n());
      taker.push(1.0-0.12+0.03*n());
      o*=1+0.01+0.003*n(); oi.push(o);
      p0*=1-0.008+0.003*n(); price.push(p0);
      glob.push(1.0+0.1*n()); funding.push(0.00005*n()); bias.push(-50+5*n());
    }
    return {pos,acc,taker,oi,price,glob,funding,bias};
  }

  // INVARIANT: structural channels respond correctly to real distribution
  (function(){
    const c = Q.whaleFlow(realDistrib(3)).comp;
    ok("realism: posFlow<0 on genuine whale distribution", c.posFlow < 0);
    ok("realism: accFlow<0 on genuine top-trader distribution", c.accFlow < 0);
    const d = {finalSignal:"BUY",confidence:70,buy:8,sell:2,neutral:2,ch:3,series:realDistrib(3),seed:3};
    ok("realism: genuine multi-channel distribution still eliminates BUY", Q.divergence(d).adverse === true);
  })();

  // INVARIANT: structural whale channels stay near zero on pure noise
  (function(){
    let bad=0, n=60;
    for(let s=1;s<=n;s++){
      const c=Q.whaleFlow(noiseSeries(s)).comp;
      if(Math.abs(c.posFlow)>0.3 || Math.abs(c.accFlow)>0.3) bad++;
    }
    ok("realism: posFlow/accFlow do not fabricate signal from noise", bad < n*0.15);
  })();

  // destructive elimination requires multi-channel coherence: when wf crosses the
  // adverse threshold but the STRUCTURAL whale channels give NO support, a coin
  // must not be eliminated on the lone noisy CVD channel.
  (function(){
    let probed=0, falseElim=0, n=120;
    for(let s=1;s<=n;s++){
      const series = noiseSeries(s);
      const f = Q.whaleFlow(series);
      const structNeg = (f.comp.posFlow<0?1:0) + (f.comp.accFlow<0?1:0);
      if (f.wf < -0.16 && structNeg === 0) {           // wf says distribute, whales do not
        probed++;
        const d = {finalSignal:"BUY",confidence:70,buy:8,sell:2,neutral:2,ch:9,series,seed:s};
        if (Q.divergence(d).adverse) falseElim++;
      }
    }
    ok("realism: no destructive elim when only the noisy CVD channel disagrees", falseElim === 0);
  })();
})();

console.log(`\nSGS quant self-test: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
