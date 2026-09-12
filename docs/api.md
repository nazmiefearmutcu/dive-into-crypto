# HTTP & WebSocket API reference

The desktop backend is a localhost-only FastAPI service (started by `uv run dive-desktop`,
serving **127.0.0.1:8780**). Source of truth:
`desktop/backend/src/diveintocrypto_desktop/api/app.py`, with response assembly in
`scan/symbol_builder.py` and `scan/scanner.py`; depth features live in `scan/evidence.py`
(grading archive, v2 stats), `scan/structure.py` (BTC-beta / clusters / vol),
`scan/replay.py` (counterfactual grid + IC), `scan/claims.py` (claims registry),
`scan/progress.py` (scan progress) and the `data/` adapters (`cvd`, `basis`,
`funding`, `orderbook`, `sentiment`, `spot`, `deribit`, `index_info`, `ratios`).

- **CORS**: `GET` and `POST` (the latter for `POST /api/evidence/grade`,
  `POST /api/evidence/replay`, `POST /api/evidence/suggest-weights` and
  `POST /api/claims`), restricted to `http://127.0.0.1`, `http://localhost` and
  their sub-ports. There are no authenticated endpoints and no other write methods.
- **Static UI**: when `desktop/ui/dist/` exists it is mounted at `/` (that bundle is committed
  on purpose; see [Contributing](../CONTRIBUTING.md)). Frozen builds (PyInstaller) resolve the
  bundle against `sys._MEIPASS` first.
- **Error contract — nothing is synthesised**: every field in every response is derived from
  real market data. When a fetch fails the API returns an explicit error object
  (`symbol_fetch_failed`, `live_fetch_failed`, `macro_unavailable`) or a per-block
  `{"unavailable": <reason>}` — it never substitutes plausible-looking numbers or zeros
  dressed as data. Derived values are transforms of real data, not fabrications.
- **Gates (v0.3)**: every stats object in the evidence surfaces is gated —
  `n < 5` → the object is `{"n": n}` only; `n < 20` → values present with
  `"gated": true`; proportions additionally carry a Wilson 95% interval
  (`wilson_lo` / `wilson_hi`).
- **Evolving shapes**: consumers should tolerate unknown fields; all v0.3 additions are additive.

---

## GET /api/health

Liveness probe. No parameters.

```json
{ "ok": true, "service": "dive-into-crypto-desktop", "version": "0.3.0", "ui_built": true }
```

| Field | Type | Meaning |
|---|---|---|
| `ok` | bool | always `true` when the process answers |
| `service` | string | service identifier |
| `version` | string | backend version (`0.3.0`) |
| `ui_built` | bool | whether the committed UI bundle (`desktop/ui/dist/`) was found at startup |

## GET /api/universe

The tradable universe: USDT-M perpetuals with status `TRADING`, quote asset `USDT`,
stablecoin/fiat bases excluded, ranked by 24h quote volume (descending).

| Param | Default | Meaning |
|---|---|---|
| `limit` | `60` | max rows returned (omit for the full ranked list) |

Row shape (list of):

| Field | Type | Meaning |
|---|---|---|
| `s` | string | symbol, e.g. `"BTCUSDT"` |
| `name` | string | base asset, e.g. `"BTC"` |
| `price` | float | last price (24h ticker) |
| `ch` | float | 24h price change, percent |
| `quote_volume` | float | 24h quote (USDT) volume — the ranking key |

Backed by `fapi/v1/exchangeInfo` + `fapi/v1/ticker/24hr` (the raw payload is cached
30s and shared with `/api/macro` — zero extra upstream calls there).

## GET /api/scan

Runs the scanner sweep and returns the ranked survivor table plus the whale-eliminated
rows. Full-universe scans run in two phases: a coarse sweep (4h/12h/1d) over the whole
requested universe with adaptive concurrency (width halves and a global cooldown starts
on any 429/451 signal), then a full 12-timeframe depth pass over the top `depth_top`
rows only.

| Param | Default | Meaning |
|---|---|---|
| `size` | `10` | max survivors returned (1–100) |
| `universe_limit` | `30` | how many top-volume symbols to scan (**1–500**; values outside are rejected with 422) |
| `depth_top` | `50` | top-N rows that get the full 12-TF depth pass (1–200) |
| `async` | `false` | `async=1` enqueues the scan and returns a `scan_id` immediately instead of blocking |

Response:

