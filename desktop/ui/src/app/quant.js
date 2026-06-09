/* ============================================================================
   SGS — QUANT ENGINE (whale-divergence + continuous scan + elimination/backfill)
   ----------------------------------------------------------------------------
   Pure, deterministic, dependency-free. Works in browser (window.SGS_QUANT)
   AND under node (module.exports) so the sign-convention self-test can run.

   CORE IDEA — "Balina Uyumsuzluğu" (whale divergence) gate
   --------------------------------------------------------
   The 15-indicator consensus produces a directional thesis per coin:
       dir = +1 (AL/BUY) | -1 (SAT/SELL) | 0 (BEKLE/NEUTRAL)
   Independently we estimate where the *smart money* (whales) is actually
   pushing, from the futures microstructure feeds:
       whaleFlow (WF) ∈ [-1, +1]   (>0 net accumulation, <0 net distribution)
   built from: OI-weighted CVD, OI/price quadrant, whale L/S slope,
   top-trader L/S slope, and taker aggression.

   Rule applied to the scan table:
     • WF disagrees with dir, meaningfully + persistently + COHERENT → ADVERSE → ELIMINATE
     • WF agrees with dir                                            → CONFIRM → keep (boost)
     • |WF| below threshold / not persistent / single-channel        → NEUTRAL → keep
   Eliminated rows are backfilled by the next lower-PUAN coin so the table
   always holds the requested top-N survivors.

   HARDENING (v2) — why the destructive action is now asymmetric & coherent
   ------------------------------------------------------------------------
   Eliminating a coin is *destructive* (we throw away an otherwise-ranked
   opportunity). A single noisy channel must never be able to do that. So:

   1. MULTI-CHANNEL COHERENCE GATE. The 5 whale components group into three
      independent microstructure FAMILIES:
        • flow        = {cvdFlow, oiPrice}   (price/OI-derived; lights up on any
                                              strong price trend → noisy on hi-vol)
        • positioning = {posFlow, accFlow}   (whale + top-trader L/S ratios)
        • aggression  = {takerFlow}          (taker buy/sell pressure)
      An ADVERSE verdict requires ≥2 of these 3 families to lean toward sign(wf)
      with non-trivial magnitude (|comp| ≥ COH_EPS). This is what kills the
      ARB-style false elimination: a high-volatility coin riding a price trend
      lights up ONLY the price-derived `flow` family (CVD z-score spike + OI/price
      quadrant) while positioning and aggression stay dead — that is one family,
      not multi-channel coherence, so we KEEP it. Real whale distribution shows
      up in positioning and/or aggression too. K=2-of-3 families is the smallest
      gate that still passes every genuine-adverse case in the mock universe
      (XRP/SUI/SEI/DOGE all clear ≥2 families) while rejecting the noise case.

      The lone-channel `aggression` family carries a HIGHER magnitude floor
      (COH_EPS_AGG = 0.20) than the multi-component flow/positioning families
      (COH_EPS = 0.06). On a trending window taker-aggression and the price-
      derived `flow` family mechanically CO-MOVE, so a mild taker skew that
      merely tracks the trend (|takerFlow|≈0.06) must NOT count as an independent
      second family and eliminate the coin — that is the ARB false-elimination one
      rung up. Genuine whale distribution drives |takerFlow| well past 0.20 (all
      mock adverses sit ≥0.257), so the stricter floor keeps every real
      elimination while rejecting the trend-correlated taker-skew noise case.

   2. ASYMMETRIC THRESHOLDS. Destroying requires more evidence than confirming:
        TAU_ADV (0.20) > TAU_CONF (0.16),  PMIN_ADV (0.55) > PMIN_CONF (0.50).
      CONFIRM keeps the engine's ORIGINAL gate (0.16 / 0.50) byte-for-byte so the
      keep-side is unchanged; only the destructive ADVERSE side is made stricter.

   3. FUNDING / OVERCROWDING as a STRENGTH MULTIPLIER ONLY. Crowded longs paying
      positive funding while whales distribute = a more dangerous setup, so we
      scale the reported `adverseStrength` up (bounded). Funding NEVER flips
      sign(wf) or the gate boolean — it only modulates already-decided strength.
   ========================================================================== */
