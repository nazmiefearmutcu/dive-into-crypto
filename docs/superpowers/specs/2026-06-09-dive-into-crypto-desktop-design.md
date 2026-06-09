# Dive Into Crypto — Desktop Edition · Design Spec

- **Date:** 2026-06-09
- **Status:** Approved (high-level) — pending spec review
- **Repo:** `github.com/nazmiefearmutcu/dive-into-crypto` (currently Android-only)
- **Branch:** `feat/desktop-edition`

---

## 1. Goal

Ship a **desktop edition** of Dive Into Crypto into the same repository that holds the
Android app, as a faithful desktop counterpart:

- A **true desktop layout** — single window, left sidebar navigation, multi-column content
  (no phone frames).
- **Terminal themes only** — the existing 9-theme web prototype is reduced to the three
  TERMINAL-family presets: **Phosphor · Amber · Ice**.
- **Real, highest-quality data fed by Crypcodile** (the user's open-source market-data
  engine), augmented with Binance `futures/data` long/short ratios.
- **Engine parity with Android** — reuse the original Python consensus reference so the
  desktop verdicts match Android's documented 15-indicator / 12-timeframe engine.
- Proper repo **monorepo restructure** (`/android` + `/desktop`) and a redesigned,
  multi-platform **README**.
- A clean, runnable **release** launched from the terminal.

This is an analysis/research tool. It places **no orders** and gives **no financial
advice** — identical posture to the Android app.

---

## 2. Decisions (from brainstorming)

| Axis | Decision |
| --- | --- |
| Desktop form | **True desktop layout** (sidebar + multi-column), not a phone-frame studio |
| Themes | **Terminal family only** — `term-phosphor`, `term-amber`, `term-ice` |
| Data source | **Crypcodile** as the core engine + Binance `futures/data` for L/S ratios; highest fidelity available |
| Repo structure | **Monorepo**: `/android` (moved) + `/desktop` (new) |
| Distribution | **Terminal-launched local app** (Python service serves prebuilt UI). Tauri sidecar packaging is an explicit *follow-up*, not part of this release. |
| Engine | **Reuse the original Python reference engine** (the one Android pins to via 61 fixtures) for indicators + consensus + whale divergence → guarantees Android↔Desktop parity |

---

## 3. Context — what already exists

### 3.1 Android app (repo root, to be moved to `/android`)
Native Kotlin/Compose scanner. README documents the canonical product precisely:
15 indicators (RSI 14, Stochastic 14/3, Williams %R 14, CCI 20, MACD 12/26/9,
EMA 9/21, SMA 50/200, Ichimoku 9/26/52, PSAR 0.02/0.2, Bollinger 20/2σ, MFI 14,
OBV/SMA20, ROC 12, ADX+DI 14, ATR-filter 14 @ weight 0), 12 timeframes
(`1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d`), weighted-vote consensus with thresholds,
conflict override, ADX regime adaptation, a 0–100 confidence formula, LOW/MED/HIGH risk,
and whale-divergence filtering (`magnitude = 0.80·whaleStrength + 0.20·priceStrength`,
empirically contrarian sign, eliminate-and-backfill). **"61/61 indicator/consensus
fixture tests pass, pinned to the original Python reference."** No `.github/` CI exists.

### 3.2 Web prototype "SGS" (the desktop source material)
`/Volumes/disk 2/Desktop_Migrate_2026-05-28/Projeler/proje/Such A Good Scanner/delivery/editable`
— in-browser React 18 + `@babel/standalone` (no build step), themed entirely via CSS
custom properties from `app/theme.js` (`sgsBuildVars`). It currently renders the app
inside **iPhone + Pixel device frames** (a studio) and ships **mock** data (`app/data.js`).

- Screens (all platform-neutral, reusable): Panel, Tarama (scan), Positions (OI·L/S),
  Sinyal (15 indicators), Lider (leaders), Logs, Görünüm (appearance), Ayarlar (settings).
