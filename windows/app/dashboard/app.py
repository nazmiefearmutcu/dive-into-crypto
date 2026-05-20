"""FastAPI dashboard for the trading bot.

Reads:
- runtime/dashboard_status.json (bot status snapshot)
- runtime/bot.log (log tail)
- runtime/state.json (fallback)

Settings page allows:
- Editing config/default.yaml
- Editing .env (API keys)
- Changing active symbol
"""

import json
import os
import re
import signal
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import yaml
from fastapi import FastAPI, Request, Query, Form
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

# Resolve paths
DASHBOARD_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = DASHBOARD_DIR.parent
RUNTIME_DIR = PROJECT_ROOT / "runtime"
STATUS_FILE = RUNTIME_DIR / "dashboard_status.json"
STATE_FILE = RUNTIME_DIR / "state.json"
LOG_FILE = RUNTIME_DIR / "bot.log"
CONFIG_FILE = PROJECT_ROOT / "config" / "default.yaml"
ENV_FILE = PROJECT_ROOT / ".env"
SYMBOL_FILE = RUNTIME_DIR / "active_symbol.txt"

app = FastAPI(title="Trading Bot Dashboard", docs_url=None, redoc_url=None)


@app.middleware("http")
async def catch_all_errors(request: Request, call_next):
    """Global error handler — prevent any unhandled exception from crashing the server."""
    try:
        return await call_next(request)
    except Exception as e:
        import traceback
        traceback.print_exc()
        return JSONResponse(
            status_code=500,
            content={"error": f"Internal server error: {type(e).__name__}: {str(e)}"},
        )


app.mount("/static", StaticFiles(directory=str(DASHBOARD_DIR / "static")), name="static")
templates = Jinja2Templates(directory=str(DASHBOARD_DIR / "templates"))


def _fmt_price(value):
    """Smart price formatter: adapts decimal places to price magnitude.
    BTC ($50000) → 2 decimals, SOL ($150) → 3, DOGE ($0.12) → 5, SHIB ($0.00001) → 8
    """
    try:
        v = float(value)
    except (TypeError, ValueError):
        return "0.00"
    if v == 0:
        return "0.00"
    abs_v = abs(v)
    if abs_v >= 100:
        return f"{v:.2f}"
    elif abs_v >= 1:
        return f"{v:.4f}"
    elif abs_v >= 0.01:
        return f"{v:.5f}"
    elif abs_v >= 0.0001:
        return f"{v:.6f}"
    else:
        return f"{v:.8f}"


templates.env.filters["price"] = _fmt_price


def _format_dt_early(iso_str: str) -> str:
    """Format ISO datetime as 'DD.MM.YYYY HH:MM:SS'."""
    if not iso_str:
        return "—"
    try:
        dt = datetime.fromisoformat(str(iso_str))
        return dt.strftime("%d.%m.%Y %H:%M:%S")
    except Exception:
        return str(iso_str)


templates.env.filters["format_dt"] = _format_dt_early
templates.env.globals["format_dt"] = _format_dt_early


# ─── Helpers ────────────────────────────────────────────────────────

def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return {}


def _read_log_tail(n: int = 200, level: Optional[str] = None, search: Optional[str] = None) -> list[dict[str, str]]:
    """Read last N lines, optionally filter by level / search."""
    if not LOG_FILE.exists():
        return []
    try:
        with open(LOG_FILE, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except Exception:
        return []

    entries = []
    for line in lines[-(n * 2):]:
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split(" | ", 3)
        entry = {
            "timestamp": parts[0].strip() if len(parts) >= 1 else "",
            "level": parts[1].strip() if len(parts) >= 2 else "INFO",
            "module": parts[2].strip() if len(parts) >= 3 else "",
            "message": parts[3].strip() if len(parts) >= 4 else line,
            "raw": line,
        }
        if level and entry["level"].upper() != level.upper():
            continue
        if search and search.lower() not in line.lower():
            continue
        entries.append(entry)

    return entries[-n:]


def _is_stale(status: dict) -> bool:
    last = status.get("last_update", "")
    if not last:
        return True
    try:
        dt = datetime.fromisoformat(last)
        return (datetime.now(timezone.utc) - dt).total_seconds() > 300
    except Exception:
        return True


def _time_ago(iso_str: str) -> str:
    if not iso_str:
        return "N/A"
    try:
        dt = datetime.fromisoformat(iso_str)
        secs = int((datetime.now(timezone.utc) - dt).total_seconds())
        if secs < 0:
            return "just now"
        if secs < 60:
            return f"{secs}s ago"
        if secs < 3600:
            return f"{secs // 60}m {secs % 60}s ago"
        if secs < 86400:
            return f"{secs // 3600}h {(secs % 3600) // 60}m ago"
        return f"{secs // 86400}d ago"
    except Exception:
        return iso_str


def _format_dt(iso_str: str) -> str:
    """Format ISO datetime as 'DD.MM.YYYY HH:MM:SS'."""
    if not iso_str:
        return "—"
    try:
        dt = datetime.fromisoformat(iso_str)
        return dt.strftime("%d.%m.%Y %H:%M:%S")
    except Exception:
        return iso_str


# Register template globals AND filters (double-register for reliability)
templates.env.globals["time_ago"] = _time_ago
templates.env.globals["format_dt"] = _format_dt
templates.env.globals["is_stale"] = _is_stale
templates.env.filters["format_dt"] = _format_dt
templates.env.filters["time_ago"] = _time_ago


# ─── JSON API endpoints (for JS polling) ──────────────────────────

@app.get("/api/status", response_class=JSONResponse)
def api_status():
    """Return full dashboard status JSON (read-only)."""
    status = _read_json(STATUS_FILE)
    if not status:
        status = _read_json(STATE_FILE)
        status["_source"] = "state.json (fallback)"
    status["_stale"] = _is_stale(status)
    return status


@app.get("/api/active-coin-signals", response_class=JSONResponse)
def api_active_coin_signals():
    """Return live multi-TF signals for the currently active coin.

    Primary source: active_coin_signals.json (written by bot each cycle).
    Fallback: extract from auto-scan results if the active coin is in top 5.
    """
    # Trigger background calculation if needed
    _ensure_live_signals()

    # Primary: bot-written file (or dashboard-calculated)
    data = _read_json(RUNTIME_DIR / "active_coin_signals.json")
    if data and data.get("symbol") and len(data.get("timeframes", {})) >= 3:
        return data

    # Fallback: extract from auto-scan or multi-scan results
    active_symbol = None
    if SYMBOL_FILE.exists():
        try:
            active_symbol = SYMBOL_FILE.read_text().strip().upper()
        except Exception:
            pass
    if not active_symbol:
        status = _read_json(STATUS_FILE)
        active_symbol = status.get("active_symbol")
    if not active_symbol:
        return {"symbol": None, "timeframes": {}, "updated_at": None}

    # Try auto-scan progress (has all_signals for top coins)
    scan_data = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    scan_results = scan_data.get("last_scan_results", [])
    if not scan_results:
        # Try multi_scan_results.json
        multi = _read_json(RUNTIME_DIR / "multi_scan_results.json")
        scan_results = multi.get("cross_ranking", [])

    for coin in scan_results:
        if coin.get("symbol") == active_symbol:
            all_sigs = coin.get("all_signals", {})
            sigs_dict = coin.get("signals", {})
            tfs = {}
            for tf in ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]:
                info = all_sigs.get(tf) or sigs_dict.get(tf)
                if info:
                    tfs[tf] = {
                        "signal": info.get("signal", "N/A"),
                        "confidence": info.get("confidence", 0),
                        "risk_level": info.get("risk_level", "N/A"),
                    }
            if tfs:
                return {
                    "symbol": active_symbol,
                    "timeframes": tfs,
                    "updated_at": scan_data.get("last_auto_scan") or scan_data.get("completed_at"),
                    "_source": "auto_scan_fallback",
                }

    # Last resort: return current TF signal from dashboard status
    status = _read_json(STATUS_FILE)
    decision = status.get("latest_decision", {})
    current_tf = status.get("timeframe", "4h")
    if decision.get("signal"):
        return {
            "symbol": active_symbol,
            "timeframes": {
                current_tf: {
                    "signal": decision.get("signal", "N/A"),
                    "confidence": decision.get("confidence", 0),
                    "risk_level": decision.get("risk_level", "N/A"),
                }
            },
            "updated_at": status.get("last_update"),
            "_source": "dashboard_status_fallback",
        }

    return {"symbol": active_symbol, "timeframes": {}, "updated_at": None}


