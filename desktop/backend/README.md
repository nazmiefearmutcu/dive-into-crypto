# Dive Into Crypto — Desktop backend

Crypcodile-fed scanner service for the Desktop Edition. Computes the canonical Dive Into
Crypto consensus (15 indicators · 12 timeframes · whale-divergence, Android-parity) over
live Binance USDT-M perpetual futures and serves it to the desktop UI over local HTTP/WS.

## Run

```bash
uv sync
uv run dive-desktop          # starts the local service and opens the UI
uv run dive-desktop --no-open --port 8780
```

## Test

```bash
uv run pytest -v             # offline tests (engine parity, parsers)
uv run pytest -m live -v     # network-gated tests against live Binance
```

See `../../docs/superpowers/specs/2026-06-09-dive-into-crypto-desktop-design.md` for the design.
