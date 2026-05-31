"""Bot service - main orchestration loop tying all components together."""

import time
import json
import threading
from datetime import datetime, timezone
from pathlib import Path
import sys
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
from src.persistence.atomic_io import atomic_write_json
from src.services.command_processor import CommandProcessor
from src.monitoring.status_exporter import StatusExporter
from src.utils.logger import get_logger
from src.utils.helpers import iso_now

logger = get_logger("services.bot_service")


def _is_valid_app_root(candidate: Path) -> bool:
    required_files = (
        candidate / "src" / "main.py",
        candidate / "dashboard" / "app.py",
        candidate / "config" / "default.yaml",
        candidate / "src" / "persistence" / "atomic_io.py",
        candidate / "src" / "persistence" / "command_queue.py",
        candidate / "src" / "persistence" / "schemas.py",
        candidate / "src" / "services" / "command_processor.py",
        candidate / "src" / "market" / "live_price_service.py",
    )
    if not candidate.is_dir():
        return False
    return all(path.is_file() for path in required_files)


def _project_root() -> Path:
    """Resolve writable project root for packaged and developer runs."""
    if hasattr(sys, "_MEIPASS"):
        exe_root = Path(sys.executable).resolve().parent
        external = exe_root / "app"
        if _is_valid_app_root(external):
            return external
        internal = Path(sys._MEIPASS).resolve() / "app"  # type: ignore[attr-defined]
        if _is_valid_app_root(internal):
            if external.exists():
                try:
                    import shutil
                    if external.is_dir():
                        shutil.rmtree(external)
                    else:
                        external.unlink()
                except Exception as exc:
                    print(
                        f"Warning: failed to clean writable app bundle {external}: {exc}",
                        file=sys.stderr,
                    )
            try:
                import shutil
                shutil.copytree(internal, external)
            except Exception as exc:
                raise RuntimeError(f"Failed to prepare writable app bundle at {external}") from exc
            if _is_valid_app_root(external):
                return external
            raise RuntimeError(f"Copied app bundle at {external} is invalid.")
        raise RuntimeError("Could not locate a valid app bundle in packaged environment.")
    return Path(__file__).resolve().parent.parent


PROJECT_ROOT = _project_root()
DEFAULT_RUNTIME_DIR = PROJECT_ROOT / "runtime"


def _as_project_path(value: str | Path | None, fallback: Path | None = None) -> Path:
    """Resolve a path value relative to project root when not absolute."""
    if value is None:
        if fallback is None:
            return PROJECT_ROOT
        return fallback if fallback.is_absolute() else PROJECT_ROOT / fallback
    try:
        text = str(value).strip()
    except Exception:
        if fallback is None:
            return PROJECT_ROOT
        return fallback if fallback.is_absolute() else PROJECT_ROOT / fallback
    if not text:
        if fallback is None:
            return PROJECT_ROOT
        return fallback if fallback.is_absolute() else PROJECT_ROOT / fallback
    candidate = Path(text).expanduser()
    if not candidate.is_absolute():
        candidate = PROJECT_ROOT / candidate
    return candidate


def _resolve_runtime_dir(config: dict[str, Any] | None = None) -> Path:
    """Resolve runtime directory from configured runtime artifact paths."""
    if not isinstance(config, dict):
        return DEFAULT_RUNTIME_DIR
    candidates = (
        config.get("dashboard_status_path"),
        config.get("state_path"),
        config.get("active_symbol_path"),
        config.get("command_queue_path"),
        config.get("log_path"),
    )
    for anchor in candidates:
        if not isinstance(anchor, (str, Path)):
            continue
        text = str(anchor).strip()
        if not text or text in {"."}:
            continue
        try:
            anchor_path = _as_project_path(anchor)
        except Exception:
            continue
        if anchor_path.suffix:
            return anchor_path.parent
        return anchor_path
    return DEFAULT_RUNTIME_DIR


def _parse_iso_datetime(value: Any) -> datetime | None:
    """Parse ISO timestamps and normalize to UTC-aware datetime objects."""
    if not value:
        return None
    try:
        text = str(value)
    except Exception:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except Exception:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


def _as_runtime_file(value: str | Path | None, *, fallback: Path, default_filename: str) -> Path:
    """Resolve runtime artifact path, supporting directory or file config values."""
    if value is None:
        return fallback
    try:
        text = str(value).strip()
    except Exception:
        return fallback
    if not text or text in {"."}:
        return fallback
    candidate = Path(text).expanduser()
    if not candidate.is_absolute():
        candidate = PROJECT_ROOT / candidate
    if candidate.suffix:
        return candidate
    return candidate / default_filename


def _resolve_status_path(config: dict[str, Any] | None = None) -> Path:
    """Resolve dashboard status file path from config with safe fallback."""
    runtime_dir = _resolve_runtime_dir(config)
    fallback = runtime_dir / "dashboard_status.json"
    if not isinstance(config, dict):
        return fallback
    return _as_runtime_file(
        config.get("dashboard_status_path"),
        fallback=fallback,
        default_filename="dashboard_status.json",
    )


def _coerce_optional_str(value: Any) -> str | None:
    return value if isinstance(value, str) else None