# Background live signal calculator for dashboard
import threading as _sig_threading

_live_signal_lock = _sig_threading.Lock()
_live_signal_thread: _sig_threading.Thread | None = None


def _calc_live_signals_bg():
    """Background thread: calculate active coin signals across all 12 TFs."""
    global _live_signal_thread
    try:
        import sys as _sys
        _sys.path.insert(0, str(PROJECT_ROOT))
        from src.api.binance_client import BinanceClient
        from src.data.market_data import MarketDataProvider
        from src.services.signal_service import SignalService
        from src.consensus.engine import ConsensusEngine

        config = _read_config()
        symbol = None
        if SYMBOL_FILE.exists():
            symbol = SYMBOL_FILE.read_text().strip().upper()
        if not symbol:
            return

        client = BinanceClient(config)
        client.initialize()

        tfs = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]
        results = {}
        for tf in tfs:
            try:
                tf_config = {**config, "timeframe": tf}
                md = MarketDataProvider(client, tf_config)
                df = md.get_ohlcv(symbol)
                if df is None or df.empty:
                    results[tf] = {"signal": "N/A", "confidence": 0, "risk_level": "N/A"}
                    continue
                svc = SignalService(tf_config)
                indicators = svc.calculate_all(df)
                consensus = ConsensusEngine(config).evaluate(indicators)
                conf = consensus["confidence"]
                zak_val = ZAK.get(tf, 50)
                results[tf] = {
                    "signal": consensus["final_signal"],
                    "confidence": conf,
                    "risk_level": consensus["risk_level"],
                    "zak": zak_val,
                    "nihai_skor": round((conf ** 2) * (zak_val / 100), 2),
                }
            except Exception:
                results[tf] = {"signal": "N/A", "confidence": 0, "risk_level": "N/A"}

        data = {
            "symbol": symbol,
            "timeframes": results,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        out = RUNTIME_DIR / "active_coin_signals.json"
        tmp = out.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, default=str))
        tmp.replace(out)
    except Exception:
        pass


def _ensure_live_signals():
    """Trigger background calculation if file is missing or stale (>2 min)."""
    global _live_signal_thread
    if _live_signal_thread and _live_signal_thread.is_alive():
        return  # already running
    sig_file = RUNTIME_DIR / "active_coin_signals.json"
    need_calc = False
    if not sig_file.exists():
        need_calc = True
    else:
        try:
            d = json.loads(sig_file.read_text())
            ts = d.get("updated_at", "")
            if ts:
                lu = datetime.fromisoformat(ts)
                age = (datetime.now(timezone.utc) - lu).total_seconds()
                if age > 120:  # older than 2 minutes
                    need_calc = True
            else:
                need_calc = True
            # Recalc if fewer than 12 TFs (old data or fallback)
            if len(d.get("timeframes", {})) < 12:
                need_calc = True
            # Recalc if active symbol changed
            current_symbol = None
            if SYMBOL_FILE.exists():
                try:
                    current_symbol = SYMBOL_FILE.read_text().strip().upper()
                except Exception:
                    pass
            if current_symbol and d.get("symbol") != current_symbol:
                need_calc = True
        except Exception:
            need_calc = True
    if need_calc:
        _live_signal_thread = _sig_threading.Thread(target=_calc_live_signals_bg, daemon=True)
        _live_signal_thread.start()


@app.get("/api/logs", response_class=JSONResponse)
def api_logs(
    n: int = Query(default=100, ge=1, le=1000),
    level: Optional[str] = Query(default=None),
    search: Optional[str] = Query(default=None),
):
    """Return filtered log entries (read-only)."""
    return _read_log_tail(n, level, search)


# ─── Config / Env helpers ────────────────────────────────────────

VALID_TIMEFRAMES = {"1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"}

# ZAK — Zaman Dilimi Ağırlık Katsayısı
ZAK = {
    "1d": 95, "12h": 90, "8h": 85, "6h": 80, "4h": 75,
    "2h": 65, "1h": 58, "30m": 48, "15m": 38, "5m": 25,
    "3m": 15, "1m": 8,
}

def _calc_nss(confidence: float, tf: str) -> float:
    """Nihai Sinyal Skoru = (güven²) × (ZAK / 100)"""
    return round((confidence ** 2) * (ZAK.get(tf, 50) / 100), 2)

def _read_config() -> dict[str, Any]:
    """Read the YAML config file."""
    if not CONFIG_FILE.exists():
        return {}
    try:
        return yaml.safe_load(CONFIG_FILE.read_text()) or {}
    except Exception:
        return {}


def _write_config(config: dict[str, Any]) -> None:
    """Write config back to YAML atomically."""
    tmp = CONFIG_FILE.with_suffix(".tmp")
    tmp.write_text(yaml.dump(config, default_flow_style=False, sort_keys=False, allow_unicode=True))
    tmp.replace(CONFIG_FILE)


def _read_env() -> dict[str, str]:
    """Parse .env file into key=value dict."""
    result: dict[str, str] = {}
    if not ENV_FILE.exists():
        return result
    for line in ENV_FILE.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            result[k.strip()] = v.strip()
    return result


def _write_env(env_data: dict[str, str]) -> None:
    """Write .env file atomically, preserving comments from .env.example."""
    lines = [
        "# Binance API credentials",
        f"BINANCE_API_KEY={env_data.get('BINANCE_API_KEY', '')}",
        f"BINANCE_API_SECRET={env_data.get('BINANCE_API_SECRET', '')}",
        "",
        "# Optional: Binance Testnet (for testing live orders without real money)",
        f"BINANCE_TESTNET_API_KEY={env_data.get('BINANCE_TESTNET_API_KEY', '')}",
        f"BINANCE_TESTNET_API_SECRET={env_data.get('BINANCE_TESTNET_API_SECRET', '')}",
        "",
        "# Use testnet for live mode (true/false)",
        f"USE_TESTNET={env_data.get('USE_TESTNET', 'false')}",
    ]
    tmp = ENV_FILE.with_suffix(".tmp")
    tmp.write_text("\n".join(lines) + "\n")
    tmp.replace(ENV_FILE)


def _read_symbol() -> str:
    """Read active symbol from file."""
    if not SYMBOL_FILE.exists():
        return "BTCUSDT"
    text = SYMBOL_FILE.read_text().strip()
    return text.split("\n")[0].strip() if text else "BTCUSDT"


def _mask_key(key: str) -> str:
    """Mask API key for display: show first 4 and last 4 chars."""
    if not key or len(key) <= 8:
        return key
    return key[:4] + "*" * (len(key) - 8) + key[-4:]


# ─── Bot Process Management ──────────────────────────────────────

PID_FILE = RUNTIME_DIR / "bot.pid"


def _read_pid() -> Optional[int]:
    """Read bot PID from pid file and verify process is alive."""
    if not PID_FILE.exists():
        return None
    try:
        pid = int(PID_FILE.read_text().strip())
        os.kill(pid, 0)  # check if alive (sends no signal)
        return pid
    except (ValueError, ProcessLookupError, PermissionError):
        try:
            PID_FILE.unlink(missing_ok=True)
        except Exception:
            pass
        return None


def _is_bot_running() -> bool:
    """Check if the bot is alive via PID file, fallback to pgrep."""
    if _read_pid() is not None:
        return True
    # Fallback: check for orphan bot processes not tracked by PID file
    try:
        result = subprocess.run(
            ["pgrep", "-f", "src\\.main"],
            capture_output=True, text=True, timeout=3,
        )
        return bool(result.stdout.strip())
    except Exception:
        return False


def _start_bot() -> dict[str, Any]:
    """Start the bot as a fully detached daemon process.

    The bot runs completely independent of the dashboard:
    - start_new_session=True: own process group, no signals from parent
    - stdin closed, stdout/stderr to files
    - PID tracked via runtime/bot.pid (written by bot itself)
    """
    global _bot_process
    if _is_bot_running():
        return {"status": "already_running", "pid": _read_pid()}

    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    stderr_f = open(RUNTIME_DIR / "bot_stderr.log", "a")
    _bot_process = subprocess.Popen(
        [sys.executable, "-m", "src.main"],
        cwd=str(PROJECT_ROOT),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=stderr_f,
        start_new_session=True,
        close_fds=True,
    )
    # Don't hold a reference — let the bot live on its own via PID file
    pid = _bot_process.pid
    _bot_process = None
    return {"status": "started", "pid": pid}


