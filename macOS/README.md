# Trading Bot - Indicator Consensus Trading System

Production-grade automated trading bot that makes autonomous buy/sell decisions based on technical indicator consensus. Designed for Binance spot markets with futures-ready architecture.

## System Architecture

```
                          ┌──────────────────┐
                          │  active_symbol.txt│  <-- User writes here
                          └────────┬─────────┘
                                   │
┌─────────────┐  ┌────────────────▼──────────────┐
│ config.yaml │──▶     Symbol Controller          │
└─────────────┘  └────────────────┬──────────────┘
                                  │
                 ┌────────────────▼──────────────┐
                 │     Binance Market Data        │
                 │     (OHLCV Fetch)              │
                 └────────────────┬──────────────┘
                                  │
                 ┌────────────────▼──────────────┐
                 │     Signal Service             │
                 │     (15 Indicators)            │
                 │  RSI, MACD, Bollinger, SMA,    │
                 │  EMA, Stochastic, ADX, CCI,    │
                 │  Williams%R, ROC, MFI, ATR,    │
                 │  Ichimoku, PSAR, OBV           │
                 └────────────────┬──────────────┘
                                  │
                 ┌────────────────▼──────────────┐
                 │     Consensus Engine           │
                 │  - Weighted scoring            │
                 │  - Risk assessment             │
                 │  - Confidence calculation       │
                 └────────────────┬──────────────┘
                                  │
                 ┌────────────────▼──────────────┐
                 │     Decision Engine            │
                 │  - Entry/Exit logic            │
                 │  - Position awareness          │
                 │  - Daily loss limits           │
                 └────────────────┬──────────────┘
                                  │
                 ┌────────────────▼──────────────┐
                 │     Execution Engine           │
                 │  - Paper trading simulator     │
                 │  - Live Binance orders         │
                 └────────────────┬──────────────┘
                                  │
                 ┌────────────────▼──────────────┐
                 │     Position Manager           │
                 │  - Stop-loss / Take-profit     │
                 │  - Trailing stop               │
                 │  - Break-even                  │
                 └────────────────┬──────────────┘
                                  │
                 ┌────────────────▼──────────────┐
                 │     State Persistence          │
                 │     (runtime/state.json)       │
                 └───────────────────────────────┘
```

## Folder Structure

```
trading_bot/
├── .env.example              # API key template
├── requirements.txt          # Python dependencies
├── README.md
├── config/
│   └── default.yaml          # All configuration
├── runtime/
│   ├── active_symbol.txt     # Current trading symbol
│   ├── state.json            # Persisted bot state
│   └── bot.log               # Log output
├── src/
│   ├── api/
│   │   └── binance_client.py # Binance API wrapper
│   ├── data/
│   │   └── market_data.py    # OHLCV data provider
│   ├── indicators/
│   │   ├── base.py           # BaseIndicator, Signal, IndicatorResult
│   │   ├── rsi.py            # RSI
│   │   ├── macd.py           # MACD
│   │   ├── bollinger.py      # Bollinger Bands
│   │   ├── sma_cross.py      # SMA Crossover
│   │   ├── ema_cross.py      # EMA Crossover
│   │   ├── stochastic.py     # Stochastic Oscillator
│   │   ├── adx_di.py         # ADX + Directional Index
│   │   ├── cci.py            # CCI
│   │   ├── williams_r.py     # Williams %R
│   │   ├── roc.py            # Rate of Change
│   │   ├── mfi.py            # Money Flow Index
│   │   ├── atr_filter.py     # ATR (risk filter only)
│   │   ├── ichimoku.py       # Ichimoku Cloud
│   │   ├── psar.py           # Parabolic SAR
│   │   └── obv.py            # On-Balance Volume
│   ├── consensus/
│   │   ├── engine.py         # Main consensus aggregator
│   │   ├── scorer.py         # Weighted scoring
│   │   └── risk.py           # Risk assessment
│   ├── trading/
│   │   ├── order_models.py   # Order, Position, TradeRecord
│   │   ├── decision_engine.py# Action determination
│   │   ├── execution_engine.py# Paper/Live execution
│   │   └── position_manager.py# SL/TP/trailing management
│   ├── control/
│   │   ├── symbol_controller.py # Symbol file watcher
│   │   └── config_watcher.py    # Config loader
│   ├── persistence/
│   │   └── state_store.py    # JSON state persistence
│   ├── services/
│   │   ├── signal_service.py # Runs all indicators
│   │   └── bot_service.py    # Main orchestration loop
│   ├── utils/
│   │   ├── logger.py         # Structured logging
│   │   ├── helpers.py        # Utility functions
│   │   └── validators.py     # Input validation
│   └── main.py               # Entry point
├── tests/
│   ├── conftest.py           # Shared fixtures
│   ├── test_indicators.py
│   ├── test_consensus.py
│   ├── test_decision_engine.py
│   ├── test_position_manager.py
│   ├── test_symbol_controller.py
│   └── test_state_store.py
└── scripts/
    ├── run_bot.py            # Main runner
    ├── run_paper_trade.py    # Paper trade runner
    └── run_backtest.py       # Backtest runner
```