def _coerce_runtime_list(value: Any) -> list[Any]:
    if isinstance(value, list):
        return value
    if isinstance(value, tuple):
        return list(value)
    return []


def _coerce_runtime_dict_list(value: Any) -> list[dict[str, Any]]:
    items = _coerce_runtime_list(value)
    result: list[dict[str, Any]] = []
    for item in items:
        if isinstance(item, dict):
            result.append(dict(item))
    return result


def _coerce_number(value: Any) -> int | float:
    if isinstance(value, bool):
        return 0
    if isinstance(value, (int, float)):
        return value
    return 0


def _normalize_auto_scan_progress(payload: Any) -> dict[str, Any]:
    data = dict(payload) if isinstance(payload, dict) else {}
    normalized = dict(data)
    normalized["scanning"] = bool(data.get("scanning", False))
    state_value = data.get("state")
    normalized["state"] = state_value if isinstance(state_value, str) and state_value.strip() else ("scanning" if normalized["scanning"] else "idle")
    normalized["pct"] = _coerce_number(data.get("pct", 0))
    normalized["done"] = _coerce_number(data.get("done", 0))
    normalized["total"] = _coerce_number(data.get("total", 0))
    normalized["last_auto_scan"] = _coerce_optional_str(data.get("last_auto_scan"))
    normalized["last_scan_results"] = _coerce_runtime_dict_list(data.get("last_scan_results", []))
    normalized["last_scan_hot_count"] = _coerce_number(data.get("last_scan_hot_count", 0))
    normalized["last_scan_total"] = _coerce_number(data.get("last_scan_total", 0))
    warnings = _coerce_warning_list(data.get("warnings", []))
    warnings.extend(_coerce_warning_list(data.get("status_warnings", [])))
    normalized["warnings"] = warnings
    normalized["status_warnings"] = list(warnings)
    return normalized