def _kill_pid(pid: int) -> None:
    """Send SIGTERM then SIGKILL to a PID.

    Gives 8 seconds for graceful shutdown (bot needs time for _shutdown() + state save).
    Only force-kills if the process doesn't exit in time.
    """
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    import time
    for _ in range(16):  # 8 seconds for graceful shutdown
        time.sleep(0.5)
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def _stop_bot() -> dict[str, Any]:
    """Stop the bot via PID file, with pgrep fallback for orphan processes.

    SAFETY: never kills the dashboard's own process or its parent.
    """
    my_pid = os.getpid()
    my_ppid = os.getppid()
    safe_pids = {my_pid, my_ppid}
    killed_pids = []

    def _safe_kill(pid: int) -> bool:
        if pid in safe_pids:
            return False
        _kill_pid(pid)
        killed_pids.append(pid)
        return True

    # 1) Try PID file first
    pid = _read_pid()
    if pid is not None:
        _safe_kill(pid)

    # 2) Fallback: find orphan bot processes via pgrep
    for pattern in ["src\\.main", "run_bot\\.py"]:
        try:
            result = subprocess.run(
                ["pgrep", "-f", pattern],
                capture_output=True, text=True, timeout=3,
            )
            for line in result.stdout.strip().splitlines():
                orphan_pid = int(line.strip())
                if orphan_pid not in killed_pids:
                    _safe_kill(orphan_pid)
        except Exception:
            pass

    try:
        PID_FILE.unlink(missing_ok=True)
    except Exception:
        pass

    if not killed_pids:
        return {"status": "not_running"}
    return {"status": "stopped", "pid": killed_pids[0], "all_killed": killed_pids}


@app.get("/api/bot/status", response_class=JSONResponse)
def api_bot_status():
    """Return bot process status."""
    pid = _read_pid()
    running = pid is not None
    status = _read_json(STATUS_FILE)
    start_time = status.get("bot_start_time") if running else None
    return {
        "running": running,
        "pid": pid,
        "start_time": start_time,
    }


@app.post("/api/bot/start", response_class=JSONResponse)
def api_bot_start():
    """Start the trading bot."""
    return _start_bot()


@app.post("/api/bot/stop", response_class=JSONResponse)
def api_bot_stop():
    """Stop the trading bot."""
    return _stop_bot()


@app.post("/api/position/close", response_class=JSONResponse)
def api_close_position(symbol: str = Form(...)):
    """Manually close an open position.

    Stops bot first to prevent state overwrite, then updates state.json,
    then restarts bot. If bot can't be stopped, writes a close-command file
    so the bot picks it up on the next cycle.
    """
    import json as _json
    import time as _time
    from datetime import datetime as _dt, timezone as _tz

    state_path = RUNTIME_DIR / "state.json"
    status_path = STATUS_FILE

    # Stop bot first so it doesn't overwrite our changes.
    # Bot removes PID file AFTER saving state, so we wait for PID file removal
    # to guarantee state.json is final.
    was_running = _is_bot_running()
    if was_running:
        _stop_bot()
        # Wait up to 10 seconds for bot to fully exit (shutdown save + PID removal)
        for _w in range(20):
            _time.sleep(0.5)
            if not _is_bot_running():
                break

    # If bot STILL running after stop attempt, write a close-command file
    # so the bot picks it up next cycle, and also update state anyway
    bot_still_alive = _is_bot_running()
    if bot_still_alive:
        # Write close command for bot to pick up
        cmd_file = RUNTIME_DIR / f"close_cmd_{symbol}.json"
        cmd_file.write_text(_json.dumps({
            "symbol": symbol,
            "ts": _dt.now(_tz.utc).isoformat(),
        }))

    # Load state — re-read from disk AFTER bot is dead so we get its final save
    if not state_path.exists():
        if was_running and not bot_still_alive:
            _start_bot()
        return JSONResponse({"error": "No state file found"}, status_code=404)
    try:
        state = _json.loads(state_path.read_text())
    except Exception:
        if was_running and not bot_still_alive:
            _start_bot()
        return JSONResponse({"error": "Failed to read state"}, status_code=500)

    positions = state.get("positions", {})
    if symbol not in positions:
        if was_running and not bot_still_alive:
            _start_bot()
        return JSONResponse({"error": f"No open position for {symbol}"}, status_code=404)

    pos = positions[symbol]
    entry_price = pos["entry_price"]
    quantity = pos["quantity"]
    side = pos.get("side", "LONG")
    leverage = pos.get("leverage", 1)

    # Get current price — try Binance first, then dashboard status, then entry price
    current_price = None
    try:
        config = _read_config()
        from src.api.binance_client import BinanceClient
        _client = BinanceClient(config)
        _client.initialize()
        current_price = _client.get_ticker_price(symbol)
    except Exception:
        pass
    if not current_price:
        # Fallback: check dashboard status for matching position price
        if status_path.exists():
            try:
                status = _json.loads(status_path.read_text())
                for p in status.get("open_positions", []):
                    if p.get("symbol") == symbol and p.get("current_price"):
                        current_price = p["current_price"]
                        break
                if not current_price:
                    current_price = status.get("current_price")
            except Exception:
                pass
    if not current_price:
        current_price = entry_price  # last resort fallback

    # Calculate PnL
    fee_pct = 0.001
    if side == "LONG":
        gross_pnl = (current_price - entry_price) * quantity
    else:
        gross_pnl = (entry_price - current_price) * quantity
    fee = (entry_price * quantity + current_price * quantity) * fee_pct
    net_pnl = gross_pnl - fee

    # Create trade record
    trade_record = {
        "symbol": symbol,
        "action": "CLOSE",
        "side": side,
        "entry_price": entry_price,
        "exit_price": current_price,
        "quantity": quantity,
        "pnl": round(net_pnl, 4),
        "fee": round(fee, 4),
        "entry_time": pos.get("open_time", ""),
        "exit_time": _dt.now(_tz.utc).isoformat(),
        "reason": "manual_close",
    }

    # Update balance
    paper_balance = state.get("paper_balance", 0)
    if leverage > 1:
        margin_returned = entry_price * quantity / leverage
        paper_balance += margin_returned + net_pnl
    else:
        paper_balance += current_price * quantity - fee

    # Update state
    del positions[symbol]
    state["positions"] = positions
    state["trade_history"] = state.get("trade_history", []) + [trade_record]
    state["paper_balance"] = round(paper_balance, 4)
    state["daily_pnl"] = round(state.get("daily_pnl", 0) + net_pnl, 4)
    state["total_realized_pnl"] = round(state.get("total_realized_pnl", 0) + net_pnl, 4)

    # Atomic write state.json
    tmp = state_path.with_suffix(".tmp")
    tmp.write_text(_json.dumps(state, indent=2, default=str))
    tmp.replace(state_path)

    # Also update dashboard_status.json to reflect closed position immediately
    if status_path.exists():
        try:
            ds = _json.loads(status_path.read_text())
            ds["open_positions"] = [p for p in ds.get("open_positions", []) if p.get("symbol") != symbol]
            ds["open_positions_count"] = len(ds["open_positions"])
            ds["balance"] = round(paper_balance, 4)
            ds["daily_pnl"] = state["daily_pnl"]
            ds["total_pnl"] = state["total_realized_pnl"]
            ds_tmp = status_path.with_suffix(".tmp")
            ds_tmp.write_text(_json.dumps(ds, indent=2, default=str))
            ds_tmp.replace(status_path)
        except Exception:
            pass

    # Restart bot (only if we successfully stopped it)
    if was_running and not bot_still_alive:
        _start_bot()

    return {
        "status": "closed",
        "symbol": symbol,
        "side": side,
        "entry_price": entry_price,
        "exit_price": current_price,
        "leverage": leverage,
        "pnl": round(net_pnl, 4),
        "fee": round(fee, 4),
        "balance_after": round(paper_balance, 4),
    }


# ─── Server-Side Alert Sound (bypasses browser autoplay policy) ──

