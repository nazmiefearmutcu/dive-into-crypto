# Dive Into Crypto — Android Deep-Data Edition (Crypcodile-KMP) — Design Spec

**Date:** 2026-06-09
**Status:** Approved (brainstorming complete)
**Target:** Android app `com.diveintocrypto.android` (Kotlin Multiplatform, Compose)
**Repo:** `/Users/nazmi/dive-into-crypto` (`android/` module)
**Branch:** `feat/android-crypcodile-deep-data`

---

## 1. Goal

Make **Crypcodile the single on-device data source** for the Android app and surface **every deep-data
category Crypcodile defines** — options chains with full greeks, IV surface / skew / term-structure,
funding & basis, open interest, liquidations, L2 order book, trades/VWAP — sourced from the
**highest-quality providers** (Deribit for options/IV/greeks/funding/OI/liquidations; Binance for
spot+perp candles/depth/OI/funding/L-S). The app must stay **live-exchange fast with zero UI freezes**
and ship with **zero errors**.

### Decisions locked during brainstorming
- **Connection model:** *Pure native Kotlin* — no Python backend. The Kotlin engine **is** Crypcodile
  on-device. Python Crypcodile becomes the **reference spec + parity-fixture source**, not a runtime dep.
- **Data routing:** *Route everything via Crypcodile* — the engine is the single source of truth; the
  existing ad-hoc Binance clients are refactored **into** the engine as connectors.
- **v1 venues:** **Deribit + Binance** (the two deepest). OKX / Bybit / Coinbase cross-venue deferred to a
  later milestone.
- **Engine placement:** `commonMain` (KMP) so the same core can later feed the iOS app `com.tbv1.ios`.

### Non-goals (v1)
- No Python backend / FastAPI bridge (explicitly rejected).
- No OKX/Bybit/Coinbase in v1 (deferred to M-later cross-venue).
- No persistent Parquet lake on device — the engine keeps **bounded in-memory rolling state**, not a
  full historical store. (Backfill via REST for history windows the screens need.)
- No changes to the existing 15-indicator consensus math — it is proven and stays bit-for-bit.

---

## 2. Architecture

A new `commonMain` module that mirrors Crypcodile's Python package layout 1:1, so the two stay aligned
and can share test vectors.

```
app/src/commonMain/kotlin/com/diveintocrypto/android/
└─ engine/                              (Kotlin port of crypcodile/)
   ├─ schema/
   │   ├─ Records.kt                    canonical @Serializable records (see §3)
   │   └─ Enums.kt                      Side, OptType, Channel, Venue
   ├─ exchanges/
   │   ├─ Connector.kt                  interface: stream(channels, symbols): Flow<Record>;
   │   │                                backfill(channel, symbol, window): List<Record>
   │   ├─ deribit/
   │   │   ├─ DeribitConnector.kt       WS: ticker.{instr}.100ms (greeks+IV), book, trades,
   │   │   │                            perpetual/funding/OI, liquidations, DVOL index
   │   │   ├─ DeribitInstruments.kt     REST public/get_instruments → chain discovery
   │   │   └─ DeribitNormalize.kt       raw JSON → canonical records
   │   └─ binance/
   │       ├─ BinanceConnector.kt       (refactor of BinanceSpotClient/FuturesClient/WsClient)
   │       │                            klines, aggTrade, depth+delta, markPrice(OI/funding),
   │       │                            forceOrder(liquidations), /futures/data L-S ratios
   │       └─ BinanceNormalize.kt
   ├─ normalize/Normalizer.kt           shared helpers (ns timestamps, symbol canonicalization)
   ├─ analytics/
   │   ├─ BlackScholes.kt               Black-76 price, greeks, implied-vol solver
   │   ├─ VolSurface.kt                 ivSurface, volSkew, termStructure, riskReversalButterfly(25Δ)
   │   ├─ Funding.kt                    fundingApr, cumulativeFunding, summary
   │   ├─ Basis.kt                      spotFutureBasis, perpBasis (mark vs index), annualized
   │   └─ Resample.kt                   OHLCV resample, VWAP/$-volume, L2 book reconstruction
   ├─ book/OrderBook.kt                 incremental L2 book (apply snapshot+deltas, top-N view)
   └─ MarketDataEngine.kt               façade: hot StateFlows per (channel,symbol); subscription
                                        lifecycle; conflation; reconnect orchestration
```

