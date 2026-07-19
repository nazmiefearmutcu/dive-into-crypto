```
┌─ DIVE INTO CRYPTO ─────────────────────────────────────────────┐
│                                                                │
│   Binance USDT-M perpetual-futures scanner.                    │
│   57 indicators · 12 timeframes · whale-divergence filter ·    │
│   one confidence-scored consensus verdict per symbol.          │
│                                                                │
│   No account. No API keys. No sign-up. Public data only.       │
└────────────────────────────────────────────────────────────────┘
```

[![License: MIT](https://img.shields.io/badge/license-MIT-39ff9e?style=flat-square)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/nazmiefearmutcu/dive-into-crypto?style=flat-square&color=39ff9e)](https://github.com/nazmiefearmutcu/dive-into-crypto/commits)
![Python](https://img.shields.io/badge/python-3.12+-1c7a52?style=flat-square)
![Kotlin](https://img.shields.io/badge/kotlin-compose-1c7a52?style=flat-square)
![React](https://img.shields.io/badge/ui-react-1c7a52?style=flat-square)

Point it at the futures market and it tells you, per symbol, whether the evidence leans **long,
short, or neutral** — and how much to trust that read. Every symbol runs a weighted vote of **57
technical indicators across all 12 timeframes**, gets cross-checked against **top-trader
positioning** (whale flow that diverges from price is empirically contrarian), and collapses into a
single verdict with a 0–100 confidence and a LOW/MEDIUM/HIGH risk read.

It is not a bot. It places no orders, holds no keys, and reads nothing but public Binance
market data. It is an instrument for *seeing* the market, not trading it for you.

> **Not financial advice. Not an automated trader.** Crypto derivatives are high-risk; every
> decision is yours. See [Disclaimer](#disclaimer).

---

## Two editions, one engine

| | Desktop | Android |
| --- | --- | --- |
| **Stack** | Python (FastAPI) + React terminal UI | Kotlin · Jetpack Compose |
| **Data** | [Crypcodile](https://github.com/nazmiefearmutcu/Crypcodile)-fed, highest fidelity | Binance USDT-M public REST + WS |
| **Runs** | local, terminal-launched | on your device, single APK |
| **Engine** | full **57 indicators + 3 overlays** | 15-indicator core (fixture-pinned) |

Both speak the same consensus vocabulary. The Android app pins its 15-indicator core to a shared
Python reference by fixture (see [Parity](#parity)); the desktop engine is the reference and runs
the full extended set.

---

## The interface — "Depth Terminal"

Not a dashboard. A precision instrument: a blueprint grid, schematic corner-brackets, hairline
rulers, tabular numerics, one phosphor accent per theme, and glow only where something is alive.
Four themes — **Phosphor · Amber · Ice · Paper** — switch live.

| Scanner — the ranked sweep | Panel — one symbol, in depth |
| --- | --- |
| ![Scanner](docs/screenshots/desktop-scanner.png) | ![Panel](docs/screenshots/desktop-panel.png) |

---

## What the engine actually does

**1 · Fifty-seven indicators, one weighted vote.**
Each indicator returns a five-level signal (`STRONG_BUY +2 … STRONG_SELL −2`); the consensus is a
weighted average with verdict thresholds, a conflict override that forces NEUTRAL on too much
disagreement, ADX-driven regime adaptation, and a confidence score. The families:

```
TREND        ema/sma cross · macd · ichimoku · psar · adx/di · supertrend · vortex ·
             aroon · schaff · trix · kst · coppock · kalman · donchian · keltner · elder-ray
MOMENTUM     roc · awesome · rvi · cmo · tsi · qstick
OSCILLATOR   rsi · stochastic · williams %r · cci · connors-rsi · stoch-rsi · ultimate · fisher · wavetrend
VOLUME       obv · mfi · cmf · vwap · chaikin · klinger · a/d line · force-index · vwma cross
VOLATILITY   bollinger · %b · squeeze · choppiness · atr-filter · atr-percentile · hist-vol · mass · range-expansion
STATISTICAL  z-score reversion · linreg slope+R² · half-life (OU) · rolling sharpe · hurst · balance-of-power
```

**2 · Three futures-native overlays** (they never touch the parity-locked vote — they annotate it):

- **Microstructure** — a bundle over open interest, funding, taker buy/sell and long/short ratios:
  OI-price divergence, OI-breakout confirmation, funding z-score fade, taker aggression, L/S
  crowding fade, and a smart-vs-dumb-money spread.
- **Regime-adaptive weighting** — reads ADX + choppiness, routes trend vs. oscillator families.
- **Multi-timeframe confluence** — a gate that asks the higher timeframes to agree before it fires.

**3 · Whale-divergence filter.**
On top of the indicators, it watches top-trader long/short positioning and looks for it to trend
*against* price over a window. A symbol whose whale flow contradicts its indicator verdict is
**eliminated** from the ranking and back-filled by the next clean survivor.

---

## Data sources

Public Binance USDT-M Futures endpoints only — no authentication.

| Data | Endpoint |
| --- | --- |
| Candles (OHLCV) | `fapi/v1/klines` |
| Open interest history | `futures/data/openInterestHist` |
| Top-trader long/short | `futures/data/topLongShort{Account,Position}Ratio` |
| Global long/short | `futures/data/globalLongShortAccountRatio` |
| Taker buy/sell | `futures/data/takerlongshortRatio` |
| Funding / mark / index | `fapi/v1/premiumIndex`, `fapi/v1/fundingRate` |

> Binance market data is geo-restricted in some regions — a network condition, not an app bug.

---

## Run the desktop edition

Requires **Python 3.12+** and [`uv`](https://docs.astral.sh/uv/). The UI ships prebuilt — no Node
needed to run it.

```bash
git clone https://github.com/nazmiefearmutcu/dive-into-crypto.git
cd dive-into-crypto/desktop/backend
uv sync
uv run dive-desktop          # serves 127.0.0.1:8780 and opens the terminal UI
```

Nothing leaves your machine except public Binance requests. Details:
**[desktop/README.md](desktop/README.md)**. Android: **[android/README.md](android/README.md)**.

---

## Parity

Cross-language parity is enforced **per indicator**, not per screen: the shared 15-indicator core is
pinned to a `BTCUSDT 1h × 300` fixture (signal + score, exact), so Android and the desktop reference
agree on that core. The desktop engine extends the core to 57 indicators plus the three overlays;
porting the extended set to the Android/Kotlin mirror is on the roadmap. Nothing is synthesised —
when a data source is unavailable it is shown as unavailable, never faked.

```bash
cd desktop/backend && uv run pytest -q      # engine, parity, parsers  (offline)
uv run pytest -m live -q                     # network-gated, against live Binance
```

---

## Repository layout

```
dive-into-crypto/
├─ android/          native Kotlin/Compose edition (Gradle)
├─ desktop/
│  ├─ backend/       Python · FastAPI · the reference engine (57 indicators + overlays)
│  └─ ui/            React "Depth Terminal" — prebuilt bundle + design reference
└─ docs/             specs · screenshots
```

---

## Disclaimer

Dive Into Crypto is an educational and research tool. It gives no financial advice, executes no
trades, and makes no guarantee of accuracy or profitability. Cryptocurrency derivatives are
high-risk. Use it at your own risk.

## License

[MIT](LICENSE) © nazmiefearmutcu