_alert_sound_proc: subprocess.Popen | None = None
_alert_sound_lock = __import__("threading").Lock()
ALERT_SOUND_FILE = DASHBOARD_DIR / "static" / "alert.mp3"


@app.post("/api/alert/play", response_class=JSONResponse)
def api_alert_play():
    """Play alert sound via macOS afplay (server-side, no browser restriction)."""
    global _alert_sound_proc
    with _alert_sound_lock:
        # Already playing? Don't stack
        if _alert_sound_proc and _alert_sound_proc.poll() is None:
            return {"status": "already_playing"}
        if not ALERT_SOUND_FILE.exists():
            return JSONResponse({"error": "alert.mp3 not found"}, status_code=404)
        # Start looping afplay in background
        # afplay doesn't have a loop flag, so we use a shell loop
        _alert_sound_proc = subprocess.Popen(
            ["bash", "-c", f'while true; do afplay "{ALERT_SOUND_FILE}"; done'],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        return {"status": "playing", "pid": _alert_sound_proc.pid}


@app.post("/api/alert/stop", response_class=JSONResponse)
def api_alert_stop():
    """Stop the server-side alert sound."""
    global _alert_sound_proc
    with _alert_sound_lock:
        if _alert_sound_proc and _alert_sound_proc.poll() is None:
            # Kill the shell loop and its child afplay
            import os as _os
            try:
                pgid = _os.getpgid(_alert_sound_proc.pid)
                _os.killpg(pgid, 9)
            except Exception:
                _alert_sound_proc.kill()
            _alert_sound_proc = None
            return {"status": "stopped"}
        _alert_sound_proc = None
        # Also kill any orphan afplay processes playing our file
        try:
            subprocess.run(
                ["pkill", "-f", f"afplay.*alert\\.mp3"],
                capture_output=True, timeout=3,
            )
        except Exception:
            pass
        return {"status": "not_playing"}


# ─── Paper Reset ─────────────────────────────────────────────────

@app.post("/api/paper/reset", response_class=JSONResponse)
def api_paper_reset(balance: float = Form(10000.0)):
    """Reset paper trading: clear all history, positions, PnL. Start fresh.
    Stops the bot first to prevent it from re-writing stale state on shutdown."""
    import json as _json
    import time as _time
    from datetime import datetime as _dt, timezone as _tz

    if balance <= 0:
        return JSONResponse({"error": "Balance must be positive"}, status_code=400)

    # Stop bot first so it doesn't overwrite fresh state on shutdown
    if _is_bot_running():
        _stop_bot()
        _time.sleep(2)  # wait for graceful shutdown

    # Also kill any orphan bot processes not tracked by PID file
    try:
        result = subprocess.run(
            ["pgrep", "-f", "src\\.main"],
            capture_output=True, text=True, timeout=5,
        )
        for pid_str in result.stdout.strip().split("\n"):
            if pid_str.strip():
                try:
                    os.kill(int(pid_str.strip()), signal.SIGTERM)
                except (ProcessLookupError, PermissionError, ValueError):
                    pass
        if result.stdout.strip():
            _time.sleep(2)
    except Exception:
        pass

    state_path = RUNTIME_DIR / "state.json"
    now = _dt.now(_tz.utc)

    fresh_state = {
        "active_symbol": "",
        "positions": {},
        "last_decision": {},
        "last_trade_time": None,
        "daily_pnl": 0.0,
        "daily_start_balance": balance,
        "daily_date": now.strftime("%Y-%m-%d"),
        "total_realized_pnl": 0.0,
        "trade_history": [],
        "paper_balance": balance,
        "bot_start_time": now.isoformat(),
    }

    # dashboard_status.json uses different keys than state.json
    fresh_dashboard = {
        "bot_status": "stopped",
        "mode": "paper",
        "market_type": "futures",
        "timeframe": "",
        "polling_interval": 1,
        "active_symbol": "",
        "current_price": 0.0,
        "last_update": now.isoformat(),
        "cycle_count": 0,
        "balance": balance,
        "daily_pnl": 0.0,
        "total_pnl": 0.0,
        "unrealized_pnl": 0.0,
        "daily_start_balance": balance,
        "open_positions_count": 0,
        "open_positions": [],
        "latest_decision": {},
        "indicator_votes": [],
        "signal_distribution": {},
        "score_details": [],
        "trade_history": [],
        "performance": {},
        "bot_start_time": now.isoformat(),
    }

    # Write fresh state.json
    tmp = state_path.with_suffix(".tmp")
    tmp.write_text(_json.dumps(fresh_state, indent=2, default=str))
    tmp.replace(state_path)

    # Write fresh dashboard_status.json (different schema from state.json)
    status_tmp = STATUS_FILE.with_suffix(".tmp")
    status_tmp.write_text(_json.dumps(fresh_dashboard, indent=2, default=str))
    status_tmp.replace(STATUS_FILE)

    return {
        "status": "ok",
        "balance": balance,
        "message": f"Paper trading reset. Starting balance: ${balance:.2f}",
    }


# ─── Scanner ─────────────────────────────────────────────────────

_scanner = None


def _get_scanner(fresh=False):
    """Get scanner, re-creating with current config if fresh=True."""
    global _scanner
    if _scanner is None or fresh:
        import sys
        sys.path.insert(0, str(PROJECT_ROOT))
        from src.services.scanner_service import ScannerService
        config = _read_config()
        _scanner = ScannerService(config, symbol_file=SYMBOL_FILE)
    return _scanner


@app.post("/api/scanner/start", response_class=JSONResponse)
def api_scanner_start(min_confidence: int = Form(55)):
    # Block if auto-scan is running
    if _is_auto_scan_active():
        return JSONResponse(
            {"error": "Otomatik tarama devam ediyor, lütfen bitmesini bekleyin."},
            status_code=409,
        )
    scanner = _get_scanner()
    if scanner.is_scanning:
        scanner.force_reset()
    # Re-create scanner with latest config (picks up timeframe changes)
    scanner = _get_scanner(fresh=True)
    ok = scanner.scan_async(min_confidence=min_confidence)
    if not ok:
        return JSONResponse({"error": "Failed to start scan"}, status_code=500)
    return {"status": "started", "min_confidence": min_confidence}


@app.post("/api/scanner/stop", response_class=JSONResponse)
def api_scanner_stop():
    scanner = _get_scanner()
    scanner.stop()
    scanner.force_reset()
    # Also stop multi-TF scanners
    for s in _multi_scanners.values():
        s.stop()
        s.force_reset()
    return {"status": "stopped"}


@app.get("/api/scanner/progress", response_class=JSONResponse)
def api_scanner_progress():
    scanner = _get_scanner()
    progress = scanner.progress
    results = scanner.results
    # Last 5 scanned for live feed during scan; top 15 after completion
    is_complete = progress.get("status") == "complete"
    if is_complete:
        # After scan, return ALL results sorted by confidence (top 15)
        top = sorted(results, key=lambda r: r["confidence"], reverse=True)
        recent = top[:15]
    else:
        recent = results[-5:] if results else []
    # Hot list: confidence >= 55 and not NEUTRAL, sorted by confidence desc
    hot = [r for r in results if r["confidence"] >= 55 and r["signal"] != "NEUTRAL"]
    hot.sort(key=lambda r: r["confidence"], reverse=True)
    # Top 15 overall (always returned for display alongside hot coins)
    top15 = sorted(results, key=lambda r: r["confidence"], reverse=True)[:15] if results else []
    return {
        "scanning": scanner.is_scanning,
        "progress": progress,
        "recent": recent,
        "hot": hot,
        "top15": top15,
        "total_scanned": len(results),
    }


@app.post("/api/scanner/select", response_class=JSONResponse)
def api_scanner_select(symbol: str = Form(...)):
    """Set the found symbol as the active trading symbol."""
    symbol = symbol.strip().upper()
    try:
        SYMBOL_FILE.parent.mkdir(parents=True, exist_ok=True)
        SYMBOL_FILE.write_text(f"{symbol}\n")
        return {"status": "ok", "symbol": symbol}
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


# ─── Multi-Timeframe Scanner ────────────────────────────────────

import threading as _mt_threading

_multi_scanners: dict[str, Any] = {}   # timeframe → ScannerService
def _get_multi_tfs() -> list[str]:
    """Return all 12 valid timeframes, sorted by duration."""
    _TF_MINUTES = {"m": 1, "h": 60, "d": 1440}
    def _tf_to_min(tf: str) -> int:
        return int(tf[:-1]) * _TF_MINUTES.get(tf[-1], 1)
    return sorted(VALID_TIMEFRAMES, key=_tf_to_min)

_MULTI_TFS = _get_multi_tfs()  # ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]
_MULTI_SCAN_FILE = RUNTIME_DIR / "multi_scan_results.json"
_MANUAL_SCAN_LOCK = RUNTIME_DIR / "manual_scan_active.json"


def _is_auto_scan_active() -> bool:
    """Check if bot's auto-scan is currently running."""
    try:
        p = RUNTIME_DIR / "auto_scan_progress.json"
        if p.exists():
            d = json.loads(p.read_text())
            return d.get("scanning", False)
    except Exception:
        pass
    return False


def _set_manual_scan_lock(active: bool) -> None:
    """Write/clear the manual scan lock file."""
    try:
        data = {"active": active, "ts": datetime.now(timezone.utc).isoformat()}
        tmp = _MANUAL_SCAN_LOCK.with_suffix(".tmp")
        tmp.write_text(json.dumps(data))
        tmp.replace(_MANUAL_SCAN_LOCK)
    except Exception:
        pass


def _is_manual_scan_active() -> bool:
    """Check if a dashboard-triggered manual scan is running."""
    # First check lock file
    try:
        if _MANUAL_SCAN_LOCK.exists():
            d = json.loads(_MANUAL_SCAN_LOCK.read_text())
            if d.get("active"):
                # Verify at least one scanner is actually alive
                for s in _multi_scanners.values():
                    if s.is_scanning:
                        return True
                # Lock says active but no scanners running → stale lock, clear it
                _set_manual_scan_lock(False)
    except Exception:
        pass
    return False


def _save_multi_results(payload: dict) -> None:
    """Persist multi-scan results to disk."""
    try:
        RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
        tmp = _MULTI_SCAN_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, default=str))
        tmp.replace(_MULTI_SCAN_FILE)
    except Exception:
        pass