- Reusable as-is: `theme.js`, `chart.jsx`, `ui.jsx`, `icons.jsx`, all screen components.
- Mobile-specific (to bypass): `ios-frame.jsx`, `android-frame.jsx`, bottom-nav + "Daha" sheet.
- Themes: 3 families × 3 = 9. **TERMINAL** = `term-phosphor`, `term-amber`, `term-ice`
  (`theme.js:101–140`). NOVA (`:20–57`) + LEDGER (`:60–98`) get removed.
- Its `quant.js` is a *different, web-prototype* engine — **not** the canonical Dive Into
  Crypto engine. It will be replaced by backend verdicts (see §5).

### 3.3 Crypcodile (the data engine) — `~/Crypcodile`
Python ≥3.12, `uv`-managed, package `crypcodile`. No HTTP server; consumed as a library
(`CrypcodileClient`), via CLI, or the Parquet lake. Connectors: Binance (spot/usdm/coinm),
Deribit, Bybit, OKX, Coinbase. Provides at high fidelity: **OHLCV** (tick-resample with
taker buy/sell split), **funding**, **mark/index**, **open interest**, **Deribit options
IV/DVOL**. Does **not** provide Binance global/top-trader/taker **long-short ratios** —
those are Binance `futures/data/*` REST endpoints (public). The Entropy TUI
(`~/Entropy/src/entropy/feeds/`) is the reference for consuming Crypcodile live
(`make_connector` + `collect` + a `Sink`).

### 3.4 Original Python reference engine — `…/TBV1_Windows/app/src`
The canonical engine Android pinned to: `consensus/{engine,risk,scorer}.py`,
`indicators/*.py` (all 15 + `base.py`), `services/{scanner_service,signal_service}.py`,
`data/market_data.py`, `api/binance_client.py`. It also contains `trading/` (execution,
orders, leverage) — **explicitly excluded** (scanner, not trader).

---

## 4. Target architecture

### 4.1 Monorepo layout
```
dive-into-crypto/
├─ README.md                  # multi-platform hub (terminal aesthetic)
├─ LICENSE                    # stays at root
├─ android/                   # ← existing Gradle project moved here verbatim
│   ├─ app/ gradle/ gradlew gradlew.bat
│   ├─ build.gradle.kts settings.gradle.kts gradle.properties
│   ├─ BENCHMARKS.md CHANGELOG.md README.md   # android-scoped docs
├─ desktop/
│   ├─ README.md              # desktop-focused docs
│   ├─ backend/               # python package: diveintocrypto_desktop
│   │   ├─ pyproject.toml     # depends on crypcodile (+ fastapi, uvicorn, aiohttp)
│   │   ├─ src/diveintocrypto_desktop/
│   │   │   ├─ engine/        # ← ported canonical reference (indicators + consensus + risk + scorer)
│   │   │   ├─ data/          # crypcodile-backed market data + binance futures/data ratios
│   │   │   ├─ scan/          # scanner + signal services (whale divergence, ranking, backfill)
│   │   │   ├─ api/           # FastAPI app: REST + WebSocket + static UI mount
│   │   │   └─ __main__.py    # `python -m diveintocrypto_desktop` → serve + open browser
│   │   └─ tests/             # parity fixtures + indicator unit tests
│   └─ ui/                    # the desktop frontend (built from SGS)
│       ├─ src/               # quant-free screens, theme (terminal-only), DesktopShell, data adapter
│       ├─ public/index.html  # production entry (no CDN; bundled)
│       ├─ build.mjs          # esbuild bundler (JSX → single offline bundle)
│       └─ dist/              # build output served by the backend
└─ docs/superpowers/specs/    # this spec + the implementation plan
```