**Canonical symbol id:** `"{venue}:{raw}"` e.g. `deribit:BTC-PERPETUAL`, `binance-usdm:BTCUSDT`,
`deribit:BTC-28MAR25-100000-C` (matches Crypcodile).

**Replaces:** `data/MarketDataRepository.kt` and `data/binance/*` become the Binance connector inside the
engine. All ViewModels depend on `MarketDataEngine` (via `AppContainer`) — the single source of truth.

**Untouched:** `domain/indicator/*`, `domain/consensus/*`, `domain/divergence/*` — fed `OHLCV` from the
engine instead of the old repository. `domain/math`, `platform/*`, `ui/theme/*` unchanged.

**"Single source of truth" ≠ added latency:** routing everything through the engine does **not** slow the
live price path. The engine is **in-process** and its Binance connector still streams **direct from the
exchange WS** — the façade is just an in-memory `StateFlow` in the same process. So the active-symbol live
tick is as fast as today's direct clients (no backend hop), while still flowing through the unified engine.

---

## 3. Canonical record schema (`engine/schema/Records.kt`)

Direct port of Crypcodile's `schema/records.py` (msgspec Structs → Kotlin `@Serializable data class`).
All records carry: `venue`, `symbol`, `symbolRaw`, `exchangeTs: Long?` (ns), `localTs: Long` (ns).

| Record | Key fields |
|---|---|
| `Trade` | id, price, amount, side(BUY/SELL/UNKNOWN), liquidation? |
| `BookSnapshot` | bids/asks: `List<Pair<Double,Double>>`, depth, seqId, isSnapshot=true |
| `BookDelta` | bids/asks (amount==0 ⇒ remove level), seqId, prevSeqId |
| `BookTicker` | bidPx, bidSz, askPx, askSz, updateId? |
| `DerivativeTicker` | lastPrice, markPrice, indexPrice, fundingRate, predictedFundingRate, fundingTs?, openInterest? |
| `OptionsChain` | underlying, underlyingPrice?, strike, expiry(ns), optType(C/P), markPrice?, bid/askPx, bid/askSz, lastPrice?, markIv?, bid/askIv?, **delta/gamma/vega/theta/rho?**, openInterest? |
| `Funding` | fundingRate, predictedFundingRate?, fundingTs, intervalHours(=8 default) |
| `OpenInterest` | openInterest, openInterestValue? |
| `Liquidation` | price, amount, side, id? |
| `OHLCV` | open,high,low,close,volume,buyVolume,sellVolume,numTrades,interval |

`Record` = sealed interface over the above (kotlinx-serialization polymorphic, tagged by `channel`).

---

## 4. Data sources — provider selection (best per category)

| Deep-data category | Primary venue | Transport | Greeks/IV origin |
|---|---|---|---|
| Options chain + Δ/Γ/V/Θ/ρ + mark-IV | **Deribit** | WS `ticker.{instr}.100ms` | native (Deribit-provided) |
| IV surface / skew / term-structure / 25Δ RR-BF | **Deribit** | computed from chain | Black-76 fit on-device |
| DVOL volatility index | **Deribit** | WS index ticker | native |
| Funding rate + APR, Open Interest | **Deribit** + Binance USD-M | WS ticker | — |
| Spot-future & perp basis | Deribit(perp/fut) × Binance(spot) | on-device ASOF-join | — |
| L2 order book + imbalance | **Binance** / Deribit | WS depth+delta / book | — |
| Liquidations tape | Binance `forceOrder` + Deribit | WS | — |
| Spot/perp candles, VWAP, trades | **Binance** | WS klines/aggTrade | — |
| Long/short ratios, taker flow (existing) | Binance `/futures/data/*` | REST poll | — |
| Cross-venue compare (deferred) | OKX / Bybit / Coinbase | WS | — |