def _load_multi_results() -> dict | None:
    """Load persisted multi-scan results from disk."""
    try:
        if _MULTI_SCAN_FILE.exists():
            return json.loads(_MULTI_SCAN_FILE.read_text())
    except Exception:
        pass
    return None


def _build_cross_ranking(tf_data: dict, full_tf_data: dict | None = None) -> list[dict]:
    """Build cross-timeframe ranking with net NSS (opposing signals subtracted).

    full_tf_data: if provided, contains ALL scanned results per TF (not just top15),
    used to fill in confidence values for TFs where the coin didn't make top15.
    """
    # Step 1: Find coins in top15 lists, calculate ZAK-weighted scores per direction
    symbol_counts: dict[str, dict] = {}
    for tf in _MULTI_TFS:
        zak = ZAK.get(tf, 50)
        d = tf_data.get(tf, {})
        top15 = d.get("top15", [])
        for r in top15:
            sym = r["symbol"]
            conf = r["confidence"]
            sig = r.get("signal", "NEUTRAL").upper()
            nss = _calc_nss(conf, tf)
            if sym not in symbol_counts:
                symbol_counts[sym] = {
                    "symbol": sym, "count": 0, "total_conf": 0,
                    "buy_nss": 0, "sell_nss": 0,
                    "best_conf": 0,
                    "tfs": [], "signals": {}, "all_signals": {}, "price": r.get("price", 0),
                }
            symbol_counts[sym]["count"] += 1
            symbol_counts[sym]["total_conf"] += conf
            if sig in ("BUY", "STRONG_BUY"):
                symbol_counts[sym]["buy_nss"] += nss
            elif sig in ("SELL", "STRONG_SELL"):
                symbol_counts[sym]["sell_nss"] += nss
            if conf > symbol_counts[sym]["best_conf"]:
                symbol_counts[sym]["best_conf"] = conf
                symbol_counts[sym]["price"] = r.get("price", 0)
            symbol_counts[sym]["tfs"].append(tf)
            symbol_counts[sym]["signals"][tf] = {"signal": r["signal"], "confidence": conf, "zak": zak, "nihai_skor": nss}
            symbol_counts[sym]["all_signals"][tf] = {"signal": r["signal"], "confidence": conf, "zak": zak, "nihai_skor": nss, "in_top15": True}

    # Calculate net_nss: dominant - opposing
    for sym, s in symbol_counts.items():
        if s["buy_nss"] >= s["sell_nss"]:
            s["dominant_dir"] = "BUY"
            s["net_nss"] = round(s["buy_nss"] - s["sell_nss"], 2)
        else:
            s["dominant_dir"] = "SELL"
            s["net_nss"] = round(s["sell_nss"] - s["buy_nss"], 2)
        s["total_nss"] = s["net_nss"]

    ranked = sorted(symbol_counts.values(), key=lambda x: -x["net_nss"])[:10]

    # Step 2: For ranked coins, fill ALL TF confidences from full results
    if full_tf_data:
        ranked_symbols = {c["symbol"] for c in ranked}
        for tf in _MULTI_TFS:
            zak = ZAK.get(tf, 50)
            all_results = full_tf_data.get(tf, [])
            for r in all_results:
                sym = r["symbol"]
                if sym in ranked_symbols:
                    for c in ranked:
                        if c["symbol"] == sym and tf not in c["all_signals"]:
                            conf = r["confidence"]
                            nss = _calc_nss(conf, tf)
                            c["all_signals"][tf] = {
                                "signal": r["signal"], "confidence": conf,
                                "zak": zak, "nihai_skor": nss, "in_top15": False,
                            }
                            break

    return ranked


@app.post("/api/scanner/multi-start", response_class=JSONResponse)
def api_scanner_multi_start():
    """Start parallel scans for all timeframes >= 15m."""
    # Block if bot's auto-scan is running
    if _is_auto_scan_active():
        return JSONResponse(
            {"error": "Otomatik tarama devam ediyor, lütfen bitmesini bekleyin."},
            status_code=409,
        )

    import sys
    sys.path.insert(0, str(PROJECT_ROOT))
    from src.services.scanner_service import ScannerService
    from src.api.binance_client import BinanceClient
    config = _read_config()

    # Stop any running multi-scanners
    for s in _multi_scanners.values():
        s.stop()
        s.force_reset()
    _multi_scanners.clear()

    # Pre-fetch symbol list ONCE so 9 threads don't each call futures_ticker()
    prefetch_client = BinanceClient(config)
    prefetch_client.initialize()
    temp_scanner = ScannerService(config, shared_client=prefetch_client)
    shared_symbols = temp_scanner._get_top_symbols_by_volume()

    # Calculate request delay based on parallel count to stay under rate limits
    # Binance: ~1200 weight/min → ~20 req/s max. With N parallel scanners: delay = N * 0.08s
    n_parallel = len(_MULTI_TFS)
    req_delay = max(0.15, n_parallel * 0.08)  # ~0.72s for 9 TFs

    # Create ALL scanners with shared symbols + rate-limited delay
    import time as _time
    for tf in _MULTI_TFS:
        scanner = ScannerService(
            config, symbol_file=SYMBOL_FILE, timeframe=tf,
            shared_symbols=shared_symbols,
        )
        scanner._request_delay = req_delay
        _multi_scanners[tf] = scanner
        _time.sleep(0.5)  # Stagger client init

    # Start all scans
    for tf in _MULTI_TFS:
        _multi_scanners[tf].scan_async(min_confidence=0)

    _set_manual_scan_lock(True)
    return {"status": "started", "timeframes": _MULTI_TFS}


@app.post("/api/scanner/multi-stop", response_class=JSONResponse)
def api_scanner_multi_stop():
    for s in _multi_scanners.values():
        s.stop()
        s.force_reset()
    _set_manual_scan_lock(False)
    return {"status": "stopped"}


@app.get("/api/scan-lock-status", response_class=JSONResponse)
def api_scan_lock_status():
    """Return which scan type is currently active (if any)."""
    auto_active = _is_auto_scan_active()
    manual_active = _is_manual_scan_active()
    return {
        "auto_scan_active": auto_active,
        "manual_scan_active": manual_active,
        "any_active": auto_active or manual_active,
    }


