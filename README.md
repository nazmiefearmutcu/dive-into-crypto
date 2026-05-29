# Trading Bot — Multi-Coin, Multi-Timeframe Indicator-Consensus Engine

Scans the **entire Binance USDT-margined perpetual-futures universe — every listed
coin** — running a **15-indicator weighted consensus on all 12 timeframes
(`1m` → `1d`)** and cross-ranking the results to surface the coins with the
strongest, most consistent multi-timeframe agreement.

> **15 indicators × 12 timeframes × every coin on the market.**

For each *(coin, timeframe)* the engine aggregates 15 technical indicators — RSI,
MACD, Bollinger, SMA/EMA crosses, Stochastic, ADX+DI, CCI, Williams %R, ROC, MFI,
ATR, Ichimoku, PSAR, OBV — into a weighted signal plus a `0–100` confidence, scores
each timeframe with **NSS** = `confidence² × (ZAK / 100)` (where ZAK weights higher
timeframes more), and cross-ranks the whole market by net NSS. Because the coin list
is fetched live, the scan always covers however many pairs Binance currently lists,
not a fixed subset. The same consensus engine also drives an autonomous paper/live
execution bot and a real-time dashboard.

## Layout

| Path | What it is |
|------|------------|
| [`macOS/`](macOS/) | Primary codebase: the bot, consensus engine, FastAPI dashboard, and full test suite. See [macOS/README.md](macOS/README.md). |
| [`windows/`](windows/) | Windows packaging layer — a one-click, iconed `.exe` launcher wrapping the same app. See [windows/README.md](windows/README.md). |

## Quick start (macOS)

```bash
cd macOS
pip install -r requirements.txt
cp .env.example .env               # add Binance keys for live mode (optional for paper)
python scripts/run_dashboard.py    # dashboard UI
# or
python scripts/run_bot.py          # headless bot
```

The scanner reads Binance's USDT-futures market to build the universe; the execution
bot itself trades **spot by default** (long-only), with futures/short support
architecture-ready. Full setup, configuration, indicator logic, and risk notes are in
[macOS/README.md](macOS/README.md).

## Disclaimer

Automated trading carries significant risk of financial loss. This is **not** financial
advice. Always start in paper mode and never risk more than you can afford to lose.