| Field | Type | Meaning |
|---|---|---|
| `survivors` | list | top `size` rows whose whale flow does not contradict the indicator verdict |
| `eliminated` | list | rows eliminated by the whale-divergence filter (kept for transparency) |
| `universeCount` | int | symbols considered |
| `scanned` | int | timeframes evaluated for fully-scanned rows (`scannedCount × 12`) |
| `scannedCount` | int | symbols that received the full 12-TF assembly (phase 2) |
| `droppedCount` | int | symbols whose data fetch failed (coarse or depth phase), logged with reason |
| `coarseCount` | int | symbols that completed the phase-1 coarse sweep |
| `depthTop` | int | the effective depth cap applied |

Each row is the full [symbol object](#the-symbol-object) (same shape as `GET
/api/symbol/{symbol}`, minus the panel-only `book` / `ls_term` blocks) plus these
ranking/structure fields:

| Field | Type | Meaning |
|---|---|---|
| `dominantDir` | int | `1` buy-side, `-1` sell-side |
| `netNss` | float | winning side's Σ(confidence² · timeWeight/100) across timeframes |
| `beta` | float \| null | rolling 90-bar 1h-return OLS beta vs BTCUSDT (null when there is not enough aligned data) |
| `corr_btc` | float \| null | Pearson correlation of 1h returns vs BTCUSDT |
| `cluster_id` | int \| null | sector-cluster id (greedy single-link clustering at corr > 0.6, top 8 clusters labeled by their largest member); null when unclustered |
| `cluster_agreement` | float \| null | share of the row's cluster-mates (clusters ≥ 3 members, else null) whose `dominantDir` matches the row's own — how much the sector backs the call |
| `cluster_rel_strength` | float \| null | row's `netNss` z-score vs its cluster mean/sd (same ≥3-member quorum; null when spread is zero) |
| `vol` | object \| null | [vol term structure](#vol-term-structure) computed from the row's reused 1h candles |
| `cone` | object | [vol cone](#vol-cone); **omitted** when returns are insufficient |
| `divergence_tier` | string | fixed tier of `divergence.score`: `NONE` / `WEAK` (5–25) / `MODERATE` (25–55) / `STRONG` (55+) on \|score\| — annotation only, elimination logic unchanged |
| `basis` | object | [basis block](#basis-block) (from ONE batch premiumIndex call + per-symbol premium history) |
| `funding_lens` | object | [funding lens](#funding-lens) |
| `spot_perp` | object | [spot lead/lag](#spot-perp); `{"unavailable": "no_spot_market"}` for perp-only listings (normal state) |
| `planning` | object | [planning strip](#planning-strip) (informational geometry from the primary-TF ATR) |
| `weights_hash` | string | sha256 prefix of the shipped weights map — replay keystone |

The panel-only `book` (order-book) block is **not** attached to scan rows; `cascade`
is attached to `/api/symbol` only (scan rows do not fetch OI/taker series).

Rows are ranked by `netNss` + 0.35 · divergence aligned to the dominant direction.

**Caching**: synchronous results are cached in memory for **20 seconds** keyed on
`size:universe_limit:depth_top`; concurrent identical requests share one computation
(single-flight lock). A refresh within the TTL returns the cached response unchanged.

**Honest cost note**: a 500-symbol scan makes ~2000+ public kline requests (500 × 3
coarse + top-N × 9 detail) and takes **minutes** — use `async=1` + the progress
endpoint for large universes.

**Async mode** — `GET /api/scan?async=1` returns immediately:

```json
{
  "scan_id": "9f2c1a7b3d4e",
  "status": "started",
  "mode": "async",
  "poll": "/api/scan/progress?scan_id=9f2c1a7b3d4e",
  "result": "/api/scan/result?scan_id=9f2c1a7b3d4e"
}
```

The synchronous path is unchanged for compatibility.

## GET /api/scan/progress

Progress surface for a running (or finished) scan. Pass `scan_id` for a specific scan,
omit it for the most recent one. Works while a big async scan runs in its task.

| Param | Default | Meaning |
|---|---|---|
| `scan_id` | *(latest)* | scan to inspect |

Response (404 `{"error": "scan_not_found"}` for unknown ids):

| Field | Type | Meaning |
|---|---|---|
| `scan_id` | string | the scan this record belongs to |
| `status` | string | `running` / `done` / `error` |
| `phase` | string | `starting` → `universe` → `phase1_coarse` → `phase2_detail` → `divergence` → `structure` → `done`/`error` |
| `completed` / `total` | int | phase-level work counter |
| `eta_seconds` | float \| null | rolling-rate estimate (last ~15s); null when idle/finished |
| `started_at` | string | ISO-8601 UTC |
| `finished_at` / `duration_seconds` | string / float | present once finished |
| `meta` | object | request parameters (`mode`, `size`, `universe_limit`, `depth_top`) |
| `error` | string | present when `status` is `error` |
| `summary` | object | survivor/dropped counters + `duration_ms` when done |
| `result_available` | bool | whether `GET /api/scan/result` currently holds this scan's result |

## GET /api/scan/result

The full scan response for a finished async scan (same shape as the synchronous
`GET /api/scan` body). Results are kept in memory for **10 minutes** (max 8 scans).
404 `{"error": "scan_not_found"}` when unknown, 410 `{"error": "scan_expired"}` when
the retention window has passed.

| Param | Default | Meaning |
|---|---|---|
| `scan_id` | — (required) | scan to fetch |

## GET /api/evidence

The evidence layer: every scan verdict is archived to a local JSONL file
(`desktop/backend/runtime/evidence.jsonl`, override with `DIVE_EVIDENCE_PATH`) and the
engine grades itself — once a verdict is older than the horizon, forward returns are
backfilled from public 1h klines and reported as hit-rate/expectancy per bucket.
Read-only aggregation; grades are computed by `POST /api/evidence/grade`.

| Param | Default | Meaning |
|---|---|---|
| `horizon` | `4h` | grading horizon — `1h`, `4h` or `24h` |

Response:

| Field | Type | Meaning |
|---|---|---|
| `horizon` | string | the horizon this summary is computed for |
| `archived_count` | int | verdicts in the archive |
| `gradable_count` | int | verdicts older than the horizon (matured) |
| `graded_count` | int | matured verdicts with a backfilled outcome |
| `coverage` | float | `graded_count / gradable_count` (0.0 when nothing is gradable yet) |
| `stale` | bool | `true` when matured verdicts are still awaiting grading |
| `by_verdict` | object | `LONG` / `SHORT` / `NEUTRAL` → [stats object](#stats-object) |
| `by_confidence` | list | buckets `0-25`, `25-50`, `50-75`, `75-100` → `{bucket, …stats}` |
| `by_indicator` | object | per-indicator association: `{name: {n, agree: stats, disagree: stats}}` — report-only, never used to refit weights |
| `calibration` | object | [calibration](#calibration) |
| `brier` | object | [brier](#brier) |
| `baselines` | object | [baselines](#baselines) |
| `windows` | object | `{"7d", "30d", "all"}` → same shape as `by_verdict`, restricted to verdicts newer than the window |
| `by_regime` | object | [slice](#slice) keyed by the row's regime (`TREND`/`RANGE`/`MIXED`, missing → `unclassified`) |
| `by_session` | object | [slice](#slice) keyed by UTC session (`0-8`, `8-16`, `16-24`) |
| `by_funding_proximity` | object | [slice](#slice) keyed by distance to the nearest funding settlement (00/08/16 UTC): `within_1h`, `1h_to_4h`, `over_4h` |
| `by_divergence_tier` | object | [slice](#slice) keyed by `NONE`/`WEAK`/`MODERATE`/`STRONG` |
| `provenance` | object | [provenance](#provenance) |
| `generated_at` | string | ISO-8601 UTC |
| `engine_version` | string | archive writer version (`desktop-0.3.0`) |

Grading semantics (documented, deterministic):

- a verdict **hits** when price moves ≥1% in the dominant direction before moving ≥1%
  against it within the horizon (both in one candle → conservative miss; no decisive
  move → miss);
- `forward` is the direction-signed return at horizon end (raw/unsigned for `NEUTRAL`;
  a NEUTRAL verdict "hits" when price stayed within ±1%);
- a verdict whose kline backfill fails is listed as failed by the grading call and
  stays ungraded — nothing is imputed;
- archived verdicts written before v0.3 lack the v2 fields (`regime`, `divergence_tier`,
  `weights_hash`, …); they are bucketed `unclassified`, never imputed.

### Stats object

Shared by every aggregation in the evidence surfaces:

| Field | Type | Meaning |
|---|---|---|
| `n` | int | sample count |
| `hit_rate` | float \| null | share of hits — present only when `n ≥ 5` |
| `avg_forward` / `median_forward` | float \| null | direction-signed forward return stats |
| `wilson_lo` / `wilson_hi` | float \| null | Wilson 95% interval on the hit-rate (z = 1.96) |
| `gated` | bool | `true` when `5 ≤ n < 20` (values shown but not yet trustworthy) |

`n < 5` → the object is `{"n": n}` only. This gate applies to **every** stats object
including the legacy `by_verdict` / `by_confidence` / `by_indicator` entries.

### Calibration

```json
{ "ece": 0.12, "bins": [ { "bucket": "75-100", "mean_conf": 80.0, "hit_rate": 0.5,
  "wilson_lo": 0.2, "wilson_hi": 0.8, "n": 6 } ] }
```

`ece` = Σ_b (n_b/N)·|hit_rate_b − mean_conf_b| over the four confidence buckets.
Empty bins carry nulls, never zeros.

### Brier

| Field | Type | Meaning |
|---|---|---|
| `score` | float \| null | mean((confidence/100 − hit)²) |
| `ref` | float \| null | base-rate (climatology) reference: mean((base_hit_rate − hit)²) |
| `skill` | float \| null | `1 − score/ref`; null when `ref` is 0 |
| `n` | int | graded events used |

### Baselines

| Field | Type | Meaning |
|---|---|---|
| `coin_mean` | float \| null | analytic mean of the random-direction null (always 0.0) |
| `coin_sd` | float \| null | √(Σf²)/n of the signed forwards — the exact null sd |
| `p_value` | float \| null | fraction of K sign-shuffled means ≥ the observed mean |
| `observed_mean` | float | mean of the graded signed forwards |
| `permutations` | int | K = 1000 |
| `seed` | int | 42 (deterministic, printed) |

### Slice

```json
{ "note": "multiple comparisons — descriptive only, not a tested hypothesis",
  "buckets": { "TREND": {…stats}, "unclassified": {…stats} } }
```

### Provenance

| Field | Type | Meaning |
|---|---|---|
| `first_ts` / `last_ts` | string \| null | archive span (ISO-8601 UTC) |
| `graded_count` | int | joined grades for this horizon |
| `failed_grades` | int | matured verdicts without a grade (fetch failures included) |
| `engine_version` | string | archive writer version |
| `window_note` | string | human-readable description of the window |

## POST /api/evidence/grade

Explicitly backfill outcomes for matured, not-yet-graded verdicts. Work is capped at
**40 symbols per call** (oldest verdicts first) and **resumable** — grades are appended
to `runtime/evidence_grades.jsonl` (override with `DIVE_EVIDENCE_GRADES_PATH`), so
repeated calls eventually cover the archive. Offline/failing fetches are reported, not
hidden. The fetch window reaches 24h before each verdict so the momentum baseline is
computable (null when there is no pre-history).

Each stored grade now carries, alongside the v1 fields: `hit_close` (close-to-close
±1% at horizon end), `mfe` / `mae` (max favorable/adverse excursion within the
horizon), `bnh_forward` (unsigned buy-and-hold return) and `momentum_hit` (trailing-24h
return sign at the verdict's timestamp agreed with the direction; null when unknown).

| Param | Default | Meaning |
|---|---|---|
| `horizon` | `4h` | grading horizon — `1h`, `4h` or `24h` |

Response: `{horizon, archived, gradable, graded, symbols_graded, failed: [{symbol, reason}], remaining, summary}`.

## GET /api/evidence/stability

Per-symbol verdict self-agreement over the last 8 archived records. List of:

| Field | Type | Meaning |
|---|---|---|
| `s` | string | symbol |
| `agree_frac` | float \| null | share of the last k records on the majority side; null when none were directional |
| `k` | int | records considered (≤ 8) |
| `median_gap_min` | float \| null | median spacing between those records, minutes |
| `last_ts` | int | newest archived ts (ms) for the symbol |

## GET /api/evidence/decisions

Thin archive reader — archived verdicts for one symbol in a time window.

| Param | Default | Meaning |
|---|---|---|
| `symbol` | *(all)* | filter by symbol (case-insensitive) |
| `from_ms` / `to_ms` | *(unbounded)* | inclusive ts window (ms) |
| `limit` | `500` | max rows (1–2000), oldest first |

Rows are the raw archive records (see [archive record](#archive-record-v2)).

## POST /api/evidence/replay

Counterfactual threshold grid over the archive. STRICTLY REPORT-ONLY: nothing writes
to engine configuration; the response carries `"report_only": true`. Bounded grid:
buy/sell ∈ {0.3, 0.4, 0.5, 0.6} × strong ∈ {1.0, 1.2, 1.4} × conflict ∈ {0.4, 0.5, 0.6}
= 36 cells, computed in a worker thread.

| Param | Default | Meaning |
|---|---|---|
| `horizon` | `4h` | grading horizon |

Response (heatmap-shaped):

| Field | Type | Meaning |
|---|---|---|
| `report_only` | bool | always `true` |
| `horizon` | string | the horizon used |
| `engine_weights_hash` | string | hash of the shipped weights map |
| `events` | object | `{replayable, skipped_weights_hash, skipped_no_signals}` — only archives stamped with the CURRENT weights hash are replayable |
| `grid` | list | one cell per threshold combination: `{buy, strong, conflict, n_directional, hit_rate, delta_vs_shipped, shipped_hit_rate, bootstrap_win_vs_shipped, fold_hit_rates, fold_spread}` |
| `axes` | object | `{buy: […], strong: […], conflict: […], metric: "hit_rate"}` for heatmap rendering |
| `shipped_cell` | object | `{buy: 0.4, strong: 1.2, conflict: 0.6}` (engine defaults) |
| `bootstrap` | object | `{n: 1000, seed: 42}` — win-rate of each cell vs the shipped cell on identical resamples |
| `folds` | int | 4 contiguous time folds used for OOS stability |
| `generated_at` | string | ISO-8601 UTC |

## GET /api/evidence/ic

Per-indicator Spearman IC of the archived signal vector vs the graded forward return
(last 200 graded events). Report-only.

| Param | Default | Meaning |
|---|---|---|
| `horizon` | `4h` | grading horizon |

Response: `{horizon, n, min_n: 50, indicators: {name: {ic, ic_ir, n}}}` —
`ic` is `"n/a"` below `min_n` or for constant (uninformative) columns; `ic_ir` is the
mean/sd of contiguous 50-event chunk ICs (null when fewer than 2 chunks or zero sd).

## POST /api/evidence/suggest-weights

IC-based weight suggestions: `raw = shipped · max(0.25, 1 + tanh(ic_ir))`, normalized
to the shipped total, each capped at **3× shipped** (one documented renormalize pass).
The payload is written to `runtime/weight_suggestions.json` (override with
`DIVE_WEIGHT_SUGGESTIONS_PATH`) — **never read by the engine**.

| Param | Default | Meaning |
|---|---|---|
| `horizon` | `4h` | grading horizon |

Response: `{suggestions: {name: {shipped, suggested, ic, ic_ir, n}}, cap_multiple: 3, disclaimer, written_to}`.

## GET /api/claims

Registered claims + their current evaluation (see `docs/claims/README.md`).
Evaluated ONLY on grades with `ts > registered_at`. Also refreshes the sidecar status
file (`runtime/claims_status.json`).

Response: `{claims: […], generated_at}` where each claim carries
`{claim_id, claim, metric, filter, horizon, min_n, threshold, registered_at,
engine_version, status: PENDING|CONFIRMED|REFUTED, n, value, evaluated_at, note}`.

## POST /api/claims

Registers a NEW claim (201). The registry file (`docs/claims/<claim_id>.yaml`) is
immutable — re-registering an id returns **409** `{"error": "claim_exists"}`; an
invalid payload returns **422** `{"error": "invalid_claim", "detail": …}`. Schema:
`{claim_id, registered_at, engine_version, claim, metric ∈ {hit_rate, avg_forward},
filter ⊆ {verdict, regime, divergence_tier, session, funding_proximity},
horizon ∈ {1h,4h,24h}, min_n ≥ 1, threshold: {op ∈ {">=","<="}, value}}`.

## GET /api/structure

Market-structure map: BTC-beta/correlation plus sector clusters over the top universe.
Computed from 1h returns — scan-cached klines when warm, else a fetch cached for 10
minutes. Symbols whose fetch fails are listed under `unavailable`, never substituted.

| Param | Default | Meaning |
|---|---|---|
| `limit` | `60` | universe size to map (1–250) |

Response:

| Field | Type | Meaning |
|---|---|---|
| `generated_at` | string | ISO-8601 UTC |
| `count` | int | symbols considered |
| `btc_symbol` | string | beta reference (`BTCUSDT`) |
| `window_bars` | int | rolling 1h-return window (90) |
| `cluster_threshold` | float | greedy clustering cut (0.6) |
| `clusters` | list | `{cluster_id, label, size, index_verified, verified_name, members: [{s, beta, corr_btc}]}` — `index_verified` is true when ≥60% of members sit inside an official composite index's constituents (`/fapi/v1/indexInfo` + `/constituents`, weekly cache); `verified_name` is that index's symbol, else null |
| `unclustered` | list | `[{s, beta, corr_btc}]` with no cluster assignment |
| `unavailable` | list | symbols whose 1h data could not be fetched |

## GET /api/symbol/{symbol}

Builds the full per-symbol data contract for one symbol (symbol is case-insensitive; it is
upper-cased server-side). Primary timeframe defaults to `1h`. This is the most expensive
endpoint: it fetches 12 timeframes of klines, open-interest history, all four long/short
ratio series, funding history, the 24h ticker, and the whale-divergence inputs.

| Param | Default | Meaning |
|---|---|---|
| `tf` | `1h` | primary timeframe for the verdict + indicator table + `candles` (any of the 12 TFs; invalid → 422) |
| `end_ms` | *(now)* | HISTORICAL view: all klines are fetched with this instant as `endTime`, so verdicts describe the market *as of* `end_ms`; live current-state extras (order book, settlement countdown, term structure) are skipped for historical views |

**Errors** — if any underlying fetch fails, the endpoint returns **HTTP 502** with:

```json
{ "error": "symbol_fetch_failed", "symbol": "BADUSDT" }
```

…rather than a degraded object with fabricated fields.

### The symbol object

| Field | Type | Meaning |
|---|---|---|
| `s` | string | symbol |
| `name` | string | base asset (symbol minus `USDT`) |
| `price` | float | last price (24h ticker; falls back to the latest 5m close) |
| `ch` | float | 24h price change, percent |
| `candles` | list | last ≤120 candles of the chosen TF — raw rows `{t, o, h, l, c, v}` |
| `multiTf` | list | one entry per timeframe (12): `{tf, signal, confidence}`; a TF with <60 candles is `{tf, "NEUTRAL", 0}` |
| `buy` / `sell` / `neutral` | int | counts of `multiTf` entries whose signal contains BUY / SELL / neither |
| `indicators` | list | the primary-TF indicator table: `{name, signal, weight, value}` per indicator |
| `finalSignal` | string | consensus verdict (`BUY`/`SELL`/`NEUTRAL`) |
| `confidence` | int | 0–100 consensus confidence |
| `action` | string | `"AL"` / `"SAT"` / `"BEKLE"` (derived verbatim from `finalSignal`) |
| `reason` | string | human-readable consensus rationale |
| `risk` | string | `LOW` / `MEDIUM` / `HIGH` |
| `series` | object | real data series (5m window, last 48 points): `oi`, `glob`/`acc`/`pos`, `taker`, `funding`, `price`, `bias` |
| `quantBias` | float | whale-divergence score for the symbol (1 dp) |
| `whaleRegime` | string | `neutral` / `adverse` / `confirm` |
| `divergence` | object | `{score, tf, coverage}` |
| `divergence_tier` | string | fixed tier of the divergence score: `NONE`/`WEAK`/`MODERATE`/`STRONG` |
| `microstructure` | object | futures-native overlay bundle — now also `basis_extreme` (contrarian on the basis z-score) and `funding_acceleration` (predicted-vs-settled funding) when the lens data is present; annotates, never alters, the consensus |
| `regime` | object | ADX/choppiness regime-adaptive weighting annotation |
| `mtfConfluence` | object | multi-timeframe confluence gate result |
| `weights_hash` | string | sha256 prefix of the shipped weights map |
| `planning` | object | [planning strip](#planning-strip) |
| `vol` | object | [vol term structure](#vol-term-structure) (from the symbol's own 1h candles) |
| `cone` | object | [vol cone](#vol-cone); **omitted** when returns are insufficient |
| `cascade` | object | [cascade proxy](#cascade-proxy); **omitted** when no OI series was fetched |
| `cvd` | object | rolling **cumulative volume delta** over the last ≤1000 public aggTrades (15-minute window); on failure `{unavailable: reason}` — never zeros |
| `basis` | object | [basis block](#basis-block) — panel-only |
| `funding_lens` | object | [funding lens](#funding-lens) — panel-only |
| `book` | object | [order-book panel](#order-book-panel) — panel-only, 10s TTL |
| `ls_term` | object | [L/S term structure](#ls-term-structure) — panel-only (16 rate-limited calls) |
| `spot_perp` | object | [spot lead/lag](#spot-perp) — panel-only |

### Basis block

```json
{ "basis_bps": 12.3, "zscore": 1.42, "ann_funding": 10.95, "label": "premium",
  "curve": { "perp": 12.3, "cq": 55.1, "nq": 30.0 }, "partial": false }
```

`basis_bps` = perp mark vs index (bps). `zscore` is vs the symbol's own trailing
`premiumIndexKlines` history (null below 30 points). `curve` carries the annualized
premium of each quarterly delivery contract vs the same index; a pair without delivery
contracts keeps its perp leg and flags `partial: true`. `label` ∈ `deep_discount` /
`discount` / `balanced` / `premium` / `deep_premium` (half-open bands at ±10/±50 bps,
published constants).

### Funding lens

```json
{ "predicted_funding": 0.00012, "last_settled": 0.0001, "apr": 0.1314,
  "seconds_to_funding": 5402.1, "regime": "long_crowding" }
```

`predicted_funding` is the current pre-settlement rate (`premiumIndex.lastFundingRate`);
`last_settled` is the `fundingRate` tail; `seconds_to_funding` is null when
`nextFundingTime` is 0/absent (never a fabricated countdown). `regime` ∈
`balanced` / `long_crowding` / `extreme_long_crowding` / `short_crowding` /
`extreme_short_crowding` / `unavailable` (annualized bands ±5% / ±25% APR).

### Cascade proxy

```json
{ "score": 72.4, "direction": "long_flush", "since_min": 12.5, "proxy": true }
```

A **proxy** composite (0–100) over already-fetched OI history, kline wicks and taker
flow — no forced-order feed is consulted, and every surface labels it a proxy.
`direction` ∈ `long_flush` / `short_flush` (the side that was flushed). Degraded
forms: `{"unavailable": "insufficient_history" | "oi_stable" | "below_threshold"}`.

### Vol term structure

```json
{ "curve": { "h1": 42.1, "h4": 40.0, "h12": 38.2, "h24": 37.7 }, "slope": -0.104,
  "inverted": true, "vol_of_vol": 0.21, "parkinson": 39.4, "ratio_btc": 1.3 }
```

Annualized close-to-close vols from overlapping h-hour log-return windows on the 1h
candles (descriptive — long legs are autocorrelated). `slope` = (h24 − h1)/h1;
`inverted` = long-horizon vol below short; `vol_of_vol` = CV of the rolling 24-bar σ;
`parkinson` = high-low estimator, annualized; `ratio_btc` vs BTC's 1h vol (null when
BTC is unavailable). Insufficient history → `{"unavailable": "insufficient_history"}`.

### Vol cone

```json
{ "sigma_1h": 0.01005, "env_24h": { "up": 0.267, "down": -0.211 },
  "env_48h": { "up": 0.383, "down": -0.277 }, "percentile": 64.2 }
```

Log-normal expected-move envelope, `P0·exp(±z·σ√h)` at z=1 (fractional bounds).
`percentile` ranks the latest 24h absolute log-move within the trailing overlapping
24h distribution (null below 30 windows). **Omitted entirely** when returns are
insufficient.

### Planning strip

```json
{ "sl_distance_pct": 3.0, "tp_1r_pct": 2.0, "tp_2r_pct": 4.0, "tp_3r_pct": 6.0,
  "envelope": { "h1": 2.0, "h4": 4.0, "h24": 9.798 }, "atr_pct": 2.0,
  "atr_tf_hours": 1.0, "note": "informational geometry only — no order semantics" }
```

1.5×ATR stop distance, the 1R/2R/3R ladder, and the ±1σ expected-move envelope
(ATR%·√hours). Missing ATR → `{"unavailable": "atr_missing"}`.

### Order-book panel

```json
{ "imbalance": { "0.5%": 0.42, "1%": 0.31, "2%": 0.18 }, "notional_1pct": 1250000.5,
  "mid": 100.05, "ts": 1700000000000 }
```

Per-band imbalance (`Σbid − Σask` / band total, −1 ask-heavy … +1 bid-heavy) at
0.5% / 1% / 2% around the mid, plus the 1% band notional (USDT). Thin book →
`{"unavailable": "book_too_thin"}`. Cached 10s. Panel-only.

### L/S term structure

```json
{ "periods": { "5m": { "glob": 1.87, "acc": 1.2, "pos": 0.95, "taker": 1.04 },
               "1h": {…}, "4h": {…}, "1d": {…} }, "cells_unavailable": 2 }
```

Latest value of each L/S family per period. Cells with insufficient history are
`null` (counted in `cells_unavailable`) — 16 rate-limited futures/data calls per
symbol, panel-only.

### Spot lead/lag

```json
{ "premium_pct": 0.08, "ret_spread_48h": -0.0021, "lead": "perp" }
```

Perp premium over spot (%), the 48h return spread, and which market moves first
(lag-1 cross-correlation asymmetry; `mixed` inside the ±0.05 margin). Perp-only
listings → `{"unavailable": "no_spot_market"}` — the normal state, not an error.

## GET /api/pulse

Lightweight watch-list ticker with a **10s cache**.

| Param | Default | Meaning |
|---|---|---|
| `symbols` | — (required) | comma-separated, e.g. `BTC,ETH` (max 20 → 422 beyond) |

List of:

| Field | Type | Meaning |
|---|---|---|
| `s` | string | symbol (USDT-suffixed automatically) |
| `price` | float \| null | mark price (from ONE batch premiumIndex call) |
| `ch` | float \| null | 24h change, from the cached universe |
| `funding_rate` | float \| null | current (pre-settlement) funding rate |
| `next_funding_time_ms` | int \| null | next settlement; null when 0/absent |
| `oi_delta_pct` | float \| null | 5m open-interest change over the last ~2h |

## GET /api/macro

Sentiment/macro backdrop — a separate failure domain from market data (`502
macro_unavailable` only if the assembly itself fails; each block below fails
independently).

| Field | Type | Meaning |
|---|---|---|
| `fng` | object | Fear & Greed: `{value, classification, updated_at, cadence: "daily"}` or `{"unavailable": …}` (alternative.me, no key, 1h cache on a daily-cadence index) |
| `stablecoin` | object | `{stable_volume_share, usdc_usdt_ratio, stable_pairs: [{s, quote_volume}], note}` — derived from the ALREADY-fetched ticker payload (0 extra calls) |
| `defillama` | object | `{total_mcap_usd, usdt_share, usdc_share, top}` or `{"unavailable": …}` (best-effort) |
| `generated_at` | string | ISO-8601 UTC |

## GET /api/options

Deribit staged slice (BTC + ETH) — an independent venue with its own timeout and
circuit-breaker; it never blocks other data. NO skew interpolation (phase 2 later).

| Field | Type | Meaning |
|---|---|---|
| `BTC` / `ETH` | object | `{dvol_level, put_call_oi_ratio, atm_iv_30d, generated_at}` or `{"unavailable": "deribit_unreachable"}` |
| `generated_at` | string | ISO-8601 UTC |

`atm_iv_30d` = mean mark_iv of the strikes nearest the underlying on the expiry
nearest 30 DTE (±15d tolerance, else omitted). Cached 30s; the circuit-breaker opens
after 3 consecutive failures for 60s and short-circuits to `deribit_unreachable`.

## GET /api/leaders

Top movers from the top-200 universe, by 24h % change.

| Param | Default | Meaning |
|---|---|---|
| `limit` | `8` | rows per side |

Response: `{ "gainers": [row…], "losers": [row…] }` — each row has the same shape as
`GET /api/universe` rows, `gainers` sorted by `ch` descending, `losers` ascending.

## GET /api/logs

The backend's real request log — a most-recent-first ring buffer (max 200 entries) recording
actual outbound work (universe fetches, scans, symbol builds and their failures). Powers the
Network Log screen.

List of:

| Field | Type | Meaning |
|---|---|---|
| `t` | string | wall-clock time, `HH:MM:SS` |
| `m` | string | message describing the request/event |
| `s` | int | status (`200`; failures logged with `502`) |
| `ms` | int | duration in milliseconds |

## WS /api/live

WebSocket push channel for one symbol (used by the Panel screen). On connect the server
defaults to `BTCUSDT` and pushes the full [symbol object](#the-symbol-object) every **~5
seconds**. The client may send a plain-text message at any time; it is stripped and
upper-cased and becomes the streamed symbol.

Failure handling mirrors the REST contract: if a cycle's build fails the server pushes

```json
{ "error": "live_fetch_failed", "symbol": "BTCUSDT" }
```

…and keeps streaming — it never substitutes stale data for the failed symbol. Disconnect
(WebSocket close) ends the loop cleanly.

## Archive record (v2)

Every scan verdict is archived (append-only JSONL) as:

| Field | Type | Meaning |
|---|---|---|
| `ts` / `symbol` / `verdict` / `confidence` / `risk` / `net_score` / `dominant_dir` / `price` / `tf_agreement` / `divergence_state` / `engine_version` / `signals` | — | v1 fields (the 60-indicator vector at verdict time in `signals`) |
| `regime` | string \| null | `TREND` / `RANGE` / `MIXED` at verdict time |
| `micro_score` | float \| null | microstructure bundle score |
| `mtf_gate` | bool \| null | higher-TF confluence gate |
| `divergence_score` / `divergence_coverage` | float \| null / int \| null | whale divergence at verdict time |
| `divergence_tier` | string \| null | fixed tier of the divergence score |
| `cluster_id` | int \| null | sector cluster |
| `cluster_agreement` | float \| null | cluster confirmation share |
| `weights_hash` | string \| null | sha256 prefix of the shipped weights map — replay keystone |

Pre-v0.3 lines lack the v2 fields; consumers must treat them as `unclassified`, never
impute them.
