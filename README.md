# TRADING-BOT (TBV1)

[![License: MIT](https://img.shields.io/github/license/nazmiefearmutcu/TRADING-BOT?color=blue)](LICENSE)
[![Stars](https://img.shields.io/github/stars/nazmiefearmutcu/TRADING-BOT?style=flat&logo=github)](https://github.com/nazmiefearmutcu/TRADING-BOT/stargazers)
[![Python](https://img.shields.io/badge/python-3.11%2B-3776ab?logo=python&logoColor=white)](https://python.org)
[![Mode](https://img.shields.io/badge/default%20mode-paper-success)](#status)
[![Platforms](https://img.shields.io/badge/platforms-macOS%20%2B%20Windows-lightgrey)](#subdirectories)

**A trading bot that won't trade until 15 indicators across 12 timeframes agree.** Paper-mode by default — live trading must be explicitly enabled per credential. Python backend, 7-tab web dashboard, macOS and Windows packaged distributions.

- 📊 **15 indicators** voting via consensus (RSI, MACD, Bollinger, SMA, EMA, Stochastic, ADX, CCI, ATR, OBV, Williams %R, VWAP, Ichimoku, PSAR, KDJ)
- 🌀 **12 timeframes** scanned per symbol (1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d) with per-TF confidence
- 🛡️ **Paper-mode by default** — live trading must be explicitly enabled per credential
- 💻 **macOS reference build** + **Windows packaged distribution** (PyInstaller `--onedir` + 20 Turkish error codes + desktop shortcut creator)
- 🇹🇷 Turkish UI (`Genel Bakış / Tarama / Pozisyonlar / Sinyaller / Performans / Loglar / Ayarlar`)

> MIT licensed. macOS launcher at `~/Desktop/Projeler/proje/yedek/Trading Bot TBV1.app`; Windows install via `windows/build_windows.bat`.

## Preview

| Genel Bakış (Overview) | Tarama (Multi-symbol scan) |
| --- | --- |
| ![Dashboard with 12-timeframe confidence grid for an example symbol, balance $10000, PNL, and paper-mode futures status banner](docs/screenshots/01-panel.png) | ![Manual scan: 581 coins ranked, top-15 BUY/SELL signals across 12 timeframes, with cross-TF consistency ranking](docs/screenshots/02-tarama.png) |

| Sinyaller (Signal history) | Ayarlar (Settings) |
| --- | --- |
| ![Signal history feed showing recent BUY/SELL decisions with timestamps and confidence levels](docs/screenshots/03-sinyaller.png) | ![Settings page with risk profile, mode, daily limit, auto-scan, indicator weights and credential management](docs/screenshots/04-ayarlar.png) |

The dashboard is a FastAPI app served at `127.0.0.1:8081`. Both macOS and
Windows builds wrap the same Python code; the dashboard markup is identical.

## Subdirectories

- [`macOS/`](macOS/README.md) — original development build (April 2026)
- [`windows/`](windows/README.md) — packaged distribution with installer, icons, and Turkish error codes (May 2026)

## Status

Paper-mode is the default. Live trading on a credentialed perpetual-futures
account requires explicit opt-in via `Ayarlar` → `Mode = LIVE` and a per-
credential confirmation prompt.

## License

MIT — see [LICENSE](LICENSE).
