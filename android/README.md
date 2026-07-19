# Dive Into Crypto

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/nazmiefearmutcu/dive-into-crypto)](https://github.com/nazmiefearmutcu/dive-into-crypto/commits)
[![Stars](https://img.shields.io/github/stars/nazmiefearmutcu/dive-into-crypto?style=social)](https://github.com/nazmiefearmutcu/dive-into-crypto/stargazers)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)

**Dive Into Crypto is a financial scanner** that watches the entire Binance USDT‑M perpetual‑futures market and tells you, per symbol, whether the evidence leans long, short, or neutral. It runs **15 technical indicators across 12 timeframes**, cross‑checks the result against **whale (top‑trader) positioning**, and collapses everything into a single confidence‑scored consensus verdict. It is a native Android app, it runs entirely **on your device**, and it reads only **public** Binance market data — no account, no API keys, no sign‑up.

> ⚠️ Dive Into Crypto is an analysis and research tool, **not financial advice** and **not an automated trader**. It places no orders. Markets are risky; you are responsible for your own decisions.

- **Platform:** Android 8.0+ (minSdk 26), single signed APK, sideload install
- **Stack:** Kotlin Multiplatform · Jetpack Compose · Ktor · kotlinx.serialization
- **Data:** Binance USDT‑M Futures public REST + WebSocket
- **Engine:** the full **57‑indicator consensus + 3 overlays** (microstructure · regime‑adaptive weighting · MTF‑confluence), every indicator pinned by fixture to the shared Python reference
- **Look:** **Depth Terminal** — a hand‑built trading instrument, not a generic dashboard
- **License:** MIT

---

## Table of contents

- [What you see](#what-you-see)
- [The multi‑scan system](#the-multi-scan-system)
- [Data sources](#data-sources)
- [How the consensus works](#how-the-consensus-works)
- [Whale‑divergence filtering](#whale-divergence-filtering)
- [Benchmarks](#benchmarks)
- [Install](#install)
- [Build from source](#build-from-source)
- [Disclaimer](#disclaimer)
- [License](#license)

---

## What you see

Dive Into Crypto has eight screens. The four primary ones live in the bottom bar; the rest are behind the "More" menu.

### Scanner — the ranked market sweep
The headline screen. It scans the whole futures universe and shows a ranked table of the strongest setups. Each row gives you:

| Field | Meaning |
| --- | --- |
| **Symbol** | Trading pair (e.g. `BTCUSDT`) |
| **Direction** | Consensus verdict: `STRONG_BUY · BUY · NEUTRAL · SELL · STRONG_SELL` |
| **netNss** | Net directional conviction across all timeframes |
| **Price** | Last traded price |
| **TFs hit / total** | How many of the 12 timeframes produced an active (non‑neutral) signal |
| **Per‑TF grid** | A mini state for each of the 12 timeframes (signal + confidence) |
| **ATR %** | Volatility (ATR‑14 as a % of price) — risk advisory only, never scores |
| **Divergence** | Whale long/short‑vs‑price divergence score, its strongest timeframe, and data coverage |

Rows are ranked by a hybrid of indicator conviction and whale divergence (`netNss + 0.35 × divergenceScore`). Coins whose whale positioning **contradicts** the indicator verdict are eliminated and back‑filled by the next clean survivor (see [Whale‑divergence filtering](#whale-divergence-filtering)). You can change how many rows are shown (5 / 10 / 15 / 20 / all) and switch to a divergence‑first sort.

### Panel — one symbol, in depth
A live detail view for the active symbol:

- **Header** — symbol, last‑update time, a "stale" flag if the data is older than 30 s.
- **Status bar** — symbol · live price · selected timeframe · current signal.
- **12‑timeframe confidence grid** — a mini card per timeframe (1m → 1d) with its signal and confidence %.
- **Final Verdict card** — the consensus output: signal, confidence (0–100), risk level (LOW / MEDIUM / HIGH), weighted score, a plain‑text reason, and whether the setup is actionable.
- **Signal Distribution** — how many indicators voted buy / sell / neutral, plus participation %.
- **Indicator breakdown** — every indicator's vote: name, signal, raw score, weight, weighted score, and reason.

### Signals — per‑timeframe drill‑down
Pick any of the 12 timeframes and see that timeframe's consensus card plus the full 15‑indicator vote table for it.

### Positions (OI · L/S) — microstructure
A leaderboard driven by **open interest, taker buy/sell flow, and long/short ratios** rather than candles — Dive Into Crypto's directed microstructure consensus (see below).

### Performance (Leaders)
Top movers by 24 h % change.

### Network Log · Appearance · Settings
- **Network Log** — live scan progress and request/error log.
- **Appearance** — 10 built‑in theme presets across 3 families (NOVA · LEDGER · TERMINAL), defaulting to **Depth Terminal · Phosphor** — the same anti‑slop instrument palette as the desktop edition (blueprint dark, single phosphor accent, sharp corners) — plus a light **Paper** variant, accent, contrast, corner‑roundness and other live tuning.
- **Settings** — confidence threshold, per‑indicator weights, favorites, and the data source (Futures / Spot). All preferences are stored on the device.

---

## The multi‑scan system

### 15 indicators
Every symbol × timeframe runs through 15 independent indicators. Each returns a five‑level signal (`STRONG_BUY = +2 … STRONG_SELL = −2`) plus a human‑readable reason.

| # | Indicator | Defaults | Family |
| --- | --- | --- | --- |
| 1 | RSI | period 14 | Oscillator |
| 2 | Stochastic | %K 14, %D 3 | Oscillator |
| 3 | Williams %R | period 14 | Oscillator |
| 4 | CCI | period 20 | Oscillator |
| 5 | MACD | 12 / 26 / 9 | Trend |
| 6 | EMA cross | 9 / 21 | Trend |
| 7 | SMA cross | 50 / 200 | Trend |
| 8 | Ichimoku | 9 / 26 / 52 | Trend |
| 9 | Parabolic SAR | AF 0.02 / 0.2 | Trend |
| 10 | Bollinger Bands | 20, 2σ | Volatility |
| 11 | MFI | period 14 | Volume |
| 12 | OBV | SMA 20 | Volume |
| 13 | ROC | period 12 | Momentum |
| 14 | ADX + DI | period 14 | Trend strength |
| 15 | ATR filter | period 14 | Volatility (filter) |

The ATR filter is a **strict filter**: it carries weight 0, so it never votes in the consensus — it only feeds the risk assessment and the on‑screen volatility advisory.

### 12 timeframes, scanned in two phases
`1m · 3m · 5m · 15m · 30m · 1h · 2h · 4h · 6h · 8h · 12h · 1d`

To scan hundreds of symbols quickly, the sweep is two‑phase:

1. **Phase 1 (coarse, whole universe):** the high timeframes `1d · 12h · 8h` are scanned across every symbol to find candidates.
2. **Phase 2 (fine, survivors only):** the lower timeframes `1m · 3m · 5m · 15m · 30m · 1h · 2h · 4h · 6h` are scanned only for the top candidates from Phase 1.

Higher timeframes carry more weight than lower ones (a daily signal outranks a 1‑minute one) when results are aggregated across timeframes into the per‑symbol ranking.

### Symbol universe
All Binance USDT‑M perpetual‑futures symbols, with stablecoin pairs removed, ordered by 24 h quote volume.

---

## Data sources

Dive Into Crypto reads only **public** Binance Futures endpoints. No authentication is required.

| Data | Endpoint |
| --- | --- |
| Candles (OHLCV) | `fapi.binance.com/fapi/v1/klines` |
| 24 h ticker | `fapi.binance.com/fapi/v1/ticker/24hr` |
| Funding rate | `fapi.binance.com/fapi/v1/fundingRate` |
| Open interest history | `fapi.binance.com/futures/data/openInterestHist` |
| Top‑trader L/S (positions) | `fapi.binance.com/futures/data/topLongShortPositionRatio` |
| Top‑trader L/S (accounts) | `fapi.binance.com/futures/data/topLongShortAccountRatio` |
| Global L/S (accounts) | `fapi.binance.com/futures/data/globalLongShortAccountRatio` |
| Taker buy/sell ratio | `fapi.binance.com/futures/data/takerlongshortRatio` |
| Live candles | Binance Futures WebSocket kline stream |

> Binance market data may be geo‑restricted in some regions; that is a network/runtime condition independent of the app.

---

## How the consensus works

The Scanner and Signals screens use an indicator‑voting consensus. The pipeline for one symbol × timeframe is:

**1. Vote.** Each of the 15 indicators produces a signal in `{ +2, +1, 0, −1, −2 }`.

**2. Weighted score.** The engine takes a weighted average:

```
weightedScore = Σ(signal × weight) / Σ(weight)
```

Indicators are weighted by family — for example MACD `2.0`, EMA‑cross `1.8`, RSI `1.5`, Bollinger `1.5`, ADX+DI `1.5`, OBV `1.5`, SMA‑cross `1.5`, Ichimoku `1.5`, Stochastic / PSAR / MFI `1.2`, Williams %R / CCI / ROC `1.0`, and the ATR filter `0.0` (never votes). Weights are user‑adjustable in Settings.

**3. Verdict thresholds.**

```
weightedScore ≥  1.2  → STRONG_BUY
weightedScore ≥  0.4  → BUY
weightedScore ≤ −0.4  → SELL
weightedScore ≤ −1.2  → STRONG_SELL
otherwise             → NEUTRAL
```

**4. Conflict override.** If the minority of directional votes is too large — `min(buy, sell) / active > 0.6` — the indicators disagree too much and the verdict is forced to `NEUTRAL` regardless of the score.

**5. Regime adaptation.** The weight matrix shifts with trend strength (ADX): in a **range/chop** regime (ADX < 20) oscillators are boosted (×1.5) and trend‑followers damped (×0.5); in a **strong trend** (ADX > 25) the reverse applies.

**6. Confidence (0–100).** Built from three components and then penalized by risk:

```
confidence = clamp(
    min(|weightedScore| / 2, 1) × 70      // conviction
  + (max(buy, sell) / active) × 20         // agreement
  + (active / total)         × 10          // participation
  − riskScore × 3                          // risk penalty
, 0, 100)
```

**7. Risk level.** A risk assessor returns LOW / MEDIUM / HIGH from high ATR volatility, a weak trend (ADX < 15), signal conflict, too few active signals, and weak conviction.

**8. Actionable?** A setup is flagged actionable only when `signal ≠ NEUTRAL`, `confidence ≥ 30`, and `risk ≠ HIGH`.

The result is one `ConsensusOutput` per symbol × timeframe carrying the signal, confidence, weighted score, vote counts, full per‑indicator breakdown, a reason string, and the risk assessment.

### Microstructure consensus (Positions screen)
Independently of the indicators, Dive Into Crypto computes a **directed** verdict from market microstructure along three axes — **price state**, **open‑interest momentum**, and **taker aggression** — mapped through a 27‑cell (3×3×3) regime table to a score in roughly −95…+50, then adjusted by a whale‑positioning override. That score buckets into the same five‑level signal (`≥ 60 STRONG_BUY · ≥ 20 BUY · ≤ −20 SELL · ≤ −60 STRONG_SELL`) and is what powers the OI · L/S leaderboard.

---

## Whale‑divergence filtering

On top of the indicators, Dive Into Crypto watches **top‑trader long/short positioning** (Binance's `topLongShortPositionRatio`) and looks for it to **diverge from price**.

- It compares the recent price trend against the recent whale long/short trend over a rolling window. Same direction → no signal; **opposite directions → divergence**.
- The divergence magnitude is a whale‑dominant blend: `magnitude = 0.80 × whaleStrength + 0.20 × priceStrength`, scaled by a timeframe factor so higher timeframes dominate.
- The predictive sign is **empirically contrarian** (calibrated on real Binance observations): when top traders sell into a rally, price has historically tended to continue upward.
- A symbol whose whale divergence **contradicts** its indicator verdict is treated as *adverse* and **eliminated** from the final ranking; the slot is back‑filled by the next clean survivor.

Whale long/short data exists for **9 of the 12 timeframes** (`5m, 15m, 30m, 1h, 2h, 4h, 6h, 12h, 1d`) — Binance does not publish it for `1m`, `3m`, or `8h` — so the divergence check uses the timeframes it has.

---

## Benchmarks

Offline, compute‑only micro‑benchmarks of the consensus engine (no network, synthetic candles, single thread) on an **Apple M4 / JDK 17**:

| Benchmark | Throughput |
| --- | ---: |
| Single consensus eval (15 indicators + engine) | **≈ 1,590 / sec** |
| Full symbol scan (12 timeframes) | **≈ 180 / sec** |
| Projected 500‑symbol universe (compute‑only) | **≈ 2.8 s** single‑threaded |

The compute cost of the whole market is small; real‑world scan time is dominated by Binance network round‑trips, which the app fans out in parallel.

- **Correctness:** 61/61 indicator/consensus fixture tests pass, pinned to the original Python reference within per‑test tolerances.
- **APK size:** 3.6 MB (release, R8‑minified).
- **Runtime:** ≈ 2.2 s cold start and ≈ 31 MB memory (PSS) on an API 34 emulator; crash‑free with minification on.

Full numbers, methodology, and a one‑command reproduction are in **[BENCHMARKS.md](BENCHMARKS.md)**.

---

## Install

1. Download `dive-into-crypto-v0.1.0.apk` from the [latest release](../../releases/latest).
2. On your Android device (8.0 / API 26 or newer), allow your browser or file manager to "install unknown apps".
3. Open the APK and install.

The app reads public market data only — there is nothing to log into.

---

## Build from source

Requirements: **JDK 17**, the **Android SDK** (platforms 34 + 35), and a `local.properties` with your `sdk.dir`.

```bash
# Debug APK (debug‑signed, for testing)
./gradlew :app:assembleDebug

# Release APK — provide your own keystore.properties at the repo root:
#   storeFile=your-release.keystore
#   storePassword=...
#   keyAlias=...
#   keyPassword=...
./gradlew :app:assembleRelease

# Run the unit + benchmark suite
./gradlew :app:testDebugUnitTest
```

If `keystore.properties` is absent, the release build is simply left unsigned rather than failing.

---

## Disclaimer

Dive Into Crypto is an educational and research tool. It does not give financial advice, it does not execute trades, and it makes no guarantee of accuracy or profitability. Cryptocurrency derivatives are high‑risk. Use it at your own risk.

---

## License

[MIT](LICENSE) © nazmiefearmutcu
