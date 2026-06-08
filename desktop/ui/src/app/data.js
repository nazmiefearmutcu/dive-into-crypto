/* ============================================================================
   SGS — mock market data (Binance USDT-M futures shaped). Deterministic.
   ========================================================================== */

const SGS_TF = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"];

function sgsRng(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}

/* Build a believable candle series for a symbol. */
function sgsCandles(seed, n, start, vol) {
  const r = sgsRng(seed);
  const out = [];
  let price = start;
  let t = Date.now() - n * 3600_000;
  for (let i = 0; i < n; i++) {
    const drift = (r() - 0.48) * vol;
    const open = price;
    const close = Math.max(open * (1 + drift), open * 0.5);
    const hi = Math.max(open, close) * (1 + r() * vol * 0.6);
    const lo = Math.min(open, close) * (1 - r() * vol * 0.6);
    out.push({ t: t + i * 3600_000, o: open, h: hi, l: lo, c: close, v: 1000 + r() * 9000 });
    price = close;
  }
  return out;
}

const SGS_SYMBOLS = [
  { s: "BTCUSDT", name: "Bitcoin", price: 68420.5, ch: 2.34, seed: 11, vol: 0.012 },
  { s: "ETHUSDT", name: "Ethereum", price: 3842.18, ch: 3.61, seed: 22, vol: 0.018 },
  { s: "SOLUSDT", name: "Solana", price: 182.44, ch: -1.92, seed: 33, vol: 0.03 },
  { s: "BNBUSDT", name: "BNB", price: 612.7, ch: 0.84, seed: 44, vol: 0.014 },
  { s: "XRPUSDT", name: "XRP", price: 0.6231, ch: 5.12, seed: 55, vol: 0.025 },
  { s: "LINKUSDT", name: "Chainlink", price: 18.92, ch: -2.77, seed: 66, vol: 0.028 },
  { s: "AVAXUSDT", name: "Avalanche", price: 41.06, ch: 7.43, seed: 77, vol: 0.035 },
  { s: "DOGEUSDT", name: "Dogecoin", price: 0.1612, ch: -3.41, seed: 88, vol: 0.04 },
  { s: "ARBUSDT", name: "Arbitrum", price: 1.214, ch: 9.88, seed: 99, vol: 0.045 },
  { s: "OPUSDT", name: "Optimism", price: 2.482, ch: -4.62, seed: 101, vol: 0.042 },
  { s: "SUIUSDT", name: "Sui", price: 1.842, ch: 12.4, seed: 112, vol: 0.05 },
  { s: "TIAUSDT", name: "Celestia", price: 9.21, ch: -6.18, seed: 123, vol: 0.048 },
  { s: "INJUSDT", name: "Injective", price: 28.7, ch: 4.05, seed: 134, vol: 0.038 },
  { s: "SEIUSDT", name: "Sei", price: 0.594, ch: 8.22, seed: 145, vol: 0.052 },
  { s: "WIFUSDT", name: "dogwifhat", price: 2.91, ch: 15.7, seed: 156, vol: 0.06 },
  { s: "NEARUSDT", name: "Near", price: 6.42, ch: -2.13, seed: 167, vol: 0.034 },
];

const SGS_SIGNAL_LEVELS = ["STRONG_SELL", "SELL", "NEUTRAL", "BUY", "STRONG_BUY"];

function sgsSignalFromScore(score) {
  if (score >= 1.4) return "STRONG_BUY";
  if (score >= 0.4) return "BUY";
  if (score > -0.4) return "NEUTRAL";
  if (score > -1.4) return "SELL";
  return "STRONG_SELL";
}