@app.get("/api/auto-scan-progress", response_class=JSONResponse)
def api_auto_scan_progress():
    """Return bot's auto-scan progress (read from file written by bot_service)."""
    data = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    if data:
        return data
    return {"scanning": False, "pct": 0, "done": 0, "total": 0}


@app.get("/api/scanner/multi-progress", response_class=JSONResponse)
def api_scanner_multi_progress():
    """Return progress and results for all 4 timeframe scanners."""
    any_scanning = False
    all_idle = True
    tf_data = {}

    for tf in _MULTI_TFS:
        scanner = _multi_scanners.get(tf)
        if not scanner:
            tf_data[tf] = {"scanning": False, "progress": {"current": 0, "total": 0, "status": "idle"}, "top15": [], "total_scanned": 0}
            continue

        all_idle = False
        progress = scanner.progress
        results = scanner.results

        if scanner.is_scanning:
            any_scanning = True

        # ZAK-weighted sorting: nihai_skor = (conf²) × (ZAK/100)
        for r in results:
            r["nihai_skor"] = _calc_nss(r["confidence"], tf)
            r["zak"] = ZAK.get(tf, 50)
        top15 = sorted(results, key=lambda r: r.get("nihai_skor", 0), reverse=True)[:15] if results else []

        tf_data[tf] = {
            "scanning": scanner.is_scanning,
            "progress": progress,
            "top15": top15,
            "total_scanned": len(results),
        }

    # If no active scanners, try loading from disk
    if all_idle:
        saved = _load_multi_results()
        if saved:
            return saved

    # Find coins that appear in ALL 4 completed top15 lists
    all_complete = True
    common_symbols = None
    for tf in _MULTI_TFS:
        d = tf_data[tf]
        if d["progress"].get("status") != "complete":
            all_complete = False
            common_symbols = None
            break
        symbols_in_tf = set(r["symbol"] for r in d["top15"])
        if common_symbols is None:
            common_symbols = symbols_in_tf
        else:
            common_symbols &= symbols_in_tf

    # Build cross-ranking with full results for all TF confidences
    if all_complete:
        full_tf_data = {}
        for tf in _MULTI_TFS:
            scanner = _multi_scanners.get(tf)
            if scanner:
                full_tf_data[tf] = scanner.results
        cross_ranking = _build_cross_ranking(tf_data, full_tf_data=full_tf_data)
    else:
        cross_ranking = []

    result = {
        "any_scanning": any_scanning,
        "timeframes": tf_data,
        "common_symbols": list(common_symbols) if common_symbols else [],
        "cross_ranking": cross_ranking,
        "scan_time": datetime.now(timezone.utc).isoformat() if all_complete else None,
    }

    # Save to disk when all complete & clear manual scan lock
    if all_complete and not any_scanning:
        _save_multi_results(result)
        _set_manual_scan_lock(False)

    return result


# ─── Risk Preset ─────────────────────────────────────────────────

# Risk = how much of your balance you put on the line per trade
RISK_PRESETS = {
    "very_low":  {"risk_per_trade": 0.02, "confidence_threshold": 60, "label": "Çok Düşük"},
    "low":       {"risk_per_trade": 0.05, "confidence_threshold": 50, "label": "Düşük"},
    "medium":    {"risk_per_trade": 0.10, "confidence_threshold": 35, "label": "Orta"},
    "high":      {"risk_per_trade": 0.20, "confidence_threshold": 30, "label": "Yüksek"},
    "very_high": {"risk_per_trade": 0.35, "confidence_threshold": 25, "label": "Çok Yüksek"},
}

# Trading mode = TP/SL style (values are PRICE MOVEMENT percentages, NOT divided by leverage)
# Leverage already multiplies PnL via position size; SL/TP stay as price %
# Scalp: çok kısa, sıkı TP/SL, hızlı giriş-çıkış
# Normal: standart swing trading
# Long: uzun vadeli, geniş TP/SL, sabırlı
TRADING_MODES = {
    "scalp": {
        "label": "Scalp",
        "stop_loss_pct": 0.005,                # %0.5 fiyat hareketi
        "take_profit_pct": 0.01,               # %1 fiyat hareketi
        "trailing_stop_pct": 0.003,            # %0.3
        "trailing_stop_activation_pct": 0.005, # %0.5
        "break_even_trigger_pct": 0.004,       # %0.4
        "desc": "Hızlı giriş-çıkış, sıkı SL/TP",
    },
    "normal": {
        "label": "Normal",
        "stop_loss_pct": 0.015,                # %1.5 fiyat hareketi
        "take_profit_pct": 0.03,               # %3 fiyat hareketi
        "trailing_stop_pct": 0.01,             # %1
        "trailing_stop_activation_pct": 0.015, # %1.5
        "break_even_trigger_pct": 0.01,        # %1
        "desc": "Standart swing trading",
    },
    "long_term": {
        "label": "Uzun Vadeli",
        "stop_loss_pct": 0.04,                 # %4 fiyat hareketi
        "take_profit_pct": 0.10,               # %10 fiyat hareketi
        "trailing_stop_pct": 0.03,             # %3
        "trailing_stop_activation_pct": 0.04,  # %4
        "break_even_trigger_pct": 0.025,       # %2.5
        "desc": "Geniş SL/TP, sabırlı pozisyon",
    },
}


def _get_current_risk_level() -> str:
    """Determine current risk level from config."""
    config = _read_config()
    risk = config.get("risk", {})
    rpt = risk.get("risk_per_trade", 0.1)
    if rpt <= 0.03:
        return "very_low"
    elif rpt <= 0.07:
        return "low"
    elif rpt <= 0.15:
        return "medium"
    elif rpt <= 0.27:
        return "high"
    return "very_high"


def _get_current_trading_mode() -> str:
    """Determine current trading mode from config."""
    config = _read_config()
    # Check explicit mode first
    mode = config.get("trading_mode")
    if mode and mode in TRADING_MODES:
        return mode
    # Otherwise detect from SL/TP values
    risk = config.get("risk", {})
    sl = risk.get("stop_loss_pct", 0.015)
    if sl <= 0.008:
        return "scalp"
    elif sl <= 0.025:
        return "normal"
    return "long_term"


@app.get("/api/risk-level", response_class=JSONResponse)
def api_get_risk_level():
    """Get current risk level."""
    level = _get_current_risk_level()
    preset = RISK_PRESETS[level]
    return {"level": level, "label": preset["label"], "presets": RISK_PRESETS}


@app.post("/api/risk-level", response_class=JSONResponse)
def api_set_risk_level(level: str = Form(...)):
    """Set risk level from preset."""
    if level not in RISK_PRESETS:
        return JSONResponse({"error": f"Invalid level: {level}"}, status_code=400)

    preset = RISK_PRESETS[level]
    config = _read_config()
    config.setdefault("risk", {})
    config["risk"]["risk_per_trade"] = preset["risk_per_trade"]
    config["risk"]["confidence_threshold"] = preset["confidence_threshold"]
    _write_config(config)

    return {
        "status": "ok",
        "level": level,
        "label": preset["label"],
        "applied": {
            "risk_per_trade": preset["risk_per_trade"],
            "confidence_threshold": preset["confidence_threshold"],
        },
    }


@app.get("/api/daily-loss-limit", response_class=JSONResponse)
def api_get_daily_loss_limit():
    """Get daily loss limit status."""
    config = _read_config()
    risk = config.get("risk", {})
    enabled = risk.get("daily_loss_limit_enabled", True)
    pct = risk.get("daily_loss_limit_pct", 0.05)
    return {"enabled": enabled, "pct": pct}


@app.post("/api/daily-loss-limit", response_class=JSONResponse)
def api_set_daily_loss_limit(
    enabled: Optional[str] = Form(default=None),
    pct: Optional[float] = Form(default=None),
):
    """Toggle daily loss limit on/off and/or set percentage."""
    config = _read_config()
    config.setdefault("risk", {})

    if enabled is not None:
        config["risk"]["daily_loss_limit_enabled"] = enabled.lower() in ("true", "1", "on")
    if pct is not None:
        config["risk"]["daily_loss_limit_pct"] = pct

    _write_config(config)

    is_enabled = config["risk"].get("daily_loss_limit_enabled", True)
    current_pct = config["risk"].get("daily_loss_limit_pct", 0.05)
    return {
        "status": "ok",
        "enabled": is_enabled,
        "pct": current_pct,
    }


