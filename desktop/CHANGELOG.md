# Changelog — Dive into Crypto · Desktop Edition

All notable changes to the desktop edition are documented here.

## [Unreleased]

### Changed
- **Consensus conflict threshold is 0.4** (`engine/config/default.yaml`, was 0.6, which the minority/active ratio can never reach). Android uses the same value.
- **Engine parity with the Android port:** stricter engulfing (the current real body must contain the prior real body, and the bars must have opposite colours); a half-life reversion significance gate (Dickey-Fuller style t-statistic); ATR low-volatility risk only below ATR ratio 0.5; the ATR filter reports NEUTRAL "baseline warming up" instead of defaulting its ratio to 1.0.
- **Scanner ties resolve to NEUTRAL** (net score 0 on a buy/sell tie, including all-neutral). A tie no longer produces a BUY row.
- **`--allow-remote` required for non-loopback hosts.** `dive-desktop --host 0.0.0.0` now refuses to start unless `--allow-remote` is passed, because the API has no authentication.
- **Origin guard.** State-changing HTTP requests and WebSocket handshakes with a non-loopback `Origin` header are answered 403 (WebSocket: close 1008). Requests without an `Origin` (curl, scripts) are unchanged.
- **`end_ms` must be at least 1.** `end_ms=0` is rejected by request validation (HTTP 422) instead of being treated as the epoch.
- **Data engine migrated from Crypcodile to Crocodile.** Crypcodile was merged into
  [Crocodile](https://github.com/nazmiefearmutcu/Crocodile) (its crypto engine now lives under
  `crocodile.crypto`) and the old pinned Crypcodile repository is no longer reachable, so a
  fresh `uv sync` could not resolve the backend. `crocodile` is now pinned to an exact upstream
  commit (same no‑public‑index policy — the `crocodile` name on PyPI is an unrelated project).
- Kline and open‑interest pages are parsed by Crocodile's public `parse_klines_page` /
  `parse_open_interest_hist` (canonical `OHLCV` / `OpenInterest` records, `source_ts`), and
  fetched through the backend's shared session instead of Crocodile's private per‑call helpers.

### Fixed
- **Basis z-score units.** Binance's premium-index history is a fraction; it is converted to percent before the z-score, so the baseline matches the current premium's scale.
- **Deribit DVOL.** The index name is now `btcdvol_usdc` / `ethdvol_usdc` and the price is read from `index_price`. A malformed options payload degrades to a partial block instead of failing the request.
- **DefiLlama stablecoins.** The live `{"peggedAssets": [...]}` payload is parsed (a bare list is still accepted), and the USDC share recognises the "USD COIN" name.
- **Spot-perp lead/lag.** Binance's "Invalid symbol" answer (code -1121, perp-only listing) is cached for 60 s; transient failures are still never cached.
- **Bounded caches.** The symbol-keyed caches (live, scan, pulse, CVD, spot) are capped and drop the oldest entries, so arbitrary symbol strings cannot grow memory without limit.
- Klines/OI fetches opened a new `aiohttp` session per request and their retry predicate never
  fired (upstream 429/5xx surfaced as `ClientResponseError`, not `TransientUpstreamError`);
  they now share the pooled session and get Retry‑After‑aware 429/5xx + timeout retries.
- `/futures/data/openInterestHist` bypassed the shared futures/data rate limiter that the
  long/short ratio calls it is always paired with use; it is now limited too.
- The root E2E `conftest.py` stubbed the whole data dependency and `aiolimiter` with
  `MagicMock`, hiding import errors; both are now imported for real.
- The frozen PyInstaller build (`dive.spec`) now bundles `engine/config/default.yaml`. Without it
  every symbol build failed with `FileNotFoundError` in the packaged app.
- `run_tests.sh` reports a failing suite correctly (the UI and backend steps are chained inside
  their subshells, so a failing `npm test` is no longer hidden by a later successful step).

## [0.3.0] — 2026-09-12