/* Per-symbol 12-TF consensus + indicator table + OI/LS series. */
function sgsBuildSymbol(sym) {
  const r = sgsRng(sym.seed * 7 + 3);
  const clip = (x, lo, hi) => Math.max(lo, Math.min(hi, x));
  const candles = sgsCandles(sym.seed, 90, sym.price / (1 + sym.ch / 100), sym.vol);
  // align last close near current price
  const adj = sym.price / candles[candles.length - 1].c;
  candles.forEach((k) => { k.o *= adj; k.h *= adj; k.l *= adj; k.c *= adj; });

  const multiTf = SGS_TF.map((tf) => {
    const sc = (r() - 0.45 + sym.ch / 40) * 2;
    const sig = sgsSignalFromScore(sc);
    const conf = Math.round(35 + Math.abs(sc) * 28 + r() * 12);
    return { tf, signal: sig, confidence: Math.min(98, conf) };
  });

  const buy = multiTf.filter((m) => m.signal.includes("BUY")).length;
  const sell = multiTf.filter((m) => m.signal.includes("SELL")).length;
  const neutral = 12 - buy - sell;

  // 15-indicator table. RSI/Stoch/MFI/Williams/ADX/CCI are clamped to their real domains.
  const BOUNDED = { RSI: [0, 100], Stochastic: [0, 100], MFI: [0, 100], "Williams %R": [-100, 0], ADX: [0, 100], CCI: [-300, 300] };
  const indicators = [
    "RSI", "MACD", "Bollinger", "EMA Cross", "SMA Cross", "Ichimoku", "PSAR", "OBV",
    "Stochastic", "ADX", "CCI", "MFI", "VWAP", "Supertrend", "Williams %R",
  ].map((name) => {
    const sc = (r() - 0.45 + sym.ch / 50) * 2;
    let value = sc * 50 + 50;
    const b = BOUNDED[name];
    if (b) value = clip(b[0] + ((sc + 2) / 4) * (b[1] - b[0]), b[0], b[1]); // map sc(~[-2,2]) into the real range
    return { name, signal: sgsSignalFromScore(sc), weight: +(0.6 + r() * 1.6).toFixed(1), value: +value.toFixed(1) };
  });

  // Headline decision via the SHARED, BACKTEST-CALIBRATED engine blend (quant.js
  // decision()), so SON KARAR uses the same indicator weighting the calibration
  // optimized. (quant.js is loaded before data.js — see SGS.html.)
  const dec = window.SGS_QUANT.decision({ buy, sell, neutral, ch: sym.ch, indicators });
  const indNorm = dec.indNorm;
  const netScore = dec.netScore;
  const finalSig = dec.finalSignal;
  // confidence rewards AGREEMENT between the TF consensus and the indicator vote:
  const coherePen = 1 - 0.35 * Math.abs(Math.sign(buy - sell) - Math.sign(indNorm)) / 2;
  const confidence = Math.min(96, Math.round((46 + Math.abs(netScore) * 26 + r() * 8) * coherePen));

  // ── Whale regime: where smart money actually sits vs the indicator thesis.
  //    Deterministic per symbol so the scan's elimination/backfill is stable.
  //    wd = target whale-flow direction; the quant engine recovers sign(wd).
  const dir = finalSig.includes("BUY") ? 1 : finalSig.includes("SELL") ? -1 : 0;
  const roll = r();
  let whaleRegime, wd;
  if (dir === 0) { whaleRegime = "neutral"; wd = 0; }
  else if (roll < 0.30) { whaleRegime = "adverse"; wd = -dir; }   // whales fight the call → eliminated
  else if (roll < 0.72) { whaleRegime = "confirm"; wd = dir; }    // whales back the call → confirmed
  else { whaleRegime = "neutral"; wd = 0; }                       // no meaningful whale lean
  const g = whaleRegime === "neutral" ? 0 : (0.75 + r() * 0.6);  // regime trend strength

  // OI / L/S 48-point series, shaped by the whale regime (+ deterministic noise).
  const N = 48;
  const oi = [], glob = [], acc = [], pos = [], taker = [], funding = [], price = [], bias = [];
  let oiv = sym.price * 1e6 * (3 + r() * 9);
  for (let i = 0; i < N; i++) {
    const t = i / (N - 1);
    const n = () => (r() - 0.5);
    oiv *= 1 + 0.012 * g * wd + n() * 0.02;             // OI rises as the regime builds
    oi.push(oiv);
    glob.push(+clip(0.95 + 0.5 * n(), 0.4, 1.9).toFixed(3));        // retail crowd · noisy
    acc.push(+clip(1.05 + 0.6 * g * wd * t + 0.05 * n(), 0.4, 2.2).toFixed(3));   // top-trader L/S
    pos.push(+clip(1.20 + 0.85 * g * wd * t + 0.05 * n(), 0.4, 2.4).toFixed(3));  // whale L/S
    taker.push(+clip(1.0 + 0.16 * g * wd + 0.05 * n(), 0.55, 1.6).toFixed(3));    // taker buy/sell
    funding.push(+((0.0001 * dir + (r() - 0.45) * 0.0004)).toFixed(6));
    price.push(candles[candles.length - N + i].c * (1 + 0.01 * g * wd * (t - 0.5)));
    bias.push(+clip(wd * g * (40 + 30 * t) + (sym.ch / 50) * 18 + 6 * n(), -96, 96).toFixed(1)); // leans on the SAME regime the engine reads → QuantGauge agrees with the scanner
  }

  return {
    ...sym, candles, multiTf, buy, sell, neutral,
    finalSignal: finalSig, confidence,
    action: finalSig.includes("BUY") ? "AL" : finalSig.includes("SELL") ? "SAT" : "BEKLE",
    reason: finalSig.includes("BUY")
      ? "Çoğunluk indikatör alış tarafında; kısa & orta vade uyumlu."
      : finalSig.includes("SELL")
      ? "Trend takipçileri satışta; momentum zayıflıyor."
      : "Karışık sinyaller — net bir yön yok, teyit bekle.",
    indicators,
    series: { oi, glob, acc, pos, taker, funding, price, bias },
    quantBias: bias[bias.length - 1],
    whaleRegime,
  };
}