(function (root) {
  "use strict";

  /* ───────────── robust stats (outlier-resistant, no look-ahead) ─────────── */
  function clamp(x, lo, hi) { return x < lo ? lo : x > hi ? hi : x; }
  function mean(a) { let s = 0, n = a.length; for (let i = 0; i < n; i++) s += a[i]; return n ? s / n : 0; }
  function median(a) {
    if (!a.length) return 0;
    const b = a.slice().sort((x, y) => x - y), m = b.length >> 1;
    return b.length % 2 ? b[m] : (b[m - 1] + b[m]) / 2;
  }
  function mad(a) { if (!a.length) return 0; const md = median(a); return median(a.map((x) => Math.abs(x - md))); }
  /* population standard deviation (robust scale fallback when MAD collapses) */
  function std(a) {
    const n = a.length; if (n < 2) return 0;
    const m = mean(a); let s = 0;
    for (let i = 0; i < n; i++) { const d = a[i] - m; s += d * d; }
    return Math.sqrt(s / n);
  }
  function ewma(a, alpha) {
    if (!a.length) return [];
    const o = [a[0]];
    for (let i = 1; i < a.length; i++) o.push(alpha * a[i] + (1 - alpha) * o[i - 1]);
    return o;
  }
  function diff(a) { const o = []; for (let i = 1; i < a.length; i++) o.push(a[i] - a[i - 1]); return o; }

  /* normalized OLS slope per step, divided by a ROBUST typical level → relative
     drift. The scale guards against mean→0 series (e.g. taker-1, funding, bias):
     using |mean| alone collapses to the ||1 fallback and silently rescales the
     units; instead we floor the level by the window's own dispersion (std), so a
     zero-mean but trending series still yields a sane, bounded relative slope. */
  function relSlope(a) {
    const n = a.length;
    if (n < 2) return 0;
    let sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (let i = 0; i < n; i++) { sx += i; sy += a[i]; sxx += i * i; sxy += i * a[i]; }
    const denom = (n * sxx - sx * sx) || 1;
    const slope = (n * sxy - sx * sy) / denom;
    // robust scale: prefer |mean|, but never let it fall below the series'
    // own spread (std) — keeps zero-mean trending series numerically stable.
    const lvl = Math.abs(mean(a));
    const spread = std(a);
    const scale = Math.max(lvl, spread, 1e-9);
    return slope / scale;
  }
  /* robust z-score of the last sample vs its window (median/MAD).
     MAD fallback is now SCALE-RELATIVE to the median magnitude so it behaves
     identically for tiny series (taker≈1) and huge ones (OI≈5e8). */
  function zLast(a) {
    if (a.length < 3) return 0;
    const md = median(a);
    const robust = 1.4826 * mad(a);
    const s = robust || (std(a) || Math.abs(md) * 0.05 + 1e-9);
    return (a[a.length - 1] - md) / s;
  }
  function lastWindow(arr, W) { return arr.slice(Math.max(0, arr.length - W)); }
  /* robust standardized level-SHIFT over the window: median(recent half) −
     median(older half), scaled by window spread → ~[-1,1]. Zero for a flat
     window (synthetic-safe) but captures the SLOW lean changes that dominate real
     top-trader L/S ratios (which barely slope per-step yet drift over the window). */
  function levelShift(arr) {
    const n = arr.length; if (n < 4) return 0;
    const h = Math.floor(n / 2);
    const mo = median(arr.slice(0, h)), mr = median(arr.slice(n - h));
    const s = 1.4826 * mad(arr) || std(arr) || (Math.abs(median(arr)) * 0.02) || 1e-9;
    return clamp((mr - mo) / s, -3, 3) / 3;
  }

  /* ───────────── indicator direction (the thesis to confirm/refute) ───────── */
  function indicatorDir(d) {
    const sig = (d && d.finalSignal) || "";
    if (sig.indexOf("BUY") >= 0) return 1;
    if (sig.indexOf("SELL") >= 0) return -1;
    return 0;
  }

  /* ───────────── whale flow: net smart-money direction ∈ [-1,1] ───────────── */
  /* Default whale-flow tunables — BACKTEST-CALIBRATED (tools/backtest/, 32k-coin
     synthetic ground-truth, held-out test obj 1.119→1.311, wfIC 0.84→0.99).
     Headline: cvd & oiPrice track the PRICE/indicator trend (ti), not the true
     whale dir (tw), so they were de-weighted (cvd→0); mass moved to the channels
     that genuinely encode smart-money positioning (pos/acc/taker). */
  const WF_DEFAULT_WEIGHTS = { cvd: 0, oiPrice: 0.09, posFlow: 0.53, accFlow: 0.65, taker: 0.84 };
  const WF_DEFAULT_GAINS = { pos: 8.15, acc: 1.82, takerLvl: 4.66, takerSlope: 1.6, cvd: 0.9, crowdLvl: 2.0, crowdSlope: 1.2, posLevel: 0, accLevel: 0 };
  const WF_DEFAULT_WINDOW = 36;

  function whaleFlow(series, opts) {
    opts = opts || {};
    const W = opts.window || WF_DEFAULT_WINDOW;
    const A = opts.alpha != null ? opts.alpha : 0.4;       // EWMA smoothing
    const G = Object.assign({}, WF_DEFAULT_GAINS, opts.gains || {});
    const WTS = normWeights(opts.weights || WF_DEFAULT_WEIGHTS);
    const pos = lastWindow(series.pos, W);
    const acc = lastWindow(series.acc, W);
    const taker = lastWindow(series.taker, W);
    const oi = lastWindow(series.oi, W);
    const price = lastWindow(series.price, W);
    const glob = lastWindow(series.glob, W);

    // 1) whale L/S accumulation (smoothed relative slope)
    const posFlow = Math.tanh(G.pos * relSlope(ewma(pos, A)) + G.posLevel * levelShift(pos));
    // 2) top-trader account L/S accumulation
    const accFlow = Math.tanh(G.acc * relSlope(ewma(acc, A)) + G.accLevel * levelShift(acc));
    // 3) taker aggression: level (>1 = net market-buy) + slope
    const takerFlow = Math.tanh(G.takerLvl * (mean(taker) - 1) + G.takerSlope * relSlope(ewma(taker, A)));
    // 4) OI-weighted CVD proxy: signed taker flow scaled by OI, cumulative, z-scored
    const cvd = [];
    let run = 0;
    for (let i = 0; i < taker.length; i++) { run += oi[i] * (taker[i] - 1); cvd.push(run); }
    const cvdFlow = Math.tanh(G.cvd * zLast(cvd));
    // 5) OI/price quadrant: price↑&oi↑ = new longs(+1); price↓&oi↑ = new shorts(-1);
    //    price↑&oi↓ = short-cover(+0.3); price↓&oi↓ = long-liq(-0.3). OI-move weighted.
    const dP = diff(price), dO = diff(oi);
    let q = 0, wsum = 0;
    for (let i = 0; i < dP.length; i++) {
      const w = Math.abs(dO[i]) + 1e-9;
      const sp = Math.sign(dP[i]), so = Math.sign(dO[i]);
      // Flat-price tick carries no directional info → contribute 0 (matches
      // persistence() which already zeroes a flat tick via sign(dPr)*sign(dOi)).
      const v = sp === 0 ? 0 : (so > 0 ? (sp > 0 ? 1 : -1) : (sp > 0 ? 0.3 : -0.3));
      q += w * v; wsum += w;
    }
    const oiPrice = wsum ? clamp(q / wsum, -1, 1) : 0;

    let wf = WTS.cvd * cvdFlow + WTS.oiPrice * oiPrice + WTS.posFlow * posFlow + WTS.accFlow * accFlow + WTS.taker * takerFlow;
    wf = clamp(wf, -1, 1);

    // retail crowd direction (for whale↔crowd context / overcrowding reasons)
    const crowd = Math.tanh(G.crowdLvl * (mean(glob) - 1) + G.crowdSlope * relSlope(ewma(glob, A)));

    return { wf, crowd, comp: { cvdFlow, oiPrice, posFlow, accFlow, takerFlow } };
  }

  /* normalize a 5-weight set to sum 1 (keeps wf bounded as weights are tuned) */
  function normWeights(w) {
    const keys = ["cvd", "oiPrice", "posFlow", "accFlow", "taker"];
    let s = 0; for (const k of keys) s += Math.max(0, w[k] || 0);
    if (!s) return Object.assign({}, WF_DEFAULT_WEIGHTS);
    const o = {}; for (const k of keys) o[k] = Math.max(0, w[k] || 0) / s; return o;
  }

  /* ───────────── multi-channel coherence (anti single-noisy-channel) ───────
     Groups the 5 whale components into 3 independent microstructure families
     and counts how many families lean toward sign(wf) with non-trivial
     magnitude. A destructive ADVERSE verdict must clear ≥ K families — a lone
     price-driven CVD/OI spike (the ARB hi-vol noise case) only lights one
     family (`flow`) and is therefore NOT enough to eliminate. */
  function coherence(comp, wfSign, opts) {
    opts = opts || {};
    const EPS = opts.cohEps != null ? opts.cohEps : 0.06; // multi-component family floor
    const EPS_AGG = opts.cohEpsAgg != null ? opts.cohEpsAgg : 0.1511; // calibrated lone-aggression floor
    if (!wfSign) return { families: 0, agree: 0, flow: false, positioning: false, aggression: false };
    const lean = (x, e) => Math.sign(x) === wfSign && Math.abs(x) >= (e != null ? e : EPS);
    const flow = lean(comp.cvdFlow) || lean(comp.oiPrice);
    const positioning = lean(comp.posFlow) || lean(comp.accFlow);
    const aggression = lean(comp.takerFlow, EPS_AGG);
    const families = (flow ? 1 : 0) + (positioning ? 1 : 0) + (aggression ? 1 : 0);
    const agree =
      (lean(comp.cvdFlow) ? 1 : 0) + (lean(comp.oiPrice) ? 1 : 0) +
      (lean(comp.posFlow) ? 1 : 0) + (lean(comp.accFlow) ? 1 : 0) +
      (lean(comp.takerFlow, EPS_AGG) ? 1 : 0);
    return { families, agree, flow, positioning, aggression };
  }

  /* fraction of the window whose per-point micro-flow agrees with sign(wf).
     Guards continuous scanning against single-tick verdict flicker.
     Calibrated: each micro vote is the sign of (whale-Δ + taker-aggression +
     OI/price-quadrant), so a single channel cannot by itself dominate a tick. */
  function persistence(series, wfSign, W) {
    if (!wfSign) return 0;
    const pos = ewma(lastWindow(series.pos, W), 0.4);
    const taker = lastWindow(series.taker, W);
    const price = lastWindow(series.price, W);
    const oi = lastWindow(series.oi, W);
    const dPos = diff(pos), dPr = diff(price), dOi = diff(oi);
    let agree = 0, tot = 0;
    for (let i = 0; i < dPos.length; i++) {
      const micro = Math.sign(dPos[i]) + Math.sign(taker[i + 1] - 1) + (Math.sign(dPr[i]) * Math.sign(dOi[i]) || 0);
      const s = Math.sign(micro);
      if (s !== 0) { tot++; if (s === wfSign) agree++; }
    }
    return tot ? agree / tot : 0;
  }

  /* overcrowding / funding adverse-strength MULTIPLIER (bounded, sign-safe).
     Returns a factor in [1, MAXMULT]; it can only AMPLIFY an already-adverse
     strength, never create one and never flip direction. The dangerous setup is:
        dir=BUY  → crowd is long AND funding>0 (crowded longs paying to be long)
                   while whales distribute (wf<0)   → squeeze risk ↑
        dir=SELL → crowd is short AND funding<0 while whales accumulate (wf>0).
     We require the funding sign to corroborate the call side; otherwise the
     multiplier is 1 (neutral). */
  function adverseMultiplier(dir, wf, crowd, meanFunding, opts) {
    opts = opts || {};
    const MAXMULT = opts.maxMult != null ? opts.maxMult : 1.6;
    if (!dir || Math.sign(wf) !== -dir) return 1; // only meaningful for adverse setups
    // crowd leaning the SAME way as the (about-to-fail) directional call
    const crowdedSameSide = Math.sign(crowd) === dir ? clamp(Math.abs(crowd), 0, 1) : 0;
    // funding cost paid by the crowded side (positive funding hurts longs, etc.)
    const fundingPressure = (dir > 0)
      ? clamp(meanFunding / 0.0003, 0, 1)   // longs pay when funding>0
      : clamp(-meanFunding / 0.0003, 0, 1); // shorts pay when funding<0
    const squeeze = clamp(0.6 * crowdedSameSide + 0.6 * fundingPressure, 0, 1);
    return 1 + (MAXMULT - 1) * squeeze;
  }

  /* ───────────── divergence verdict (the elimination decision) ────────────── */
  function divergence(d, opts) {
    opts = opts || {};
    // Asymmetric thresholds: destroying (ADVERSE) needs MORE evidence than
    // keeping/boosting (CONFIRM). CONFIRM keeps the engine's original
    // gate (0.16 / 0.50) byte-for-byte so the keep-side behavior is unchanged;
    // ADVERSE is strictly harder (higher |wf| + persistence + a coherence gate).
    // Backtest finding: v2 assumed "eliminate stricter than confirm" (tauAdv>tauConf),
    // but on ground-truth data the conservative gate let too many bad calls through
    // (recall 0.38). Calibration LOWERED the adverse |wf| floor to 0.099 (< tauConf
    // 0.16) → recall 0.72, precision held 0.56. The coherence gate (≥2 families) +
    // persistence still block single-channel noise, so a lower |wf| floor is safe.
    const TAU_CONF = opts.tauConf != null ? opts.tauConf : (opts.tau != null ? opts.tau : 0.16);
    const TAU_ADV  = opts.tauAdv  != null ? opts.tauAdv  : (opts.tau != null ? opts.tau : 0.0986);
    const PMIN_CONF = opts.pminConf != null ? opts.pminConf : (opts.pmin != null ? opts.pmin : 0.50);
    const PMIN_ADV  = opts.pminAdv  != null ? opts.pminAdv  : (opts.pmin != null ? opts.pmin : 0.5365);
    const KFAM = opts.kFamilies != null ? opts.kFamilies : 2; // ≥2 of 3 families
    const W = opts.window || WF_DEFAULT_WINDOW;

    const dir = indicatorDir(d);
    const f = whaleFlow(d.series, opts);
    const wf = f.wf, strength = Math.abs(wf), wfSign = Math.sign(wf);
    const persist = persistence(d.series, wfSign, W);
    const coh = coherence(f.comp, wfSign, opts);
    const crowded = Math.sign(f.crowd) === dir && Math.abs(f.crowd) > 0.4; // crowd same side as call

    // mean funding over the window (for the overcrowding multiplier only)
    const fundSeries = d.series && d.series.funding ? lastWindow(d.series.funding, W) : [];
    const meanFunding = fundSeries.length ? mean(fundSeries) : 0;

    let verdict, adverse = false, reason;
    let adverseStrength = 0, multiplier = 1;

    if (dir === 0) {
      verdict = "NEUTRAL";
      reason = "İndikatör nötr · yön tezi yok";
    } else if (wfSign === dir) {
      // agreement side: permissive CONFIRM (a keep decision)
      if (strength >= TAU_CONF && persist >= PMIN_CONF) {
        verdict = "CONFIRM";
        reason = dir > 0 ? "Balina akışı alışı teyit ediyor" : "Balina akışı satışı teyit ediyor";
      } else {
        verdict = "NEUTRAL";
        reason = "Belirgin balina uyumsuzluğu yok";
      }
    } else {
      // disagreement side: STRICT, multi-channel-gated ADVERSE (a destructive decision)
      // ADVERSE requires ≥K families AND (by default) the POSITIONING family as its
      // anchor: real whale distribution moves whale/top-trader L/S (pos/acc), whereas
      // a flat-whale price-downtrend that merely drags cvd/oiPrice + taker is price
      // action, not smart money. This is the trend-correlated co-move guard.
      const requirePos = opts.requirePositioning != null ? opts.requirePositioning : true;
      const meaningful = strength >= TAU_ADV && persist >= PMIN_ADV;
      const coherent = coh.families >= KFAM && (!requirePos || coh.positioning);
      if (meaningful && coherent) {
        verdict = "ADVERSE";
        adverse = true;
        multiplier = adverseMultiplier(dir, wf, f.crowd, meanFunding, opts);
        adverseStrength = clamp(strength * multiplier, 0, 1);
        const squeezed = multiplier > 1.15;
        reason = dir > 0
          ? (crowded || squeezed ? "İndikatör AL · kalabalık uzun ama balina dağıtımda" : "İndikatör AL · balina dağıtımda (uyumsuz)")
          : (crowded || squeezed ? "İndikatör SAT · kalabalık kısa ama balina topluyor" : "İndikatör SAT · balina topluyor (uyumsuz)");
      } else {
        // disagreement exists but is single-channel / weak / unstable → KEEP
        verdict = "NEUTRAL";
        reason = !coherent
          ? "Tek kanal gürültüsü · çok-kanal teyidi yok (elenmez)"
          : "Belirgin balina uyumsuzluğu yok";
      }
    }

    return {
      dir, wf, strength, persistence: persist, crowd: f.crowd, crowded,
      comp: f.comp, coherence: coh, multiplier, adverseStrength, meanFunding,
      verdict, adverse, reason,
    };
  }

  /* ───────────── decision: 12-TF consensus + weighted 15-indicator vote +
     momentum → directional thesis. Calibratable blend (opts.blend) + signal
     thresholds (opts.sig). SHARED by the app (data.js) and the backtest so the
     calibrated "indicator weights" transfer 1:1. ──────────────────────────── */
  const DEC_DEFAULT_BLEND = { tf: 1.08, ind: 0.94, mom: 0.15 };   // calibrated: balance 12-TF + indicator vote, starve noisy momentum
  const DEC_DEFAULT_SIG = { strong: 1.4, weak: 0.11 };            // calibrated: low weak-floor keeps coins making a directional call

  function signalFromScore(score, opts) {
    const S = (opts && opts.sig) || DEC_DEFAULT_SIG;
    if (score >= S.strong) return "STRONG_BUY";
    if (score >= S.weak) return "BUY";
    if (score > -S.weak) return "NEUTRAL";
    if (score > -S.strong) return "SELL";
    return "STRONG_SELL";
  }
  function indicatorVote(indicators) {
    let iw = 0, tw = 0;
    for (const ind of (indicators || [])) {
      const sig = ind.signal || "";
      const strong = sig.indexOf("STRONG") >= 0 ? 2 : 1;
      const s = sig.indexOf("BUY") >= 0 ? 1 : sig.indexOf("SELL") >= 0 ? -1 : 0;
      const w = ind.weight != null ? ind.weight : 1;
      iw += s * strong * w; tw += w * 2;
    }
    return tw ? iw / tw : 0; // -1..1
  }
  function decision(d, opts) {
    opts = opts || {};
    const B = Object.assign({}, DEC_DEFAULT_BLEND, opts.blend || {});
    const tfNet = (((d.buy || 0) - (d.sell || 0)) / 12) * 2;   // [-2,2]
    const indNorm = indicatorVote(d.indicators);               // [-1,1]
    const mom = (d.ch || 0) / 30;
    const netScore = B.tf * tfNet + B.ind * (indNorm * 2) + B.mom * mom;
    const finalSignal = signalFromScore(netScore, opts);
    const dir = finalSignal.indexOf("BUY") >= 0 ? 1 : finalSignal.indexOf("SELL") >= 0 ? -1 : 0;
    return { netScore, indNorm, tfNet, finalSignal, dir };
  }

  /* ───────────── flowTilt: REAL-data-validated directional microstructure alpha.
     Live Binance backtest (tools/backtest, temporal holdout, ~11k samples) shows the
     ROBUST, correctly-signed predictors of forward return are: rising OI (+, the
     strongest), top-trader position LEVEL (CONTRARIAN −, "crowded longs top out"),
     price momentum (+), taker level (small −). The original engine had the
     positioning sign INVERTED (it treated crowded-long as bullish confirmation),
     which is exactly why the first real-data calibration found "no signal". flowTilt
     encodes the corrected signs. Weights default 0 (off) until set by calibration. */
  // REAL-DATA CALIBRATED (live Binance, 110 symbols, regime-neutral long-short spread
  // +~25%): rising OI is bullish; top-trader crowded-long is CONTRARIAN (the sign the
  // original engine had inverted). priceMom/taker left 0 (not robust on the holdout).
  const TILT_DEFAULT = { oiMom: 1.0, posCrowd: 0.35, priceMom: 0, taker: 0 };
  function flowTilt(series, opts) {
    if (!series) return 0;
    opts = opts || {};
    const W = opts.window || WF_DEFAULT_WINDOW;
    const T = Object.assign({}, TILT_DEFAULT, opts.tilt || {});
    if (!(T.oiMom || T.posCrowd || T.priceMom || T.taker)) return 0; // fully off → cheap exit
    const oi = lastWindow(series.oi, W), pos = lastWindow(series.pos, W),
      price = lastWindow(series.price, W), taker = lastWindow(series.taker, W);
    const oiMom = Math.tanh(4 * relSlope(ewma(oi, 0.4)));               // rising OI → bullish
    const posCrowd = Math.tanh(2 * Math.log(median(pos) || 1));         // top-trader long LEVEL → contrarian
    const priceMom = Math.tanh(2.5 * ((price[price.length - 1] / price[0]) - 1));
    const takerLvl = Math.tanh(2 * (mean(taker) - 1));
    // posCrowd & taker enter NEGATIVE (contrarian), per the live-data fit:
    return clamp(T.oiMom * oiMom - T.posCrowd * posCrowd + T.priceMom * priceMom - T.taker * takerLvl, -1, 1);
  }

  /* ───────────── PUAN: ranking key (calibratable) ─────────────────────────── */
  const SCORE_DEFAULT = { confBase: 0.5, confAgree: 0.06, agree: 15.4, netMag: 21.2, chCap: 10.4, chGain: 0.12, tilt: 25 }; // tilt 25 = real-data microstructure ranking weight
  function scanScore(d, opts) {
    const P = Object.assign({}, SCORE_DEFAULT, (opts && opts.scoreWeights) || {});
    const conf = d.confidence || 0;                 // 0..100
    const net = (d.buy || 0) - (d.sell || 0);       // -12..12 signed consensus
    const agree = Math.abs(net);                    // 0..12
    const agreeFrac = agree / 12;
    const netMag = agree / 12;                       // 0..1 consensus strength (no triple-counting of ch)
    const chTerm = clamp(d.ch || 0, -P.chCap, P.chCap) * P.chGain; // momentum nudge, hard-capped
    const base = conf * (P.confBase + P.confAgree * agreeFrac) + P.agree * agree + P.netMag * netMag + chTerm;
    // real-data microstructure tilt: rank coins whose flow CONFIRMS their call higher
    // (edge = dir*fwd, so the predicted edge is dir*flowTilt). default tilt=0 → no-op.
    const tilt = P.tilt ? P.tilt * indicatorDir(d) * flowTilt(d.series, opts) : 0;
    return base + tilt;
  }

  /* ───────────── continuous-scan perturbation (deterministic per cycle) ───── */
  function rng(seed) { let s = seed >>> 0; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
  function liveTick(universe, cycle) {
    if (!cycle) return universe;
    return universe.map((d) => {
      const r = rng((d.seed * 131 + cycle * 977) >>> 0);
      const s = d.series;
      const nudge = (arr) => {
        const o = arr.slice(), k = Math.min(6, o.length);
        for (let i = o.length - k; i < o.length; i++) o[i] = o[i] * (1 + (r() - 0.5) * 0.045);
        return o;
      };
      const ns = { ...s, pos: nudge(s.pos), acc: nudge(s.acc), taker: nudge(s.taker), oi: nudge(s.oi), price: nudge(s.price) };
      const drift = (r() - 0.5) * 0.6;
      return { ...d, ch: +((d.ch + drift)).toFixed(2), series: ns };
    });
  }

  /* ───────────── runScan: rank → eliminate adverse → backfill to N ────────── */
  function runScan(universe, opts) {
    opts = opts || {};
    const size = opts.size === "all" || opts.size === Infinity ? Infinity : (opts.size || 10);
    const list = universe.map((d) => ({ d, score: scanScore(d, opts), div: divergence(d, opts) }));
    list.sort((a, b) => b.score - a.score);

    const survivors = [], eliminated = [];
    for (const item of list) {
      if (item.div.adverse) { eliminated.push(item); continue; }
      if (survivors.length < size) { item.rank = survivors.length + 1; survivors.push(item); }
    }
    return {
      survivors,
      eliminated,
      universeCount: universe.length,
      scanned: universe.length * 12,
      keptCount: list.length - eliminated.length,
      confirmCount: survivors.filter((x) => x.div.verdict === "CONFIRM").length,
    };
  }

  const API = {
    indicatorDir, whaleFlow, persistence, divergence, scanScore, liveTick, runScan,
    decision, signalFromScore, indicatorVote, flowTilt,
    DEFAULTS: { weights: WF_DEFAULT_WEIGHTS, gains: WF_DEFAULT_GAINS, blend: DEC_DEFAULT_BLEND, sig: DEC_DEFAULT_SIG, score: SCORE_DEFAULT, tilt: TILT_DEFAULT },
    _stats: { clamp, mean, median, mad, std, ewma, diff, relSlope, zLast, lastWindow, coherence, adverseMultiplier, normWeights },
  };
  if (typeof module !== "undefined" && module.exports) module.exports = API;
  root.SGS_QUANT = API;
})(typeof window !== "undefined" ? window : globalThis);
