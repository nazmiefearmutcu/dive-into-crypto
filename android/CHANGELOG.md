# Changelog

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
