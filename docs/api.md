# HTTP & WebSocket API reference

The desktop backend is a localhost-only FastAPI service (started by `uv run dive-desktop`,
serving **127.0.0.1:8780**). Source of truth:
`desktop/backend/src/diveintocrypto_desktop/api/app.py`, with response assembly in
`scan/symbol_builder.py` and `scan/scanner.py`.

- **CORS**: `GET` only, restricted to `http://127.0.0.1`, `http://localhost` and their
  sub-ports. There are no authenticated endpoints and no write methods.
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

Runs the full scanner sweep and returns the ranked survivor table plus the whale-eliminated
rows.

| Param | Default | Meaning |
|---|---|---|
| `size` | `10` | max survivors returned |
| `universe_limit` | `30` | how many top-volume symbols to scan |

Response:

| Field | Type | Meaning |
|---|---|---|
| `survivors` | list | top `size` rows whose whale flow does not contradict the indicator verdict |
| `eliminated` | list | rows eliminated by the whale-divergence filter (kept for transparency) |
| `universeCount` | int | symbols considered |
| `scanned` | int | timeframes evaluated (`symbols × 12`) |

Each row is the full [symbol object](#the-symbol-object) (same shape as `GET
/api/symbol/{symbol}`) plus two ranking fields: `dominantDir` (`1` buy-side, `-1` sell-side)
and `netNss` (winning side's Σ(confidence² · timeWeight/100) across timeframes). Rows are
ranked by `netNss` + 0.35 · divergence aligned to the dominant direction.

**Caching**: results are cached in memory for **20 seconds** keyed on
`size:universe_limit`; concurrent identical requests share one computation (single-flight
lock). A refresh within the TTL returns the cached response unchanged.

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