**Auth:** all chosen channels are **public, no API key**. Endpoints:
- Binance spot `https://api.binance.com`, USD-M `https://fapi.binance.com`, WS `wss://fstream.binance.com`,
  spot WS `wss://stream.binance.com:9443`.
- Deribit `https://www.deribit.com/api/v2` (REST), `wss://www.deribit.com/ws/api/v2` (WS).

---

## 5. Screens

### 5.1 Existing screens (re-wired to engine, behavior preserved)
Panel, Scanner, Signals, Positions, Performance, Logs, Settings, Appearance. Same UX; data now flows from
`MarketDataEngine`. Logs screen extended to show **multi-venue** WS/REST activity (Deribit + Binance).

### 5.2 New deep-data screens (terminal aesthetic, Phosphor/Amber/Ice themes)

| Route | Screen | Content | Source |
|---|---|---|---|
| `OPTIONS_SURFACE` | Options / IV Surface | expiry×strike IV heatmap-grid + ATM term-structure line | Deribit chain |
| `GREEKS` | Greeks Chain | per-strike Δ/Γ/V/Θ/ρ + mark/bid/ask IV; expiry selector | Deribit |
| `SKEW_TERM` | Skew & Term | vol-skew curve per expiry, 25Δ RR & BF, term-structure | Deribit |
| `FUNDING_BASIS` | Funding & Basis | funding rate/APR/cumulative; spot-future & perp basis, annualized | Deribit×Binance |
| `OI_LIQ` | OI & Liquidations | OI history + live liquidation tape (sided, sized) | Binance+Deribit |
| `ORDERBOOK` | Order Book | live L2 ladder + depth chart + bid/ask imbalance | Binance/Deribit |
| `TAPE_VWAP` | Tape & VWAP | live trade tape, VWAP, $-volume per bar | Binance |

Navigation: extend `ui/nav/NavRoute.kt` and the mobile shell with a "Deep Data" section grouping the 7 new
routes. Charts rendered with Compose `Canvas` (no heavy 3rd-party chart dep), consistent with current app.

---

## 6. The never-freeze engineering (hard requirement)

**Three-clock decoupling** — stream cadence ≠ compute cadence ≠ render cadence:

1. **Ingest (fast):** each connector exposes cold `Flow<Record>` from WS; runs on `Dispatchers.IO`.
2. **Conflate:** `MarketDataEngine` applies `.conflate()` / `.sample(250ms)` per channel so intermediate
   frames are dropped under load — a slow consumer never blocks ingestion (backpressure-safe).
3. **Compute (off-main):** analytics (IV-surface fit, greeks aggregation, book reconstruction) run on
   `Dispatchers.Default` on a **throttled cadence**, never per raw tick. Results published to `StateFlow`.
4. **Render:** UI collects the latest `StateFlow` snapshot on `Dispatchers.Main`. Order book snapshots to UI
   at ~15 fps; surface/greeks at ~1–4 Hz.

**Bounded subscriptions:** stream only the **active underlying's** option chain, nearest *K* expiries
(default K=4); lazy subscribe on screen-enter, unsubscribe on exit. No global "subscribe to everything."

**Instrument-count guard:** Deribit BTC+ETH chains are hundreds of instruments; cap concurrent option
subscriptions, batch-subscribe, and prioritize near-the-money strikes.

**Resilience (zero-errors):**
- Per-frame parse isolation — one malformed message is logged and skipped, never kills a stream.
- Reconnect with exponential backoff + jitter; WS heartbeat/ping; resubscribe on reconnect.
- Sequence-gap detection on order book deltas → resnapshot on gap.
- Graceful empty/loading states in every new screen.
- All multi-venue connection events visible in the Logs screen.