### Added
- **Engine depth.** Full futures data expansion: basis and term structure (perp / quarterly /
  next-quarterly), funding-arbitrage lens, a labelled liquidation-cascade proxy, volatility term
  structure, order-book imbalance snapshot, `/api/macro` (Fear & Greed and stablecoin share),
  long/short term structure, spot-perp lead/lag (`DIVE_SPOT_BASE`), a Deribit options slice
  (DVOL, put/call open interest, ATM IV) with its own failure domain, and index constituents.
- **Full-universe scan.** Two-phase adaptive scan over up to 500 symbols, with an async mode
  (`/api/scan?async=1`, `/api/scan/progress`, `/api/scan/result`).
- **Evidence layer.** Every scan verdict is appended to `runtime/evidence.jsonl`. The grader
  backfills forward returns and reports hit-rate and expectancy with Wilson intervals, calibration
  (ECE), Brier skill and permutation baselines. It is report-only and never refits weights.
  Endpoints include `GET /api/evidence`, `POST /api/evidence/grade` and `GET /api/evidence/stability`.
- **Counterfactual replay and claims.** Report-only threshold replay (`POST /api/evidence/replay`),
  per-indicator information coefficients with weight suggestions that are never auto-applied
  (`GET /api/evidence/ic`, `POST /api/evidence/suggest-weights`), and a claims registry
  (`/api/claims`) that evaluates only after registration.
- **Price-action patterns:** engulfing, liquidity sweep, pivot structure.
- **Market structure.** 90-bar 1h beta and correlation against BTCUSDT, correlation clustering,
  `GET /api/structure`.
- **CVD** from public aggregate trades on the symbol contract.
- **UI (Depth Terminal v3).** Evidence dashboard, new views (including comparison, market map,
  portfolio tracker and cluster explorer), a pulse strip, command palette (Ctrl+K), and a Turkish
  and English interface with a language setting.

### Changed
- Evidence archive schema v2 (regime, microstructure, MTF, divergence tier, cluster and weights
  hash), so each archived verdict records the context it was produced in.

## [0.2.0] — 2026-09-11

### Changed
- **Reference backend hardened.** Forming (unfinished) klines are dropped so a signal never reads
  an open bar. Requests retry with jittered backoff and honour `Retry-After` on 429/5xx. Universe
  and per-symbol caches are added (errors are never cached). Indicator math runs off the event loop.
  `DIVE_FAPI_BASE` overrides the futures host.
- **Scan honesty.** Responses report `scannedCount` and `droppedCount`, and each dropped symbol is
  logged with its reason.
- **UI honesty.** The OI · L/S view and the Signals view render from real endpoints only. A
  DATA STALE banner names the failing endpoint. Keyboard access, ARIA labels and reduced-motion
  support were added.

### Removed
- About 2,900 lines of unreferenced prototype UI files.

## [0.1.0] — 2026-06-09

First desktop release — a terminal‑themed counterpart to the Android app.

### Added
- **Terminal‑themed desktop UI** — single window, sidebar navigation, multi‑column layout
  (no phone frames). Three terminal themes: Phosphor · Amber · Ice, with live tuning.
- **Crypcodile‑fed data layer** — real Binance USDT‑M data (12‑timeframe OHLCV, open interest,
  global/top‑trader/taker long‑short ratios, funding/mark, volume‑ranked universe).
- **Canonical consensus engine** ported from the original Python reference — 15 indicators +
  weighted‑vote consensus + risk + whale‑divergence. **Android parity** pinned by 30 fixture tests.
- **Two‑phase scanner** — netNss ranking + whale‑divergence elimination & back‑fill.
- **Local FastAPI service** (`dive-desktop`) serving the prebuilt UI + REST/WS over `127.0.0.1`.
- Screens: Tarama · Panel · OI·L/S · Sinyal · Lider · Ağ Günlüğü · Görünüm · Ayarlar.

### Notes
- Public Binance data only — no account, no API keys, places no orders.
- `crypcodile` is pinned to an exact upstream commit (reproducible; no public‑index fallthrough).