### 4.2 Data flow
```
Binance public REST/WS ─┐
Deribit (IV/DVOL, BTC/ETH)─┤
                          ▼
                   ┌──────────────┐
                   │  Crypcodile  │  OHLCV·funding·OI·mark·IV  (core engine)
                   └──────┬───────┘
 Binance futures/data ────┤  global/top-trader/taker L-S ratios, OI-hist
 (ratios, via aiohttp)    ▼
              ┌───────────────────────────┐
              │  desktop/backend (Python) │
              │  • engine/  (15 indicators + consensus, reference parity)
              │  • scan/    (whale divergence, ranking, eliminate+backfill)
              │  • api/     (FastAPI: REST + WS, serves ui/dist)
              └───────────┬───────────────┘
                          │  JSON (per-symbol objects + scan results) · WS live ticks
                          ▼
              ┌───────────────────────────┐
              │  desktop/ui (React, built) │
              │  • DesktopShell (sidebar + multi-column)
              │  • screens (reused) · theme (terminal-only) · chart · ui · icons
              │  • data.js adapter → fetch/WS from localhost backend
              └───────────────────────────┘
```

---

## 5. Engine strategy — parity over re-implementation

The canonical Dive Into Crypto engine already exists in Python (§3.4) and Android pins to
it (61 fixtures). The desktop backend is Python, so we **port that reference engine into
`desktop/backend/engine/`** rather than re-implementing indicators from scratch.

- **Reuse:** the 15 indicator modules, `consensus/engine.py`, `risk.py`, `scorer.py`,
  and the signal/scanner service logic (consensus, whale divergence, ranking, backfill).
- **Exclude:** everything under `trading/` (execution, orders, leverage, positions) and
  any live-trading/credentialed paths. Strip imports accordingly.
- **Swap the data source:** the reference's `data/market_data.py` + `api/binance_client.py`
  are replaced by a **Crypcodile-backed data layer** (§6). The engine consumes candles +
  ratios; it does not care where they come from.
- **Parity gate (M1 acceptance):** port the Android/Python fixture vectors and prove the
  desktop engine reproduces them within the same tolerances. If the original fixtures are
  not recoverable, lock parity against the README-documented formulas with TDD reference
  values. Either way, indicator correctness is *proven*, not asserted.

