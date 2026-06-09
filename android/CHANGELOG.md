# Changelog

## v0.1.0 — 2026-06-07

First public release.

- Binance USDT‑M perpetual‑futures consensus scanner: **15 technical indicators across 12 timeframes**.
- Two‑phase market sweep (coarse high‑timeframe pass over the whole universe, then a fine low‑timeframe pass on survivors).
- Weighted‑vote consensus with regime‑adaptive weighting, conflict override, confidence scoring, and a risk assessor.
- **Whale‑divergence filtering** from Binance top‑trader long/short positioning, with adverse‑coin elimination and back‑fill.
- Independent microstructure consensus (price state · open‑interest momentum · taker aggression) for the OI · L/S leaderboard.
- Eight screens: Scanner, Panel, Signals, Positions (OI · L/S), Performance, Network Log, Appearance, Settings.
- English UI, 9 theme presets, on‑device preferences.
- Reads only public Binance market data — no account or API keys.
- Android 8.0+ (minSdk 26), targetSdk 34. Signed release APK, ~3.6 MB.
