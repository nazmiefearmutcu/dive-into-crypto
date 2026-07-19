# Dive Into Crypto — Desktop backend

The **reference** scanner service. Computes the canonical Dive Into Crypto consensus — **57
indicators across 12 timeframes**, three futures-native overlays (microstructure · regime-adaptive
weighting · MTF-confluence), and a whale-divergence filter — over live Binance USDT-M perpetual
futures, and serves it to the Depth Terminal UI over local HTTP/WS.

Its 15-indicator core is the parity anchor the Android app pins to (fixture-exact, per indicator);
the remaining 42 indicators and the three overlays are desktop-side extensions.

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

Design notes: `../../docs/superpowers/specs/2026-06-09-dive-into-crypto-desktop-design.md`.
Data is fed by [Crypcodile](https://github.com/nazmiefearmutcu/Crypcodile) (pinned commit). Public
Binance data only — no account, no keys.