Consequence for the frontend: the SGS web `quant.js` is **not** the source of truth and is
removed. The backend emits fully-decided per-symbol objects; the UI renders them. The one
screen that computed client-side (Tarama's `liveTick`/`runScan`) is rewired to the backend
`/scan` endpoint.

---

## 6. Data layer — "highest quality available"

Per symbol, the scanner needs: multi-TF OHLCV (12 TFs), open-interest history, global &
top-trader long/short ratios, taker buy/sell ratio, funding. Sources:

| Data | Source | Mechanism |
| --- | --- | --- |
| OHLCV (12 TFs) | **Crypcodile** | official `/fapi/v1/klines` via Crypcodile's Binance connector for the market-wide snapshot (includes taker-buy volume); tick-resample (`resample_ohlcv`) for live-streamed focus symbols |
| Open interest (hist) | Binance `futures/data/openInterestHist` | Crypcodile-style fetcher |
| Global L/S (accounts) | Binance `futures/data/globalLongShortAccountRatio` | fetcher |
| Top-trader L/S (positions/accounts) | Binance `futures/data/topLongShortPositionRatio` / `…AccountRatio` | fetcher |
| Taker L/S | Binance `futures/data/takerlongshortRatio` | fetcher |
| Funding / mark / index | **Crypcodile** | `derivative_ticker` / `funding` channels + `premiumIndex` |
| Live focus-symbol ticks | **Crypcodile** WS | `make_connector` + `collect` + queue sink → WS push to UI |
| IV / DVOL (BTC/ETH) | **Crypcodile** Deribit `options_chain` | `iv_surface` / `term_structure` — quality bonus shown where relevant |

- **Symbol universe:** all Binance USDT-M perpetuals, stablecoin pairs removed, ordered by
  24h quote volume (matches Android), via Crypcodile `list_instruments` / `exchangeInfo`.
- **Two-phase scan** (matches Android): coarse pass on `1d/12h/8h` over the whole universe,
  fine pass on the lower TFs for survivors.
- **L/S coverage caveat** (honest, matches Android README): Binance publishes L/S for only
  9 of 12 TFs (`5m 15m 30m 1h 2h 4h 6h 12h 1d`); the divergence check uses what exists.
- **Rate-limit & resilience:** respect Binance weight budget; parallel fan-out with backoff;
  surface request/latency/errors to the Network Log screen (real, not mock).
- The Binance `futures/data` ratio fetchers are written as a small Crypcodile-style module
  so the whole data layer stays Crypcodile-centric and reusable.

---

## 7. Backend service (FastAPI)

`python -m diveintocrypto_desktop` starts uvicorn on `127.0.0.1:<port>`, serves
`ui/dist`, opens the browser, and exposes:

| Endpoint | Returns |
| --- | --- |
| `GET /` | the built desktop UI |
| `GET /api/universe` | ranked symbol list (24h volume) |
| `GET /api/scan?size=N&sort=…` | scan result: ranked survivors + eliminated (with reason, WF) |
| `GET /api/symbol/{s}` | full per-symbol object (candles, multiTf, indicators, series, verdict) |
| `GET /api/leaders` | gainers/losers |
| `GET /api/logs` | recent network activity (real request log) |
| `WS /api/live` | live focus-symbol updates (price/series) via Crypcodile WS |
| `GET /api/health` | service + data-source status |

- All payloads conform to the **data contract** (§8) so the screens render unchanged.
- Localhost-only bind; CORS locked to the served origin; standard secure headers.
- Graceful degradation: if a data source is unavailable, the affected fields are marked
  unavailable (never silently faked) and the UI shows it.

---

## 8. Data contract (backend → screen shape)

The backend emits per-symbol objects matching the shape the screens already read:

```jsonc
{
  "s": "BTCUSDT", "name": "Bitcoin", "price": 0, "ch": 0,
  "candles": [{ "t":0,"o":0,"h":0,"l":0,"c":0,"v":0 }],          // primary TF, real OHLCV
  "multiTf": [{ "tf":"1m","signal":"NEUTRAL","confidence":0 }],   // 12 entries
  "buy": 0, "sell": 0, "neutral": 0,
  "indicators": [{ "name":"RSI","signal":"NEUTRAL","weight":1.5,"value":0 }], // 15 entries
  "finalSignal": "NEUTRAL", "confidence": 0, "action": "BEKLE", "reason": "…",
  "risk": "LOW",                                                  // new: LOW|MEDIUM|HIGH
  "series": { "oi":[], "glob":[], "acc":[], "pos":[], "taker":[], "funding":[], "price":[], "bias":[] },
  "quantBias": 0, "whaleRegime": "neutral", "divergence": { "score":0, "tf":"1h", "coverage":0 }
}
```

- Scalar/array shapes follow the existing SGS contract (candles `{t,o,h,l,c,v}`, `multiTf`
  12×, `indicators` 15×, `series.*` arrays). New fields (`risk`, `divergence`) are additive.
- The 15 indicator **names** align to the Android/README canonical set (replacing the web
  prototype's VWAP/Supertrend with the canonical ROC/ATR-filter).
- The `data.js` adapter fetches these and exposes the same globals the screens use
  (`SGS_DATA`, `SGS_DATA_MAP`, `SGS_GAINERS/LOSERS`, `SGS_LOGS`, `sgsFmtPrice/Big`).

---

## 9. Frontend desktop port

### 9.1 DesktopShell (replaces the studio)
New `DesktopShell` component:
- **Left sidebar** — persistent nav for all 8 routes (no bottom-nav, no "Daha" sheet).
- **Top bar** — logo, symbol search, theme switch (3 terminal presets), refresh.
- **Multi-column content** — e.g. Panel = chart + 12-TF grid + verdict side-by-side;
  Tarama = wide ranked table; Positions = gauge + sparkline grid. Responsive to window size.
- Reuses `AppShell`'s navigation state model (`route/symbol/tf/favorites/refreshKey` +
  the `ctx` object) so screens receive `ctx` unchanged.
- Bypasses `ios-frame.jsx` / `android-frame.jsx` entirely.

### 9.2 Theme strip → terminal only
- `theme.js`: delete NOVA (`:20–57`) + LEDGER (`:60–98`); keep the 3 TERMINAL presets.
- `SGS.html`/shell: `FAMILIES = ["TERMINAL"]`; default preset `term-phosphor`.
- `appearance.jsx`: drop the family selector (hardcode TERMINAL); trim accent swatches to
  the terminal accents (`#38FF9E`, `#FFB638`, `#56C7FF`).
- Scanlines/CRT axes retained (they belong to terminal aesthetic).

### 9.3 Production build (offline, no CDN)
- `esbuild` bundles all JSX + React (pinned) into a single offline `bundle.js`; `index.html`
  drops `type="text/babel"` and the unpkg CDN scripts. Fonts either bundled or
  system-fallback so the app runs with no network for its own shell.
- Output to `ui/dist/`, served by the backend.

### 9.4 data.js adapter (replaces mock)
- Fetches `/api/*`; opens `WS /api/live` for the focus symbol; exposes the same globals.
- Tarama screen rewired to `/api/scan` (server-side ranking) instead of client `runScan`.
- No deterministic mock generation in the shipped path.

---

## 10. Repo restructure (Android stays buildable)

1. `git mv` all Android files into `android/`: `app/ gradle/ gradlew gradlew.bat
   build.gradle.kts settings.gradle.kts gradle.properties .editorconfig BENCHMARKS.md
   CHANGELOG.md` and the current `README.md` → `android/README.md`.
2. Gradle paths are project-relative and move as a block → the Android build is unaffected
   (`cd android && ./gradlew :app:assembleDebug` still works). No `.github/` CI to update.
3. New root `README.md` = the multi-platform hub (§11).
4. Work on `feat/desktop-edition`; integrate via PR. **No force-push to main.**

---

## 11. README design

**Root `README.md`** — a multi-platform hub in the terminal aesthetic, matching the
quality/voice of the current README:
- One-paragraph product statement (scanner; on-device/local; public data; not advice).
- **Two editions** table: Android (native, on-device) · Desktop (terminal UI, Crypcodile-fed).
- Shared engine section (15 indicators, 12 TFs, consensus, whale-divergence) — single source
  of truth, linked from both editions.
- Per-edition install/run + screenshots.
- Badges, data-sources table, disclaimer, license.

**`desktop/README.md`** — desktop-focused: architecture, Crypcodile data layer, the three
terminal themes (with swatches), `python -m diveintocrypto_desktop` quickstart, build from
source, engine-parity note, troubleshooting (geo-restriction, rate limits).

Optional ASCII/screenshot capture of the three terminal themes for the README.

---

## 12. Distribution & release

- **Primary deliverable:** terminal-launched local app. `uv run dive-desktop` (or
  `python -m diveintocrypto_desktop`) installs deps, starts the backend, serves the prebuilt
  UI, opens the browser. Documented one-command quickstart.
- **Versioning:** desktop starts at `desktop-v0.1.0`; tag form keeps Android (`v0.1.0`) and
  desktop releases distinct. Desktop `CHANGELOG.md` under `desktop/`.
- **GitHub release:** tag `desktop-v0.1.0`, release notes, attach a source bundle
  (`dive-into-crypto-desktop-v0.1.0.zip` = `desktop/` + run instructions).
- **Tauri packaging** (`.dmg/.exe/.AppImage` via Python sidecar, the showMe pattern) is an
  explicit **follow-up**, out of scope for this release.
- **GitHub Pages demo:** **off by default.** Pages cannot run the Python backend; a Pages
  build would have to use mock data. To avoid presenting sample data as live, we do not ship
  a Pages demo. (If ever added, it must be unmistakably badged "DEMO — sample data.")

---

## 13. Honesty & repo hygiene (hard requirements)

- **No mock-as-real.** The shipped desktop path uses real Crypcodile/Binance data. Any
  remaining sample/demo path is isolated and unmistakably badged. Unavailable data is shown
  as unavailable, never synthesized to look live.
- **No Claude attribution** in commits or metadata (`includeCoAuthoredBy:false`, no trailers,
  human author) — per the repo's established policy.
- **No secrets.** Public endpoints only; no API keys; nothing to log into. Any future
  keystore/signing material stays gitignored and local.
- **Not financial advice / no orders** — preserved verbatim from Android.

---

## 14. Testing strategy

- **Backend, TDD:** each of the 15 indicators unit-tested to reference values; consensus +
  risk + confidence + conflict-override + regime adaptation tested to the README formulas;
  whale-divergence + ranking + eliminate/backfill tested. **Parity gate:** reproduce the
  Android/Python fixtures within tolerance.
- **Data layer:** integration tests against live Binance/Crypcodile for a couple of symbols
  (network-gated), plus recorded-fixture tests for offline CI; verify L/S 9-of-12 coverage
  handling and rate-limit backoff.
- **Frontend:** the theme strip leaves exactly 3 presets; DesktopShell renders every screen;
  data adapter maps backend JSON → screen globals; production bundle runs offline (no CDN).
- **End-to-end:** start backend → load UI → a real scan renders ranked rows with real
  verdicts; focus symbol streams live; Network Log shows real requests.
- **Android:** unaffected — re-run `:app:testDebugUnitTest` after the move to confirm.

---

## 15. Milestones & acceptance

| M | Scope | Done when |
| --- | --- | --- |
| **M0** | Monorepo move | `android/` builds (`assembleDebug`) + tests pass; root README placeholder; PR-able |
| **M1** | Backend engine + data | Reference engine ported (no `trading/`); **parity fixtures pass**; Crypcodile data layer + Binance ratio fetchers; `/api/*` + WS serve real per-symbol objects |
| **M2** | UI port + terminal themes | DesktopShell (sidebar/multi-column); exactly 3 terminal presets; esbuild offline bundle runs with no CDN |
| **M3** | Wiring + polish | `data.js` adapter live (REST+WS); Tarama uses `/api/scan`; end-to-end real data; no mock in shipped path |
| **M4** | README + release | Root hub + desktop README; `python -m diveintocrypto_desktop` quickstart works; `desktop-v0.1.0` tag + GitHub release |

---

## 16. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Indicator math drift vs Android | Reuse the reference engine + parity fixtures (M1 gate) |
| Crypcodile lacks L/S ratios | Add Binance `futures/data` fetchers (public, documented) |
| Binance geo-restriction / CORS | Backend (server-side HTTP, no browser CORS) fetches; surface geo errors honestly |
| Rate limits on full-universe scan | Two-phase scan + weight budget + backoff; cache between cycles |
| Scope creep (Tauri, Pages) | Both explicitly deferred / off |
| Touching the live repo | Feature branch + PR; Android moved as a block; no force-push |
| Crypcodile API churn | Pin Crypcodile version; thin adapter isolates it |

---

## 17. Out of scope (YAGNI)

- Auto-trading / order execution (excluded from the ported engine).
- Tauri/Electron native installers (follow-up).
- GitHub Pages demo (off by default).
- Non-terminal themes (removed).
- Mobile-frame studio preview (replaced by desktop shell).
- Accounts, API keys, persistence beyond local device prefs.

---

## 18. Open questions

None blocking. Engine-parity source (original fixtures vs README-formula TDD) is resolved
inside M1 by inspection of the reference repo; both paths yield a proven engine.
