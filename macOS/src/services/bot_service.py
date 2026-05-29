"""Bot service - main orchestration loop tying all components together."""

import time
import json
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from src.api.binance_client import BinanceClient
from src.data.market_data import MarketDataProvider
from src.market.live_price_service import LivePriceService, RestPriceAdapter
from src.services.signal_service import SignalService
from src.services.scanner_service import ScannerService
from src.consensus.engine import ConsensusEngine
from src.trading.decision_engine import DecisionEngine
from src.trading.execution_engine import ExecutionEngine, LIVE_SHORT_UNSUPPORTED_REASON
from src.trading.leverage_manager import LeverageManager
from src.trading.order_models import TradeAction, PositionSide
from src.trading.position_manager import PositionManager
from src.control.symbol_controller import SymbolController
from src.control.config_watcher import ConfigWatcher, load_config
from src.persistence.command_queue import CommandQueue
from src.persistence.schemas import CommandKind, CommandSchema
from src.persistence.state_store import StateStore
from src.services.command_processor import CommandProcessor
from src.monitoring.status_exporter import StatusExporter
from src.utils.logger import get_logger
from src.utils.helpers import iso_now

logger = get_logger("services.bot_service")


def _compute_candle_key(df: Any) -> str:
    """Stable key for the latest candle in an OHLCV DataFrame.

    Uses ``open_time`` of the last row when available, falling back to
    ``close_time``. Returns the empty string for an empty/missing frame.

    This deliberately does NOT read ``df.index[-1]`` — the upstream
    ``MarketDataProvider`` calls ``reset_index(drop=True)``, so the index
    is a RangeIndex and ``df.index[-1]`` is just ``len(df) - 1``. That
    integer is effectively constant across cycles (the candle limit is
    fixed at 200), which silently broke same-candle detection prior to S3.
    """
    if df is None:
        return ""
    try:
        if df.empty:
            return ""
    except Exception:
        return ""
    for col in ("open_time", "close_time"):
        if col in df.columns:
            try:
                val = df[col].iloc[-1]
            except Exception:
                continue
            if val is None:
                continue
            try:
                # pandas Timestamp / datetime → ISO
                return val.isoformat()
            except AttributeError:
                return str(val)
    return ""


