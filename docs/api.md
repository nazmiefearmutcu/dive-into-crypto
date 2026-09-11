# HTTP & WebSocket API reference

The desktop backend is a localhost-only FastAPI service (started by `uv run dive-desktop`,
serving **127.0.0.1:8780**). Source of truth:
`desktop/backend/src/diveintocrypto_desktop/api/app.py`, with response assembly in
`scan/symbol_builder.py` and `scan/scanner.py`; depth features live in `scan/evidence.py`
(grading archive), `scan/structure.py` (BTC-beta/clusters), `scan/progress.py` (scan
progress) and `data/cvd.py` (cumulative volume delta).

- **CORS**: `GET` and `POST` (the latter only for `POST /api/evidence/grade`),
  restricted to `http://127.0.0.1`, `http://localhost` and their
  sub-ports. There are no authenticated endpoints and no other write methods.
- **Static UI**: when `desktop/ui/dist/` exists it is mounted at `/` (that bundle is committed
  on purpose; see [Contributing](../CONTRIBUTING.md)).
- **Error contract — nothing is synthesised**: every field in every response is derived from
  real Binance USDT-M futures data. When a fetch fails the API returns an explicit error
  object (`symbol_fetch_failed`, `live_fetch_failed`) — it never substitutes plausible-looking
  numbers, zeros dressed as data, or cached values from another symbol. Derived values (e.g.
  the whale `bias` series) are transforms of real data, not fabrications.
- **Evolving shapes**: this documents the code as it stands today; response fields may gain
  entries as features land (consumers should tolerate unknown fields).

---

## GET /api/health

Liveness probe. No parameters.

```json
{ "ok": true, "service": "dive-into-crypto-desktop", "version": "0.1.0", "ui_built": true }
```

| Field | Type | Meaning |
|---|---|---|
| `ok` | bool | always `true` when the process answers |
| `service` | string | service identifier |
| `version` | string | backend version |
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

Backed by `fapi/v1/exchangeInfo` + `fapi/v1/ticker/24hr`.

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
/api/symbol/{symbol}`) plus these ranking/structure fields:

| Field | Type | Meaning |
|---|---|---|
| `dominantDir` | int | `1` buy-side, `-1` sell-side |
| `netNss` | float | winning side's Σ(confidence² · timeWeight/100) across timeframes |
| `beta` | float \| null | rolling 90-bar 1h-return OLS beta vs BTCUSDT (null when there is not enough aligned data) |
| `corr_btc` | float \| null | Pearson correlation of 1h returns vs BTCUSDT |
| `cluster_id` | int \| null | sector-cluster id (greedy single-link clustering at corr > 0.6, top 8 clusters labeled by their largest member); null when unclustered |

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
| `by_verdict` | object | `LONG` / `SHORT` / `NEUTRAL` → `{n, hit_rate, avg_forward, median_forward}` |
| `by_confidence` | list | buckets `0-25`, `25-50`, `50-75`, `75-100` → `{bucket, n, hit_rate, avg_forward, median_forward}` |
| `by_indicator` | object | per-indicator association: `{name: {n, agree: {n, hit_rate}, disagree: {n, hit_rate}}}` — report-only, never used to refit weights |
| `generated_at` | string | ISO-8601 UTC |
| `engine_version` | string | archive writer version |

Grading semantics (documented, deterministic):

- a verdict **hits** when price moves ≥1% in the dominant direction before moving ≥1%
  against it within the horizon (both in one candle → conservative miss; no decisive
  move → miss);
- `forward` is the direction-signed return at horizon end (raw/unsigned for `NEUTRAL`;
  a NEUTRAL verdict "hits" when price stayed within ±1%);
- a verdict whose kline backfill fails is listed as failed by the grading call and
  stays ungraded — nothing is imputed.

## POST /api/evidence/grade

Explicitly backfill outcomes for matured, not-yet-graded verdicts. Work is capped at
**40 symbols per call** (oldest verdicts first) and **resumable** — grades are appended
to `runtime/evidence_grades.jsonl` (override with `DIVE_EVIDENCE_GRADES_PATH`), so
repeated calls eventually cover the archive. Offline/failing fetches are reported, not
hidden.

| Param | Default | Meaning |
|---|---|---|
| `horizon` | `4h` | grading horizon — `1h`, `4h` or `24h` |

Response: `{horizon, archived, gradable, graded, symbols_graded, failed: [{symbol, reason}], remaining, summary}`.

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
| `clusters` | list | `{cluster_id, label, size, members: [{s, beta, corr_btc}]}` — id 1..8, labeled by the largest member |
| `unclustered` | list | `[{s, beta, corr_btc}]` with no cluster assignment |
| `unavailable` | list | symbols whose 1h data could not be fetched |

## GET /api/symbol/{symbol}

Builds the full per-symbol data contract for one symbol (symbol is case-insensitive; it is
upper-cased server-side). Primary timeframe is `1h`. This is the most expensive endpoint: it
fetches 12 timeframes of klines, open-interest history, all four long/short ratio series,
funding history, the 24h ticker, and the whale-divergence inputs.

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
| `candles` | list | last ≤120 candles of the primary TF — raw rows `{t, o, h, l, c, v}` (t = exchange ms) |
| `multiTf` | list | one entry per timeframe (12): `{tf, signal, confidence}`; a TF with <60 candles is `{tf, "NEUTRAL", 0}` |
| `buy` / `sell` / `neutral` | int | counts of `multiTf` entries whose signal contains BUY / SELL / neither |
| `indicators` | list | the primary-TF indicator table: `{name, signal, weight, value}` per indicator (`signal` is one of `STRONG_BUY/BUY/NEUTRAL/SELL/STRONG_SELL`, `weight` the configured consensus weight, `value` the indicator's primary raw reading rounded to 4 dp) |
| `finalSignal` | string | consensus verdict (`BUY`/`SELL`/`NEUTRAL`) |
| `confidence` | int | 0–100 consensus confidence |
| `action` | string | `"AL"` / `"SAT"` / `"BEKLE"` (buy / sell / wait — derived verbatim from `finalSignal`) |
| `reason` | string | human-readable consensus rationale |
| `risk` | string | `LOW` / `MEDIUM` / `HIGH` |
| `series` | object | real data series (5m window, last 48 points): `oi` open interest, `glob`/`acc`/`pos` global–/top-account–/top-position long/short ratios, `taker` taker buy/sell ratio, `funding` funding rates, `price` closes, `bias` whale-position lean (transform of `pos`, clamped ±96) |
| `quantBias` | float | whale-divergence score for the symbol (1 dp) |
| `whaleRegime` | string | `neutral` / `adverse` (whale flow contradicts the verdict) / `confirm` |
| `divergence` | object | `{score, tf, coverage}` — score (1 dp), best timeframe or `null`, and how many timeframes detected divergence |
| `microstructure` | object | futures-native overlay bundle (OI-price divergence, funding z-score fade, taker aggression, L/S crowding, smart-vs-dumb spread) — annotates, never alters, the consensus |
| `regime` | object | ADX/choppiness regime-adaptive weighting annotation |
| `mtfConfluence` | object | multi-timeframe confluence gate result |
| `cvd` | object | rolling **cumulative volume delta** over the last ≤1000 public aggTrades (15-minute window): `{window_trades, cvd, buy_vol, sell_vol, delta_series, first_t, last_t, window_seconds}`; on failure `{unavailable: reason}` — never zeros |

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
