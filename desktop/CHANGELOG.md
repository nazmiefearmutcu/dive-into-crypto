# Changelog — Dive Into Crypto · Desktop Edition

All notable changes to the desktop edition are documented here.

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
