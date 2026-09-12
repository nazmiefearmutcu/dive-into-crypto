# Dive Into Crypto — Desktop backend

The **reference** scanner service. Computes the canonical Dive Into Crypto consensus — **60
indicators across 12 timeframes**, three futures-native overlays (microstructure · regime-adaptive
weighting · MTF-confluence), and a whale-divergence filter — over live Binance USDT-M perpetual
futures, and serves it to the Depth Terminal UI over local HTTP/WS.

It is the parity anchor the Android app pins to: all 60 indicators are fixture-verified per
indicator (signal + score, exact) against this reference, and the three overlays are mirrored in
Kotlin with matching unit tests.

## Run

```bash
uv sync
uv run dive-desktop                       # starts the service and opens the UI (127.0.0.1:8780)
uv run dive-desktop --no-open --port 8780
```

## Test

```bash
uv run pytest -q             # offline: engine, consensus, parity fixtures, parsers, overlays
uv run pytest -m live -q     # network-gated, against live Binance
```

## Depth features (backend)

- **Full-universe scanning** — `/api/scan` accepts `universe_limit` up to **500**. Phase 1
  sweeps the whole requested universe over the three highest-weight timeframes (4h/12h/1d)
  with adaptive concurrency + 429/451-aware global pacing; phase 2 fetches the remaining 9
  timeframes for the top `depth_top` rows (default 50, cap 200). A 500-symbol scan makes
  ~2000+ public requests and takes minutes — use `&async=1` (immediate `scan_id`) and poll
  `GET /api/scan/progress` / fetch `GET /api/scan/result`.
- **Evidence layer** — every scan verdict is appended to `runtime/evidence.jsonl`
  (`DIVE_EVIDENCE_PATH` overrides). `POST /api/evidence/grade?horizon=4h` backfills forward
  returns from public klines (40 symbols/call, resumable); `GET /api/evidence` reports
  hit-rate/expectancy per verdict and confidence bucket plus a report-only per-indicator
  association. The evidence layer never breaks scanning and never refits parameters.
- **CVD** — `/api/symbol/{symbol}` carries a `cvd` object: rolling cumulative volume delta,
  buy/sell split and a downsampled delta series from the public aggTrades feed
  (`{unavailable: reason}` when the feed fails). `DIVE_FAPI_BASE` applies.
- **Market structure** — scan rows gain `beta`, `corr_btc`, `cluster_id` (rolling 90-bar 1h
  beta/corr vs BTCUSDT + greedy corr>0.6 sector clustering); `GET /api/structure` maps the
  top universe. Report-only annotations.

Design notes: `../../docs/superpowers/specs/2026-06-09-dive-into-crypto-desktop-design.md`.
Data is fed by [Crypcodile](https://github.com/nazmiefearmutcu/Crypcodile) (pinned commit). Public
Binance data only — no account, no keys.