class BotService:
    """Main bot orchestrator - runs the continuous trading loop."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.running = False

        # Initialize components
        self.binance_client = BinanceClient(config)
        self.market_data = MarketDataProvider(self.binance_client, config)
        # S3 live-price seam — REST/polling rescue today; the subscribe_symbol
        # hook is the future WebSocket adapter swap point.
        self.live_price_service = LivePriceService(RestPriceAdapter(self.binance_client))
        self.signal_service = SignalService(config)
        self.consensus_engine = ConsensusEngine(config)
        self.position_manager = PositionManager(config)
        self.leverage_manager = LeverageManager(config, self.binance_client)
        self.decision_engine = DecisionEngine(config, self.position_manager, self.leverage_manager)
        self.execution_engine = ExecutionEngine(config, self.binance_client, self.position_manager)
        self.symbol_controller = SymbolController(config.get("active_symbol_path", "runtime/active_symbol.txt"))
        self.state_store = StateStore(config.get("state_path", "runtime/state.json"))
        self.status_exporter = StatusExporter(config.get("dashboard_status_path", "runtime/dashboard_status.json"))

        # S5: control-plane command queue + processor. The dashboard writes
        # to the SAME file (see dashboard/app.py COMMAND_QUEUE_FILE). The
        # default path is anchored to the runtime dir derived from
        # dashboard_status_path so test harnesses that point at tmp_path get
        # their own isolated queue automatically.
        _ds_path = Path(config.get("dashboard_status_path", "runtime/dashboard_status.json"))
        _runtime_dir = _ds_path.parent if _ds_path.parent != Path(".") else Path("runtime")
        self.command_queue = CommandQueue(
            config.get("command_queue_path", _runtime_dir / "command_queue.json")
        )
        self.command_processor = CommandProcessor(self.command_queue)
        self.command_processor.register(CommandKind.MANUAL_CLOSE, self._handle_manual_close)
        self.command_processor.register(CommandKind.PAPER_RESET, self._handle_paper_reset)
        # Configuration health surfaces: surfaced to dashboard so an operator
        # who picks live+futures+short knows the execution path won't honor it.
        self._status_warnings: list[str] = []
        config_path = config.get("_config_path", "config/default.yaml")
        self.config_watcher = ConfigWatcher(config_path)
        self.config_watcher.config = config
        # Set mtime to current so we don't reload on first cycle
        try:
            self.config_watcher._last_mtime = Path(config_path).stat().st_mtime
        except Exception:
            self.config_watcher._last_mtime = 0.0

        self.polling_interval = config.get("polling_interval_seconds", 60)
        self._cycle_count = 0

        # Same-candle detection: don't open new positions on unchanged data
        self._last_candle_time: str | None = None
        self._candle_changed = False

        # Per-cycle state for status export
        self._last_indicator_results: list[Any] | None = None
        self._last_consensus: dict[str, Any] | None = None
        self._last_decision: dict[str, Any] | None = None
        self._last_execution: dict[str, Any] | None = None
        self._last_price: float | None = None
        # S3: track the candle close the decision engine acted on separately
        # from the freshest live tick. The dashboard exports both.
        self._last_signal_price: float | None = None

        # Auto-scan: multi-TF scan every 10 minutes in background
        self._auto_scan_interval = config.get("auto_scan_interval_seconds", 600)  # 10 min
        self._scanning_active = False  # True while any scan is in progress
        # Restore last scan time from progress file to avoid immediate re-scan on restart
        self._last_auto_scan_time: float = 0.0
        try:
            _prog_path = Path(config.get("dashboard_status_path", "runtime/dashboard_status.json")).parent / "auto_scan_progress.json"
            if _prog_path.exists():
                _prog = json.loads(_prog_path.read_text())
                _last_ts = _prog.get("last_auto_scan") or _prog.get("completed_at") or _prog.get("started_at")
                if _last_ts:
                    from datetime import datetime as _dt, timezone as _tz
                    _parsed = _dt.fromisoformat(_last_ts)
                    _age_secs = (_dt.now(_tz.utc) - _parsed).total_seconds()
                    if _age_secs < self._auto_scan_interval:
                        # Recent scan exists — set timer so we wait the remaining interval
                        self._last_auto_scan_time = time.time() - _age_secs
                        logger.info(f"Restored last scan time: {_last_ts} ({_age_secs:.0f}s ago)")
        except Exception:
            pass
        self._scan_threads: list[threading.Thread] = []
        self._scan_lock = threading.Lock()
        self._multi_scan_done_count = 0
        self._multi_scan_results: dict[str, list] = {}  # tf → top15 results
        self._multi_scan_full: dict[str, list] = {}    # tf → ALL results

        # All 12 timeframes sorted by duration
        _TF_MINUTES = {"m": 1, "h": 60, "d": 1440}
        self._multi_tfs = sorted(
            ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"],
            key=lambda t: int(t[:-1]) * _TF_MINUTES.get(t[-1], 1),
        )

        # ZAK — Timeframe Weight Coefficient
        self._ZAK = {
            "1d": 95, "12h": 90, "8h": 85, "6h": 80, "4h": 75,
            "2h": 65, "1h": 58, "30m": 48, "15m": 38, "5m": 25,
            "3m": 15, "1m": 8,
        }

    def initialize(self) -> None:
        """Initialize all components and restore state."""
        # Clean stale manual scan lock from previous runs
        # (auto_scan_progress.json is kept — it has last_auto_scan timestamp)
        try:
            p = Path("runtime/manual_scan_active.json")
            if p.exists():
                p.unlink()
                logger.info("Cleaned stale manual scan lock file")
        except Exception:
            pass
        # Mark any in-progress auto-scan as not-scanning (stale from previous run)
        try:
            p = Path("runtime/auto_scan_progress.json")
            if p.exists():
                _d = json.loads(p.read_text())
                if _d.get("scanning"):
                    _d["scanning"] = False
                    p.write_text(json.dumps(_d, default=str))
                    logger.info("Reset stale auto-scan scanning flag")
        except Exception:
            pass
        logger.info("=" * 60)
        logger.info("Trading Bot Initializing...")
        logger.info(f"Mode: {self.config.get('mode', 'paper')}")
        logger.info(f"Market: {self.config.get('market_type', 'spot')}")
        logger.info(f"Timeframe: {self.config.get('timeframe', '1h')}")
        logger.info("=" * 60)

        # Initialize Binance client
        self.binance_client.initialize()

        # Restore state
        state = self.state_store.load()
        self._restore_state(state)

        # Load initial symbol
        symbol = self.symbol_controller.get_current_symbol()
        if symbol:
            logger.info(f"Active symbol: {symbol}")
        else:
            logger.warning("No valid symbol found, defaulting to BTCUSDT")
            self.symbol_controller.set_symbol("BTCUSDT")

        self.state_store.update(bot_start_time=iso_now())
        logger.info("Bot initialization complete")

    def _restore_state(self, state: dict[str, Any]) -> None:
        """Restore bot state from persisted data."""
        # Restore positions
        positions_data = state.get("positions", {})
        if positions_data:
            self.position_manager.load_positions(positions_data)

        # Restore trade history
        history = state.get("trade_history", [])
        if history:
            self.position_manager.load_trade_history(history)

        # Restore paper balance
        paper_balance = state.get("paper_balance", self.config.get("paper", {}).get("starting_balance", 10000.0))
        self.execution_engine.set_paper_balance(paper_balance)

        # Restore PnL tracking
        self.position_manager.total_realized_pnl = state.get("total_realized_pnl", 0.0)

        # Check daily reset
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        saved_date = state.get("daily_date")
        if saved_date != today:
            self.state_store.update(
                daily_pnl=0.0,
                daily_start_balance=paper_balance,
                daily_date=today,
            )
            logger.info(f"New trading day: {today}. Daily PnL reset.")

        logger.info(
            f"State restored | balance={paper_balance:.2f} | "
            f"positions={len(positions_data)} | "
            f"total_pnl={self.position_manager.total_realized_pnl:.4f}"
        )

    def run(self) -> None:
        """Run the main bot loop."""
        self.running = True
        logger.info("Bot loop started")

        while self.running:
            try:
                self._cycle()
            except KeyboardInterrupt:
                logger.info("Keyboard interrupt received, shutting down...")
                self.running = False
                break
            except Exception as e:
                logger.error(f"Unexpected error in main loop: {e}", exc_info=True)
                time.sleep(5)
                continue

            time.sleep(self.polling_interval)

        self._shutdown()

    def _apply_config(self, new_config: dict[str, Any]) -> None:
        """Apply a new config to all components at runtime."""
        old_tf = self.config.get("timeframe")
        old_interval = self.config.get("polling_interval_seconds")
        self.config = new_config

        # Update polling interval
        self.polling_interval = new_config.get("polling_interval_seconds", 60)

        # Re-initialize components that depend on config
        self.signal_service = SignalService(new_config)
        self.consensus_engine = ConsensusEngine(new_config)
        self.leverage_manager = LeverageManager(new_config, self.binance_client)
        # Update position manager's risk config so max_open_positions etc. take effect
        self.position_manager.risk_config = new_config.get("risk", {})
        self.decision_engine = DecisionEngine(new_config, self.position_manager, self.leverage_manager)
        self.market_data = MarketDataProvider(self.binance_client, new_config)

        changes = []
        if old_tf != new_config.get("timeframe"):
            changes.append(f"timeframe: {old_tf} -> {new_config.get('timeframe')}")
        if old_interval != new_config.get("polling_interval_seconds"):
            changes.append(f"interval: {old_interval}s -> {new_config.get('polling_interval_seconds')}s")
        if changes:
            logger.info(f"Config reloaded: {', '.join(changes)}")
        else:
            logger.info("Config reloaded (no critical changes)")

    def _cycle(self) -> None:
        """Execute one full trading cycle."""
        self._cycle_count += 1
        logger.info(f"--- Cycle #{self._cycle_count} ---")

        # 0. Check for config changes
        changed, new_config = self.config_watcher.check_for_changes()
        if changed:
            self._apply_config(new_config)

        # 1. Check for symbol change
        changed, symbol = self.symbol_controller.check_for_change()
        if not symbol:
            logger.warning("No valid symbol. Skipping cycle.")
            return

        if changed:
            logger.info(f"Symbol switched to {symbol}. Adapting...")
            self.state_store.update(active_symbol=symbol)

        # S5: drain control-plane commands BEFORE the decision pipeline so
        # operator closes/resets land before the engine re-evaluates state.
        self._process_pending_commands()
        self._refresh_status_warnings()

        # 2. Fetch market data
        df = self.market_data.get_ohlcv(symbol)
        if df is None or df.empty:
            logger.warning(f"No market data for {symbol}. Skipping cycle.")
            return

        # S3: signal_price = candle close, distinct from display_price.
        signal_price = float(df["close"].iloc[-1])
        current_price = signal_price  # kept for downstream call-sites
        latest_candle_time = _compute_candle_key(df)

        # Refresh the live tick via LivePriceService (REST polling rescue).
        # Failure is non-fatal — the helper keeps the last good snapshot so
        # the dashboard stays honest about freshness instead of dropping to
        # 'unavailable' on a single REST hiccup.
        try:
            self.live_price_service.subscribe_symbol(
                symbol, market_type=self.config.get("market_type", "spot")
            )
            self.live_price_service.refresh(symbol)
        except Exception as e:
            logger.debug(f"LivePriceService refresh failed for {symbol}: {e}")

        # Same-candle detection — uses df["open_time"].iloc[-1] (not the
        # post-reset RangeIndex). See _compute_candle_key() above.
        if latest_candle_time != self._last_candle_time:
            self._candle_changed = True
            self._last_candle_time = latest_candle_time
        else:
            self._candle_changed = False

        logger.info(f"Current price: {symbol} = {current_price} | candle_changed={self._candle_changed}")

        # S3 cadence split: only re-run the indicator → consensus → decision
        # → execute pipeline when the candle has actually advanced. With
        # polling_interval=1s and timeframe=4h, this collapses ~14 400
        # redundant recomputes per candle into one. SL/TP/position checks
        # (steps 10–13) still run every cycle so the live price keeps
        # flowing to the dashboard and reverse-signal/SL hits aren't
        # missed.
        balance = self.execution_engine.get_balance()
        daily_pnl = self.state_store.get("daily_pnl", 0.0)
        daily_start_balance = self.state_store.get("daily_start_balance", balance)

        signal_stack_fresh = (
            self._candle_changed
            or self._last_consensus is None
            or self._last_decision is None
        )

        if signal_stack_fresh:
            # 3. Calculate all indicators
            indicator_results = self.signal_service.calculate_all(df)

            # 4. Run consensus engine
            consensus = self.consensus_engine.evaluate(indicator_results)

            # 6. Decision engine
            decision = self.decision_engine.decide(
                symbol=symbol,
                consensus=consensus,
                current_price=current_price,
                balance=balance,
                daily_pnl=daily_pnl,
                daily_start_balance=daily_start_balance,
            )

            # 7. Execute
            execution_result = self.execution_engine.execute(decision)

            # 8. Update state
            self._update_state_after_cycle(
                symbol, decision, execution_result, consensus, current_price, balance
            )
        else:
            # Same candle as last cycle — reuse cached signal stack. The
            # dashboard still gets a fresh display_price via the live-price
            # refresh above.
            indicator_results = self._last_indicator_results
            consensus = self._last_consensus
            decision = self._last_decision
            execution_result = {"executed": False, "reason": "same_candle_reuse"}
            logger.debug(
                f"Same candle ({latest_candle_time}) — signal stack reused; "
                f"price refresh only"
            )

        # 9. Log decision summary
        self._log_cycle_summary(symbol, consensus, decision, execution_result, balance)

        # 10. Process manual close commands from dashboard
        self._process_close_commands()

        # 11. Check SL/TP for ALL non-active positions
        self._check_other_positions(symbol, balance)

        # 11. Auto-scan market every 10 minutes — switch to best coin
        self._auto_scan_market()

        # 12. Update live multi-TF signals for active coin (background, non-blocking)
        self._update_active_coin_signals(symbol)

        # 13. Export dashboard status snapshot
        self._last_indicator_results = indicator_results
        self._last_consensus = consensus
        self._last_decision = decision
        self._last_execution = execution_result
        self._last_price = current_price
        self._last_signal_price = signal_price
        self._export_dashboard_status(balance)

    def _update_state_after_cycle(
        self,
        symbol: str,
        decision: dict[str, Any],
        execution_result: dict[str, Any],
        consensus: dict[str, Any],
        current_price: float,
        balance: float,
    ) -> None:
        """Persist state after each cycle."""
        daily_pnl = self.state_store.get("daily_pnl", 0.0)

        if execution_result.get("executed") and "pnl" in execution_result:
            daily_pnl += execution_result["pnl"]

        self.state_store.update(
            active_symbol=symbol,
            positions=self.position_manager.get_positions_dict(),
            last_decision={
                "action": decision["action"],
                "signal": consensus["final_signal"],
                "confidence": consensus["confidence"],
                "risk_level": consensus["risk_level"],
                "price": current_price,
                "timestamp": iso_now(),
            },
            last_trade_time=iso_now() if execution_result.get("executed") else self.state_store.get("last_trade_time"),
            daily_pnl=round(daily_pnl, 4),
            total_realized_pnl=round(self.position_manager.total_realized_pnl, 4),
            trade_history=[t.to_dict() for t in self.position_manager.trade_history[-100:]],
            paper_balance=round(self.execution_engine.paper_balance, 4),
        )

    def _log_cycle_summary(
        self,
        symbol: str,
        consensus: dict[str, Any],
        decision: dict[str, Any],
        execution_result: dict[str, Any],
        balance: float,
    ) -> None:
        """Log a summary of the cycle."""
        pos = self.position_manager.get_position(symbol)
        pos_info = f"{pos.side.value} qty={pos.quantity} entry={pos.entry_price} lev={pos.leverage}x" if pos else "FLAT"

        logger.info(
            f"Summary | {symbol} | signal={consensus['final_signal']} "
            f"conf={consensus['confidence']}% | risk={consensus['risk_level']} | "
            f"action={decision['action']} | pos={pos_info} | "
            f"balance={balance:.2f} | daily_pnl={self.state_store.get('daily_pnl', 0):.4f}"
        )

        if execution_result.get("executed"):
            logger.info(f"Execution: {json.dumps(execution_result, default=str)}")

    # ── S5 command-queue draining ──────────────────────────────

    def _process_pending_commands(self) -> None:
        """Drain pending control-plane commands from the queue.

        Runs once per cycle BEFORE the indicator → decision → execute
        pipeline so that an operator close clears the position before the
        decision engine could re-evaluate the freshly-flat state. Per-tick
        cap (`max_per_tick=16`) caps blast radius if the queue is malformed.
        """
        if not hasattr(self, "command_processor"):
            return
        try:
            self.command_processor.process_pending(max_per_tick=16)
        except Exception as exc:  # noqa: BLE001
            logger.error(f"Command queue drain failed: {exc}", exc_info=True)

    def _handle_manual_close(self, cmd: "CommandSchema") -> dict[str, Any]:
        """Apply a `manual_close` queue command.

        Paper mode: routes through the existing paper close path
        (ExecutionEngine.execute) so PnL and balance accounting stay
        identical to every other close.

        Live mode: closing a LIVE position via the bot's seam is not yet
        verified end-to-end (futures shorts are guarded — see
        `_assert_live_action_supported`). Long live close goes through the
        execution engine as-is; short live close is refused with a
        non-silent failure so the queue's `error` field surfaces in the
        dashboard. Either way, the queue keeps the command terminal with
        explicit status — no fake "executed: True" without a real fill.
        """
        symbol = cmd.payload.get("symbol", "").strip().upper()
        if not symbol:
            raise ValueError("manual_close payload missing symbol")

        position = self.position_manager.get_position(symbol)
        if position is None:
            logger.info(
                f"manual_close for {symbol}: no open position (already closed)"
            )
            return {"executed": False, "reason": "no position"}

        price = None
        try:
            price = self.binance_client.get_ticker_price(symbol)
        except Exception as exc:  # noqa: BLE001
            logger.debug(f"manual_close ticker fetch failed for {symbol}: {exc}")
        if not price:
            # Last-known display price keeps the close deterministic in tests.
            snap = (
                self.live_price_service.snapshot(symbol)
                if hasattr(self, "live_price_service") else None
            )
            if snap and snap.price is not None:
                price = snap.price
            else:
                price = position.entry_price

        close_action = (
            TradeAction.CLOSE_LONG if position.side.value == "LONG"
            else TradeAction.CLOSE_SHORT
        )
        decision = {
            "action": close_action.value,
            "symbol": symbol,
            "quantity": position.quantity,
            "price": price,
            "reason": "manual_close (dashboard)",
            "timestamp": iso_now(),
            "consensus_signal": "NEUTRAL",
            "confidence": 0,
            "risk_level": "LOW",
            "leverage": position.leverage,
        }

        # Live-side futures short close guard. Paper mode is unaffected.
        guard = self._assert_live_action_supported(close_action)
        if guard is not None:
            logger.warning(
                f"manual_close refused for {symbol}: {guard} — position kept open."
            )
            raise RuntimeError(guard)

        exec_result = self.execution_engine.execute(decision)
        if exec_result.get("executed"):
            pnl = exec_result.get("pnl", 0)
            daily_pnl = self.state_store.get("daily_pnl", 0.0) + pnl
            self.state_store.update(
                daily_pnl=round(daily_pnl, 4),
                total_realized_pnl=round(self.position_manager.total_realized_pnl, 4),
                positions=self.position_manager.get_positions_dict(),
                trade_history=[t.to_dict() for t in self.position_manager.trade_history[-100:]],
                paper_balance=round(self.execution_engine.paper_balance, 4),
            )
            logger.info(
                f"✅ manual_close {symbol} via command queue | "
                f"mode={exec_result.get('mode', self.execution_engine.mode)} | "
                f"PnL={pnl:.4f}"
            )
        else:
            reason = exec_result.get("reason", "unknown")
            logger.warning(f"manual_close {symbol} not executed: {reason}")
            # Non-executed live close = fail closed so the operator sees
            # the queue command marked FAILED, not a silent PROCESSED.
            if self.execution_engine.mode == "live":
                raise RuntimeError(f"live close not executed: {reason}")
        return exec_result

    def _handle_paper_reset(self, cmd: "CommandSchema") -> dict[str, Any]:
        """Apply a `paper_reset` queue command.

        Live mode rejects this — there is no "reset" on a live exchange.
        Paper mode: close all open positions at last-known price, then
        rewind paper balance to either the requested value or the
        configured starting balance.
        """
        if self.execution_engine.mode != "paper":
            raise RuntimeError("paper_reset rejected: bot is in live mode")

        target_balance = float(
            cmd.payload.get("balance",
                            self.config.get("paper", {}).get("starting_balance", 10000.0))
        )
        if target_balance <= 0:
            raise ValueError("paper_reset balance must be positive")

        # Close any open paper positions at last-known price so PnL accounting
        # stays consistent (no orphaned positions after a reset).
        for sym in list(self.position_manager.positions.keys()):
            pos = self.position_manager.positions.get(sym)
            if pos is None:
                continue
            price = None
            try:
                price = self.binance_client.get_ticker_price(sym)
            except Exception:  # noqa: BLE001
                price = None
            if not price:
                price = pos.entry_price
            try:
                self.position_manager.close_position(
                    sym, price, reason="paper_reset",
                    fee_pct=self.execution_engine.paper_fee_pct,
                )
            except Exception as exc:  # noqa: BLE001 — keep best-effort
                logger.warning(f"paper_reset close_position failed for {sym}: {exc}")

        # Wipe trade history & PnL since this is a fresh sandbox.
        self.position_manager.trade_history = []
        self.position_manager.total_realized_pnl = 0.0
        self.execution_engine.set_paper_balance(target_balance)

        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        self.state_store.update(
            paper_balance=round(target_balance, 4),
            positions=self.position_manager.get_positions_dict(),
            trade_history=[],
            daily_pnl=0.0,
            daily_start_balance=target_balance,
            daily_date=today,
            total_realized_pnl=0.0,
        )
        logger.info(
            f"✅ paper_reset via command queue | balance={target_balance:.2f} | "
            f"positions cleared"
        )
        return {"executed": True, "balance": target_balance}

    def _assert_live_action_supported(self, action: TradeAction) -> Optional[str]:
        """Return a refusal reason if this action cannot be safely executed
        live; otherwise None.

        Today: live OPEN_SHORT and CLOSE_SHORT are NOT wired through to the
        exchange (see ExecutionEngine._execute_live). On live+futures we
        refuse the action up front so the queue command turns FAILED with a
        clear explanation and the dashboard surfaces it.
        """
        if self.execution_engine.mode != "live":
            return None
        if action in (TradeAction.OPEN_SHORT, TradeAction.CLOSE_SHORT):
            return LIVE_SHORT_UNSUPPORTED_REASON
        return None

    def _refresh_status_warnings(self) -> None:
        """Re-evaluate config-time invariants that should surface to the
        operator. Today this is mostly about futures live short support.

        Kept idempotent: same warning string is set once per cycle even if
        the underlying config didn't change.
        """
        warnings: list[str] = []
        if (
            self.config.get("mode") == "live"
            and str(self.config.get("market_type", "spot")).lower() == "futures"
        ):
            warnings.append(LIVE_SHORT_UNSUPPORTED_REASON)
        self._status_warnings = warnings

    # ── Legacy file-glob close command reader ──────────────────
    # Kept callable for the transitional period; the queue-based path is the
    # new source of truth. test_cycle_cadence.py stubs this method directly.

    def _process_close_commands(self) -> None:
        """Check for manual close commands written by dashboard and execute them."""
        runtime_dir = Path("runtime")
        try:
            cmd_files = list(runtime_dir.glob("close_cmd_*.json"))
        except Exception:
            return
        for cmd_file in cmd_files:
            try:
                data = json.loads(cmd_file.read_text())
                sym = data.get("symbol", "")
                cmd_file.unlink(missing_ok=True)  # remove command file immediately
                if not sym:
                    continue
                pos = self.position_manager.get_position(sym)
                if not pos:
                    logger.info(f"Close command for {sym} but no position found (already closed)")
                    continue
                price = self.binance_client.get_ticker_price(sym)
                if not price:
                    price = pos.entry_price
                close_action = (
                    TradeAction.CLOSE_LONG if pos.side.value == "LONG"
                    else TradeAction.CLOSE_SHORT
                )
                decision = {
                    "action": close_action.value,
                    "symbol": sym,
                    "quantity": pos.quantity,
                    "price": price,
                    "reason": "Manuel kapatma (dashboard)",
                    "timestamp": iso_now(),
                    "consensus_signal": "NEUTRAL",
                    "confidence": 0,
                    "risk_level": "LOW",
                    "leverage": pos.leverage,
                }
                exec_result = self.execution_engine.execute(decision)
                if exec_result.get("executed"):
                    pnl = exec_result.get("pnl", 0)
                    daily_pnl = self.state_store.get("daily_pnl", 0.0) + pnl
                    self.state_store.update(
                        daily_pnl=round(daily_pnl, 4),
                        total_realized_pnl=round(self.position_manager.total_realized_pnl, 4),
                        positions=self.position_manager.get_positions_dict(),
                        trade_history=[t.to_dict() for t in self.position_manager.trade_history[-100:]],
                        paper_balance=round(self.execution_engine.paper_balance, 4),
                    )
                    logger.info(f"✅ {sym} manually closed via dashboard | PnL: {pnl:.4f}")
            except Exception as e:
                logger.warning(f"Failed to process close command {cmd_file}: {e}")
                try:
                    cmd_file.unlink(missing_ok=True)
                except Exception:
                    pass

    def _check_other_positions(self, active_symbol: str, balance: float) -> None:
        """Check SL/TP/trailing AND signal reversal for non-active positions.

        S5 exit-semantics policy (same as decision_engine):
          - paper mode: AUTO-CLOSE on stop_loss/take_profit/trailing_stop/
            break_even_stop/liquidation; the close path removes the
            position so no stale `warning` accumulates next cycle.
          - live mode: warning-only (`live_exit_unsupported:<reason>`) —
            the live execution path is not yet verified for non-active exits.
          - Signal reversal: AUTO-CLOSE in both modes (routes through
            ExecutionEngine.execute, which guards live shorts up front).
        """
        positions = dict(self.position_manager.positions)  # copy to avoid mutation during iteration
        mode = self.execution_engine.mode
        for sym, pos in positions.items():
            if sym == active_symbol:
                continue  # already handled by main decision engine
            try:
                price = self.binance_client.get_ticker_price(sym)
                if not price:
                    continue

                # SL/TP/trailing/liquidation check
                exit_reason = self.position_manager.update_position(sym, price)
                if exit_reason:
                    if mode == "paper":
                        close_action = (
                            TradeAction.CLOSE_LONG if pos.side.value == "LONG"
                            else TradeAction.CLOSE_SHORT
                        )
                        decision = {
                            "action": close_action.value,
                            "symbol": sym,
                            "quantity": pos.quantity,
                            "price": price,
                            "reason": f"auto_close:{exit_reason}",
                            "timestamp": iso_now(),
                            "consensus_signal": "NEUTRAL",
                            "confidence": 0,
                            "risk_level": "LOW",
                            "leverage": pos.leverage,
                        }
                        exec_result = self.execution_engine.execute(decision)
                        if exec_result.get("executed"):
                            pnl = exec_result.get("pnl", 0)
                            daily_pnl = self.state_store.get("daily_pnl", 0.0) + pnl
                            self.state_store.update(
                                daily_pnl=round(daily_pnl, 4),
                                total_realized_pnl=round(self.position_manager.total_realized_pnl, 4),
                                positions=self.position_manager.get_positions_dict(),
                                trade_history=[t.to_dict() for t in self.position_manager.trade_history[-100:]],
                                paper_balance=round(self.execution_engine.paper_balance, 4),
                            )
                            logger.info(
                                f"✅ {sym} auto-closed on {exit_reason} (paper) | "
                                f"PnL={pnl:.4f}"
                            )
                            # Position is removed by close_position; nothing more to do.
                            continue
                        # If paper close failed unexpectedly, surface the
                        # reason as a warning instead of pretending we closed.
                        pos.warning = f"paper_exit_failed:{exit_reason}"
                        logger.warning(
                            f"{sym} paper auto-close on {exit_reason} did not "
                            f"execute: {exec_result.get('reason')}"
                        )
                    else:
                        # Live mode: warning-only. Tag with explicit prefix so
                        # the dashboard can render it as "live exit not wired".
                        pos.warning = f"live_exit_unsupported:{exit_reason}"
                        logger.warning(
                            f"Position {sym} hit {exit_reason} in live mode — "
                            f"NOT auto-closing (execution path not verified)."
                        )

                # ── Signal reversal check for non-active positions ──
                try:
                    df = self.market_data.get_ohlcv(sym)
                    if df is not None and not df.empty:
                        indicator_results = self.signal_service.calculate_all(df)
                        consensus = self.consensus_engine.evaluate(indicator_results)
                        final_signal = consensus["final_signal"]
                        confidence = consensus["confidence"]
                        should_trade = consensus["should_trade"]

                        # Update signal tracking on position
                        pos.current_signal = final_signal
                        pos.current_confidence = confidence

                        # Check for reversal
                        signal_reversed = False
                        if pos.side.value == "LONG" and final_signal in ("SELL", "STRONG_SELL"):
                            signal_reversed = True
                        elif pos.side.value == "SHORT" and final_signal in ("BUY", "STRONG_BUY"):
                            signal_reversed = True

                        if signal_reversed and should_trade:
                            logger.info(
                                f"⚡ Signal reversal on {sym}: {pos.side.value} position "
                                f"but signal={final_signal} conf={confidence}% — auto-closing!"
                            )
                            # Build a close decision and execute it
                            close_action = (
                                TradeAction.CLOSE_LONG if pos.side.value == "LONG"
                                else TradeAction.CLOSE_SHORT
                            )
                            decision = {
                                "action": close_action.value,
                                "symbol": sym,
                                "quantity": pos.quantity,
                                "price": price,
                                "reason": f"Signal reversed: {pos.side.value} → {final_signal}",
                                "timestamp": iso_now(),
                                "consensus_signal": final_signal,
                                "confidence": confidence,
                                "risk_level": consensus["risk_level"],
                                "leverage": pos.leverage,
                            }
                            exec_result = self.execution_engine.execute(decision)
                            if exec_result.get("executed"):
                                pnl = exec_result.get("pnl", 0)
                                daily_pnl = self.state_store.get("daily_pnl", 0.0) + pnl
                                self.state_store.update(
                                    daily_pnl=round(daily_pnl, 4),
                                    total_realized_pnl=round(self.position_manager.total_realized_pnl, 4),
                                    positions=self.position_manager.get_positions_dict(),
                                    trade_history=[t.to_dict() for t in self.position_manager.trade_history[-100:]],
                                    paper_balance=round(self.execution_engine.paper_balance, 4),
                                )
                                logger.info(
                                    f"✅ {sym} position auto-closed on reversal | PnL: {pnl:.4f}"
                                )
                except Exception as e:
                    logger.warning(f"Failed to check signal for {sym}: {e}")

            except Exception as e:
                logger.warning(f"Failed to check position {sym}: {e}")

    # ── Live multi-TF signals for active coin ──────────────────────

    def _update_active_coin_signals(self, symbol: str) -> None:
        """Calculate signals for the active coin across all 12 TFs in a background thread.

        Writes result to runtime/active_coin_signals.json every cycle.
        Non-blocking: launches a daemon thread.
        """
        # Don't launch if a previous one is still running
        if hasattr(self, "_active_signals_thread") and self._active_signals_thread.is_alive():
            return

        def _worker():
            try:
                results = {}
                for tf in self._multi_tfs:
                    try:
                        md = MarketDataProvider(self.binance_client, {**self.config, "timeframe": tf})
                        df = md.get_ohlcv(symbol)
                        if df is None or df.empty:
                            results[tf] = {"signal": "N/A", "confidence": 0, "risk_level": "N/A"}
                            continue
                        svc = SignalService({**self.config, "timeframe": tf})
                        indicators = svc.calculate_all(df)
                        consensus = ConsensusEngine(self.config).evaluate(indicators)
                        conf = consensus["confidence"]
                        zak = self._ZAK.get(tf, 50)
                        results[tf] = {
                            "signal": consensus["final_signal"],
                            "confidence": conf,
                            "risk_level": consensus["risk_level"],
                            "zak": zak,
                            "final_score": round((conf ** 2) * (zak / 100), 2),
                        }
                    except Exception as e:
                        logger.debug(f"Active coin signal calc failed for {tf}: {e}")
                        results[tf] = {"signal": "N/A", "confidence": 0, "risk_level": "N/A"}

                # Write to file
                data = {
                    "symbol": symbol,
                    "timeframes": results,
                    "updated_at": iso_now(),
                }
                runtime_dir = Path(self.config.get("dashboard_status_path", "runtime/dashboard_status.json")).parent
                out = runtime_dir / "active_coin_signals.json"
                tmp = out.with_suffix(".tmp")
                tmp.write_text(json.dumps(data, default=str))
                tmp.replace(out)
            except Exception as e:
                logger.warning(f"Active coin signals update failed: {e}")

        self._active_signals_thread = threading.Thread(target=_worker, daemon=True)
        self._active_signals_thread.start()

    def _is_manual_scan_active(self) -> bool:
        """Check if a dashboard-triggered manual scan is currently running.
        Auto-clears stale locks older than 30 minutes.
        """
        try:
            runtime_dir = Path(self.config.get("dashboard_status_path", "runtime/dashboard_status.json")).parent
            lock_file = runtime_dir / "manual_scan_active.json"
            if lock_file.exists():
                d = json.loads(lock_file.read_text())
                if d.get("active", False):
                    # Check if lock is stale (>30 min old)
                    ts = d.get("ts", "")
                    if ts:
                        from datetime import datetime, timezone
                        lock_time = datetime.fromisoformat(ts)
                        age_minutes = (datetime.now(timezone.utc) - lock_time).total_seconds() / 60
                        if age_minutes > 30:
                            logger.warning(f"Stale manual scan lock ({age_minutes:.0f} min old) — auto-clearing")
                            lock_file.unlink(missing_ok=True)
                            return False
                    return True
        except Exception:
            pass
        return False

    def _auto_scan_market(self) -> None:
        """Launch multi-TF auto-scan in background threads (non-blocking).

        Scans all 12 timeframes (1m–1d) in parallel.
        Uses a threading.Lock to guarantee only one batch runs at a time.
        Timer counts 10 min from scan COMPLETION.
        Skips if a manual scan is running (from dashboard).
        Skips if auto-scan is disabled via dashboard toggle.
        """
        # S6: anchor runtime paths on the same dashboard_status_path the
        # rest of the bot uses. Pre-S6 this referenced self.project_root /
        # self._read_config_from_disk(), both of which are undefined on
        # BotService — the live bot would AttributeError on cycle #1.
        runtime_dir = self._runtime_dir()
        flag_file = runtime_dir / "auto_scan_disabled"
        if flag_file.exists():
            self._write_auto_scan_progress({
                "scanning": False, "total": 0, "done": 0, "pct": 0,
                "reason": "auto_scan_disabled_flag",
            })
            return
        disk_cfg = self._read_yaml_config()
        if not disk_cfg.get("auto_scan_enabled", True):
            self._write_auto_scan_progress({
                "scanning": False, "total": 0, "done": 0, "pct": 0,
                "reason": "auto_scan_enabled_false",
            })
            return

        # Fast check: is a scan already in progress?
        if self._scanning_active:
            if any(t.is_alive() for t in self._scan_threads):
                return
            # Threads finished but flag wasn't cleared (shouldn't happen, safety net)
            self._scanning_active = False

        # Block if manual scan is active — don't consume the timer
        if self._is_manual_scan_active():
            logger.debug("Manual scan active — skipping auto-scan this cycle")
            return

        # Timer check
        now = time.time()
        if now - self._last_auto_scan_time < self._auto_scan_interval:
            return

        # Try to acquire lock (non-blocking) — only for initial checks
        if not self._scan_lock.acquire(blocking=False):
            return

        try:
            if self._scanning_active or any(t.is_alive() for t in self._scan_threads):
                return

            # Double-check manual scan right before committing
            if self._is_manual_scan_active():
                logger.debug("Manual scan active (double-check) — aborting auto-scan")
                return

            # Mark scan as active IMMEDIATELY to prevent re-entry
            self._scanning_active = True
            self._last_auto_scan_time = now  # prevent re-trigger while scanning
        finally:
            # Release lock early — _scanning_active flag prevents re-entry
            # Lock must be free for scan threads + progress writer to use it
            self._scan_lock.release()

        try:

            symbol_path = Path(self.config.get("active_symbol_path", "runtime/active_symbol.txt"))
            total_tfs = len(self._multi_tfs)
            self._multi_scan_done_count = 0
            self._multi_scan_results = {}
            self._multi_scan_full = {}
            self._scan_threads = []

            # Pre-fetch symbol list ONCE
            _prefetch_scanner = ScannerService(self.config, shared_client=self.binance_client)
            shared_symbols = _prefetch_scanner._get_top_symbols_by_volume()

            # ── 2-PHASE SCAN ──
            # Phase 1: Scan ALL coins on top 3 ZAK TFs (1d, 12h, 8h) → find promising coins
            # Phase 2: Deep-scan top 50 coins on remaining 9 TFs
            # This reduces API calls from ~7900 to ~2400 (~3x faster)
            _phase1_tfs = ["1d", "12h", "8h"]
            _phase2_tfs = [tf for tf in self._multi_tfs if tf not in _phase1_tfs]
            _PHASE2_TOP_N = 50  # How many coins survive Phase 1

            logger.info(f"🔍 2-Phase auto-scan: Phase1={_phase1_tfs} (all {len(shared_symbols)} coins) → Phase2={_phase2_tfs} (top {_PHASE2_TOP_N})")

            # Limit concurrent threads to avoid Binance rate limits
            _scan_semaphore = threading.Semaphore(3)
            req_delay = 0.15

            # Write initial progress file — preserve last completed scan data
            self._auto_scan_start_time = iso_now()
            self._auto_scan_scanners: dict[str, ScannerService] = {}
            prev_progress = {}
            try:
                runtime_dir = Path(self.config.get("dashboard_status_path", "runtime/dashboard_status.json")).parent
                prev_file = runtime_dir / "auto_scan_progress.json"
                if prev_file.exists():
                    prev_progress = json.loads(prev_file.read_text())
                # Fallback: if no last_auto_scan, try dashboard_status.json
                if not prev_progress.get("last_auto_scan"):
                    ds_path = Path(self.config.get("dashboard_status_path", "runtime/dashboard_status.json"))
                    if ds_path.exists():
                        ds = json.loads(ds_path.read_text())
                        if ds.get("last_auto_scan"):
                            prev_progress["last_auto_scan"] = ds["last_auto_scan"]
                        if ds.get("last_scan_results"):
                            prev_progress["last_scan_results"] = ds["last_scan_results"]
                        if ds.get("last_scan_total"):
                            prev_progress["last_scan_total"] = ds["last_scan_total"]
                        if ds.get("last_scan_hot_count") is not None:
                            prev_progress["last_scan_hot_count"] = ds["last_scan_hot_count"]
            except Exception:
                pass
            self._write_auto_scan_progress({
                "scanning": True,
                "total": total_tfs,
                "done": 0,
                "pct": 0,
                "done_tfs": [],
                "all_tfs": self._multi_tfs,
                "started_at": self._auto_scan_start_time,
                # Preserve last completed scan data for display
                "last_auto_scan": prev_progress.get("last_auto_scan"),
                "last_scan_results": prev_progress.get("last_scan_results"),
                "last_scan_hot_count": prev_progress.get("last_scan_hot_count"),
                "last_scan_total": prev_progress.get("last_scan_total"),
            })

            # Background thread to update progress based on per-coin scanning
            def _progress_writer():
                import time as _t
                while True:
                    _t.sleep(3)
                    with self._scan_lock:
                        done_count = self._multi_scan_done_count
                        done_tfs = list(self._multi_scan_results.keys())
                    if done_count >= total_tfs:
                        break  # final progress written by _process_multi_scan_results
                    # Calculate TF-weighted progress:
                    # done TFs = 100%, running TFs = their coin%, pending = 0%
                    tf_pct_sum = done_count * 100  # completed TFs
                    total_coins = 0
                    scanned_coins = 0
                    for tf, scanner in list(self._auto_scan_scanners.items()):
                        try:
                            prog = scanner._scan_progress
                            t = prog.get("total", 0)
                            c = prog.get("current", 0)
                            total_coins += t
                            scanned_coins += c
                            if tf not in done_tfs and t > 0:
                                tf_pct_sum += int(c * 100 / t)
                        except Exception:
                            pass
                    if total_tfs > 0:
                        pct = int(tf_pct_sum / total_tfs)
                    else:
                        pct = 0
                    self._write_auto_scan_progress({
                        "scanning": True,
                        "total": total_tfs,
                        "done": done_count,
                        "pct": pct,
                        "done_tfs": done_tfs,
                        "all_tfs": self._multi_tfs,
                        "started_at": self._auto_scan_start_time,
                        "coins_scanned": scanned_coins,
                        "coins_total": total_coins,
                        # Preserve last completed scan data
                        "last_auto_scan": prev_progress.get("last_auto_scan"),
                        "last_scan_results": prev_progress.get("last_scan_results"),
                        "last_scan_hot_count": prev_progress.get("last_scan_hot_count"),
                        "last_scan_total": prev_progress.get("last_scan_total"),
                    })

            pw = threading.Thread(target=_progress_writer, daemon=True)
            pw.start()

            def _scan_single_tf(tf: str, symbols_override: list[str] | None = None):
                _scan_semaphore.acquire()
                try:
                    syms = symbols_override if symbols_override else shared_symbols
                    scanner = ScannerService(
                        self.config, symbol_file=symbol_path, timeframe=tf,
                        shared_symbols=syms,
                    )
                    scanner._request_delay = req_delay
                    self._auto_scan_scanners[tf] = scanner
                    scanner.scan(min_confidence=0)
                    results = scanner.results
                    # Net Signal Score (NSS) = (confidence²) × (ZAK / 100)
                    zak = self._ZAK.get(tf, 50)
                    for r in results:
                        r["zak"] = zak
                        r["final_score"] = round((r["confidence"] ** 2) * (zak / 100), 2)
                    top15 = sorted(results, key=lambda r: r["final_score"], reverse=True)[:15]

                    with self._scan_lock:
                        self._multi_scan_results[tf] = top15
                        self._multi_scan_full[tf] = results
                        self._multi_scan_done_count += 1
                        done = self._multi_scan_done_count

                    logger.info(f"  ✅ {tf} scan done: {len(syms)}→{len(results)} coins ({done}/{total_tfs})")

                    # When ALL timeframes are done, process combined results
                    if done == total_tfs:
                        self._process_multi_scan_results(symbol_path)
                except Exception as e:
                    logger.error(f"Auto-scan {tf} failed: {e}", exc_info=True)
                    with self._scan_lock:
                        if tf not in self._multi_scan_results:
                            self._multi_scan_results[tf] = []
                            self._multi_scan_full[tf] = []
                        self._multi_scan_done_count += 1
                        done = self._multi_scan_done_count
                    if done == total_tfs:
                        self._process_multi_scan_results(symbol_path)
                finally:
                    _scan_semaphore.release()

            # ── PHASE 1: High-ZAK TFs scan ALL coins ──
            phase1_threads = []
            for i, tf in enumerate(_phase1_tfs):
                t = threading.Thread(target=_scan_single_tf, args=(tf, None), daemon=True, name=f"scan-{tf}")
                self._scan_threads.append(t)
                phase1_threads.append(t)
                t.start()
                if i < len(_phase1_tfs) - 1:
                    time.sleep(1)

            # Wait for Phase 1 to complete
            for t in phase1_threads:
                t.join()

            # ── Determine Phase 2 survivors ──
            # Phase 1 threads are done (join completed), safe to read without lock
            _p1_scores: dict[str, float] = {}
            for tf in _phase1_tfs:
                for r in self._multi_scan_full.get(tf, []):
                    sym = r["symbol"]
                    _p1_scores[sym] = _p1_scores.get(sym, 0) + r.get("final_score", 0)

            # Top N coins by Phase 1 aggregate NSS
            _survivors = sorted(_p1_scores.items(), key=lambda x: -x[1])[:_PHASE2_TOP_N]
            _phase2_symbols = [s[0] for s in _survivors]
            logger.info(f"🏅 Phase 1 complete. Top {len(_phase2_symbols)} coins for Phase 2: {', '.join(_phase2_symbols[:10])}...")

            # ── PHASE 2: Remaining TFs scan only survivors ──
            for i, tf in enumerate(_phase2_tfs):
                t = threading.Thread(target=_scan_single_tf, args=(tf, _phase2_symbols), daemon=True, name=f"scan-{tf}")
                self._scan_threads.append(t)
                t.start()
                if i < len(_phase2_tfs) - 1:
                    time.sleep(1)
        except Exception as e:
            logger.error(f"Auto-scan failed: {e}", exc_info=True)
            self._scanning_active = False

    def _process_multi_scan_results(self, symbol_path: Path) -> None:
        """Process combined multi-TF scan results: cross-ranking, auto-select, save."""
        try:
            tf_data = self._multi_scan_results
            total_tfs = len(self._multi_tfs)

            # Build cross-ranking with net NSS (opposing signals subtracted)
            symbol_stats: dict[str, dict] = {}
            for tf in self._multi_tfs:
                zak = self._ZAK.get(tf, 50)
                top15 = tf_data.get(tf, [])
                for r in top15:
                    sym = r["symbol"]
                    conf = r["confidence"]
                    sig = r["signal"].upper()
                    nss = round((conf ** 2) * (zak / 100), 2)
                    if sym not in symbol_stats:
                        symbol_stats[sym] = {
                            "symbol": sym, "count": 0, "total_conf": 0,
                            "buy_nss": 0, "sell_nss": 0,
                            "best_conf": 0, "price": r.get("price", 0),
                            "signals": {}, "all_signals": {},
                        }
                    symbol_stats[sym]["count"] += 1
                    symbol_stats[sym]["total_conf"] += conf
                    if sig in ("BUY", "STRONG_BUY"):
                        symbol_stats[sym]["buy_nss"] += nss
                    elif sig in ("SELL", "STRONG_SELL"):
                        symbol_stats[sym]["sell_nss"] += nss
                    # NEUTRAL contributes nothing
                    if conf > symbol_stats[sym]["best_conf"]:
                        symbol_stats[sym]["best_conf"] = conf
                        symbol_stats[sym]["price"] = r.get("price", 0)
                    symbol_stats[sym]["signals"][tf] = {
                        "signal": r["signal"], "confidence": conf, "zak": zak, "final_score": nss,
                    }
                    symbol_stats[sym]["all_signals"][tf] = {
                        "signal": r["signal"], "confidence": conf, "zak": zak, "final_score": nss, "in_top15": True,
                    }

            # Calculate net_nss: dominant direction NSS minus opposing direction NSS
            for sym, s in symbol_stats.items():
                if s["buy_nss"] >= s["sell_nss"]:
                    s["dominant_dir"] = "BUY"
                    s["net_nss"] = round(s["buy_nss"] - s["sell_nss"], 2)
                else:
                    s["dominant_dir"] = "SELL"
                    s["net_nss"] = round(s["sell_nss"] - s["buy_nss"], 2)
                s["total_nss"] = s["net_nss"]  # for backward compat

            # Sort: by net NSS (opposing direction subtracted)
            cross_ranked = sorted(
                symbol_stats.values(),
                key=lambda x: -x["net_nss"],
            )

            # Fill non-top15 TF confidences from full results for top 10
            ranked_symbols = {c["symbol"] for c in cross_ranked[:10]}
            full_data = self._multi_scan_full
            for tf in self._multi_tfs:
                zak = self._ZAK.get(tf, 50)
                all_results = full_data.get(tf, [])
                for r in all_results:
                    sym = r["symbol"]
                    if sym in ranked_symbols:
                        for c in cross_ranked[:10]:
                            if c["symbol"] == sym and tf not in c["all_signals"]:
                                conf = r["confidence"]
                                nss = round((conf ** 2) * (zak / 100), 2)
                                c["all_signals"][tf] = {
                                    "signal": r["signal"], "confidence": conf,
                                    "zak": zak, "final_score": nss, "in_top15": False,
                                }
                                break

            # Log cross-ranking top 10
            logger.info(f"🏆 Multi-TF cross-ranking (top 10 — net NSS):")
            for i, c in enumerate(cross_ranked[:10]):
                tfs_str = ", ".join(f"{tf}={c['signals'][tf]['final_score']:.0f}" for tf in self._multi_tfs if tf in c['signals'])
                logger.info(f"  #{i+1} {c['symbol']:12s} | {c['dominant_dir']} net={c['net_nss']:.0f} | {c['count']}/{total_tfs} TF | {tfs_str}")

            # Auto-select: ONLY pick a coin if ALL 9 TFs agree on direction
            # (every all_signals entry is BUY/STRONG_BUY or every one is SELL/STRONG_SELL)
            # ── Triple-check auto_select_enabled ──
            # 1) Primary: re-read YAML config from disk (no caching race)
            # 2) Fallback: runtime flag file (belt-and-suspenders)
            # 3) Last resort: in-memory self.config
            auto_select_on = None
            try:
                _cfg_path = Path(self.config.get("_config_path", "config/default.yaml"))
                import yaml as _yaml
                _fresh_cfg = _yaml.safe_load(_cfg_path.read_text()) or {}
                auto_select_on = _fresh_cfg.get("auto_select_enabled", True)
                logger.info(f"Auto-select from YAML: {auto_select_on}")
            except Exception as _e:
                logger.warning(f"Could not read YAML for auto_select: {_e}")
            # Runtime flag file override: if runtime/auto_select_disabled exists → force OFF
            _flag_file = Path("runtime/auto_select_disabled")
            if _flag_file.exists():
                logger.info("Auto-select FORCE OFF via runtime/auto_select_disabled flag file")
                auto_select_on = False
            # Final fallback
            if auto_select_on is None:
                auto_select_on = self.config.get("auto_select_enabled", True)
                logger.info(f"Auto-select from memory config: {auto_select_on}")
            best = None
            if not auto_select_on:
                logger.info("Auto-select disabled — skipping coin selection")
            for c in cross_ranked[:10] if auto_select_on else []:
                sigs = c.get("all_signals", {})
                if len(sigs) < total_tfs:
                    continue  # need data for all TFs
                directions = set()
                for info in sigs.values():
                    sig = info["signal"].upper()
                    if sig in ("BUY", "STRONG_BUY"):
                        directions.add("BUY")
                    elif sig in ("SELL", "STRONG_SELL"):
                        directions.add("SELL")
                    else:
                        directions.add("NEUTRAL")
                if len(directions) == 1 and "NEUTRAL" not in directions:
                    best = c
                    best["_unanimous_direction"] = directions.pop()
                    break

            if best:
                direction = best["_unanimous_direction"]
                current_symbol = self.symbol_controller.get_current_symbol()
                if best["symbol"] != current_symbol:
                    logger.info(
                        f"⚡ Unanimous {direction} across {total_tfs} TFs: "
                        f"{current_symbol} → {best['symbol']} "
                        f"(best_conf={best['best_conf']}%)"
                    )
                    scanner = ScannerService(self.config, symbol_file=symbol_path)
                    scanner.set_active_symbol(best["symbol"])
                else:
                    logger.info(
                        f"✅ Current symbol {current_symbol} still unanimous {direction} "
                        f"across {total_tfs} TFs"
                    )
            else:
                logger.info(
                    f"No coin with unanimous direction across all {total_tfs} TFs "
                    f"— keeping current symbol"
                )

            # Save results to state store + multi_scan_results.json
            self.state_store.update(
                last_auto_scan=iso_now(),
                last_scan_results=cross_ranked[:10],
                last_scan_hot_count=sum(1 for c in cross_ranked if c["count"] == total_tfs),
                last_scan_total=len(symbol_stats),
            )

            # Save full multi-scan data for dashboard
            multi_result = {
                "any_scanning": False,
                "timeframes": {},
                "common_symbols": [c["symbol"] for c in cross_ranked if c["count"] == total_tfs],
                "cross_ranking": cross_ranked[:10],
                "scan_time": iso_now(),
            }
            for tf in self._multi_tfs:
                top15 = tf_data.get(tf, [])
                multi_result["timeframes"][tf] = {
                    "scanning": False,
                    "progress": {"current": 0, "total": 0, "status": "complete"},
                    "top15": top15,
                    "total_scanned": len(top15),
                }
            try:
                runtime_dir = Path(self.config.get("dashboard_status_path", "runtime/dashboard_status.json")).parent
                scan_file = runtime_dir / "multi_scan_results.json"
                tmp = scan_file.with_suffix(".tmp")
                tmp.write_text(json.dumps(multi_result, default=str))
                tmp.replace(scan_file)
                logger.info(f"Multi-TF results saved to {scan_file}")
            except Exception as e:
                logger.warning(f"Failed to save multi-scan results: {e}")

            # Mark progress as complete with full scan data
            scan_time = iso_now()
            self._write_auto_scan_progress({
                "scanning": False,
                "total": total_tfs,
                "done": total_tfs,
                "pct": 100,
                "done_tfs": self._multi_tfs,
                "all_tfs": self._multi_tfs,
                "completed_at": scan_time,
                "last_auto_scan": scan_time,
                "last_scan_results": cross_ranked[:5],
                "last_scan_hot_count": sum(1 for c in cross_ranked if c["count"] == total_tfs),
                "last_scan_total": len(symbol_stats),
            })

        except Exception as e:
            logger.error(f"Multi-scan processing failed: {e}", exc_info=True)
        finally:
            self._last_auto_scan_time = time.time()
            self._scanning_active = False

    def _runtime_dir(self) -> Path:
        """Return the runtime directory derived from ``dashboard_status_path``.

        S6: every runtime artifact (flag files, progress, command queue,
        active_symbol.txt) is anchored on the same parent so tmp_path
        isolated test runs never bleed into the developer's real repo.
        """
        ds = Path(self.config.get("dashboard_status_path", "runtime/dashboard_status.json"))
        return ds.parent if str(ds.parent) != "." else Path("runtime")

    def _read_yaml_config(self) -> dict[str, Any]:
        """Re-read the YAML config from disk so toggles applied via the
        dashboard land before the next scan decision.

        S6: this replaces the pre-S6 reference to the undefined
        ``self._read_config_from_disk()``. The chosen pattern mirrors
        the YAML reread that already exists in ``_process_multi_scan_results``
        for ``auto_select_enabled``.
        """
        cfg_path = Path(self.config.get("_config_path", "config/default.yaml"))
        try:
            import yaml as _yaml
            return _yaml.safe_load(cfg_path.read_text()) or {}
        except Exception:
            return {}

    def _write_auto_scan_progress(self, data: dict) -> None:
        """Write auto-scan progress to a JSON file for the dashboard to read.

        S6: always surface a top-level ``state`` field derived from the
        payload so the dashboard renders idle vs scanning vs disabled vs
        complete vs error honestly — never silent. ``reason`` is preserved
        when the caller provides it (e.g. ``auto_scan_disabled_flag``)."""
        try:
            payload = dict(data)
            payload.setdefault("state", self._derive_progress_state(payload))
            runtime_dir = self._runtime_dir()
            runtime_dir.mkdir(parents=True, exist_ok=True)
            progress_file = runtime_dir / "auto_scan_progress.json"
            tmp = progress_file.with_suffix(".tmp")
            tmp.write_text(json.dumps(payload, default=str))
            tmp.replace(progress_file)
        except Exception:
            pass  # Non-critical

    @staticmethod
    def _derive_progress_state(payload: dict) -> str:
        """Pure helper: pick the right state label from a progress payload."""
        reason = payload.get("reason", "")
        if reason in {"auto_scan_disabled_flag", "auto_scan_enabled_false"}:
            return "disabled"
        if payload.get("scanning"):
            return "scanning"
        if payload.get("error"):
            return "error"
        total = payload.get("total", 0) or 0
        done = payload.get("done", 0) or 0
        if total > 0 and done >= total:
            return "complete"
        return "idle"

    def _patch_dashboard_status(self, updates: dict) -> None:
        """Directly patch dashboard_status.json with given key-value pairs."""
        try:
            status_path = Path(self.config.get("dashboard_status_path", "runtime/dashboard_status.json"))
            if status_path.exists():
                ds = json.loads(status_path.read_text())
            else:
                ds = {}
            ds.update(updates)
            tmp = status_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(ds, indent=2, default=str))
            tmp.replace(status_path)
        except Exception as e:
            logger.warning(f"Failed to patch dashboard_status.json: {e}")

    def _fetch_all_position_prices(self) -> dict[str, float]:
        """Fetch current prices for all symbols with open positions."""
        prices: dict[str, float] = {}
        positions = self.position_manager.get_positions_dict()
        active_symbol = self.symbol_controller.get_current_symbol()
        for sym in positions:
            # Skip active symbol — we already have its price from the cycle
            if sym == active_symbol and self._last_price:
                prices[sym] = self._last_price
                continue
            try:
                price = self.binance_client.get_ticker_price(sym)
                if price:
                    prices[sym] = price
            except Exception as e:
                logger.warning(f"Failed to fetch price for {sym}: {e}")
        return prices

    def _export_dashboard_status(self, balance: float) -> None:
        """Export dashboard snapshot after each cycle."""
        try:
            all_prices = self._fetch_all_position_prices()
            # Resolve the canonical S3 price fields from LivePriceService.
            symbol = self.state_store.get("active_symbol") or ""
            snap = self.live_price_service.snapshot(symbol) if symbol else None
            display_price = snap.price if snap and snap.price is not None else self._last_signal_price
            display_price_source = snap.source if snap else None
            price_age_ms = snap.age_ms if snap else None
            mark_price = snap.mark_price if snap else None
            best_bid = snap.best_bid if snap else None
            best_ask = snap.best_ask if snap else None

            self.status_exporter.export(
                config=self.config,
                state=self.state_store.state,
                consensus=self._last_consensus,
                indicator_results=self._last_indicator_results,
                decision=self._last_decision,
                execution_result=self._last_execution,
                balance=balance,
                current_price=display_price,
                cycle_count=self._cycle_count,
                running=self.running,
                all_prices=all_prices,
                signal_price=self._last_signal_price,
                display_price=display_price,
                display_price_source=display_price_source,
                price_age_ms=price_age_ms,
                mark_price=mark_price,
                best_bid=best_bid,
                best_ask=best_ask,
                status_warnings=getattr(self, "_status_warnings", []),
            )
        except Exception as e:
            logger.error(f"Failed to export dashboard status: {e}")

    def _shutdown(self) -> None:
        """Graceful shutdown - save state and export stopped status."""
        logger.info("Shutting down bot...")
        self.state_store.save()
        try:
            self.status_exporter.export_stopped(self.config, self.state_store.state)
        except Exception as e:
            logger.error(f"Failed to export shutdown status: {e}")
        logger.info("State saved. Bot stopped.")

    def stop(self) -> None:
        """Signal the bot to stop."""
        self.running = False
