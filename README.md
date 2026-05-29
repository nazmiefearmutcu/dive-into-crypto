# Trading Bot — Multi-Coin, Multi-Timeframe Indicator-Consensus Engine

[![License: MIT](https://img.shields.io/github/license/nazmiefearmutcu/TRADING-BOT?color=blue)](LICENSE)
[![Stars](https://img.shields.io/github/stars/nazmiefearmutcu/TRADING-BOT?style=flat&logo=github)](https://github.com/nazmiefearmutcu/TRADING-BOT/stargazers)
[![Python](https://img.shields.io/badge/python-3.11%2B-3776ab?logo=python&logoColor=white)](https://python.org)
[![Mode](https://img.shields.io/badge/default%20mode-paper-success)](#status)
[![Platforms](https://img.shields.io/badge/platforms-macOS%20%2B%20Windows-lightgrey)](#subdirectories)

**A trading bot that won't trade until 15 indicators agree across 12 timeframes — on every coin on the market.** It scans the entire Binance USDT-margined perpetual-futures universe, runs a 15-indicator weighted consensus on all 12 timeframes for each coin, and cross-ranks the results to surface the coins with the strongest, most consistent multi-timeframe signal. Paper-mode by default; live trading must be explicitly enabled per credential. Python backend, 7-tab web dashboard, macOS and Windows packaged distributions.

> **15 indicators × 12 timeframes × every coin on the market.**

- 📊 **15 indicators** voting via consensus (RSI, MACD, Bollinger, SMA, EMA, Stochastic, ADX, CCI, Williams %R, ROC, MFI, ATR, Ichimoku, PSAR, OBV)
- 🌀 **12 timeframes** scanned per symbol (1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d) with per-timeframe confidence
- 🌐 **Whole-market scan** — every listed USDT-futures coin, cross-ranked by multi-timeframe agreement (net NSS)
- 🛡️ **Paper-mode by default** — live trading must be explicitly enabled per credential
- 💻 **macOS reference build** + **Windows packaged distribution** (PyInstaller `--onedir`, 20 English error codes, desktop-shortcut creator)

## Preview

#### Dashboard (Overview)
![Dashboard: bot-running indicator, 12-timeframe confidence grid for the active symbol with per-timeframe BUY / SELL / HOLD votes, balance and PnL counters, paper-mode futures status banner, recent positions and signal-history strips](docs/screenshots/01-dashboard.png)

#### Scanner (Whole-market scan)
![Scanner: whole-market scan results ranked by cross-timeframe consistency, top BUY and top SELL columns with per-symbol confidence chips, a 12-timeframe colored heat strip per row, and manual rescan controls](docs/screenshots/02-scanner.png)

#### Positions (Open positions)
![Positions: open-position cards with LONG / SHORT badges, leverage chip, Close button, PnL bar, an entry / current / amount / liquidation-price grid, an SL + TP + Trailing strip, paper-mode footer, and a trade-history table](docs/screenshots/03-positions.png)

#### Signals (Signal history)
![Signals: signal feed with timestamped BUY / SELL decisions, per-decision confidence percentage, contributing-indicators list, time-since strip, and infinite scroll](docs/screenshots/04-signals.png)

#### Performance
![Performance: lifetime PnL summary, win rate, average win and loss, best and worst trades, equity-curve sparkline, and trade-volume and turnover counters](docs/screenshots/05-performance.png)

#### Logs (Bot logs)
![Logs: live bot log tail with severity colour-coding, decision-cycle markers, per-symbol entry and exit lines, and a scroll-to-follow toggle](docs/screenshots/06-logs.png)

#### Settings
![Settings: risk-profile picker, mode toggle (PAPER / LIVE), daily limit and per-trade size, auto-scan cadence, indicator-weights matrix, and credential management with a reveal toggle](docs/screenshots/07-settings.png)

The dashboard is a FastAPI app served locally on `127.0.0.1`. Both the macOS and
Windows builds wrap the same Python code; the dashboard markup is identical.

## Subdirectories

- [`macOS/`](macOS/README.md) — original development / reference build
- [`windows/`](windows/README.md) — packaged distribution with installer, icons, and English error codes

## Status

Paper-mode is the default. Live trading on a credentialed perpetual-futures
account requires explicit opt-in via **Settings → Mode = LIVE** and a
per-credential confirmation prompt.

## License

MIT — see [LICENSE](LICENSE).