---

## 7. Milestones

Each milestone ships green (tests pass, app builds, existing screens keep working).

| # | Milestone | Outcome |
|---|---|---|
| **M1** | Engine skeleton + canonical schema + **Binance connector refactor** + `MarketDataEngine` façade | Existing 8 screens work unchanged, now via engine. Old `MarketDataRepository`/`data/binance` removed. |
| **M2** | **Analytics port** (BlackScholes, VolSurface, Funding, Basis, Resample) | Pure-function analytics with **parity tests vs fixtures exported from Python Crypcodile**. |
| **M3** | **Deribit connector** (chain+greeks+IV, DVOL, funding/OI, liquidations, book, trades) | Deribit data flows into engine; connector tests vs recorded WS fixtures. |
| **M4** | **Deep-data screens** (7) + nav | All deep data visible & live. |
| **M5** | **Perf hardening** + multi-venue Logs | Frame-time budget met; soak test (long-run, no leak, no jank); conflation tuned. |
| **M-later** | Cross-venue (OKX/Bybit/Coinbase) | Side-by-side compare screen. |

---

## 8. Testing & correctness ("no errors")

- **TDD** throughout (test-driven-development skill): test → fail → implement → pass, per unit.
- **Crypcodile parity vectors:** a small script exports JSON fixtures from the Python engine
  (`BlackScholes` greeks, `iv_surface`, `funding_apr`, `basis`, OHLCV resample) for representative inputs;
  Kotlin analytics assert bit-for-bit/epsilon match. This makes the port *provably* Crypcodile.
- **Connector tests:** feed recorded Deribit/Binance WS frames (captured fixtures) through normalizers →
  assert canonical records.
- **Engine tests:** subscription lifecycle, conflation, reconnect/backoff, book delta replay & gap handling.
- **ViewModel/state tests:** each new screen's ViewModel produces correct UI state from engine flows
  (kotlinx-coroutines-test, Turbine-style flow assertions).
- **Existing suite:** all current indicator/consensus/divergence tests must stay green after the M1 refactor.
- Existing test stack: JUnit, Ktor mock client, kotlinx-coroutines-test.

---

## 9. Config & settings

Add to `SettingsData`:
- `enabledVenues: Set<String>` (default `{"deribit","binance"}`).
- `optionExpiriesK: Int` (default 4) — chain depth.
- `orderbookDepth: Int` (default 25), `orderbookFps: Int` (default 15).
- `surfaceRefreshHz: Int` (default 2).
- Keep existing `wsDataSource` semantics folded into venue selection.

No API keys required. No secrets stored.

---

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Option-chain instrument explosion janks UI | Bounded K-expiry subscriptions, near-the-money priority, off-main compute, conflation. |
| On-device Black-76/surface CPU cost | Throttled recompute (1–4 Hz), incremental where possible, `Dispatchers.Default`. |
| Deribit WS schema drift / bad frames | Per-frame parse isolation, fixtures, normalizer unit tests. |
| Order book desync | Seq-gap detection → resnapshot; reconstruction tests. |
| M1 refactor regresses existing screens | Keep consensus engine untouched; full existing-suite must stay green; behavior-parity check on Panel/Scanner. |
| KMP/iOS portability accidentally broken | Engine stays in commonMain, no Android-only APIs in engine; platform `expect/actual` only for WS/HTTP engine + time. |

---

## 11. Definition of done (v1)

- All 7 deep-data screens live and streaming from Deribit/Binance via the on-device Crypcodile-KMP engine.
- All existing screens function identically, now engine-backed.
- Crypcodile parity tests + connector/engine/ViewModel tests all green; existing suite green.
- No UI freeze under sustained multi-stream load (soak test passes; frame budget held).
- Release build compiles clean (R8), no runtime crashes in manual smoke across all screens.