@app.get("/api/auto-select", response_class=JSONResponse)
def api_get_auto_select():
    """Get auto-select enabled status."""
    config = _read_config()
    return {"enabled": config.get("auto_select_enabled", True)}


@app.post("/api/auto-select", response_class=JSONResponse)
def api_set_auto_select(enabled: str = Form(...)):
    """Toggle auto coin selection on/off."""
    config = _read_config()
    is_enabled = enabled.lower() in ("true", "1", "on")
    config["auto_select_enabled"] = is_enabled
    _write_config(config)
    # Also manage runtime flag file as belt-and-suspenders for bot process
    flag_file = RUNTIME_DIR / "auto_select_disabled"
    if is_enabled:
        flag_file.unlink(missing_ok=True)
    else:
        flag_file.write_text("disabled")
    return {"status": "ok", "enabled": is_enabled}


@app.get("/api/auto-scan-toggle", response_class=JSONResponse)
def api_get_auto_scan_toggle():
    """Get auto-scan enabled status."""
    config = _read_config()
    return {"enabled": config.get("auto_scan_enabled", True)}


@app.post("/api/auto-scan-toggle", response_class=JSONResponse)
def api_set_auto_scan_toggle(enabled: str = Form(...)):
    """Toggle auto-scan on/off."""
    config = _read_config()
    is_enabled = enabled.lower() in ("true", "1", "on")
    config["auto_scan_enabled"] = is_enabled
    _write_config(config)
    flag_file = RUNTIME_DIR / "auto_scan_disabled"
    if is_enabled:
        flag_file.unlink(missing_ok=True)
    else:
        flag_file.write_text("disabled")
    return {"status": "ok", "enabled": is_enabled}


@app.get("/api/trading-mode", response_class=JSONResponse)
def api_get_trading_mode():
    """Get current trading mode."""
    mode = _get_current_trading_mode()
    return {"mode": mode, "modes": TRADING_MODES}


@app.post("/api/trading-mode", response_class=JSONResponse)
def api_set_trading_mode(mode: str = Form(...)):
    """Set trading mode (scalp / normal / long_term)."""
    if mode not in TRADING_MODES:
        return JSONResponse({"error": f"Invalid mode: {mode}"}, status_code=400)

    preset = TRADING_MODES[mode]
    config = _read_config()
    config.setdefault("risk", {})
    config["risk"]["stop_loss_pct"] = preset["stop_loss_pct"]
    config["risk"]["take_profit_pct"] = preset["take_profit_pct"]
    config["risk"]["trailing_stop_pct"] = preset["trailing_stop_pct"]
    config["risk"]["trailing_stop_activation_pct"] = preset["trailing_stop_activation_pct"]
    config["risk"]["break_even_trigger_pct"] = preset["break_even_trigger_pct"]
    config["trading_mode"] = mode
    _write_config(config)

    return {
        "status": "ok",
        "mode": mode,
        "label": preset["label"],
        "applied": {
            "stop_loss_pct": preset["stop_loss_pct"],
            "take_profit_pct": preset["take_profit_pct"],
            "trailing_stop_pct": preset["trailing_stop_pct"],
            "trailing_stop_activation_pct": preset["trailing_stop_activation_pct"],
            "break_even_trigger_pct": preset["break_even_trigger_pct"],
        },
    }


# ─── Config API endpoints ────────────────────────────────────────

@app.get("/api/config", response_class=JSONResponse)
def api_get_config():
    """Return current config."""
    return _read_config()


@app.post("/api/config", response_class=JSONResponse)
async def api_update_config(request: Request):
    """Update config from JSON body."""
    body = await request.json()

    config = _read_config()
    if not config:
        return JSONResponse({"error": "Config file not found"}, status_code=404)

    # Merge updates into config
    for key, value in body.items():
        if key in config:
            if isinstance(config[key], dict) and isinstance(value, dict):
                config[key].update(value)
            else:
                config[key] = value
        elif "." in key:
            parts = key.split(".", 1)
            if parts[0] in config and isinstance(config[parts[0]], dict):
                config[parts[0]][parts[1]] = value

    _write_config(config)
    return {"status": "ok", "message": "Config updated"}


@app.get("/api/env", response_class=JSONResponse)
def api_get_env():
    """Return env vars with masked secrets."""
    env = _read_env()
    masked = {}
    for k, v in env.items():
        if "SECRET" in k or "KEY" in k:
            masked[k] = _mask_key(v)
        else:
            masked[k] = v
    masked["_file_exists"] = ENV_FILE.exists()
    return masked


@app.get("/api/symbol", response_class=JSONResponse)
def api_get_symbol():
    """Return active symbol."""
    return {"symbol": _read_symbol()}


# ─── HTML Pages ───────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
def page_index(request: Request):
    status = _read_json(STATUS_FILE)
    bot_running = _is_bot_running()
    # Fallback: if process check failed but status file says running (recently updated)
    if not bot_running and status.get("bot_status") == "running":
        last_update = status.get("last_update", "")
        try:
            from datetime import datetime, timezone
            lu = datetime.fromisoformat(last_update)
            age = (datetime.now(timezone.utc) - lu).total_seconds()
            if age < 120:  # updated within last 2 minutes — bot is likely running
                bot_running = True
        except Exception:
            pass
    bot_start = status.get("bot_start_time") if bot_running else None

    risk_level = _get_current_risk_level()
    trading_mode = _get_current_trading_mode()

    config = _read_config()
    risk_cfg = config.get("risk", {})
    dll_enabled = risk_cfg.get("daily_loss_limit_enabled", True)
    dll_pct = risk_cfg.get("daily_loss_limit_pct", 0.05)
    auto_select_enabled = config.get("auto_select_enabled", True)
    auto_scan_enabled = config.get("auto_scan_enabled", True)

    # Merge auto-scan data from authoritative source (auto_scan_progress.json)
    scan_progress = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    if scan_progress.get("last_auto_scan"):
        status["last_auto_scan"] = scan_progress["last_auto_scan"]
    if scan_progress.get("last_scan_results"):
        status["last_scan_results"] = scan_progress["last_scan_results"]
    if scan_progress.get("last_scan_hot_count") is not None:
        status["last_scan_hot_count"] = scan_progress["last_scan_hot_count"]
    if scan_progress.get("last_scan_total") is not None:
        status["last_scan_total"] = scan_progress["last_scan_total"]

    return templates.TemplateResponse(
        request=request,
        name="index.html",
        context={
            "status": status,
            "page": "dashboard",
            "bot_running": bot_running,
            "bot_start_time": bot_start,
            "risk_level": risk_level,
            "risk_presets": RISK_PRESETS,
            "trading_mode": trading_mode,
            "trading_modes": TRADING_MODES,
            "dll_enabled": dll_enabled,
            "dll_pct": dll_pct,
            "auto_select_enabled": auto_select_enabled,
            "auto_scan_enabled": auto_scan_enabled,
        },
    )


@app.get("/tarama", response_class=HTMLResponse)
def page_tarama(request: Request):
    status = _read_json(STATUS_FILE)
    # Merge auto-scan data
    scan_progress = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    if scan_progress.get("last_auto_scan"):
        status["last_auto_scan"] = scan_progress["last_auto_scan"]
    if scan_progress.get("last_scan_results"):
        status["last_scan_results"] = scan_progress["last_scan_results"]
    if scan_progress.get("last_scan_hot_count") is not None:
        status["last_scan_hot_count"] = scan_progress["last_scan_hot_count"]
    if scan_progress.get("last_scan_total") is not None:
        status["last_scan_total"] = scan_progress["last_scan_total"]
    return templates.TemplateResponse(
        request=request,
        name="tarama.html",
        context={"status": status, "page": "tarama"},
    )


@app.get("/positions", response_class=HTMLResponse)
def page_positions(request: Request):
    status = _read_json(STATUS_FILE)
    return templates.TemplateResponse(
        request=request,
        name="positions.html",
        context={"status": status, "page": "positions"},
    )


@app.get("/signals", response_class=HTMLResponse)
def page_signals(request: Request):
    status = _read_json(STATUS_FILE)
    return templates.TemplateResponse(
        request=request,
        name="signals.html",
        context={"status": status, "page": "signals"},
    )