## Setup

### 1. Install dependencies

```bash
cd trading_bot
pip install -r requirements.txt
```

### 2. Configure API keys

```bash
cp .env.example .env
# Edit .env and add your Binance API keys
```

For **paper mode**, API keys are optional (public endpoints are used for market data).
For **live mode**, API keys are **required**.

### 3. Configure the bot

Edit `config/default.yaml` to set:
- `mode`: `"paper"` (default) or `"live"`
- `timeframe`: candle interval (e.g., `"1h"`, `"4h"`, `"15m"`)
- `polling_interval_seconds`: how often the bot runs a cycle
- Risk parameters (stop-loss, take-profit, trailing stop, etc.)
- Indicator weights and thresholds
- Consensus engine thresholds

### 4. Set the active trading symbol

```bash
echo "BTCUSDT" > runtime/active_symbol.txt
```

## Usage

### Paper Trading (Default)

```bash
python scripts/run_bot.py
# or
python scripts/run_paper_trade.py
# or with a specific symbol
python scripts/run_paper_trade.py ETHUSDT
```

### Live Trading

1. Set `mode: "live"` in `config/default.yaml`
2. Ensure `.env` has valid Binance API keys
3. Run:

```bash
python scripts/run_bot.py
```

**Warning**: Live mode places real orders with real money. Use at your own risk. Consider using Binance Testnet first by setting `USE_TESTNET=true` in `.env`.

### Backtest

```bash
python scripts/run_backtest.py                     # BTCUSDT, 1h, 500 candles
python scripts/run_backtest.py ETHUSDT 4h 1000     # Custom
```

### Changing Active Symbol at Runtime

While the bot is running, simply edit the symbol file:

```bash
echo "ETHUSDT" > runtime/active_symbol.txt
```

The bot will detect the change on the next cycle and switch to the new symbol automatically.

## .env Configuration

```env
BINANCE_API_KEY=your_api_key_here
BINANCE_API_SECRET=your_api_secret_here

# Optional: Testnet
BINANCE_TESTNET_API_KEY=your_testnet_key
BINANCE_TESTNET_API_SECRET=your_testnet_secret
USE_TESTNET=false
```

## Config Reference (config/default.yaml)

| Key | Description | Default |
|-----|-------------|---------|
| `mode` | Trading mode | `"paper"` |
| `market_type` | Market type | `"spot"` |
| `timeframe` | Candle interval | `"1h"` |
| `polling_interval_seconds` | Loop interval | `60` |
| `risk.risk_per_trade` | % of balance risked per trade | `0.02` |
| `risk.stop_loss_pct` | Stop-loss distance from entry | `0.025` |
| `risk.take_profit_pct` | Take-profit distance | `0.05` |
| `risk.trailing_stop_pct` | Trailing stop distance | `0.02` |
| `risk.confidence_threshold` | Min confidence to trade | `55` |
| `risk.max_open_positions` | Max concurrent positions | `1` |
| `risk.daily_loss_limit_pct` | Daily loss limit | `0.05` |
| `paper.starting_balance` | Paper trading balance | `10000.0` |
| `indicator_weights.*` | Weight for each indicator | varies |
| `consensus.strong_buy_threshold` | Score for STRONG_BUY | `1.2` |
| `consensus.buy_threshold` | Score for BUY | `0.4` |

## Indicator and Consensus Logic

### Signal Standard

Every indicator returns:
- **signal**: `STRONG_BUY` / `BUY` / `NEUTRAL` / `SELL` / `STRONG_SELL`
- **score**: `+2` / `+1` / `0` / `-1` / `-2`
- **reason**: Human-readable explanation

### Indicator Strategies