def _coerce_warning_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, (list, tuple)):
        warnings: list[str] = []
        for item in value:
            if item is None:
                continue
            warnings.append(item if isinstance(item, str) else str(item))
        return warnings
    return [str(value)]


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
        mode = self.config.get("mode")
        if isinstance(mode, str):
            self.config["mode"] = mode.strip().lower()
        market_type = self.config.get("market_type")
        if isinstance(market_type, str):
            self.config["market_type"] = market_type.strip().lower()
        timeframe = self.config.get("timeframe")
        if isinstance(timeframe, str):
            tf = timeframe.strip()
            self.config["timeframe"] = "1M" if tf.upper() == "1M" else tf.lower()
        risk = self.config.get("risk")
        if isinstance(risk, dict):
            max_risk_level = risk.get("max_risk_level")
            if isinstance(max_risk_level, str):
                risk["max_risk_level"] = max_risk_level.strip().upper()

        config_path = _as_project_path(
            config.get("_config_path"),
            fallback=PROJECT_ROOT / "config" / "default.yaml",
        )
        self._config_path = config_path

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
        self._bind_runtime_artifacts(config)
        # Configuration health surfaces: surfaced to dashboard so an operator
        # who picks live+futures+short knows the execution path won't honor it.
        self._status_warnings: list[str] = []
        self._last_multi_scan_warnings: list[str] = []
        self.config_watcher = ConfigWatcher(config_path)
        self.config_watcher.config = config
        # Set mtime to current so we don't reload on first cycle
        try:
            self.config_watcher._last_mtime = self.config_watcher.config_path.stat().st_mtime
        except Exception:
            self.config_watcher._last_mtime = 0.0

        self.polling_interval = config.get("polling_interval_seconds", 60)
        self._cycle_count = 0

        # Same-candle detection: don't open new positions on unchanged data
        self._last_candle_time: str | None = None
        self._candle_changed = False

        # Per-cycle state for status export
        self._last_indicator_results: list[Any] | None = None
        self._last_indicator_errors: list[str] = []
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
            _prog_path = self._runtime_dir() / "auto_scan_progress.json"
            if _prog_path.exists():
                _prog = json.loads(_prog_path.read_text(encoding="utf-8"))
                if not isinstance(_prog, dict):
                    raise ValueError(
                        f"auto_scan_progress must be a mapping, got {type(_prog).__name__}"
                    )
                _last_ts = _prog.get("last_auto_scan") or _prog.get("completed_at") or _prog.get("started_at")
                if not _last_ts:
                    raise ValueError("auto_scan_progress missing timestamp markers")
                _parsed = _parse_iso_datetime(_last_ts)
                if _parsed is None:
                    raise ValueError(f"unparseable manual scan timestamp: {_last_ts}")
                _age_secs = (datetime.now(timezone.utc) - _parsed).total_seconds()
                if _age_secs < self._auto_scan_interval:
                    # Recent scan exists — set timer so we wait the remaining interval
                    self._last_auto_scan_time = time.time() - _age_secs
                    logger.info(f"Restored last scan time: {_last_ts} ({_age_secs:.0f}s ago)")
        except Exception as exc:
            if _prog_path.exists():
                logger.warning(
                    f"Failed to restore last auto-scan time from {_prog_path}: {exc}"
                )
                try:
                    _prog_path.unlink(missing_ok=True)
                except Exception as cleanup_exc:
                    logger.warning(f"Failed to remove stale auto-scan progress file {_prog_path}: {cleanup_exc}")
                # Fail closed: avoid an immediate re-scan when the progress
                # file is corrupt or unreadable.
                self._last_auto_scan_time = time.time()
        self._scan_threads: list[threading.Thread] = []
        self._scan_lock = threading.Lock()
        self._multi_scan_done_count = 0
        self._multi_scan_results: dict[str, list] = {}  # tf → top15 results
        self._multi_scan_full: dict[str, list] = {}    # tf → ALL results
        self._last_auto_scan_progress_error: str | None = None
        self._last_dashboard_status_patch_error: str | None = None
        self._last_auto_select_error: str | None = None

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
            p = self._runtime_dir() / "manual_scan_active.json"
            if p.exists():
                p.unlink()
                logger.info("Cleaned stale manual scan lock file")
        except Exception as exc:
            logger.warning(f"Failed to clean stale manual scan lock: {exc}")
        # Mark any in-progress auto-scan as not-scanning (stale from previous run)
        try:
            p = self._runtime_dir() / "auto_scan_progress.json"
            if p.exists():
                _d = json.loads(p.read_text(encoding="utf-8"))
                if not isinstance(_d, dict):
                    raise ValueError(
                        f"auto_scan_progress must be a mapping, got {type(_d).__name__}"
                    )
                if "scanning" not in _d:
                    raise ValueError("auto_scan_progress missing scanning state")
                if _d.get("scanning"):
                    _d["scanning"] = False
                    from src.persistence.atomic_io import atomic_write_json
                    atomic_write_json(p, _d)
                    logger.info("Reset stale auto-scan scanning flag")
        except Exception as exc:
            logger.warning(f"Failed to reset stale auto-scan progress: {exc}")
            try:
                if p.exists():
                    p.unlink(missing_ok=True)
            except Exception as cleanup_exc:
                logger.warning(f"Failed to remove stale auto-scan progress file {p}: {cleanup_exc}")
            try:
                if p.exists():
                    p.unlink(missing_ok=True)
            except Exception as unlink_exc:
                logger.warning(
                    f"Failed to remove unreadable auto-scan progress file {p}: {unlink_exc}"
                )
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
            if not self.symbol_controller.set_symbol("BTCUSDT"):
                logger.warning("Failed to write default BTCUSDT symbol during initialization")

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
        old_runtime = _resolve_runtime_dir(self.config)
        normalized_config = dict(new_config)
        mode = normalized_config.get("mode")
        if isinstance(mode, str):
            normalized_config["mode"] = mode.strip().lower()
        market_type = normalized_config.get("market_type")
        if isinstance(market_type, str):
            normalized_config["market_type"] = market_type.strip().lower()
        timeframe = normalized_config.get("timeframe")
        if isinstance(timeframe, str):
            tf = timeframe.strip()
            normalized_config["timeframe"] = "1M" if tf.upper() == "1M" else tf.lower()
        risk = normalized_config.get("risk")
        if isinstance(risk, dict):
            max_risk_level = risk.get("max_risk_level")
            if isinstance(max_risk_level, str):
                risk["max_risk_level"] = max_risk_level.strip().upper()
        self.config = normalized_config
        self.binance_client.mode = normalized_config.get("mode", "paper")
        self.binance_client.market_type = normalized_config.get("market_type", "spot")
        self.execution_engine.mode = normalized_config.get("mode", "paper")
        self.execution_engine.paper_config = normalized_config.get("paper", {})
        self.execution_engine.paper_fee_pct = self.execution_engine.paper_config.get("fee_pct", 0.001)
        self._bind_runtime_artifacts(normalized_config)
        new_runtime = _resolve_runtime_dir(normalized_config)
        if old_runtime != new_runtime:
            logger.info(f"Runtime directory changed: {old_runtime} -> {new_runtime}")

        # Update polling interval
        self.polling_interval = normalized_config.get("polling_interval_seconds", 60)

        # Re-initialize components that depend on config
        self.signal_service = SignalService(normalized_config)
        self.consensus_engine = ConsensusEngine(normalized_config)
        self.leverage_manager = LeverageManager(normalized_config, self.binance_client)
        # Update position manager's risk config so max_open_positions etc. take effect
        self.position_manager.risk_config = normalized_config.get("risk", {})
        self.decision_engine = DecisionEngine(normalized_config, self.position_manager, self.leverage_manager)
        self.market_data = MarketDataProvider(self.binance_client, normalized_config)
        if hasattr(self, "_auto_scan_scanners"):
            self._auto_scan_scanners.clear()

        changes = []
        if old_tf != normalized_config.get("timeframe"):
            changes.append(f"timeframe: {old_tf} -> {normalized_config.get('timeframe')}")
        if old_interval != normalized_config.get("polling_interval_seconds"):
            changes.append(f"interval: {old_interval}s -> {normalized_config.get('polling_interval_seconds')}s")
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
            self._last_indicator_errors = list(getattr(self.signal_service, "last_errors", []))

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
            self._refresh_status_warnings()
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

        pre_close_positions = deepcopy(self.position_manager.positions)
        pre_close_history = deepcopy(self.position_manager.trade_history)
        pre_close_realized_pnl = self.position_manager.total_realized_pnl
        pre_close_balance = self.execution_engine.paper_balance

        exec_result = self.execution_engine.execute(decision)
        if exec_result.get("executed"):
            pnl = exec_result.get("pnl", 0)
            daily_pnl = self.state_store.get("daily_pnl", 0.0) + pnl
            try:
                self.state_store.update(
                    daily_pnl=round(daily_pnl, 4),
                    total_realized_pnl=round(self.position_manager.total_realized_pnl, 4),
                    positions=self.position_manager.get_positions_dict(),
                    trade_history=[t.to_dict() for t in self.position_manager.trade_history[-100:]],
                    paper_balance=round(self.execution_engine.paper_balance, 4),
                )
            except Exception:
                self.position_manager.positions = deepcopy(pre_close_positions)
                self.position_manager.trade_history = deepcopy(pre_close_history)
                self.position_manager.total_realized_pnl = pre_close_realized_pnl
                self.execution_engine.set_paper_balance(pre_close_balance)
                raise
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

        pre_reset_positions = deepcopy(self.position_manager.positions)
        pre_reset_history = deepcopy(self.position_manager.trade_history)
        pre_reset_realized_pnl = self.position_manager.total_realized_pnl
        pre_reset_balance = self.execution_engine.paper_balance

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
        try:
            self.state_store.update(
                paper_balance=round(target_balance, 4),
                positions=self.position_manager.get_positions_dict(),
                trade_history=[],
                daily_pnl=0.0,
                daily_start_balance=target_balance,
                daily_date=today,
                total_realized_pnl=0.0,
            )
        except Exception:
            self.position_manager.positions = deepcopy(pre_reset_positions)
            self.position_manager.trade_history = deepcopy(pre_reset_history)
            self.position_manager.total_realized_pnl = pre_reset_realized_pnl
            self.execution_engine.set_paper_balance(pre_reset_balance)
            raise
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
            str(self.config.get("mode", "paper")).strip().lower() == "live"
            and str(self.config.get("market_type", "spot")).strip().lower() == "futures"
        ):
            warnings.append(LIVE_SHORT_UNSUPPORTED_REASON)
        yaml_read_error = getattr(self, "_yaml_read_error", None)
        if yaml_read_error:
            warnings.append(f"Runtime config read failed: {yaml_read_error}")
        config_reload_error = getattr(self.config_watcher, "last_error", None)
        if config_reload_error:
            warnings.append(f"Config reload failed: {config_reload_error}")
        config_reload_warnings = getattr(self.config_watcher, "last_warnings", [])
        if config_reload_warnings:
            warnings.extend(config_reload_warnings)
        state_load_error = getattr(self.state_store, "_last_load_error", None)
        if state_load_error:
            warnings.append(f"State load failed: {state_load_error}")
        state_save_error = getattr(self.state_store, "_last_save_error", None)
        if state_save_error:
            warnings.append(f"State save failed: {state_save_error}")
        status_write_error = getattr(self.status_exporter, "_last_write_error", None)
        if status_write_error:
            warnings.append(f"Dashboard status write failed: {status_write_error}")
        auto_scan_progress_error = getattr(self, "_last_auto_scan_progress_error", None)
        if auto_scan_progress_error:
            warnings.append(f"Auto-scan progress write failed: {auto_scan_progress_error}")
        dashboard_status_patch_error = getattr(self, "_last_dashboard_status_patch_error", None)
        if dashboard_status_patch_error:
            warnings.append(f"Dashboard status patch failed: {dashboard_status_patch_error}")
        auto_select_error = getattr(self, "_last_auto_select_error", None)
        if auto_select_error:
            warnings.append(f"Auto-select failed: {auto_select_error}")
        indicator_errors = getattr(self, "_last_indicator_errors", [])
        if indicator_errors:
            warnings.extend(f"Indicator failed: {err}" for err in indicator_errors)
        multi_scan_warnings = getattr(self, "_last_multi_scan_warnings", [])
        if multi_scan_warnings:
            warnings.extend(f"Multi-scan warning: {err}" for err in multi_scan_warnings)
        self._status_warnings = warnings

    # ── Legacy file-glob close command reader ──────────────────
    # Kept callable for the transitional period; the queue-based path is the
    # single source of truth. This cleanup prevents old or manually-created
    # legacy files from triggering legacy side effects.

    def _process_close_commands(self) -> None:
        """Clear stale legacy close command files without executing actions."""
        runtime_dir = self._runtime_dir()
        try:
            cmd_files = list(runtime_dir.glob("close_cmd_*.json"))
        except Exception:
            return

        if not cmd_files:
            return

        logger.warning(
            "Legacy close command files detected; ignoring dashboard side effects and "
            "cleaning files for safety."
        )

        for cmd_file in cmd_files:
            try:
                cmd_file.unlink(missing_ok=True)
            except Exception as e:
                logger.warning(f"Failed to process close command {cmd_file}: {e}")
                try:
                    cmd_file.unlink(missing_ok=True)
                except Exception as inner_exc:
                    logger.warning(
                        f"Second attempt to remove legacy close command file {cmd_file} failed: {inner_exc}"
                    )

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
                        tf_config = {**self.config, "timeframe": tf}
                        svc = SignalService(tf_config)
                        indicators = svc.calculate_all(df)
                        consensus = ConsensusEngine(tf_config).evaluate(indicators)
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
                runtime_dir = self._runtime_dir()
                out = runtime_dir / "active_coin_signals.json"
                atomic_write_json(out, data)
            except Exception as e:
                logger.warning(f"Active coin signals update failed: {e}")
                try:
                    out = self._runtime_dir() / "active_coin_signals.json"
                    if out.exists():
                        out.unlink()
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to remove stale active coin signals after write error: {cleanup_exc}"
                    )

        self._active_signals_thread = threading.Thread(target=_worker, daemon=True)
        self._active_signals_thread.start()

    def _is_manual_scan_active(self) -> bool:
        """Check if a dashboard-triggered manual scan is currently running.
        Auto-clears stale locks older than 30 minutes.
        """
        try:
            runtime_dir = self._runtime_dir()
            lock_file = runtime_dir / "manual_scan_active.json"
            if lock_file.exists():
                d = json.loads(lock_file.read_text(encoding="utf-8"))
                if not isinstance(d, dict):
                    raise ValueError(f"manual scan lock must be a mapping, got {type(d).__name__}")
                if "active" not in d:
                    raise ValueError("manual scan lock missing active state")
                if not d.get("active", False):
                    lock_file.unlink(missing_ok=True)
                    return False
                # Check if lock is stale (>30 min old)
                ts = d.get("ts", "")
                if not ts:
                    logger.warning("Manual scan lock missing ts — auto-clearing")
                    lock_file.unlink(missing_ok=True)
                    return False
                lock_time = _parse_iso_datetime(ts)
                if lock_time is None:
                    logger.warning(f"Malformed manual scan lock ts: {ts} — auto-clearing")
                    lock_file.unlink(missing_ok=True)
                    return False
                age_minutes = (datetime.now(timezone.utc) - lock_time).total_seconds() / 60
                if age_minutes > 30:
                    logger.warning(f"Stale manual scan lock ({age_minutes:.0f} min old) — auto-clearing")
                    lock_file.unlink(missing_ok=True)
                    return False
                return True
        except Exception as exc:
            if lock_file.exists():
                try:
                    age_minutes = (
                        datetime.now(timezone.utc).timestamp() - lock_file.stat().st_mtime
                    ) / 60
                except Exception:
                    age_minutes = None
                if age_minutes is not None and age_minutes > 30:
                    logger.warning(
                        f"Stale manual scan lock ({age_minutes:.0f} min old) after read error — auto-clearing"
                    )
                    try:
                        lock_file.unlink()
                    except Exception as cleanup_exc:
                        logger.warning(
                            f"Failed to remove stale manual scan lock after read error: {cleanup_exc}"
                        )
                    return False
            logger.warning(f"Manual scan lock read failed; treating as inactive: {exc}")
            try:
                lock_file.unlink(missing_ok=True)
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove unreadable manual scan lock after read error: {cleanup_exc}"
                )
            return False
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
        if not disk_cfg:
            logger.warning("Could not reread YAML config for auto-scan; disabling auto-scan for safety")
            self._write_auto_scan_progress({
                "scanning": False, "total": 0, "done": 0, "pct": 0,
                "error": "auto_scan_config_unreadable",
                "reason": "auto_scan_config_unreadable",
            })
            return
        if not disk_cfg.get("auto_scan_enabled", False):
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

            symbol_path = _as_runtime_file(
                self.config.get("active_symbol_path"),
                fallback=self._runtime_dir() / "active_symbol.txt",
                default_filename="active_symbol.txt",
            )
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
            prev_progress = {
                "last_auto_scan": self.state_store.get("last_auto_scan"),
                "last_scan_results": self.state_store.get("last_scan_results"),
                "last_scan_hot_count": self.state_store.get("last_scan_hot_count"),
                "last_scan_total": self.state_store.get("last_scan_total"),
            }
            try:
                runtime_dir = self._runtime_dir()
                prev_file = runtime_dir / "auto_scan_progress.json"
                if prev_file.exists():
                    loaded_prev = json.loads(prev_file.read_text(encoding="utf-8"))
                    if not isinstance(loaded_prev, dict):
                        raise ValueError(
                            f"auto_scan_progress must be a mapping, got {type(loaded_prev).__name__}"
                        )
                    prev_progress = _normalize_auto_scan_progress(loaded_prev)
                # Fallback: if no last_auto_scan, try dashboard_status.json
                if not prev_progress.get("last_auto_scan"):
                    ds_path = _resolve_status_path(self.config)
                    if ds_path.exists():
                        try:
                            ds = json.loads(ds_path.read_text(encoding="utf-8"))
                            if not isinstance(ds, dict):
                                raise ValueError(
                                    f"dashboard_status must be a mapping, got {type(ds).__name__}"
                                )
                            if "last_auto_scan" in ds:
                                prev_progress["last_auto_scan"] = ds["last_auto_scan"]
                            if "last_scan_results" in ds:
                                prev_progress["last_scan_results"] = ds["last_scan_results"]
                            if ds.get("last_scan_total") is not None:
                                prev_progress["last_scan_total"] = ds["last_scan_total"]
                            if ds.get("last_scan_hot_count") is not None:
                                prev_progress["last_scan_hot_count"] = ds["last_scan_hot_count"]
                        except Exception as ds_exc:
                            logger.warning(f"Failed to read dashboard status for auto-scan restore: {ds_exc}")
                            try:
                                ds_path.unlink(missing_ok=True)
                            except Exception as cleanup_exc:
                                logger.warning(f"Failed to remove stale dashboard status file {ds_path}: {cleanup_exc}")
                prev_progress = _normalize_auto_scan_progress(prev_progress)
            except Exception as exc:
                logger.warning(f"Failed to read previous auto-scan progress: {exc}")
                try:
                    if prev_file.exists():
                        prev_file.unlink(missing_ok=True)
                except Exception as cleanup_exc:
                    logger.warning(f"Failed to remove stale auto-scan progress file {prev_file}: {cleanup_exc}")
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
                    if not self._scanning_active:
                        break
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
                        except Exception as exc:
                            logger.debug(f"Failed to read auto-scan progress for {tf}: {exc}")
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
                    syms = shared_symbols if symbols_override is None else symbols_override
                    scanner = ScannerService(
                        self.config, symbol_file=symbol_path, timeframe=tf,
                        shared_symbols=syms,
                    )
                    scanner._request_delay = req_delay
                    self._auto_scan_scanners[tf] = scanner
                    scanner.scan(min_confidence=0)
                    if scanner._scan_progress.get("status") == "error":
                        raise RuntimeError(f"Auto-scan {tf} reported an analysis error")
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
            self._last_auto_select_error = f"auto-scan failed: {e}"
            self._refresh_status_warnings()
            scan_time = iso_now()
            self._write_auto_scan_progress({
                "scanning": False,
                "total": total_tfs,
                "done": self._multi_scan_done_count,
                "pct": int((self._multi_scan_done_count * 100) / total_tfs) if total_tfs else 0,
                "done_tfs": list(self._multi_scan_results.keys()),
                "all_tfs": self._multi_tfs,
                "completed_at": scan_time,
                "error": True,
                "reason": "auto_scan_failed",
                "last_auto_scan": scan_time,
                "last_scan_results": [],
                "last_scan_hot_count": 0,
                "last_scan_total": 0,
            })
            self._scanning_active = False

    def _process_multi_scan_results(self, symbol_path: Path) -> None:
        """Process combined multi-TF scan results: cross-ranking, auto-select, save."""
        try:
            def _remove_stale_multi_scan_results() -> None:
                try:
                    stale_scan_file = self._runtime_dir() / "multi_scan_results.json"
                    if stale_scan_file.exists():
                        stale_scan_file.unlink()
                except Exception as unlink_exc:
                    logger.warning(f"Failed to remove stale multi-scan results: {unlink_exc}")

            tf_data = self._multi_scan_results
            total_tfs = len(self._multi_tfs)
            auto_select_failed = False

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

            if not symbol_stats:
                logger.error("Multi-scan failed: no symbol data produced")
                self._last_auto_select_error = "multi-scan produced no symbol data"
                self._refresh_status_warnings()
                _remove_stale_multi_scan_results()
                scan_time = iso_now()
                self._write_auto_scan_progress({
                    "scanning": False,
                    "total": total_tfs,
                    "done": total_tfs,
                    "pct": 100,
                    "done_tfs": self._multi_tfs,
                    "all_tfs": self._multi_tfs,
                    "completed_at": scan_time,
                    "error": True,
                    "reason": "multi_scan_no_symbol_data",
                    "last_auto_scan": scan_time,
                    "last_scan_results": [],
                    "last_scan_hot_count": 0,
                    "last_scan_total": 0,
                })
                return

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
            # 3) Last resort: fail closed (disabled) if config cannot be read
            auto_select_on = False
            try:
                _cfg_path = _as_project_path(
                    self.config.get("_config_path"),
                    fallback=self._config_path,
                )
                import yaml as _yaml
                _fresh_cfg = _yaml.safe_load(_cfg_path.read_text(encoding="utf-8"))
                if _fresh_cfg is None:
                    _fresh_cfg = {}
                if not isinstance(_fresh_cfg, dict):
                    raise ValueError(
                        f"Config root must be a mapping, got {type(_fresh_cfg).__name__}"
                    )
                auto_select_on = bool(_fresh_cfg.get("auto_select_enabled", False))
                logger.info(f"Auto-select from YAML: {auto_select_on}")
            except Exception as _e:
                logger.warning(f"Could not read YAML for auto_select: {_e}")
            # Runtime flag file override: if auto_select_disabled exists in runtime → force OFF
            _flag_file = self._runtime_dir() / "auto_select_disabled"
            if _flag_file.exists():
                logger.info(f"Auto-select FORCE OFF via {_flag_file.name} flag file")
                auto_select_on = False
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
                    if scanner.set_active_symbol(best["symbol"]):
                        self._last_auto_select_error = None
                    else:
                        auto_select_failed = True
                        self._last_auto_select_error = f"failed to write active symbol {best['symbol']}"
                        logger.warning(self._last_auto_select_error)
                        self._refresh_status_warnings()
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

            runtime_warnings = self._collect_auto_scan_warnings()
            self._last_multi_scan_warnings = list(runtime_warnings)
            existing_status_warnings = self.state_store.get("status_warnings", [])
            if not isinstance(existing_status_warnings, list):
                existing_status_warnings = []
            merged_status_warnings = list(
                dict.fromkeys(
                    [
                        *(item if isinstance(item, str) else str(item) for item in existing_status_warnings),
                        *runtime_warnings,
                    ]
                )
            )
            try:
                self.state_store.update(status_warnings=merged_status_warnings)
            except Exception as exc:
                logger.warning(f"Failed to persist multi-scan warnings to state store: {exc}")
                self._last_state_save_error = str(exc)
                self._refresh_status_warnings()
            else:
                self._refresh_status_warnings()

            # Save full multi-scan data for dashboard
            multi_result = {
                "any_scanning": False,
                "timeframes": {},
                "common_symbols": [c["symbol"] for c in cross_ranked if c["count"] == total_tfs],
                "cross_ranking": cross_ranked[:10],
                "scan_time": iso_now(),
                "status_warnings": list(runtime_warnings),
                "warnings": list(runtime_warnings),
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
                scan_file = self._runtime_dir() / "multi_scan_results.json"
                atomic_write_json(scan_file, multi_result)
                logger.info(f"Multi-TF results saved to {scan_file}")
            except Exception as e:
                logger.warning(f"Failed to save multi-scan results: {e}")
                self._last_auto_select_error = f"failed to save multi-scan results: {e}"
                self._refresh_status_warnings()
                _remove_stale_multi_scan_results()
                scan_time = iso_now()
                self._last_multi_scan_warnings = list(runtime_warnings)
                self._write_auto_scan_progress({
                    "scanning": False,
                    "total": total_tfs,
                    "done": total_tfs,
                    "pct": 100,
                    "done_tfs": self._multi_tfs,
                    "all_tfs": self._multi_tfs,
                    "completed_at": scan_time,
                    "error": True,
                    "reason": "multi_scan_results_write_failed",
                    "last_auto_scan": scan_time,
                    "last_scan_results": cross_ranked[:5],
                    "last_scan_hot_count": sum(1 for c in cross_ranked if c["count"] == total_tfs),
                    "last_scan_total": len(symbol_stats),
                    "warnings": list(runtime_warnings),
                })
                return

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
                "warnings": list(runtime_warnings),
            })
            if not auto_select_failed and self._last_auto_select_error:
                self._last_auto_select_error = None
                self._refresh_status_warnings()

        except Exception as e:
            logger.error(f"Multi-scan processing failed: {e}", exc_info=True)
            self._last_auto_select_error = f"multi-scan processing failed: {e}"
            self._refresh_status_warnings()
            _remove_stale_multi_scan_results()
            scan_time = iso_now()
            self._write_auto_scan_progress({
                "scanning": False,
                "total": total_tfs,
                "done": self._multi_scan_done_count,
                "pct": int((self._multi_scan_done_count * 100) / total_tfs) if total_tfs else 0,
                "done_tfs": list(self._multi_scan_results.keys()),
                "all_tfs": self._multi_tfs,
                "completed_at": scan_time,
                "error": True,
                "reason": "multi_scan_processing_failed",
                "last_auto_scan": scan_time,
                "last_scan_results": [],
                "last_scan_hot_count": 0,
                "last_scan_total": 0,
            })
        finally:
            self._last_auto_scan_time = time.time()
            self._scanning_active = False

    def _runtime_dir(self) -> Path:
        """Return the runtime directory derived from ``dashboard_status_path``.

        S6: every runtime artifact (flag files, progress, command queue,
        active_symbol.txt) is anchored on the same parent so tmp_path
        isolated test runs never bleed into the developer's real repo.
        """
        return _resolve_runtime_dir(self.config)

    def _bind_runtime_artifacts(self, config: dict[str, Any]) -> None:
        """Rebind runtime-backed collaborators after config changes."""
        runtime_dir = _resolve_runtime_dir(config)
        active_symbol_path = _as_runtime_file(
            config.get("active_symbol_path"),
            fallback=runtime_dir / "active_symbol.txt",
            default_filename="active_symbol.txt",
        )
        state_path = _as_runtime_file(
            config.get("state_path"),
            fallback=runtime_dir / "state.json",
            default_filename="state.json",
        )
        status_path = _as_runtime_file(
            config.get("dashboard_status_path"),
            fallback=runtime_dir / "dashboard_status.json",
            default_filename="dashboard_status.json",
        )
        queue_path = _as_runtime_file(
            config.get("command_queue_path"),
            fallback=runtime_dir / "command_queue.json",
            default_filename="command_queue.json",
        )

        self.symbol_controller = SymbolController(str(active_symbol_path))
        self.state_store = StateStore(str(state_path))
        self.status_exporter = StatusExporter(str(status_path))
        self.command_queue = CommandQueue(str(queue_path))
        self.command_processor = CommandProcessor(self.command_queue)
        self.command_processor.register(CommandKind.MANUAL_CLOSE, self._handle_manual_close)
        self.command_processor.register(CommandKind.PAPER_RESET, self._handle_paper_reset)

    def _read_yaml_config(self) -> dict[str, Any]:
        """Re-read the YAML config from disk so toggles applied via the
        dashboard land before the next scan decision.

        S6: this replaces the pre-S6 reference to the undefined
        ``self._read_config_from_disk()``. The chosen pattern mirrors
        the YAML reread that already exists in ``_process_multi_scan_results``
        for ``auto_select_enabled``.
        """
        cfg_path = _as_project_path(
            self.config.get("_config_path"),
            fallback=self._config_path,
        )
        try:
            import yaml as _yaml
            cfg = _yaml.safe_load(cfg_path.read_text(encoding="utf-8"))
            if cfg is None:
                cfg = {}
            if not isinstance(cfg, dict):
                raise ValueError(
                    f"Config root must be a mapping, got {type(cfg).__name__}"
                )
            self._yaml_read_error = None
            return cfg
        except Exception as exc:
            self._yaml_read_error = str(exc)
            return {}

    def _write_auto_scan_progress(self, data: dict) -> None:
        """Write auto-scan progress to a JSON file for the dashboard to read.

        S6: always surface a top-level ``state`` field derived from the
        payload so the dashboard renders idle vs scanning vs disabled vs
        complete vs error honestly — never silent. ``reason`` is preserved
        when the caller provides it (e.g. ``auto_scan_disabled_flag``)."""
        try:
            self._last_auto_scan_progress_error = None
            payload = _normalize_auto_scan_progress(data)
            runtime_warnings = self._collect_auto_scan_warnings()
            if runtime_warnings:
                warnings = _coerce_warning_list(payload.get("warnings", []))
                warnings.extend(runtime_warnings)
                deduped_warnings = list(dict.fromkeys(warnings))
                payload["warnings"] = deduped_warnings
                payload["status_warnings"] = list(deduped_warnings)
            payload["state"] = self._derive_progress_state(payload)
            runtime_dir = self._runtime_dir()
            runtime_dir.mkdir(parents=True, exist_ok=True)
            progress_file = runtime_dir / "auto_scan_progress.json"
            atomic_write_json(progress_file, payload)
        except Exception as exc:
            self._last_auto_scan_progress_error = str(exc)
            logger.warning(f"Failed to write auto-scan progress: {exc}")
            try:
                progress_file = self._runtime_dir() / "auto_scan_progress.json"
                if progress_file.exists():
                    progress_file.unlink()
            except Exception as cleanup_exc:
                logger.warning(f"Failed to remove stale auto-scan progress after write error: {cleanup_exc}")
            self._refresh_status_warnings()

    def _collect_auto_scan_warnings(self) -> list[str]:
        warnings: list[str] = []
        scanners = getattr(self, "_auto_scan_scanners", {})
        if not isinstance(scanners, dict):
            return warnings
        for tf, scanner in scanners.items():
            try:
                progress = scanner.progress if isinstance(scanner.progress, dict) else {}
            except Exception as exc:
                warnings.append(f"{tf} progress warning: {exc}")
                continue
            progress_warnings = progress.get("warnings")
            if isinstance(progress_warnings, list):
                for warning in progress_warnings:
                    if warning is None:
                        continue
                    warnings.append(f"{tf} progress warning: {warning if isinstance(warning, str) else str(warning)}")
            indicator_warnings = getattr(scanner, "last_indicator_errors", [])
            if isinstance(indicator_warnings, list):
                for warning in indicator_warnings:
                    if warning is None:
                        continue
                    warnings.append(f"{tf} indicator warning: {warning if isinstance(warning, str) else str(warning)}")
        return list(dict.fromkeys(warnings))

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
            self._last_dashboard_status_patch_error = None
            status_path = _resolve_status_path(self.config)
            if status_path.exists():
                ds = json.loads(status_path.read_text(encoding="utf-8"))
            else:
                ds = {}
            ds.update(updates)
            atomic_write_json(status_path, ds, indent=2)
        except Exception as e:
            self._last_dashboard_status_patch_error = str(e)
            logger.warning(f"Failed to patch dashboard_status.json: {e}")
            try:
                status_path = _resolve_status_path(self.config)
                if status_path.exists():
                    status_path.unlink()
            except Exception as cleanup_exc:
                logger.warning(f"Failed to remove stale dashboard status after patch error: {cleanup_exc}")
            self._refresh_status_warnings()

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
        self._refresh_status_warnings()
        try:
            shutdown_state = dict(self.state_store.state)
            shutdown_state["status_warnings"] = list(getattr(self, "_status_warnings", []))
            self.status_exporter.export_stopped(self.config, shutdown_state)
        except Exception as e:
            logger.error(f"Failed to export shutdown status: {e}")
        logger.info("State saved. Bot stopped.")

    def stop(self) -> None:
        """Signal the bot to stop."""
        self.running = False
