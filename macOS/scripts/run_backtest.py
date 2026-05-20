#!/usr/bin/env python3
"""Mini backtest module - runs historical analysis and reports performance.

Usage:
    python scripts/run_backtest.py                     # Default: BTCUSDT, 1h, 500 candles
    python scripts/run_backtest.py ETHUSDT 4h 1000     # Custom symbol, timeframe, candles
"""

import sys
import json
from pathlib import Path
from datetime import datetime, timezone

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from dotenv import load_dotenv
from src.control.config_watcher import load_config
from src.api.binance_client import BinanceClient
from src.data.market_data import MarketDataProvider
from src.services.signal_service import SignalService
from src.consensus.engine import ConsensusEngine
from src.trading.decision_engine import DecisionEngine
from src.trading.execution_engine import ExecutionEngine
from src.trading.position_manager import PositionManager
from src.trading.order_models import TradeAction
from src.utils.logger import setup_logger, get_logger


def run_backtest(symbol: str = "BTCUSDT", timeframe: str = "1h", candle_limit: int = 500):
    env_path = project_root / ".env"
    if env_path.exists():
        load_dotenv(env_path)

    config = load_config("config/default.yaml")
    config["mode"] = "paper"
    config["timeframe"] = timeframe
    config["candle_limit"] = candle_limit

    setup_logger(log_file=None)  # Console only
    logger = get_logger("backtest")

    logger.info(f"Backtest: {symbol} | {timeframe} | {candle_limit} candles")

    # Initialize
    client = BinanceClient(config)
    client.initialize()
    market_data = MarketDataProvider(client, config)
    signal_service = SignalService(config)
    consensus_engine = ConsensusEngine(config)
    position_manager = PositionManager(config)
    decision_engine = DecisionEngine(config, position_manager)

    paper_config = config.get("paper", {})
    fee_pct = paper_config.get("fee_pct", 0.001)
    starting_balance = paper_config.get("starting_balance", 10000.0)
    balance = starting_balance

    # Fetch data
    df = market_data.get_ohlcv(symbol)
    if df is None or df.empty:
        logger.error("No data fetched for backtest")
        return

    # We need a warmup period for indicators
    warmup = 60
    if len(df) <= warmup:
        logger.error(f"Not enough data. Need > {warmup} candles, got {len(df)}")
        return

    trades: list[dict] = []
    actions_dist: dict[str, int] = {}
    signals_dist: dict[str, int] = {}

    logger.info(f"Running backtest over {len(df) - warmup} candles after {warmup} warmup...")

    for i in range(warmup, len(df)):
        window = df.iloc[:i + 1].copy()
        current_price = float(window["close"].iloc[-1])

        # Calculate signals
        results = signal_service.calculate_all(window)
        consensus = consensus_engine.evaluate(results)

        sig = consensus["final_signal"]
        signals_dist[sig] = signals_dist.get(sig, 0) + 1

        # Decision
        daily_pnl = sum(t.get("pnl", 0) for t in trades)
        decision = decision_engine.decide(
            symbol=symbol,
            consensus=consensus,
            current_price=current_price,
            balance=balance,
            daily_pnl=daily_pnl,
            daily_start_balance=starting_balance,
        )

        action = decision["action"]
        actions_dist[action] = actions_dist.get(action, 0) + 1

        # Simulate execution
        if action == TradeAction.OPEN_LONG.value:
            qty = decision["quantity"]
            cost = current_price * qty * (1 + fee_pct)
            if cost <= balance:
                balance -= cost
                position_manager.open_position(
                    symbol, __import__("src.trading.order_models", fromlist=["PositionSide"]).PositionSide.LONG,
                    current_price, qty
                )

        elif action == TradeAction.CLOSE_LONG.value:
            pos = position_manager.get_position(symbol)
            if pos:
                record = position_manager.close_position(symbol, current_price, "backtest_exit", fee_pct)
                if record:
                    balance += current_price * record.quantity * (1 - fee_pct)
                    trades.append(record.to_dict())

    # Close any remaining position at last price
    last_price = float(df["close"].iloc[-1])
    pos = position_manager.get_position(symbol)
    if pos:
        record = position_manager.close_position(symbol, last_price, "backtest_end", fee_pct)
        if record:
            balance += last_price * record.quantity * (1 - fee_pct)
            trades.append(record.to_dict())

    # Report
    total_trades = len(trades)
    wins = sum(1 for t in trades if t.get("pnl", 0) > 0)
    losses = sum(1 for t in trades if t.get("pnl", 0) < 0)
    total_pnl = sum(t.get("pnl", 0) for t in trades)
    win_rate = (wins / total_trades * 100) if total_trades > 0 else 0

    print("\n" + "=" * 60)
    print(f"  BACKTEST RESULTS: {symbol} ({timeframe})")
    print("=" * 60)
    print(f"  Period:           {len(df) - warmup} candles")
    print(f"  Starting Balance: ${starting_balance:,.2f}")
    print(f"  Final Balance:    ${balance:,.2f}")
    print(f"  Total PnL:        ${total_pnl:,.4f}")
    print(f"  Return:           {((balance - starting_balance) / starting_balance * 100):,.2f}%")
    print(f"  Total Trades:     {total_trades}")
    print(f"  Wins:             {wins}")
    print(f"  Losses:           {losses}")
    print(f"  Win Rate:         {win_rate:.1f}%")
    print("-" * 60)
    print("  Signal Distribution:")
    for sig, count in sorted(signals_dist.items()):
        print(f"    {sig:15s}: {count}")
    print("  Action Distribution:")
    for act, count in sorted(actions_dist.items()):
        print(f"    {act:15s}: {count}")
    print("=" * 60)

    if trades:
        print("\n  Last 5 Trades:")
        for t in trades[-5:]:
            print(f"    {t['side']} | entry={t['entry_price']:.2f} exit={t.get('exit_price', 'N/A')} | pnl={t['pnl']:.4f} | {t['reason']}")

    return {
        "symbol": symbol,
        "timeframe": timeframe,
        "candles": len(df) - warmup,
        "starting_balance": starting_balance,
        "final_balance": round(balance, 4),
        "total_pnl": round(total_pnl, 4),
        "return_pct": round((balance - starting_balance) / starting_balance * 100, 2),
        "total_trades": total_trades,
        "wins": wins,
        "losses": losses,
        "win_rate": round(win_rate, 1),
        "signals": signals_dist,
        "actions": actions_dist,
    }


if __name__ == "__main__":
    sym = sys.argv[1] if len(sys.argv) > 1 else "BTCUSDT"
    tf = sys.argv[2] if len(sys.argv) > 2 else "1h"
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 500
    run_backtest(sym, tf, limit)