| Indicator | Buy Signal | Sell Signal |
|-----------|-----------|-------------|
| **RSI** | RSI <= 35 oversold | RSI >= 65 overbought |
| **MACD** | Bullish crossover | Bearish crossover |
| **Bollinger** | Price near/below lower band | Price near/above upper band |
| **SMA Cross** | Short SMA crosses above long | Short crosses below |
| **EMA Cross** | Short EMA above long + positive slope | Below + negative slope |
| **Stochastic** | Oversold reversal (<20, turning up) | Overbought reversal (>80, turning down) |
| **ADX+DI** | +DI > -DI with strong ADX | -DI > +DI with strong ADX |
| **CCI** | CCI <= -100 | CCI >= 100 |
| **Williams %R** | Oversold reversal | Overbought reversal |
| **ROC** | Positive momentum rising | Negative momentum falling |
| **MFI** | MFI <= 30 | MFI >= 70 |
| **ATR** | Risk filter only (no direction) | Adjusts position sizing |
| **Ichimoku** | Price above cloud + TK cross | Price below cloud |
| **PSAR** | SAR flips below price | SAR flips above price |
| **OBV** | Volume confirms uptrend | Volume confirms downtrend |

### Consensus Engine

1. **Weighted scoring**: Each indicator has a configurable weight. Trend/momentum indicators (MACD, SMA, EMA, Ichimoku) have higher weights.
2. **Risk assessment**: Checks ATR volatility, ADX trend strength, signal conflict ratio.
3. **Confidence score** (0-100): Based on score magnitude, signal agreement, and risk.
4. **Trade decision**: Only trades when confidence exceeds threshold and risk is acceptable.

## Example Log Output

```
2024-01-15 14:00:01 | INFO     | trading_bot.services.bot_service | --- Cycle #42 ---
2024-01-15 14:00:01 | INFO     | trading_bot.data.market_data     | OHLCV loaded | BTCUSDT | 1h | 200 candles | latest close=43250.50
2024-01-15 14:00:01 | INFO     | trading_bot.services.bot_service | Current price: BTCUSDT = 43250.5
2024-01-15 14:00:02 | INFO     | trading_bot.services.signal_service | Signals calculated: 15 total, 11 active (non-neutral)
2024-01-15 14:00:02 | INFO     | trading_bot.consensus.engine     | Consensus: BUY | confidence=68 | risk=LOW | should_trade=True | w_score=0.742 | buy=8 sell=3
2024-01-15 14:00:02 | INFO     | trading_bot.trading.decision_engine | Decision: OPEN_LONG | BTCUSDT | qty=0.00460000 | price=43250.5 | BUY signal | BUY conf=68 risk=LOW
2024-01-15 14:00:02 | INFO     | trading_bot.trading.position_manager | Position OPENED | BTCUSDT LONG | qty=0.0046 | entry=43250.5 | SL=42169.24 | TP=45413.02
2024-01-15 14:00:02 | INFO     | trading_bot.trading.execution_engine | [PAPER] OPEN LONG | BTCUSDT | qty=0.0046 | price=43250.5 | fee=0.1990 | balance=9800.77
2024-01-15 14:00:02 | INFO     | trading_bot.services.bot_service | Summary | BTCUSDT | signal=BUY conf=68% | risk=LOW | action=OPEN_LONG | pos=LONG qty=0.0046 entry=43250.5 | balance=9800.77 | daily_pnl=0.0000
```

## Example Decision JSON

```json
{
  "action": "OPEN_LONG",
  "symbol": "BTCUSDT",
  "quantity": 0.0046,
  "price": 43250.5,
  "reason": "BUY signal | BUY conf=68 risk=LOW",
  "timestamp": "2024-01-15T14:00:02+00:00",
  "consensus_signal": "BUY",
  "confidence": 68,
  "risk_level": "LOW"
}
```

## Running Tests

```bash
cd trading_bot
python -m pytest tests/ -v
```

## Risks and Limitations

- **This is NOT financial advice.** Automated trading carries significant risk of financial loss.
- Past indicator performance does not guarantee future results.
- The bot relies on Binance API availability - outages can affect operation.
- Paper trading results do not account for slippage, liquidity, or real market impact.
- Network latency can affect live order execution.
- The bot makes decisions based on technical indicators only - it does not consider fundamentals, news, or market sentiment.
- Always start with paper trading to validate the strategy before going live.
- Never risk more than you can afford to lose.
- The default configuration is conservative but should be tuned for your risk tolerance.
- Futures/short trading is architecture-ready but the first version focuses on spot (long-only).
