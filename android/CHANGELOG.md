# Changelog

## [Unreleased] — 0.4.0 (versionCode 4)

Google Play preparation. Not yet released.

### Changed
- **Consensus conflict threshold is 0.4** (was 0.6, which the minority/active ratio can never reach). The desktop edition uses the same value.
- **Core indicator weights aligned to the desktop reference:** SMA cross 1.8 (was 1.5), Ichimoku 2.0 (was 1.5), Parabolic SAR 1.3 (was 1.2), OBV 1.2 (was 1.5).
- **Regime matrix no longer changes verdicts.** The regime label and its adaptive score are shown as annotations only. The Dynamic Regime Matrix control was removed from Settings; the regime overlay never changes a verdict.
- **Stricter engulfing:** the current real body must contain the prior real body, and the two bars must have opposite colours (matches the Python reference).
- **Half-life reversion gate:** a signal needs a statistically significant reversion (Dickey-Fuller style t-statistic at or below -2.86) and a half-life within the configured limits; otherwise it is NEUTRAL.
- **ATR low-volatility risk** is flagged only when the ATR ratio is below 0.5.
- **Bollinger squeeze vote sign fixed:** a close below the lower band during a squeeze votes SELL, and a close above the upper band votes BUY (the signs were inverted).
- **Scanner ties resolve to NEUTRAL.** A buy/sell tie (including all-neutral) no longer produces a BUY row.
- **Background scan matches the foreground scanner.** NEUTRAL symbols are no longer written to the
  home-screen widget, the alert hook or the evidence archive.
- **Widget and share card use direction-only wording** (LONG / SHORT / no clear bias) instead of the raw BUY / SELL tokens.
- **Clear all local data is durable.** After a successful clear the app restarts itself, so no
  in-memory alert, setting or portfolio state can write the deleted values back. The
  notification-prompt flag is kept on purpose, so a permanently denied notification request still
  opens the notification settings.
- **Toolchain:** compileSdk and targetSdk 36, Android Gradle Plugin 8.11.1, Gradle 8.13.
- **Crocodile-aligned OHLCV schema:** the OHLCV record's `buyVolume`, `sellVolume`, `numTrades`
  and `vwap` fields are nullable, because not every source provides them.

### Added — Play compliance
- Disclaimer: the app is for analysis and research, is not financial advice and is not an automated trader.
- Neutral wording in UI copy.
- Open-source licenses screen in Settings, and a "Clear all local data" action in Settings.
- UI language follows the device locale (Turkish or English) until the user picks one.
- Privacy policy screen in Settings (About → privacy).
- Region-block handling: a Binance geo-restriction response (HTTP 451) is recognised as a
  region restriction, and the app's live feeds stop reconnecting instead of retrying indefinitely.
  HTTP 403 is shown as a separate "forbidden" state with a Retry action.
- Release signing hardening: release builds fail when signing values are missing, and CI signs
  the AAB and APK and verifies both signatures before publishing.

## v0.3.0 — 2026-09-12

Alerts v2, portfolio tracking, background scans and depth parity with the desktop engine.

### Added
- Alerts v2: AND-condition groups, per-rule cooldowns, migration from v1, and a digest view.
- Portfolio tracker (local only) with live P&L and engine agreement.
- Background scans every 15 minutes through WorkManager, plus a home-screen widget.
- Verdict share card.
- Pattern indicators: engulfing, liquidity sweep and pivot structure, pinned to the Python reference.
- Evidence grader v2 (Wilson intervals, gates, ECE, Brier score, windows), a pulse strip,
  a two-pane layout on tablets, and a Turkish and English interface.

### Changed
- versionCode 3, versionName 0.3.0.
- Test suite grew from 154 to 327 unit tests.

## v0.2.0 — 2026-09-11

**Honesty release: all random-data fabrication removed; the promised weights and overlays are actually wired.**

