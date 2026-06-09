# Dive Into Crypto

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/nazmiefearmutcu/dive-into-crypto)](https://github.com/nazmiefearmutcu/dive-into-crypto/commits)
[![Stars](https://img.shields.io/github/stars/nazmiefearmutcu/dive-into-crypto?style=social)](https://github.com/nazmiefearmutcu/dive-into-crypto/stargazers)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Python](https://img.shields.io/badge/Python-3776AB?logo=python&logoColor=white)
![React](https://img.shields.io/badge/React-149ECA?logo=react&logoColor=white)

**Dive Into Crypto is a financial scanner** for the entire Binance USDT‑M perpetual‑futures
market. For every symbol it runs **15 technical indicators across 12 timeframes**, cross‑checks
the result against **whale (top‑trader) positioning**, and collapses everything into a single
confidence‑scored consensus verdict — long, short, or neutral. It reads only **public** Binance
market data: no account, no API keys, no sign‑up.

It ships in **two editions** that share one consensus engine:

| Edition | Stack | Data | Runs | Status |
| --- | --- | --- | --- | --- |
| **[📱 Android](android/)** | Kotlin · Jetpack Compose | Binance USDT‑M public REST + WS | On your device | Released — `v0.1.0` |
| **[🖥️ Desktop](desktop/)** | Python (Crypcodile) + React terminal UI | Crypcodile‑fed, highest‑fidelity | Local, terminal‑launched | Released — `desktop-v0.1.0` |

> ⚠️ Dive Into Crypto is an analysis and research tool, **not financial advice** and **not an
> automated trader**. It places no orders. Markets are risky; you are responsible for your own decisions.

---

## The Desktop Edition

A single‑window terminal‑themed desktop scanner. Real Binance USDT‑M data flows through
[**Crypcodile**](https://github.com/nazmiefearmutcu/Crypcodile) (the open‑source market‑data
engine) into the same canonical consensus engine the Android app uses — so the desktop verdicts
**match Android exactly** (pinned by 30 parity tests). Three terminal themes: **Phosphor · Amber · Ice**.

| Scanner — ranked market sweep | Panel — one symbol, in depth |
| --- | --- |
| ![Desktop scanner](docs/screenshots/desktop-scanner.png) | ![Desktop panel](docs/screenshots/desktop-panel.png) |

### Run it

```bash
git clone https://github.com/nazmiefearmutcu/dive-into-crypto.git
cd dive-into-crypto/desktop/backend
uv sync
uv run dive-desktop          # starts the local service and opens the UI in your browser
```

The UI is prebuilt and served by the local service; nothing leaves your machine except public
Binance market‑data requests. Full details: **[desktop/README.md](desktop/README.md)**.

---

## The Android Edition

Native Kotlin/Compose, runs entirely on your device, single signed APK. Install and usage:
**[android/README.md](android/README.md)** · APK on the [latest release](../../releases/latest).

---

## The shared engine

Both editions implement the *same* consensus, pinned to one Python reference (the desktop reuses
it directly; Android pins to it via 61 fixture tests).

### 15 indicators
Every symbol × timeframe runs 15 independent indicators, each returning a five‑level signal
(`STRONG_BUY = +2 … STRONG_SELL = −2`).

| # | Indicator | Defaults | Family |
| --- | --- | --- | --- |
| 1 | RSI | period 14 | Oscillator |
| 2 | Stochastic | %K 14, %D 3 | Oscillator |
| 3 | Williams %R | period 14 | Oscillator |
| 4 | CCI | period 20 | Oscillator |
| 5 | MACD | 12 / 26 / 9 | Trend |
| 6 | EMA cross | 9 / 21 | Trend |
| 7 | SMA cross | 50 / 200 | Trend |
| 8 | Ichimoku | 9 / 26 / 52 | Trend |
| 9 | Parabolic SAR | AF 0.02 / 0.2 | Trend |
| 10 | Bollinger Bands | 20, 2σ | Volatility |
| 11 | MFI | period 14 | Volume |
| 12 | OBV | SMA 20 | Volume |
| 13 | ROC | period 12 | Momentum |
| 14 | ADX + DI | period 14 | Trend strength |
| 15 | ATR filter | period 14 | Volatility (filter, weight 0) |

### Consensus
A weighted vote (`weightedScore = Σ(signal × weight) / Σ(weight)`) with verdict thresholds
(`≥1.2 STRONG_BUY · ≥0.4 BUY · ≤−0.4 SELL · ≤−1.2 STRONG_SELL`), a conflict override that forces
NEUTRAL on too much disagreement, ADX‑driven regime adaptation, a 0–100 confidence score, and a
LOW/MEDIUM/HIGH risk assessment.

### Whale‑divergence filtering
On top of the indicators, Dive Into Crypto watches **top‑trader long/short positioning** and looks
for it to **diverge from price** (empirically contrarian, calibrated on real Binance observations).
A symbol whose whale flow contradicts its indicator verdict is **eliminated** from the ranking and
back‑filled by the next clean survivor. The 12 timeframes are
`1m · 3m · 5m · 15m · 30m · 1h · 2h · 4h · 6h · 8h · 12h · 1d`.

---

## Data sources

Public Binance USDT‑M Futures endpoints only — no authentication.

| Data | Endpoint |
| --- | --- |
| Candles (OHLCV) | `fapi.binance.com/fapi/v1/klines` |
| Open interest history | `fapi.binance.com/futures/data/openInterestHist` |
| Top‑trader L/S (positions / accounts) | `fapi.binance.com/futures/data/topLongShort*Ratio` |
| Global L/S (accounts) | `fapi.binance.com/futures/data/globalLongShortAccountRatio` |
| Taker buy/sell ratio | `fapi.binance.com/futures/data/takerlongshortRatio` |
| Funding / mark / index | `fapi.binance.com/fapi/v1/premiumIndex`, `…/fundingRate` |

> Binance market data may be geo‑restricted in some regions; that is a network/runtime condition
> independent of the app.

---

## Repository layout

```
dive-into-crypto/
├─ android/    # native Kotlin/Compose edition (Gradle)
├─ desktop/    # Python (Crypcodile) backend + React terminal UI
└─ docs/       # specs, plans, screenshots
```

---

## Disclaimer

Dive Into Crypto is an educational and research tool. It does not give financial advice, it does
not execute trades, and it makes no guarantee of accuracy or profitability. Cryptocurrency
derivatives are high‑risk. Use it at your own risk.

## License

[MIT](LICENSE) © nazmiefearmutcu