const SGS_DATA = SGS_SYMBOLS.map(sgsBuildSymbol);
const SGS_DATA_MAP = Object.fromEntries(SGS_DATA.map((d) => [d.s, d]));

/* Leaderboard (gainers / losers). */
const SGS_GAINERS = [...SGS_DATA].sort((a, b) => b.ch - a.ch).slice(0, 8);
const SGS_LOSERS = [...SGS_DATA].sort((a, b) => a.ch - b.ch).slice(0, 8);

/* Network log lines. */
const SGS_LOGS = [
  { t: "12:04:51", m: "GET /fapi/v1/klines BTCUSDT 1h", s: 200, ms: 42 },
  { t: "12:04:51", m: "GET /futures/data/openInterestHist", s: 200, ms: 61 },
  { t: "12:04:50", m: "GET /futures/data/globalLongShortAccountRatio", s: 200, ms: 58 },
  { t: "12:04:50", m: "WS futures@markPrice connected", s: 101, ms: 12 },
  { t: "12:04:49", m: "Scan phase 2 · 50 survivors dispatched", s: 200, ms: 7 },
  { t: "12:04:48", m: "GET /fapi/v1/ticker/24hr", s: 200, ms: 88 },
  { t: "12:04:47", m: "GET /futures/data/takerlongshortRatio", s: 200, ms: 54 },
  { t: "12:04:46", m: "Rate limit weight 480/2400", s: 200, ms: 3 },
  { t: "12:04:45", m: "GET /fapi/v1/premiumIndex", s: 200, ms: 47 },
  { t: "12:04:44", m: "Retry /klines SUIUSDT (1/3)", s: 429, ms: 210 },
  { t: "12:04:43", m: "Consensus engine warm · 15 indicators", s: 200, ms: 9 },
  { t: "12:04:42", m: "GET /fapi/v1/exchangeInfo", s: 200, ms: 134 },
];

function sgsFmtPrice(v) {
  if (v >= 1000) return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (v >= 1) return v.toFixed(3);
  return v.toFixed(4);
}
function sgsFmtBig(v) {
  if (v >= 1e9) return (v / 1e9).toFixed(2) + "B";
  if (v >= 1e6) return (v / 1e6).toFixed(2) + "M";
  if (v >= 1e3) return (v / 1e3).toFixed(2) + "K";
  return v.toFixed(0);
}

Object.assign(window, {
  SGS_TF, SGS_DATA, SGS_DATA_MAP, SGS_GAINERS, SGS_LOSERS, SGS_LOGS,
  SGS_SIGNAL_LEVELS, sgsFmtPrice, sgsFmtBig,
});