### Removed — nothing is synthesised anymore
- **Signals screen:** the "fallback ticker" that synthesized a price every second (`lastClose × (1 ± 0.02%)`) while the WebSocket was quiet — and silently recomputed consensus on those FAKE ticks — is deleted. Quiet streams now expose explicit staleness state (`isStale` + `dataAgeMs`) and stop feeding data.
- **Positions (OI · L/S) screen:** the simulated price fallback and the entire simulated-series injection (random OI value, long/short account & position & global ratios, taker volumes) are deleted. REST-refreshed series keep their last REAL points; staleness is labelled instead.
- A full `Random`/`nextDouble` sweep of commonMain confirms no market-data synthesis remains (the only randomness left is reconnect-backoff jitter, which is not data).

### Fixed — the "weighted consensus" was only half true
- The full **57-name weight map** was defined but never consumed: the 42 extended indicators silently scored at weight 1.0. The map is now the canonical source (`domain/consensus/Weights.kt`) wired into `SettingsStore` defaults and `ConsensusEngine` construction — core 15 keep the F2 matrix values (existing verdicts unchanged), extended 42 take their desktop-reference weights (e.g. `squeeze 2.5`, `supertrend 2.0`). User overrides in Settings still win.

### Added — overlays, resilience, live panel
- **Three strategy overlays are finally live as ADDITIVE annotations** (they never touch the parity-locked vote): Regime (`TREND/RANGE/MIXED`), MTF-confluence (score/direction/gate/label), and Microstructure (score/direction/label/active signals) — on Scanner rows and the Panel.
- **WebSocket self-healing**: `BinanceWsClient.reconnectingKlineStream` (exponential backoff 1s→2s→4s…cap 30s, ±20% jitter, reset on first frame, reconnects on graceful close too). The ViewModels' `while(true){collect}catch{delay}` restart loops are gone.
- **Panel live recompute**: verdict (multimodal + regime + microstructure + MTF) recomputes over cached candles at most every 5s or on candle close, and the state is re-emitted only when a value actually changed; the 12-TF grid refreshes on candle close.
- **Scanner honesty**: `failedCount` exposes symbols whose fetch failed (previously swallowed); per-TF cells are backfilled for ALL displayed rows (previously only the first 24); the duplicate `feed`/`hotList` state fields, unused `selectTimeframe`, and dead `MAX_PARALLEL`/`PHASE2_TOP_N`/`FINAL_TOP_N` constants are removed; the risk label logic is now one canonical `ScannerViewModel.riskFromAgreement`.

### Fixed — housekeeping
- Candle cache is a **bounded LRU (96 keys)** — it used to grow without bound in the app-scoped engine (~500 symbols × 12 TF keys).
- targetSdk 34 → 35; versionCode 2; versionName 0.2.0.
- Theme-count typo in the v0.1.0 notes below corrected: **10** presets ship, not 9.

### Tests
- Suite grown 129 → **150** unit tests (all green): WS backoff/reconnect schedule, LRU eviction, full-weights persistence + wiring, scanner cross-rank / elimination-backfill / per-TF backfill / risk bands.

## v0.1.0 — 2026-06-07

First public release.

- Binance USDT‑M perpetual‑futures consensus scanner: **15 technical indicators across 12 timeframes**.
- Two‑phase market sweep (coarse high‑timeframe pass over the whole universe, then a fine low‑timeframe pass on survivors).
- Weighted‑vote consensus with regime‑adaptive weighting, conflict override, confidence scoring, and a risk assessor.
- **Whale‑divergence filtering** from Binance top‑trader long/short positioning, with adverse‑coin elimination and back‑fill.
- Independent microstructure consensus (price state · open‑interest momentum · taker aggression) for the OI · L/S leaderboard.
- Eight screens: Scanner, Panel, Signals, Positions (OI · L/S), Performance, Network Log, Appearance, Settings.
- English UI, 10 theme presets, on‑device preferences.
- Reads only public Binance market data — no account or API keys.
- Android 8.0+ (minSdk 26), targetSdk 34. Signed release APK, ~3.6 MB.