@app.get("/logs", response_class=HTMLResponse)
def page_logs(
    request: Request,
    level: Optional[str] = Query(default=None),
    search: Optional[str] = Query(default=None),
):
    entries = _read_log_tail(200, level, search)
    return templates.TemplateResponse(
        request=request,
        name="logs.html",
        context={
            "entries": entries,
            "page": "logs",
            "current_level": level or "ALL",
            "current_search": search or "",
        },
    )


@app.get("/performance", response_class=HTMLResponse)
def page_performance(request: Request):
    status = _read_json(STATUS_FILE)
    # Compute daily summary for the template
    trade_history = status.get("trade_history", [])
    daily: dict[str, list[float]] = {}
    for t in trade_history:
        exit_time = t.get("exit_time", "")
        if not exit_time:
            continue
        date_str = str(exit_time)[:10]
        daily.setdefault(date_str, []).append(t.get("pnl", 0.0))

    daily_summaries = []
    for d in sorted(daily.keys()):
        pnls = daily[d]
        w = [p for p in pnls if p > 0]
        daily_summaries.append({
            "date": d,
            "trades": len(pnls),
            "wins": len(w),
            "losses": len(pnls) - len(w),
            "pnl": round(sum(pnls), 4),
            "win_rate": round(len(w) / len(pnls) * 100, 1) if pnls else 0,
        })

    return templates.TemplateResponse(
        request=request,
        name="performance.html",
        context={
            "status": status,
            "daily_summaries": daily_summaries,
            "page": "performance",
        },
    )


# ─── Settings Page ───────────────────────────────────────────────

@app.get("/settings", response_class=HTMLResponse)
def page_settings(request: Request, saved: Optional[str] = Query(default=None)):
    config = _read_config()
    env = _read_env()
    symbol = _read_symbol()
    return templates.TemplateResponse(
        request=request,
        name="settings.html",
        context={
            "page": "settings",
            "config": config,
            "env": env,
            "symbol": symbol,
            "mask_key": _mask_key,
            "saved": saved,
            "valid_timeframes": sorted(VALID_TIMEFRAMES, key=lambda t: (
                {"m": 0, "h": 1, "d": 2}.get(t[-1], 3), int(t[:-1])
            )),
        },
    )


@app.post("/settings/config", response_class=HTMLResponse)
def save_config(
    request: Request,
    mode: str = Form(...),
    market_type: str = Form(...),
    timeframe: str = Form(...),
    polling_interval_seconds: int = Form(...),
    candle_limit: int = Form(...),
    risk_per_trade: float = Form(...),
    stop_loss_pct: float = Form(...),
    take_profit_pct: float = Form(...),
    trailing_stop_pct: float = Form(...),
    trailing_stop_activation_pct: float = Form(...),
    max_open_positions: int = Form(...),
    daily_loss_limit_enabled: str = Form("true"),
    daily_loss_limit_pct: float = Form(...),
    confidence_threshold: int = Form(...),
    max_risk_level: str = Form(...),
    break_even_trigger_pct: float = Form(...),
    starting_balance: float = Form(...),
    fee_pct: float = Form(...),
    # Consensus
    strong_buy_threshold: float = Form(...),
    buy_threshold: float = Form(...),
    sell_threshold: float = Form(...),
    strong_sell_threshold: float = Form(...),
    min_active_signals: int = Form(...),
    conflict_ratio_threshold: float = Form(...),
    # No-trade
    adx_min: float = Form(...),
    atr_high_percentile: float = Form(...),
    min_confidence: float = Form(...),
    # Leverage
    leverage_enabled: str = Form("true"),
    leverage_scale_ratio: float = Form(0.80),
    leverage_min: int = Form(2),
):
    """Save general config from the settings form."""
    config = _read_config()

    # Validate timeframe
    if timeframe not in VALID_TIMEFRAMES:
        return RedirectResponse("/settings?saved=error_timeframe", status_code=303)

    config["mode"] = mode
    config["market_type"] = market_type
    config["timeframe"] = timeframe
    config["polling_interval_seconds"] = polling_interval_seconds
    config["candle_limit"] = candle_limit

    config.setdefault("risk", {})
    config["risk"]["risk_per_trade"] = risk_per_trade
    config["risk"]["stop_loss_pct"] = stop_loss_pct
    config["risk"]["take_profit_pct"] = take_profit_pct
    config["risk"]["trailing_stop_pct"] = trailing_stop_pct
    config["risk"]["trailing_stop_activation_pct"] = trailing_stop_activation_pct
    config["risk"]["max_open_positions"] = max_open_positions
    config["risk"]["daily_loss_limit_enabled"] = daily_loss_limit_enabled.lower() in ("true", "1")
    config["risk"]["daily_loss_limit_pct"] = daily_loss_limit_pct
    config["risk"]["confidence_threshold"] = confidence_threshold
    config["risk"]["max_risk_level"] = max_risk_level
    config["risk"]["break_even_trigger_pct"] = break_even_trigger_pct

    config.setdefault("paper", {})
    config["paper"]["starting_balance"] = starting_balance
    config["paper"]["fee_pct"] = fee_pct

    config.setdefault("consensus", {})
    config["consensus"]["strong_buy_threshold"] = strong_buy_threshold
    config["consensus"]["buy_threshold"] = buy_threshold
    config["consensus"]["sell_threshold"] = sell_threshold
    config["consensus"]["strong_sell_threshold"] = strong_sell_threshold
    config["consensus"]["min_active_signals"] = min_active_signals
    config["consensus"]["conflict_ratio_threshold"] = conflict_ratio_threshold

    config.setdefault("no_trade", {})
    config["no_trade"]["adx_min"] = adx_min
    config["no_trade"]["atr_high_percentile"] = atr_high_percentile
    config["no_trade"]["min_confidence"] = min_confidence

    config.setdefault("leverage", {})
    config["leverage"]["enabled"] = leverage_enabled.lower() == "true"
    config["leverage"]["scale_ratio"] = leverage_scale_ratio
    config["leverage"]["min_leverage"] = leverage_min

    _write_config(config)
    return RedirectResponse("/settings?saved=config", status_code=303)


@app.post("/settings/weights", response_class=HTMLResponse)
async def save_weights(request: Request):
    """Save indicator weights from the settings form."""
    form = await request.form()
    config = _read_config()
    config.setdefault("indicator_weights", {})

    for key in config["indicator_weights"]:
        form_key = f"weight_{key}"
        if form_key in form:
            try:
                config["indicator_weights"][key] = float(form[form_key])
            except ValueError:
                pass

    _write_config(config)
    return RedirectResponse("/settings?saved=weights", status_code=303)


@app.post("/settings/env", response_class=HTMLResponse)
def save_env(
    request: Request,
    binance_api_key: str = Form(default=""),
    binance_api_secret: str = Form(default=""),
    binance_testnet_api_key: str = Form(default=""),
    binance_testnet_api_secret: str = Form(default=""),
    use_testnet: str = Form(default="false"),
):
    """Save .env API keys."""
    current = _read_env()

    # Only update if a non-masked value is provided
    def _update(field: str, new_val: str) -> str:
        if not new_val or "*" in new_val:
            return current.get(field, "")
        return new_val

    env_data = {
        "BINANCE_API_KEY": _update("BINANCE_API_KEY", binance_api_key),
        "BINANCE_API_SECRET": _update("BINANCE_API_SECRET", binance_api_secret),
        "BINANCE_TESTNET_API_KEY": _update("BINANCE_TESTNET_API_KEY", binance_testnet_api_key),
        "BINANCE_TESTNET_API_SECRET": _update("BINANCE_TESTNET_API_SECRET", binance_testnet_api_secret),
        "USE_TESTNET": use_testnet,
    }
    _write_env(env_data)
    return RedirectResponse("/settings?saved=env", status_code=303)


@app.post("/settings/symbol", response_class=HTMLResponse)
def save_symbol(
    request: Request,
    symbol: str = Form(...),
):
    """Update active trading symbol."""
    symbol = symbol.strip().upper()
    if not re.match(r"^[\w]{2,30}$", symbol, re.UNICODE):
        return RedirectResponse("/settings?saved=error_symbol", status_code=303)
    SYMBOL_FILE.parent.mkdir(parents=True, exist_ok=True)
    SYMBOL_FILE.write_text(symbol + "\n")
    return RedirectResponse("/settings?saved=symbol", status_code=303)
