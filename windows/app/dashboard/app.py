"""FastAPI dashboard for the trading bot.

Reads:
- runtime/dashboard_status.json (bot status snapshot)
- runtime/bot.log (log tail)
- runtime/state.json (fallback)

Settings page allows:
- Editing config/default.yaml
- Changing active symbol
- (S7) .env editing is REMOVED — managed on disk only, see .env.example
"""

import json
from copy import deepcopy
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import yaml
from fastapi import FastAPI, Request, Query, Form
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, FileResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from src.monitoring.metrics import compute_daily_summary
from src.utils.validators import RESCUE_RISK_PER_TRADE_MAX, validate_config, validate_symbol

# Resolve paths
def _resolve_project_root() -> Path:
    """Resolve writable project root for dashboard/runtime files.

    In packaged mode, prefer the external ``app`` next to the exe so runtime
    state/log files stay on disk instead of the read-only PyInstaller temp.
    """

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

    default_root = Path(__file__).resolve().parent.parent
    if hasattr(sys, "_MEIPASS"):
        exe_root = Path(sys.executable).resolve().parent
        external = exe_root / "app"
        if _is_valid_app_root(external):
            return external
        internal = Path(sys._MEIPASS) / "app"  # type: ignore[attr-defined]
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
    return default_root


PROJECT_ROOT = _resolve_project_root()
DASHBOARD_DIR = PROJECT_ROOT / "dashboard"
RUNTIME_DIR = PROJECT_ROOT / "runtime"
STATUS_FILE = RUNTIME_DIR / "dashboard_status.json"
STATE_FILE = RUNTIME_DIR / "state.json"
LOG_FILE = RUNTIME_DIR / "bot.log"
CONFIG_FILE = PROJECT_ROOT / "config" / "default.yaml"
ENV_FILE = PROJECT_ROOT / ".env"
SYMBOL_FILE = RUNTIME_DIR / "active_symbol.txt"
COMMAND_QUEUE_FILE = RUNTIME_DIR / "command_queue.json"
PID_FILE = RUNTIME_DIR / "bot.pid"

# Lazy-imported command queue so dashboard tests can monkeypatch the path.
sys.path.insert(0, str(PROJECT_ROOT))
from src.persistence.command_queue import CommandQueue  # noqa: E402
from src.persistence.schemas import CommandKind  # noqa: E402
from src.utils.logger import get_logger  # noqa: E402

logger = get_logger("dashboard")


def _as_project_path(value: str | Path | None, fallback: Path | None = None) -> Path:
    """Resolve a file path relative to project root when not absolute."""
    if fallback is None:
        fallback = PROJECT_ROOT
    if value is None:
        return fallback
    try:
        p = Path(value)
    except Exception:
        return fallback
    if not p.is_absolute():
        return PROJECT_ROOT / p
    return p


def _sync_runtime_paths_from_config() -> None:
    """Synchronize runtime file paths from dashboard configuration.

    This keeps dashboard reads/writes aligned with bot-side
    ``dashboard_status_path`` when operators use alternate runtime
    directories.
    """
    global RUNTIME_DIR, STATUS_FILE, STATE_FILE, LOG_FILE, SYMBOL_FILE, PID_FILE, COMMAND_QUEUE_FILE
    global _MULTI_SCAN_FILE, _MANUAL_SCAN_LOCK

    config = _read_config()
    fallback_runtime_dir = PROJECT_ROOT / "runtime"
    fallback_status_path = fallback_runtime_dir / "dashboard_status.json"

    def _as_runtime_dir(value: Any) -> Path:
        if not isinstance(value, (str, Path)):
            return fallback_runtime_dir
        text = str(value).strip()
        if not text or text in {"."}:
            return fallback_runtime_dir
        p = _as_project_path(value)
        if p.suffix:
            return p.parent
        return p

    def _as_runtime_file(value: Any, default_name: str, *, fallback: Path) -> Path:
        if not isinstance(value, (str, Path)):
            return fallback
        text = str(value).strip()
        if not text or text in {"."}:
            return fallback
        p = _as_project_path(value)
        if p.suffix:
            return p
        return p / default_name

    runtime_dir = _as_runtime_dir(config.get("dashboard_status_path"))
    if runtime_dir == Path("."):
        runtime_dir = fallback_runtime_dir

    RUNTIME_DIR = runtime_dir
    STATUS_FILE = _as_runtime_file(
        config.get("dashboard_status_path"),
        "dashboard_status.json",
        fallback=fallback_status_path,
    )
    STATE_FILE = _as_runtime_file(
        config.get("state_path"),
        "state.json",
        fallback=runtime_dir / "state.json",
    )
    LOG_FILE = _as_runtime_file(
        config.get("log_path"),
        "bot.log",
        fallback=runtime_dir / "bot.log",
    )
    SYMBOL_FILE = _as_runtime_file(
        config.get("active_symbol_path"),
        "active_symbol.txt",
        fallback=runtime_dir / "active_symbol.txt",
    )
    PID_FILE = _as_runtime_file(
        config.get("pid_path"),
        "bot.pid",
        fallback=runtime_dir / "bot.pid",
    )
    COMMAND_QUEUE_FILE = _as_runtime_file(
        config.get("command_queue_path"),
        "command_queue.json",
        fallback=runtime_dir / "command_queue.json",
    )
    _MULTI_SCAN_FILE = runtime_dir / "multi_scan_results.json"
    _MANUAL_SCAN_LOCK = runtime_dir / "manual_scan_active.json"


def _get_command_queue() -> CommandQueue:
    """Return a CommandQueue aligned with bot runtime path configuration."""
    _sync_runtime_paths_from_config()
    config = _read_config()
    runtime_dir = RUNTIME_DIR
    def _as_runtime_file(value: Any, default_name: str, *, fallback: Path) -> Path:
        if not isinstance(value, (str, Path)):
            return fallback
        text = str(value).strip()
        if not text or text in {"."}:
            return fallback
        p = _as_project_path(value)
        if p.suffix:
            return p
        return p / default_name

    configured_path = config.get("command_queue_path")
    if isinstance(configured_path, (str, Path)) and str(configured_path).strip():
        return CommandQueue(_as_runtime_file(
            configured_path,
            "command_queue.json",
            fallback=COMMAND_QUEUE_FILE,
        ))

    return CommandQueue(runtime_dir / "command_queue.json")


def _build_bot_launch_commands(bot_config_path: str) -> list[list[str]]:
    """Build Windows-safe launch candidates for the bot worker process."""
    launch_candidates: list[list[str]] = []
    launcher_arg = "--run-bot"

    def _looks_like_packaged_launcher_exe(candidate: Path) -> bool:
        if not candidate.is_file():
            return False
        if candidate.suffix.lower() != ".exe":
            return False
        exe_name = candidate.name.lower()
        if exe_name.startswith("python") or exe_name in {"py.exe", "pyw.exe", "uvicorn.exe"}:
            return False
        app_dir = candidate.parent / "app"
        return app_dir.exists() and (app_dir / "src" / "main.py").is_file() and (app_dir / "dashboard" / "app.py").is_file()

    if _is_windows():
        exe_path = Path(sys.executable).resolve()
        if _is_packaged_runtime() or _looks_like_packaged_launcher_exe(exe_path):
            launch_candidates.append([str(exe_path), launcher_arg, bot_config_path])

        if not launch_candidates:
            exe_name = exe_path.name.lower()
            is_python_executable = exe_name.startswith("python") or exe_name in {"py", "py.exe", "pyw.exe", "pythonw.exe", "python3.exe"}
            if is_python_executable:
                # Keep interpreter fallback for non-packaged dev/debug runs.
                launch_candidates.append([sys.executable, "-m", "src.main", bot_config_path])
            else:
                # Packaged exe-style launchers should never hit a non-packaged fallback.
                # Surface this as a hard launch failure with the clear command list.
                launch_candidates.append([str(exe_path), launcher_arg, bot_config_path])
                launch_candidates.append(["python", "-m", "src.main", bot_config_path])
                launch_candidates.append(["python3", "-m", "src.main", bot_config_path])

    else:
        launch_candidates = [[sys.executable, "-m", "src.main", bot_config_path]]

    return launch_candidates


def _resolve_favicon_path() -> Path | None:
    """Return an existing favicon path from likely runtime locations."""
    project_roots = [
        DASHBOARD_DIR / "static" / "favicon.ico",
        Path(__file__).resolve().parent / "static" / "favicon.ico",
        PROJECT_ROOT / "dashboard" / "static" / "favicon.ico",
        Path(sys.executable).resolve().parent / "tbv1.ico",
        Path(sys.executable).resolve().parent / "tbv1_256.png",
        Path(sys.executable).resolve().parent / "packaging" / "tbv1.ico",
        Path(sys.executable).resolve().parent / "packaging" / "tbv1_256.png",
        Path(__file__).resolve().parent.parent.parent / "packaging" / "tbv1.ico",
        Path(__file__).resolve().parent.parent.parent / "packaging" / "tbv1_256.png",
    ]

    for candidate in project_roots:
        try:
            if candidate.exists() and candidate.is_file():
                return candidate
        except Exception as exc:
            logger.debug(f"Skipping favicon candidate {candidate}: {exc}")

    for base in (Path(sys.executable).resolve().parent, Path(sys.executable).resolve().parent.parent):
        try:
            candidate = base / "app" / "dashboard" / "static" / "favicon.ico"
            if candidate.exists() and candidate.is_file():
                return candidate
        except Exception as exc:
            logger.debug(f"Skipping favicon candidate {candidate}: {exc}")
        try:
            candidate = base / "app" / "tbv1.ico"
            if candidate.exists() and candidate.is_file():
                return candidate
            candidate = base / "app" / "tbv1_256.png"
            if candidate.exists() and candidate.is_file():
                return candidate
        except Exception as exc:
            logger.debug(f"Skipping favicon candidate {candidate}: {exc}")
    return None


def _terminate_process_with_reap(proc: subprocess.Popen[bytes], timeout: float = 2.0) -> bool:
    """Terminate a best-effort started process and reap resources."""
    try:
        proc.terminate()
    except Exception:
        logger.warning("Failed to terminate process during cleanup", exc_info=True)
        return False

    try:
        proc.wait(timeout=timeout)
        return True
    except Exception:
        logger.debug("Process did not exit after terminate; escalating to kill")

    try:
        proc.kill()
    except Exception:
        logger.warning("Failed to kill process during cleanup", exc_info=True)
        return False
    try:
        proc.wait(timeout=timeout)
    except Exception:
        logger.warning("Failed to reap process after kill", exc_info=True)
        return False
    return True

app = FastAPI(title="Trading Bot Dashboard", docs_url=None, redoc_url=None)


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    """Serve favicon from dashboard static assets."""
    favicon_path = _resolve_favicon_path()
    if favicon_path is not None:
        media_type = "image/png" if favicon_path.suffix.lower() == ".png" else "image/x-icon"
        return FileResponse(path=favicon_path, media_type=media_type)
    return Response(status_code=404)


@app.middleware("http")
async def catch_all_errors(request: Request, call_next):
    """Global error handler — prevent any unhandled exception from crashing the server."""
    _sync_runtime_paths_from_config()
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
        dt = _parse_iso_datetime(iso_str)
        return dt.strftime("%d.%m.%Y %H:%M:%S") if dt else str(iso_str)
    except Exception:
        return str(iso_str)


def _parse_iso_datetime(value: Any) -> datetime | None:
    """Parse ISO timestamps and normalize them to UTC-aware datetime objects."""
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


templates.env.filters["format_dt"] = _format_dt_early
templates.env.globals["format_dt"] = _format_dt_early


# ─── Helpers ────────────────────────────────────────────────────────

def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            raise ValueError(f"expected JSON object, got {type(data).__name__}")
        return data
    except Exception as exc:
        logger.warning(f"Failed to read JSON file {path}: {exc}")
        try:
            path.unlink()
        except Exception as cleanup_exc:
            logger.warning(f"Failed to remove stale JSON file {path}: {cleanup_exc}")
        return {
            "_json_error": str(exc),
            "_json_error_path": str(path),
        }


def _status_warning_list(payload: dict[str, Any]) -> list[str]:
    warnings = payload.get("status_warnings")
    if isinstance(warnings, list):
        return warnings
    if warnings is None:
        warnings_list: list[str] = []
    else:
        warnings_list = [str(warnings)]
    payload["status_warnings"] = warnings_list
    return warnings_list


def _normalize_dashboard_status(payload: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}

    warnings = _status_warning_list(payload)

    def ensure_dict(key: str) -> None:
        value = payload.get(key)
        if value is None:
            payload[key] = {}
            return
        if isinstance(value, dict):
            return
        warnings.append(f"{key} has invalid type {type(value).__name__}; using empty object")
        payload[key] = {}

    def ensure_list(key: str) -> None:
        value = payload.get(key)
        if value is None:
            payload[key] = []
            return
        if isinstance(value, list):
            return
        warnings.append(f"{key} has invalid type {type(value).__name__}; using empty list")
        payload[key] = []

    ensure_dict("latest_decision")
    ensure_dict("performance")
    ensure_dict("signal_distribution")
    ensure_list("open_positions")
    ensure_list("indicator_votes")
    ensure_list("trade_history")
    ensure_list("last_scan_results")

    active_symbol = payload.get("active_symbol")
    if active_symbol is None:
        payload["active_symbol"] = None
    elif isinstance(active_symbol, str) and validate_symbol(active_symbol):
        payload["active_symbol"] = active_symbol.strip().upper()
    else:
        warnings.append(
            f"active_symbol has invalid type {type(active_symbol).__name__}; using empty value"
        )
        payload["active_symbol"] = None

    last_auto_scan = payload.get("last_auto_scan")
    if last_auto_scan is not None and not isinstance(last_auto_scan, str):
        warnings.append(
            f"last_auto_scan has invalid type {type(last_auto_scan).__name__}; using empty value"
        )
        payload["last_auto_scan"] = None

    for key in ("last_scan_total", "last_scan_hot_count"):
        value = payload.get(key)
        if value is None:
            payload[key] = 0
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            warnings.append(f"{key} has invalid type {type(value).__name__}; using 0")
            payload[key] = 0

    return payload


def _normalize_active_coin_signals(payload: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}

    warnings = _status_warning_list(payload)
    timeframes = payload.get("timeframes")
    if timeframes is None:
        payload["timeframes"] = {}
    elif not isinstance(timeframes, dict):
        warnings.append(
            f"timeframes has invalid type {type(timeframes).__name__}; using empty object"
        )
        payload["timeframes"] = {}

    updated_at = payload.get("updated_at")
    if updated_at is not None and not isinstance(updated_at, str):
        warnings.append(
            f"updated_at has invalid type {type(updated_at).__name__}; using empty value"
        )
        payload["updated_at"] = None

    return payload


_log_read_error: str | None = None


def _read_log_tail(n: int = 200, level: Optional[str] = None, search: Optional[str] = None) -> list[dict[str, str]]:
    """Read last N lines, optionally filter by level / search."""
    global _log_read_error
    if not LOG_FILE.exists():
        _log_read_error = None
        return []
    try:
        with open(LOG_FILE, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        _log_read_error = None
    except Exception:
        _log_read_error = f"Failed to read log file {LOG_FILE}"
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
    dt = _parse_iso_datetime(last)
    if dt is None:
        return True
    return (datetime.now(timezone.utc) - dt).total_seconds() > 300


# S3: a live-tick that hasn't been refreshed in more than this many
# milliseconds counts as 'snapshot', even if the bot says it's running.
# Conservative default: 5 minutes — same as _is_stale's threshold so the
# two staleness signals don't disagree at the edge.
_PRICE_AGE_STALE_MS = 5 * 60 * 1000


def _price_display(status: dict, bot_running: Optional[bool] = None) -> dict:
    """Canonical price display contract.

    Source priority:
      1. ``display_price`` — the live tick from LivePriceService (S3+).
      2. ``current_price`` — legacy field (pre-S3 snapshots). For S3+ the
         exporter mirrors display_price into current_price so this branch
         is redundant; it stays for backward compatibility with snapshots
         written by older builds.

    The dashboard's live-price element MUST NEVER substitute
    ``latest_decision.price`` — that field is decision metadata, captured
    at decision time and stale by definition between cycles.

    Returns ``{text, state, is_live, raw}``:
      - state="live"        → numeric price + bot running + not stale + fresh tick
      - state="snapshot"    → numeric price but bot stopped, data stale, or tick stale
      - state="unavailable" → price missing/null/<=0 (no honest number to show)
    """
    raw = status.get("display_price")
    if raw is None:
        raw = status.get("current_price")
    try:
        v = float(raw) if raw is not None else None
    except (TypeError, ValueError):
        v = None

    bot_status = status.get("bot_status")
    bot_stopped = (bot_running is False) or (bot_running is None and bot_status != "running")
    stale = _is_stale(status)

    # An old live tick must downgrade to 'snapshot' even when the rest of
    # the snapshot looks fresh. price_age_ms is set by the bot from the
    # LivePriceService cache; None means 'no info' which is not used as a
    # staleness signal (the bot might not be running LivePriceService yet).
    age_ms = status.get("price_age_ms")
    try:
        age_int = int(age_ms) if age_ms is not None else None
    except (TypeError, ValueError):
        age_int = None
    tick_stale = age_int is not None and age_int > _PRICE_AGE_STALE_MS

    if v is None or v <= 0:
        return {"text": "No Data", "state": "unavailable", "is_live": False, "raw": None}

    if stale or bot_stopped or tick_stale:
        return {
            "text": "$" + _fmt_price(v),
            "state": "snapshot",
            "is_live": False,
            "raw": v,
        }

    return {
        "text": "$" + _fmt_price(v),
        "state": "live",
        "is_live": True,
        "raw": v,
    }


def _time_ago(iso_str: str) -> str:
    if not iso_str:
        return "N/A"
    dt = _parse_iso_datetime(iso_str)
    if dt is None:
        return iso_str
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


def _format_dt(iso_str: str) -> str:
    """Format ISO datetime as 'DD.MM.YYYY HH:MM:SS'."""
    if not iso_str:
        return "—"
    try:
        dt = _parse_iso_datetime(iso_str)
        return dt.strftime("%d.%m.%Y %H:%M:%S") if dt else str(iso_str)
    except Exception:
        return iso_str


# Register template globals AND filters (double-register for reliability)
templates.env.globals["time_ago"] = _time_ago
templates.env.globals["format_dt"] = _format_dt
templates.env.globals["is_stale"] = _is_stale
templates.env.globals["price_display"] = _price_display
templates.env.filters["format_dt"] = _format_dt
templates.env.filters["time_ago"] = _time_ago


# ─── JSON API endpoints (for JS polling) ──────────────────────────

@app.get("/api/status", response_class=JSONResponse)
def api_status():
    """Return full dashboard status JSON (read-only)."""
    global _live_signal_error
    status = _read_json(STATUS_FILE)
    warnings: list[str] = []
    read_error = status.pop("_json_error", None)
    if read_error:
        warnings.append(f"Failed to read dashboard_status.json: {read_error}")
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            warnings.append(f"Failed to read state.json: {fallback_error}")
    elif not status:
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            warnings.append(f"Failed to read state.json: {fallback_error}")
    status = _normalize_dashboard_status(status)
    if warnings:
        _status_warning_list(status).extend(warnings)
    if _live_signal_error:
        _status_warning_list(status).append(
            f"Live signal refresh failed: {_live_signal_error}"
        )
    status["_stale"] = _is_stale(status)
    status["_bot_running"] = _resolve_bot_running(status)
    status["_price_display"] = _price_display(status, bot_running=status["_bot_running"])
    return status


@app.get("/api/active-coin-signals", response_class=JSONResponse)
def api_active_coin_signals():
    """Return live multi-TF signals for the currently active coin.

    Primary source: active_coin_signals.json (written by bot each cycle).
    Fallback: extract from auto-scan results if the active coin is in top 5.
    """
    # Trigger background calculation if needed (gated by
    # ``dashboard_fallback_enabled`` — see ``_ensure_live_signals``).
    _ensure_live_signals()
    global _live_signal_error

    active_symbol = None
    if SYMBOL_FILE.exists():
        try:
            active_symbol = SYMBOL_FILE.read_text(encoding="utf-8").strip().upper()
            if not validate_symbol(active_symbol):
                logger.warning(f"Invalid active symbol file contents: {active_symbol!r}")
                try:
                    SYMBOL_FILE.unlink()
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to remove invalid active symbol file after read error: {cleanup_exc}"
                    )
                active_symbol = None
        except Exception as exc:
            logger.warning(f"Failed to read active symbol file {SYMBOL_FILE}: {exc}")
            try:
                SYMBOL_FILE.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale active symbol file after read error: {cleanup_exc}"
                )
    if not active_symbol:
        status = _read_json(STATUS_FILE)
        status_error = status.pop("_json_error", None)
        if status_error or not status:
            if status_error:
                _live_signal_error = f"status file unreadable: {status_error}"
            fallback = _read_json(STATE_FILE)
            fallback_error = fallback.pop("_json_error", None)
            if fallback and not fallback_error:
                status = _normalize_dashboard_status(fallback)
            elif fallback_error:
                _live_signal_error = f"state file unreadable: {fallback_error}"
        active_symbol = status.get("active_symbol") if isinstance(status, dict) else None
        if active_symbol is not None and not isinstance(active_symbol, str):
            active_symbol = str(active_symbol)
        if active_symbol and not validate_symbol(active_symbol):
            _live_signal_error = f"invalid active symbol from status/state: {active_symbol!r}"
            active_symbol = None

    # Primary: bot-written file (or dashboard-calculated). S6: every
    # return path labels ``_source`` so the dashboard UI can tell apart
    # authoritative bot-owned data from fallbacks and render staleness
    # honestly.
    data = _normalize_active_coin_signals(_read_json(RUNTIME_DIR / "active_coin_signals.json"))
    signal_error = data.pop("_json_error", None)
    if signal_error:
        _live_signal_error = f"active_coin_signals.json unreadable: {signal_error}"
    timeframes = data.get("timeframes") if isinstance(data, dict) else {}
    if (
        active_symbol
        and data
        and data.get("symbol") == active_symbol
        and isinstance(timeframes, dict)
        and len(timeframes) >= 3
    ):
        data.setdefault("_source", "bot_owned")
        if signal_error:
            _live_signal_error = f"active_coin_signals.json unreadable: {signal_error}"
        if _live_signal_error:
            _status_warning_list(data).append(_live_signal_error)
        elif not signal_error:
            _live_signal_error = None
        return data

    # Fallback: extract from auto-scan or multi-scan results
    if not active_symbol:
        payload = {"symbol": None, "timeframes": {}, "updated_at": None,
                   "_source": "empty"}
        if _live_signal_error:
            payload["status_warnings"] = [_live_signal_error]
        return payload

    # Try auto-scan progress (has all_signals for top coins)
    scan_data = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    scan_error = scan_data.pop("_json_error", None)
    scan_warnings: list[str] = []
    if scan_error:
        _live_signal_error = f"auto_scan_progress.json unreadable: {scan_error}"
        scan_warnings.append(_live_signal_error)
    auto_scan_time = scan_data.get("last_auto_scan") if isinstance(scan_data, dict) else None
    scan_results = scan_data.get("last_scan_results", [])
    if not isinstance(scan_results, list):
        _live_signal_error = (
            f"auto_scan_progress.json last_scan_results has invalid type {type(scan_results).__name__}"
        )
        scan_warnings.append(_live_signal_error)
        scan_results = []
    multi = _read_json(RUNTIME_DIR / "multi_scan_results.json")
    multi_error = multi.pop("_json_error", None)
    if multi_error:
        scan_warnings.append(f"multi_scan_results.json unreadable: {multi_error}")
    multi_scan_time = multi.get("scan_time") if isinstance(multi, dict) else None
    multi_results = multi.get("cross_ranking", [])
    if not isinstance(multi_results, list):
        _live_signal_error = (
            f"multi_scan_results.json cross_ranking has invalid type {type(multi_results).__name__}"
        )
        scan_warnings.append(_live_signal_error)
        multi_results = []
    auto_dt = _parse_iso_datetime(auto_scan_time) if isinstance(auto_scan_time, str) else None
    multi_dt = _parse_iso_datetime(multi_scan_time) if isinstance(multi_scan_time, str) else None
    if isinstance(multi_results, list) and (not scan_results or (multi_dt is not None and (auto_dt is None or multi_dt > auto_dt))):
        scan_results = multi_results

    for coin in scan_results:
        if not isinstance(coin, dict):
            continue
        if coin.get("symbol") == active_symbol:
            all_sigs = coin.get("all_signals", {})
            if not isinstance(all_sigs, dict):
                all_sigs = {}
            sigs_dict = coin.get("signals", {})
            if not isinstance(sigs_dict, dict):
                sigs_dict = {}
            tfs = {}
            for tf in _MULTI_TFS:
                info = all_sigs.get(tf) or sigs_dict.get(tf)
                if not isinstance(info, dict):
                    continue
                if info:
                    tfs[tf] = {
                        "signal": info.get("signal", "N/A"),
                        "confidence": info.get("confidence", 0),
                        "risk_level": info.get("risk_level", "N/A"),
                    }
            if tfs:
                payload = {
                    "symbol": active_symbol,
                    "timeframes": tfs,
                    "updated_at": (multi_scan_time if scan_results is multi_results else auto_scan_time) or scan_data.get("completed_at"),
                    "_source": "auto_scan_fallback",
                }
                if scan_warnings:
                    _status_warning_list(payload).extend(scan_warnings)
                if _live_signal_error:
                    _status_warning_list(payload).append(_live_signal_error)
                return _normalize_active_coin_signals(payload)

    # Last resort: return current TF signal from dashboard status
    status = _read_json(STATUS_FILE)
    status_error = status.pop("_json_error", None)
    if status_error:
        _live_signal_error = f"dashboard_status.json unreadable: {status_error}"
    decision = status.get("latest_decision", {})
    if not isinstance(decision, dict):
        decision = {}
    current_tf = status.get("timeframe", "4h")
    if decision.get("signal"):
        payload = {
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
        if _live_signal_error:
            _status_warning_list(payload).append(_live_signal_error)
        return _normalize_active_coin_signals(payload)

    payload = {"symbol": active_symbol, "timeframes": {}, "updated_at": None,
               "_source": "no_data"}
    if _live_signal_error:
        payload["status_warnings"] = [_live_signal_error]
    return _normalize_active_coin_signals(payload)


# Background live signal calculator for dashboard
import threading as _sig_threading

_live_signal_lock = _sig_threading.Lock()
_live_signal_thread: _sig_threading.Thread | None = None
_live_signal_error: str | None = None


def _calc_live_signals_bg():
    """Background thread: calculate active coin signals across all 12 TFs."""
    global _live_signal_thread, _live_signal_error
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
            symbol = SYMBOL_FILE.read_text(encoding="utf-8").strip().upper()
            if not validate_symbol(symbol):
                _live_signal_error = f"invalid active symbol file contents: {symbol!r}"
                logger.warning(_live_signal_error)
                try:
                    SYMBOL_FILE.unlink()
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to remove invalid active symbol file after read error: {cleanup_exc}"
                    )
                symbol = None
        if not symbol:
            status = _read_json(STATUS_FILE)
            status_error = status.pop("_json_error", None)
            if status_error or not status:
                fallback = _read_json(STATE_FILE)
                fallback_error = fallback.pop("_json_error", None)
                if fallback and not fallback_error:
                    status = _normalize_dashboard_status(fallback)
                elif fallback_error:
                    _live_signal_error = f"state file unreadable: {fallback_error}"
            symbol = status.get("active_symbol") if isinstance(status, dict) else None
            if symbol is not None and not isinstance(symbol, str):
                symbol = str(symbol)
            if symbol and not validate_symbol(symbol):
                _live_signal_error = f"invalid active symbol from status/state: {symbol!r}"
                symbol = None
        if not symbol:
            _live_signal_error = "no active symbol available for live signal refresh"
            logger.warning(_live_signal_error)
            return

        client = BinanceClient(config)
        client.initialize()

        results = {}
        failure_count = 0
        for tf in _MULTI_TFS:
            try:
                tf_config = {**config, "timeframe": tf}
                md = MarketDataProvider(client, tf_config)
                df = md.get_ohlcv(symbol)
                if df is None or df.empty:
                    results[tf] = {"signal": "N/A", "confidence": 0, "risk_level": "N/A"}
                    failure_count += 1
                    continue
                svc = SignalService(tf_config)
                indicators = svc.calculate_all(df)
                consensus = ConsensusEngine(tf_config).evaluate(indicators)
                conf = consensus["confidence"]
                zak_val = ZAK.get(tf, 50)
                results[tf] = {
                    "signal": consensus["final_signal"],
                    "confidence": conf,
                    "risk_level": consensus["risk_level"],
                    "zak": zak_val,
                    "final_score": round((conf ** 2) * (zak_val / 100), 2),
                }
            except Exception:
                results[tf] = {"signal": "N/A", "confidence": 0, "risk_level": "N/A"}
                failure_count += 1

        if failure_count >= len(_MULTI_TFS):
            _live_signal_error = "all live signal timeframe calculations failed"
            logger.warning(_live_signal_error)
            from src.persistence.atomic_io import atomic_write_json
            out = RUNTIME_DIR / "active_coin_signals.json"
            atomic_write_json(
                out,
                {
                    "symbol": None,
                    "timeframes": {},
                    "updated_at": iso_now(),
                    "_source": "error",
                    "error": True,
                    "status_warnings": [_live_signal_error],
                },
                indent=2,
            )
            return

        data = {
            "symbol": symbol,
            "timeframes": results,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        out = RUNTIME_DIR / "active_coin_signals.json"
        from src.persistence.atomic_io import atomic_write_json
        atomic_write_json(out, data, indent=2)
        _live_signal_error = (
            None if failure_count == 0 else f"{failure_count}/{len(_MULTI_TFS)} live signal timeframe calculations failed"
        )
    except Exception as exc:
        _live_signal_error = str(exc)
        logger.warning(f"Live signal refresh failed: {exc}", exc_info=True)
        try:
            out = RUNTIME_DIR / "active_coin_signals.json"
            if out.exists():
                out.unlink()
        except Exception as cleanup_exc:
            logger.warning(
                f"Failed to remove stale active coin signals after write error: {cleanup_exc}"
            )


def _ensure_live_signals():
    """Trigger background calculation if file is missing or stale (>2 min).

    S6: gated by ``dashboard_fallback_enabled``. When the bot is the
    trusted writer of ``active_coin_signals.json``, keep this ``False``
    so the dashboard does not race the bot with a duplicate Binance pass.
    """
    global _live_signal_thread, _live_signal_error
    try:
        if not _read_config().get("dashboard_fallback_enabled", False):
            return
    except Exception as exc:
        _live_signal_error = f"dashboard fallback gate failed: {exc}"
        return
    if _live_signal_thread and _live_signal_thread.is_alive():
        return  # already running
    sig_file = RUNTIME_DIR / "active_coin_signals.json"
    need_calc = False
    if not sig_file.exists():
        need_calc = True
    else:
        try:
            d = _read_json(sig_file)
            ts = d.get("updated_at", "")
            if ts:
                lu = _parse_iso_datetime(ts)
                if lu is None:
                    need_calc = True
                else:
                    age = (datetime.now(timezone.utc) - lu).total_seconds()
                    if age > 120:  # older than 2 minutes
                        need_calc = True
            else:
                need_calc = True
            # Recalc if fewer than 12 TFs (old data or fallback)
            timeframes = d.get("timeframes", {})
            if not isinstance(timeframes, dict) or len(timeframes) < len(_MULTI_TFS):
                need_calc = True
            # Recalc if active symbol changed
            current_symbol = None
            if SYMBOL_FILE.exists():
                try:
                    current_symbol = SYMBOL_FILE.read_text(encoding="utf-8").strip().upper()
                except Exception as exc:
                    logger.warning(f"Failed to read active symbol file {SYMBOL_FILE}: {exc}")
                    try:
                        SYMBOL_FILE.unlink()
                    except Exception as cleanup_exc:
                        logger.warning(
                            f"Failed to remove stale active symbol file after read error: {cleanup_exc}"
                        )
            if current_symbol and d.get("symbol") != current_symbol:
                need_calc = True
        except Exception:
            need_calc = True
    if need_calc:
        try:
            _live_signal_thread = _sig_threading.Thread(target=_calc_live_signals_bg, daemon=True)
            _live_signal_thread.start()
        except Exception as exc:
            _live_signal_error = f"failed to start live signal refresh thread: {exc}"
            logger.warning(_live_signal_error)


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

# ZAK — Timeframe Weight Coefficient
ZAK = {
    "1d": 95, "12h": 90, "8h": 85, "6h": 80, "4h": 75,
    "2h": 65, "1h": 58, "30m": 48, "15m": 38, "5m": 25,
    "3m": 15, "1m": 8,
}

def _calc_nss(confidence: float, tf: str) -> float:
    """Net Signal Score (NSS) = (confidence²) × (ZAK / 100)."""
    return round((confidence ** 2) * (ZAK.get(tf, 50) / 100), 2)

def _read_config() -> dict[str, Any]:
    """Read the YAML config file."""
    global _config_read_error
    if not CONFIG_FILE.exists():
        _config_read_error = None
        return {}
    try:
        raw = yaml.safe_load(CONFIG_FILE.read_text(encoding="utf-8"))
        if isinstance(raw, dict):
            config = raw
            invalid_sections: list[str] = []
            for section in ("risk", "leverage", "paper", "consensus", "no_trade", "indicator_weights"):
                value = config.get(section)
                if value is None or isinstance(value, dict):
                    continue
                invalid_sections.append(f"{section} must be a mapping, got {type(value).__name__}")
                config[section] = {}
            if invalid_sections:
                _config_read_error = "; ".join(invalid_sections)
                logger.error(f"{_config_read_error} at {CONFIG_FILE}")
            else:
                _config_read_error = None
            return config
        _config_read_error = f"Config root must be a mapping, got {type(raw).__name__}"
        logger.error(f"{_config_read_error} at {CONFIG_FILE}")
        return {}
    except Exception as exc:
        _config_read_error = str(exc)
        logger.error(f"Failed to read config {CONFIG_FILE}: {exc}")
        return {}


def _atomic_write_text(path: Path, content: str) -> None:
    """Write text with an atomic replacement."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=str(path.parent),
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError as cleanup_exc:
            sys.stderr.write(f"Warning: failed to remove temp file {tmp_name}: {cleanup_exc}\n")
        raise


def _write_config(config: dict[str, Any]) -> None:
    """Write config back to YAML atomically."""
    _atomic_write_text(
        CONFIG_FILE,
        yaml.dump(config, default_flow_style=False, sort_keys=False, allow_unicode=True),
    )


def _merge_config_updates(base_config: dict[str, Any], updates: dict[str, Any]) -> dict[str, Any]:
    """Merge partial updates into an existing config.

    Behaviors match existing dashboard semantics:
    - root-level keys are replaced when present
    - dict values are merged, not replaced
    - dotted key notation updates nested keys only when parent exists and is dict-like
    """
    merged = deepcopy(base_config)
    for key, value in updates.items():
        if "." in key:
            section, nested_key = key.split(".", 1)
            if isinstance(merged.get(section), dict):
                merged[section][nested_key] = value
            continue

        if key not in merged:
            continue
        if isinstance(merged[key], dict) and isinstance(value, dict):
            merged[key].update(value)
        else:
            merged[key] = value
    return merged


def _validate_config_for_write(config: dict[str, Any]) -> tuple[bool, list[str]]:
    """Validate config before writing and return (is_valid, errors)."""
    errors = validate_config(config)
    return len(errors) == 0, errors


_TRUE_BOOL_VALUES = {"true", "1", "on", "yes"}
_FALSE_BOOL_VALUES = {"false", "0", "off", "no", "disabled"}
_SYMBOL_PATTERN = re.compile(r"^[A-Z0-9]{2,20}$")
_config_read_error: str | None = None
_bot_probe_error: str | None = None


def _parse_bool_form(value: str | None, field_name: str, *, default: bool | None = None) -> bool:
    """Parse a form/API bool string with an explicit allow-list."""
    if value is None:
        if default is not None:
            return default
        raise ValueError(f"Missing value for {field_name}")
    normalized = str(value).strip().lower()
    if normalized in _TRUE_BOOL_VALUES:
        return True
    if normalized in _FALSE_BOOL_VALUES:
        return False
    raise ValueError(f"Invalid value for {field_name}: {value}")


def _normalize_symbol(value: Any, field_name: str) -> str:
    """Normalize and validate symbol text from UI/API input."""
    if value is None:
        raise ValueError(f"Missing value for {field_name}")
    normalized = str(value).strip().upper()
    if not _SYMBOL_PATTERN.fullmatch(normalized):
        raise ValueError(f"Invalid value for {field_name}")
    return normalized


def _read_env() -> dict[str, str]:
    """Parse .env file into key=value dict."""
    result: dict[str, str] = {}
    if not ENV_FILE.exists():
        return result
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            result[k.strip()] = v.strip()
    return result


# S8: ``_write_env`` was removed. Earlier rescue stages disabled the only
# HTTP caller (``POST /settings/env`` returns 403 without parsing the body),
# leaving the helper unreachable from the running app. Deletion is the
# fail-loud quarantine: any future regression that tries to write secrets
# from the dashboard will now raise ``NameError`` at import time instead of
# silently grafting a writer back into the rescue build. The .env file is
# the only source of truth for Binance credentials — see ``.env.example``.


def _read_symbol() -> str:
    """Read active symbol from file."""
    if not SYMBOL_FILE.exists():
        return "BTCUSDT"
    text = SYMBOL_FILE.read_text(encoding="utf-8").strip()
    return text.split("\n")[0].strip() if text else "BTCUSDT"


def _mask_key(key: str) -> str:
    """Mask API key for display: show first 4 and last 4 chars."""
    if not key or len(key) <= 8:
        return key
    return key[:4] + "*" * (len(key) - 8) + key[-4:]


# ─── Bot Process Management ──────────────────────────────────────


def _is_windows() -> bool:
    """Detect the runtime OS for process control compatibility."""
    return os.name == "nt"


def _get_process_commandline(pid: int) -> str:
    """Return command line for a PID in a cross-platform best-effort way."""
    global _bot_probe_error
    if _is_windows():
        return _get_windows_process_commandline(pid)

    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "args="],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except Exception as exc:
        _bot_probe_error = f"failed to read process command line for pid {pid}: {exc}"

    return ""


def _is_packaged_runtime() -> bool:
    """Return True when running inside a PyInstaller frozen bundle."""
    if bool(getattr(sys, "frozen", False)):
        return True
    if hasattr(sys, "_MEIPASS"):
        return True
    exe_path = Path(sys.executable).resolve()
    if exe_path.suffix.lower() != ".exe":
        return False
    # In onedir bundles, the launcher exe sits next to the app/ folder.
    # Use this structure check to avoid false positives on dev interpreters like
    # python.exe / pyw.exe / py.exe.
    return (exe_path.parent / "app").exists()


def _get_windows_process_commandline(pid: int) -> str:
    """Read command line for a specific Windows PID with WMIC + PowerShell fallback."""
    global _bot_probe_error
    if not _is_windows() or pid <= 0:
        return ""

    try:
        result = subprocess.run(
            ["wmic", "process", "where", f"ProcessId={pid}", "get", "CommandLine", "/FORMAT:LIST"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if result.returncode == 0:
            for raw_line in result.stdout.splitlines():
                line = raw_line.strip()
                if line.startswith("CommandLine="):
                    return line.split("=", 1)[1].strip()
    except Exception as exc:
        _bot_probe_error = f"failed to read process command line for pid {pid}: {exc}"

    try:
        result = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                f"(Get-CimInstance Win32_Process -Filter \"ProcessId = {pid}\" | Select-Object -ExpandProperty CommandLine)",
            ],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except Exception as exc:
        _bot_probe_error = f"failed to read process command line for pid {pid}: {exc}"

    return ""


def _get_windows_process_name(pid: int) -> str:
    """Read process name for a specific Windows PID with PowerShell fallback."""
    global _bot_probe_error
    if not _is_windows() or pid <= 0:
        return ""

    try:
        result = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                f"(Get-Process -Id {pid} -ErrorAction Stop | Select-Object -ExpandProperty ProcessName)",
            ],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except Exception as exc:
        _bot_probe_error = f"failed to read process name for pid {pid}: {exc}"

    return ""


def _allows_python_process_name_fallback() -> bool:
    """Allow python-family process-name matching only in a real Python runtime."""
    exe_name = Path(sys.executable).name.lower()
    return exe_name.startswith("python") or exe_name in {"py", "py.exe", "pyw", "pyw.exe", "pythonw.exe", "python3.exe"}


def _read_pid() -> Optional[int]:
    """Read bot PID from pid file and verify process is alive."""
    global _bot_probe_error
    if not PID_FILE.exists():
        return None

    def _remove_stale_pid_file() -> None:
        try:
            PID_FILE.unlink(missing_ok=True)
        except Exception as cleanup_exc:
            logger.warning(f"Failed to remove stale PID file {PID_FILE}: {cleanup_exc}")

    try:
        pid = int(PID_FILE.read_text(encoding="utf-8").strip())
        command_line = _get_process_commandline(pid)
        process_name = _get_windows_process_name(pid)
        if command_line and not _is_likely_bot_commandline(command_line):
            # Keep runtime reads honest if a recycled PID points elsewhere.
            _remove_stale_pid_file()
            return None
        if process_name:
            lowered_name = process_name.lower()
            python_name_ok = _allows_python_process_name_fallback()
            if not (
                lowered_name.startswith("tradingbotv1")
                or (python_name_ok and lowered_name in {"python", "pythonw", "python3", "py", "pyw"})
            ):
                _remove_stale_pid_file()
                return None

        if _is_windows():
            # Additional verification for Windows to avoid stale/recycled PIDs.
            candidates = _find_windows_bot_pids()
            if candidates:
                if pid not in candidates:
                    logger.warning(
                        "Windows bot PID not present in best-effort process list; "
                        "deferring to os.kill(pid, 0) instead of dropping the PID file"
                    )
            elif not command_line and not process_name:
                logger.warning(
                    "Windows bot PID could not be positively identified; "
                    "deferring to os.kill(pid, 0) instead of dropping the PID file"
                )
        os.kill(pid, 0)  # check if alive (sends no signal)
        _bot_probe_error = None
        return pid
    except ProcessLookupError:
        _remove_stale_pid_file()
        return None
    except PermissionError:
        # Permission errors can occur for processes we still identify by command
        # line, but cannot signal directly. Keep it as running to avoid
        # false negative stop/start decisions.
        return pid
    except ValueError:
        _remove_stale_pid_file()
        return None


def _is_bot_running() -> bool:
    """Check if the bot is alive via PID file, fallback to pgrep."""
    if _read_pid() is not None:
        return True
    if _is_windows():
        pids = _find_windows_bot_pids()
        if pids:
            return True
        if _bot_probe_error:
            logger.warning(f"Bot process probe failed; treating as running: {_bot_probe_error}")
            return True
        return False
    # Fallback: check for orphan bot processes not tracked by PID file
    try:
        result = subprocess.run(
            ["pgrep", "-f", "src\\.main"],
            capture_output=True, text=True, timeout=3,
        )
        return bool(result.stdout.strip())
    except Exception as exc:
        logger.warning(f"Bot-running probe failed; treating as running: {exc}")
        return True


def _resolve_bot_running(status: dict[str, Any]) -> bool:
    """Resolve bot running state from PID checks with a recent-status fallback."""
    bot_running = _is_bot_running()
    if bot_running:
        return True
    if status.get("bot_status") != "running":
        return False
    last_update = status.get("last_update", "")
    dt = _parse_iso_datetime(last_update)
    if dt is None:
        return False
    age = (datetime.now(timezone.utc) - dt).total_seconds()
    return age < 120


def _get_running_bot_pid_hint() -> Optional[int]:
    """Return a best-effort bot pid for clients that already report running state."""
    pid = _read_pid()
    if pid is not None:
        return pid
    if _is_windows():
        candidates = _find_windows_bot_pids()
        return candidates[0] if candidates else None
    return None


def _start_bot() -> dict[str, Any]:
    """Start the bot as a fully detached daemon process.

    The bot runs completely independent of the dashboard:
    - start_new_session=True: own process group, no signals from parent
    - stdin closed, stdout/stderr to files
    - PID tracked via runtime/bot.pid (written by bot itself)
    """
    if _is_bot_running():
        return {"status": "already_running", "pid": _get_running_bot_pid_hint()}

    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    stderr_f = open(RUNTIME_DIR / "bot_stderr.log", "a", encoding="utf-8")
    bot_config_path = str((PROJECT_ROOT / "config" / "default.yaml").resolve())
    launch_candidates = _build_bot_launch_commands(bot_config_path)

    # De-duplicate while preserving order.
    seen: set[str] = set()
    unique_launchers: list[list[str]] = []
    for cmd in launch_candidates:
        key = " ".join(cmd)
        if key in seen:
            continue
        seen.add(key)
        unique_launchers.append(cmd)

    last_error: Exception | None = None
    bot_process: subprocess.Popen[bytes] | None = None
    for cmd in unique_launchers:
        try:
            popen_kwargs = {
                "cwd": str(PROJECT_ROOT),
                "stdin": subprocess.DEVNULL,
                "stdout": subprocess.DEVNULL,
                "stderr": stderr_f,
                "close_fds": True,
            }
            if not _is_windows():
                popen_kwargs["start_new_session"] = True
            bot_process = subprocess.Popen(cmd, **popen_kwargs)
            # If the process exits immediately, try the next candidate.
            if bot_process.poll() is not None:
                last_error = RuntimeError(
                    f"command exited immediately (code {bot_process.returncode}): {cmd}"
                )
                try:
                    if not _terminate_process_with_reap(bot_process):
                        logger.warning("Failed to clean up immediate-exit bot process during startup")
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to clean up immediate-exit bot process during startup: {cleanup_exc}"
                    )
                bot_process = None
                continue
            break
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            bot_process = None
    if bot_process is None:
        stderr_f.close()
        raise RuntimeError(f"unable to launch bot; tried {unique_launchers}: {last_error}")

    # Wait for the worker to bootstrap and write its PID file.
    # If it exits during this window, report a startup failure instead of
    # returning a false-positive "started" status.
    # 12s mirrors the Windows smoke gating window and is more tolerant on
    # slower packaged-startup hosts.
    startup_check_deadline = time.time() + 12.0
    while time.time() < startup_check_deadline:
        if bot_process.poll() is not None:
            last_error = RuntimeError(f"command exited during startup (code {bot_process.returncode}): {bot_process.args}")
            try:
                if not _terminate_process_with_reap(bot_process):
                    logger.warning("Failed to clean up booting bot process after startup failure")
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to clean up booting bot process after startup failure: {cleanup_exc}"
                )
            bot_process = None
            break
        if _read_pid() == bot_process.pid:
            break
        time.sleep(0.1)

    if bot_process is None:
        stderr_f.close()
        raise RuntimeError(f"unable to launch bot; tried {unique_launchers}: {last_error}")
    expected_pid = bot_process.pid
    if _read_pid() != expected_pid:
        # If PID file is missing here, we still prefer to report explicit
        # startup failure rather than pretend the daemon is alive.
        try:
            if not _terminate_process_with_reap(bot_process):
                logger.warning("Failed to clean up bot process after missing PID file during startup")
        except Exception as cleanup_exc:
            logger.warning(
                f"Failed to clean up bot process after missing PID file during startup: {cleanup_exc}"
            )
        bot_process = None
        stderr_f.close()
        raise RuntimeError(
            f"bot started without writing PID file for expected PID {expected_pid}"
        )

    # Don't hold a reference — let the bot live on its own via PID file
    pid = bot_process.pid
    bot_process = None
    stderr_f.close()
    return {"status": "started", "pid": pid}


def _kill_pid(pid: int) -> bool:
    """Send SIGTERM then SIGKILL to a PID on POSIX; best-effort fallback elsewhere.

    Gives 8 seconds for graceful shutdown (bot needs time for _shutdown() + state save).
    Only force-kills if the process doesn't exit in time.
    """
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    except Exception:
        return False
    import time
    for _ in range(16):  # 8 seconds for graceful shutdown
        time.sleep(0.5)
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        except Exception:
            return False
    if not _is_windows():
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            return True
        except Exception:
            return False
        return True
    else:
        # On Windows force-terminate after grace period if process ignores SIGTERM.
        try:
            result = subprocess.run(
                ["taskkill", "/F", "/T", "/PID", str(pid)],
                capture_output=True,
                text=True,
                check=False,
            )
            return result.returncode == 0
        except Exception:
            return False
    return False


def _is_pid_alive(pid: int) -> bool:
    """Return False only when the OS confirms the process does not exist."""
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except Exception:
        # Treat permission/OS restrictions as still-running to avoid false
        # negatives in stop cleanup logic.
        return True


def _is_likely_bot_commandline(cmd: str) -> bool:
    """Heuristic matcher for trading-bot worker command lines."""
    if not cmd:
        return False
    lowered = cmd.lower()
    return "src.main" in lowered or "run_bot.py" in lowered or "--run-bot" in lowered


def _is_likely_bot_process_name(name: str) -> bool:
    """Heuristic matcher for trading-bot worker process names."""
    if not name:
        return False
    lowered = name.lower()
    if lowered.startswith("tradingbotv1"):
        return True
    if not _allows_python_process_name_fallback():
        return False
    return lowered in {"python", "pythonw", "python3", "py", "pyw"}


def _find_windows_bot_pids() -> list[int]:
    """Find likely running bot processes on Windows via command-line or process-name signatures."""
    global _bot_probe_error
    _bot_probe_error = None
    pids: list[int] = []
    seen: set[int] = set()

    def _add_pid(pid: int, command_line: str) -> None:
        if pid <= 1:
            return
        if pid in seen:
            return
        if command_line:
            if not _is_likely_bot_commandline(command_line):
                return
        else:
            process_name = _get_windows_process_name(pid)
            if not _is_likely_bot_process_name(process_name):
                return
        seen.add(pid)
        pids.append(pid)

    # Primary: wmic (available on most modern Windows environments).
    try:
        result = subprocess.run(
            ["wmic", "process", "get", "CommandLine,ProcessId", "/FORMAT:LIST"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if result.returncode == 0:
            current_cmd: str | None = None
            current_pid: int | None = None
            for raw_line in result.stdout.splitlines():
                line = raw_line.strip()
                if not line:
                    if current_pid is not None and current_cmd is not None:
                        _add_pid(current_pid, current_cmd)
                    current_cmd = None
                    current_pid = None
                    continue
                if line.startswith("CommandLine="):
                    current_cmd = line.split("=", 1)[1].strip()
                elif line.startswith("ProcessId="):
                    text = line.split("=", 1)[1].strip()
                    try:
                        current_pid = int(text)
                    except ValueError:
                        current_pid = None
                    if current_pid is not None and current_cmd is not None:
                        _add_pid(current_pid, current_cmd)
                        current_cmd = None
                        current_pid = None
            if current_pid is not None and current_cmd is not None:
                _add_pid(current_pid, current_cmd)
    except FileNotFoundError:
        _bot_probe_error = "wmic process enumeration unavailable"
        result = None  # type: ignore[assignment]
    except Exception:
        _bot_probe_error = "wmic process enumeration failed"
        result = None  # type: ignore[assignment]

    # Fallback: CIM process query via PowerShell for environments where wmic fails.
    if pids:
        return pids
    try:
        result = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                "Get-CimInstance Win32_Process | ForEach-Object { \"{0}|{1}\" -f $_.ProcessId, $_.CommandLine }",
            ],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if result.returncode == 0:
            for raw_line in result.stdout.splitlines():
                line = raw_line.strip()
                if "|" not in line:
                    continue
                pid_text, command_line = line.split("|", 1)
                pid_text = pid_text.strip()
                if not pid_text.isdigit():
                    continue
                _add_pid(int(pid_text), command_line)
    except Exception:
        _bot_probe_error = "powershell process enumeration failed"

    if pids:
        _bot_probe_error = None
    return pids


def _stop_bot() -> dict[str, Any]:
    """Stop the bot via PID file, with orphan-process fallback.

    SAFETY: never kills the dashboard's own process or its parent.
    """
    my_pid = os.getpid()
    my_ppid = os.getppid()
    safe_pids = {my_pid, my_ppid}
    attempted_pids: list[int] = []
    stopped_pids: list[int] = []

    def _safe_kill(pid: int) -> None:
        if pid in safe_pids:
            return
        attempted_pids.append(pid)
        if not _kill_pid(pid):
            logger.warning("Could not kill bot pid %s", pid)
            return
        stopped_pids.append(pid)

    # 1) Try PID file first
    pid = _read_pid()
    if pid is not None:
        _safe_kill(pid)

    # 2) Fallback: find orphan bot processes.
    if _is_windows():
        for orphan_pid in _find_windows_bot_pids():
            if orphan_pid not in attempted_pids:
                _safe_kill(orphan_pid)
    else:
        for pattern in ["src\\.main", "run_bot\\.py"]:
            try:
                result = subprocess.run(
                    ["pgrep", "-f", pattern],
                    capture_output=True, text=True, timeout=3,
                )
                for line in result.stdout.strip().splitlines():
                    orphan_pid = int(line.strip())
                    if orphan_pid not in attempted_pids:
                        _safe_kill(orphan_pid)
            except Exception as exc:
                logger.warning(f"Failed to scan orphan bot processes during stop: {exc}")

    still_running: list[int] = []
    for pid in dict.fromkeys(attempted_pids):
        if _is_pid_alive(pid):
            still_running.append(pid)

    if not still_running:
        if not attempted_pids and _bot_probe_error:
            logger.warning(
                f"Bot process probe failed during stop; treating bot as still running: {_bot_probe_error}"
            )
            return {
                "status": "still_running",
                "pid": None,
                "all_running": [],
                "warning": _bot_probe_error,
            }
        try:
            PID_FILE.unlink(missing_ok=True)
        except Exception as cleanup_exc:
            logger.warning(f"Failed to remove stale PID file {PID_FILE}: {cleanup_exc}")
        if not attempted_pids:
            return {"status": "not_running"}
        if stopped_pids:
            return {"status": "stopped", "pid": stopped_pids[0], "all_killed": stopped_pids}
        return {"status": "stopped"}

    return {
        "status": "still_running",
        "pid": still_running[0],
        "all_running": still_running,
    }


@app.get("/api/bot/status", response_class=JSONResponse)
def api_bot_status():
    """Return bot process status."""
    pid = _get_running_bot_pid_hint()
    status = _read_json(STATUS_FILE)
    status_error = status.pop("_json_error", None)
    state_snapshot = _read_json(STATE_FILE)
    state_error = state_snapshot.pop("_json_error", None)
    warning_parts: list[str] = []
    if status_error:
        warning_parts.append(f"Status file unreadable: {status_error}")
        if state_snapshot and not state_error:
            status = _normalize_dashboard_status(state_snapshot)
            status["_source"] = "state.json (fallback)"
        elif state_snapshot and not state_error and not status.get("bot_start_time"):
            status["bot_start_time"] = state_snapshot.get("bot_start_time")
    elif not status:
        if state_snapshot and not state_error:
            status = _normalize_dashboard_status(state_snapshot)
            status["_source"] = "state.json (fallback)"
    if state_error:
        warning_parts.append(f"State file unreadable: {state_error}")
    status = _normalize_dashboard_status(status)
    running = _resolve_bot_running(status)
    start_time = status.get("bot_start_time") if running else None
    status_warnings = list(_status_warning_list(status))
    if warning_parts:
        status_warnings.extend(warning_parts)
    if _bot_probe_error:
        status_warnings.append(f"Bot probe warning: {_bot_probe_error}")
    payload = {
        "running": running,
        "pid": pid if running else None,
        "start_time": start_time,
    }
    if warning_parts:
        payload["warning"] = "; ".join(warning_parts)
    if status_warnings:
        payload["status_warnings"] = status_warnings
    return payload


@app.post("/api/bot/start", response_class=JSONResponse)
def api_bot_start():
    """Start the trading bot."""
    try:
        return _start_bot()
    except Exception as exc:  # noqa: BLE001
        logger.error("Failed to start bot: %s", exc)
        return JSONResponse(
            {
                "status": "error",
                "error": "bot_start_failed",
                "message": str(exc),
            },
            status_code=500,
        )


@app.post("/api/bot/stop", response_class=JSONResponse)
def api_bot_stop():
    """Stop the trading bot."""
    return _stop_bot()


@app.post("/api/position/close", response_class=JSONResponse)
def api_close_position(
    symbol: str = Form(...),
    idempotency_key: Optional[str] = Form(default=None),
):
    """Enqueue a manual close command for the bot.

    S4: the dashboard never mutates `state.json` directly. It writes one
    command to `runtime/command_queue.json` and returns immediately. The
    bot's CommandProcessor drains pending commands each cycle and routes
    `manual_close` through the existing close path. Duplicate requests
    with the same `idempotency_key` collapse to the same command.
    """
    from src.persistence.atomic_io import RuntimeIOError
    from src.persistence.schemas import SchemaValidationError

    try:
        symbol = _normalize_symbol(symbol, "symbol")
    except ValueError as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)

    # Default key collapses double-submits while a command is still pending.
    key = idempotency_key or f"close::{symbol}::pending"

    try:
        queue = _get_command_queue()
        cmd = queue.enqueue(
            kind=CommandKind.MANUAL_CLOSE,
            payload={"symbol": symbol},
            idempotency_key=key,
        )
    except (RuntimeIOError, SchemaValidationError) as exc:
        # Queue file exists but is malformed — refuse to silently lose commands.
        return JSONResponse(
            {"error": f"command queue corrupt: {exc}"},
            status_code=500,
        )

    return {
        "status": "enqueued",
        "command_id": cmd.id,
        "idempotency_key": cmd.idempotency_key,
        "kind": cmd.kind.value,
        "symbol": symbol,
        "created_at": cmd.created_at,
    }


# ─── Server-Side Alert Sound (bypasses browser autoplay policy) ──

_alert_sound_proc: subprocess.Popen | None = None
_alert_sound_lock = __import__("threading").Lock()
ALERT_SOUND_FILE = DASHBOARD_DIR / "static" / "alert.mp3"


@app.post("/api/alert/play", response_class=JSONResponse)
def api_alert_play():
    """Play alert sound (macOS-only helper)."""
    if _is_windows():
        return JSONResponse(
            {"error": "alert_sound_not_supported", "message": "Server-side alert sound is available on macOS only"},
            status_code=501,
        )
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
    if _is_windows():
        return JSONResponse(
            {"status": "not_supported", "message": "Server-side alert sound is available on macOS only"},
            status_code=501,
        )
    global _alert_sound_proc
    with _alert_sound_lock:
        if _alert_sound_proc and _alert_sound_proc.poll() is None:
            # Kill the shell loop and its child afplay
            import os as _os
            try:
                pgid = _os.getpgid(_alert_sound_proc.pid)
                _os.killpg(pgid, 9)
            except Exception as exc:
                try:
                    _alert_sound_proc.kill()
                except Exception as kill_exc:
                    logger.warning(f"Failed to kill alert shell during stop: {kill_exc}")
                    return JSONResponse(
                        {"error": "alert_sound_stop_failed", "message": str(kill_exc)},
                        status_code=500,
                    )
                logger.warning(f"Failed to kill alert process group during stop: {exc}")
            _alert_sound_proc = None
            return {"status": "stopped"}
        _alert_sound_proc = None
        # Also kill any orphan afplay processes playing our file
        try:
            subprocess.run(
                ["pkill", "-f", f"afplay.*alert\\.mp3"],
                capture_output=True, timeout=3,
            )
        except Exception as exc:
            logger.warning(f"Failed to pkill orphan alert audio during stop: {exc}")
        return {"status": "not_playing"}


# ─── Paper Reset ─────────────────────────────────────────────────

@app.post("/api/paper/reset", response_class=JSONResponse)
def api_paper_reset(
    balance: float = Form(10000.0),
    idempotency_key: Optional[str] = Form(default=None),
):
    """Enqueue a paper-reset command for the bot.

    S4: the dashboard no longer races the bot for `state.json`. The bot
    picks up the `paper_reset` command, snapshots into a clean state, and
    publishes a fresh `dashboard_status.json`.
    """
    from src.persistence.atomic_io import RuntimeIOError
    from src.persistence.schemas import SchemaValidationError

    if balance <= 0:
        return JSONResponse({"error": "Balance must be positive"}, status_code=400)

    key = idempotency_key or f"paper_reset::{balance:.4f}::pending"

    try:
        queue = _get_command_queue()
        cmd = queue.enqueue(
            kind=CommandKind.PAPER_RESET,
            payload={"balance": float(balance)},
            idempotency_key=key,
        )
    except (RuntimeIOError, SchemaValidationError) as exc:
        return JSONResponse(
            {"error": f"command queue corrupt: {exc}"},
            status_code=500,
        )

    return {
        "status": "enqueued",
        "command_id": cmd.id,
        "idempotency_key": cmd.idempotency_key,
        "kind": cmd.kind.value,
        "balance": float(balance),
        "created_at": cmd.created_at,
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
        if _config_read_error or not config:
            logger.warning(
                "Cannot create scanner because config could not be loaded: %s",
                _config_read_error or "missing config",
            )
            return None
        _scanner = ScannerService(config, symbol_file=SYMBOL_FILE)
    return _scanner


@app.post("/api/scanner/start", response_class=JSONResponse)
def api_scanner_start(min_confidence: int = Form(55)):
    # Block if auto-scan is running
    if min_confidence < 0 or min_confidence > 100:
        return JSONResponse(
            {"error": "min_confidence must be between 0 and 100"},
            status_code=400,
        )
    if _is_auto_scan_active() or _is_manual_scan_active():
        return JSONResponse(
            {"error": "A scan is in progress, please wait for it to finish."},
            status_code=409,
        )
    scanner = _get_scanner()
    if scanner is None:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error or 'missing config'}",
            },
            status_code=503,
        )
    if scanner.is_scanning:
        scanner.force_reset()
    # Re-create scanner with latest config (picks up timeframe changes)
    scanner = _get_scanner(fresh=True)
    if scanner is None:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error or 'missing config'}",
            },
            status_code=503,
        )
    ok = scanner.scan_async(min_confidence=min_confidence)
    if not ok:
        return JSONResponse({"error": "Failed to start scan"}, status_code=500)
    return {"status": "started", "min_confidence": min_confidence}


@app.post("/api/scanner/stop", response_class=JSONResponse)
def api_scanner_stop():
    scanner = _get_scanner()
    if scanner is not None:
        scanner.stop()
        scanner.force_reset()
    # Also stop multi-TF scanners
    for s in _multi_scanners.values():
        s.stop()
        s.force_reset()
    _multi_scanners.clear()
    _set_manual_scan_lock(False)
    payload = {"status": "stopped"}
    if scanner is None:
        payload["warning"] = f"Config read failed: {_config_read_error or 'missing config'}"
    return payload


@app.get("/api/scanner/progress", response_class=JSONResponse)
def api_scanner_progress():
    scanner = _get_scanner()
    if scanner is None:
        return JSONResponse(
            {
                "scanning": False,
                "progress": {"current": 0, "total": 0, "symbol": "", "status": "error", "hot_count": 0},
                "recent": [],
                "hot": [],
                "top15": [],
                "total_scanned": 0,
                "warning": f"Config read failed: {_config_read_error or 'missing config'}",
            },
            status_code=503,
        )
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
    try:
        symbol = _normalize_symbol(symbol, "symbol")
    except ValueError as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)
    try:
        _atomic_write_text(SYMBOL_FILE, f"{symbol}\n")
        return {"status": "ok", "symbol": symbol}
    except Exception as e:
        try:
            if SYMBOL_FILE.exists():
                SYMBOL_FILE.unlink()
        except Exception as cleanup_exc:
            logger.warning(
                f"Failed to remove stale symbol file after dashboard write error: {cleanup_exc}"
            )
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
            d = json.loads(p.read_text(encoding="utf-8"))
            if not isinstance(d, dict):
                raise ValueError(f"auto-scan state must be a mapping, got {type(d).__name__}")
            if "scanning" not in d:
                raise ValueError("auto-scan state missing scanning flag")
            return bool(d.get("scanning", False))
    except Exception as exc:
        if p.exists():
            try:
                age_minutes = (datetime.now(timezone.utc).timestamp() - p.stat().st_mtime) / 60
            except Exception:
                age_minutes = None
            if age_minutes is not None and age_minutes > 30:
                logger.warning(f"Stale auto-scan state file ({age_minutes:.0f} min old) — auto-clearing")
                try:
                    p.unlink()
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to remove stale auto-scan state file after read error: {cleanup_exc}"
                    )
                return False
            logger.warning(f"Auto-scan state read failed; treating as inactive: {exc}")
            try:
                p.unlink(missing_ok=True)
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove unreadable auto-scan state file after read error: {cleanup_exc}"
                )
            return False
    return False


def _set_manual_scan_lock(active: bool) -> bool:
    """Write/clear the manual scan lock file."""
    try:
        data = {"active": active, "ts": datetime.now(timezone.utc).isoformat()}
        from src.persistence.atomic_io import atomic_write_json
        atomic_write_json(_MANUAL_SCAN_LOCK, data, indent=2)
        return True
    except Exception as exc:
        logger.warning(f"Failed to write manual scan lock {_MANUAL_SCAN_LOCK}: {exc}")
        if not active:
            try:
                if _MANUAL_SCAN_LOCK.exists():
                    _MANUAL_SCAN_LOCK.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale manual scan lock after clear error: {cleanup_exc}"
                )
        return False


def _is_manual_scan_active() -> bool:
    """Check if a dashboard-triggered manual scan is running."""
    # First check lock file
    try:
        if _MANUAL_SCAN_LOCK.exists():
            d = json.loads(_MANUAL_SCAN_LOCK.read_text(encoding="utf-8"))
            if not isinstance(d, dict):
                raise ValueError(
                    f"manual scan lock must be a mapping, got {type(d).__name__}"
                )
            if "active" not in d:
                raise ValueError("manual scan lock missing active state")
            if d.get("active"):
                # Lock records start time to avoid stale hangs after dashboard crash/restart.
                ts = d.get("ts", "")
                if not ts:
                    logger.warning("Manual scan lock missing ts — auto-clearing")
                    _set_manual_scan_lock(False)
                    return False
                lock_time = _parse_iso_datetime(ts)
                if lock_time is None:
                    logger.warning(f"Malformed manual scan lock ts: {ts} — auto-clearing")
                    _set_manual_scan_lock(False)
                    return False
                age_minutes = (datetime.now(timezone.utc) - lock_time).total_seconds() / 60
                if age_minutes > 30:
                    logger.warning(f"Stale manual scan lock ({age_minutes:.0f} min old) — auto-clearing")
                    _set_manual_scan_lock(False)
                    return False
                # Verify at least one scanner is actually alive
                for s in _multi_scanners.values():
                    if s.is_scanning:
                        return True
                # Lock says active but no scanners running → stale lock, clear it
                _set_manual_scan_lock(False)
    except Exception as exc:
        if _MANUAL_SCAN_LOCK.exists():
            try:
                age_minutes = (
                    datetime.now(timezone.utc).timestamp() - _MANUAL_SCAN_LOCK.stat().st_mtime
                ) / 60
            except Exception:
                age_minutes = None
            if age_minutes is not None and age_minutes > 30:
                logger.warning(
                    f"Stale manual scan lock ({age_minutes:.0f} min old) after read error — auto-clearing"
                )
                try:
                    _MANUAL_SCAN_LOCK.unlink()
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to remove stale manual scan lock after read error: {cleanup_exc}"
                    )
                return False
            logger.warning(f"Manual scan state read failed; treating as inactive: {exc}")
            try:
                _MANUAL_SCAN_LOCK.unlink(missing_ok=True)
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove unreadable manual scan lock after read error: {cleanup_exc}"
                )
            return False
    return False


def _save_multi_results(payload: dict) -> None:
    """Persist multi-scan results to disk."""
    try:
        from src.persistence.atomic_io import atomic_write_json
        RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
        atomic_write_json(_MULTI_SCAN_FILE, payload, indent=2)
    except Exception as exc:
        logger.warning(f"Failed to save multi-scan results to {_MULTI_SCAN_FILE}: {exc}")
        try:
            if _MULTI_SCAN_FILE.exists():
                _MULTI_SCAN_FILE.unlink()
        except Exception as cleanup_exc:
            logger.warning(
                f"Failed to remove stale multi-scan results after write error: {cleanup_exc}"
            )


def _load_multi_results() -> dict | None:
    """Load persisted multi-scan results from disk."""
    try:
        if _MULTI_SCAN_FILE.exists():
            data = json.loads(_MULTI_SCAN_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                if not isinstance(data.get("timeframes"), dict):
                    raise ValueError("expected timeframes mapping")
                if not isinstance(data.get("cross_ranking"), list):
                    raise ValueError("expected cross_ranking list")
                if not isinstance(data.get("common_symbols"), list):
                    raise ValueError("expected common_symbols list")
                if not data.get("scan_time"):
                    raise ValueError("expected completed scan_time")
                if data.get("any_scanning") not in {False, 0, None}:
                    raise ValueError("expected any_scanning to be false")
                return data
            raise ValueError(f"expected JSON object, got {type(data).__name__}")
    except Exception as exc:
        if _MULTI_SCAN_FILE.exists():
            logger.warning(f"Failed to load multi-scan results from {_MULTI_SCAN_FILE}: {exc}")
            try:
                _MULTI_SCAN_FILE.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale multi-scan results after load error: {cleanup_exc}"
                )
    return None


def _prefer_fresher_multi_scan_snapshot(saved: dict[str, Any], progress: dict[str, Any] | None) -> dict[str, Any]:
    saved = saved if isinstance(saved, dict) else {}
    progress = progress if isinstance(progress, dict) else {}
    if not saved or not progress:
        return saved

    saved_time = saved.get("scan_time")
    progress_time = progress.get("last_auto_scan") or progress.get("completed_at")
    saved_dt = _parse_iso_datetime(saved_time) if isinstance(saved_time, str) else None
    progress_dt = _parse_iso_datetime(progress_time) if isinstance(progress_time, str) else None
    if progress_dt is None or (saved_dt is not None and saved_dt >= progress_dt):
        return saved

    merged = dict(saved)
    warnings = []
    for key in ("status_warnings", "warnings"):
        value = merged.get(key)
        if isinstance(value, list):
            for item in value:
                if item is None:
                    continue
                text = item if isinstance(item, str) else str(item)
                if text not in warnings:
                    warnings.append(text)
    for key in ("status_warnings", "warnings"):
        value = progress.get(key)
        if isinstance(value, list):
            for item in value:
                if item is None:
                    continue
                text = item if isinstance(item, str) else str(item)
                if text not in warnings:
                    warnings.append(text)
    progress_results = progress.get("last_scan_results")
    if not isinstance(progress_results, list):
        progress_results = progress.get("cross_ranking")
    if isinstance(progress_results, list):
        merged["cross_ranking"] = progress_results
        merged["last_scan_results"] = progress_results
    for key in ("last_auto_scan", "scan_time"):
        value = progress.get("last_auto_scan") or progress.get("completed_at")
        if isinstance(value, str):
            merged["scan_time"] = value
            merged["last_auto_scan"] = value
            merged["last_update"] = value
            break
    if warnings:
        merged["status_warnings"] = warnings
        merged["warnings"] = list(warnings)
    if isinstance(progress.get("last_scan_total"), (int, float)) and not isinstance(progress.get("last_scan_total"), bool):
        merged["last_scan_total"] = progress["last_scan_total"]
    if isinstance(progress.get("last_scan_hot_count"), (int, float)) and not isinstance(progress.get("last_scan_hot_count"), bool):
        merged["last_scan_hot_count"] = progress["last_scan_hot_count"]
    return merged


def _merge_multi_scan_into_status(status: dict[str, Any], multi_scan: dict[str, Any] | None) -> dict[str, Any]:
    status = _normalize_dashboard_status(status if isinstance(status, dict) else {})
    multi = multi_scan if isinstance(multi_scan, dict) else {}
    if not multi:
        return status

    warnings = _status_warning_list(status)
    seen_warnings = set(warnings)
    for key in ("status_warnings", "warnings"):
        value = multi.get(key)
        if isinstance(value, list):
            for item in value:
                if item is None:
                    continue
                text = item if isinstance(item, str) else str(item)
                if text not in seen_warnings:
                    seen_warnings.add(text)
                    warnings.append(text)

    scan_time = multi.get("scan_time")
    current_ts = status.get("last_auto_scan") or status.get("last_update")
    candidate_dt = _parse_iso_datetime(scan_time) if isinstance(scan_time, str) else None
    current_dt = _parse_iso_datetime(current_ts) if isinstance(current_ts, str) else None
    should_replace = candidate_dt is not None and (current_dt is None or candidate_dt > current_dt)

    cross_ranking = multi.get("cross_ranking")
    if should_replace or not status.get("last_scan_results"):
        if isinstance(cross_ranking, list):
            status["last_scan_results"] = cross_ranking
        timeframes = multi.get("timeframes") if isinstance(multi.get("timeframes"), dict) else {}
        totals = [int(t.get("total_scanned", 0)) for t in timeframes.values() if isinstance(t, dict) and isinstance(t.get("total_scanned"), (int, float))]
        if totals or isinstance(cross_ranking, list):
            status["last_scan_total"] = max(totals or [len(cross_ranking or [])])
        common = multi.get("common_symbols")
        if isinstance(common, list):
            status["last_scan_hot_count"] = len(common)
    if isinstance(scan_time, str) and (should_replace or not status.get("last_auto_scan")):
        status["last_auto_scan"] = scan_time
    if isinstance(scan_time, str) and should_replace:
        status["last_update"] = scan_time
    if warnings:
        status["status_warnings"] = warnings
    return status


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
            symbol_counts[sym]["signals"][tf] = {"signal": r["signal"], "confidence": conf, "zak": zak, "final_score": nss}
            symbol_counts[sym]["all_signals"][tf] = {"signal": r["signal"], "confidence": conf, "zak": zak, "final_score": nss, "in_top15": True}

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
                                "zak": zak, "final_score": nss, "in_top15": False,
                            }
                            break

    return ranked


@app.post("/api/scanner/multi-start", response_class=JSONResponse)
def api_scanner_multi_start():
    """Start parallel scans for all 12 timeframes."""
    # Block if any scan is already running
    if _is_auto_scan_active() or _is_manual_scan_active():
        return JSONResponse(
            {"error": "A scan is in progress, please wait for it to finish."},
            status_code=409,
        )

    import sys
    sys.path.insert(0, str(PROJECT_ROOT))
    from src.services.scanner_service import ScannerService
    from src.api.binance_client import BinanceClient
    config = _read_config()
    if _config_read_error or not config:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error or 'missing config'}",
            },
            status_code=503,
        )

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
    if not shared_symbols:
        return JSONResponse(
            {"error": "failed to fetch symbols for multi-scan"},
            status_code=503,
        )

    # Calculate request delay based on parallel count to stay under rate limits
    # Binance: ~1200 weight/min → ~20 req/s max. With N parallel scanners: delay = N * 0.08s
    n_parallel = len(_MULTI_TFS)
    req_delay = max(0.15, n_parallel * 0.08)  # ~0.72s for 9 TFs

    # Create ALL scanners with shared symbols + rate-limited delay
    import time as _time
    created_scanners: list[Any] = []
    try:
        for tf in _MULTI_TFS:
            scanner = ScannerService(
                config, symbol_file=SYMBOL_FILE, timeframe=tf,
                shared_symbols=shared_symbols,
            )
            scanner._request_delay = req_delay
            _multi_scanners[tf] = scanner
            created_scanners.append(scanner)
            _time.sleep(0.5)  # Stagger client init

        # Start all scans
        for tf in _MULTI_TFS:
            if not _multi_scanners[tf].scan_async(min_confidence=0):
                raise RuntimeError(f"failed to start scan thread for {tf}")

        if not _set_manual_scan_lock(True):
            raise RuntimeError("failed to set manual scan lock")
        return {"status": "started", "timeframes": _MULTI_TFS}
    except Exception as exc:
        for scanner in created_scanners:
            try:
                scanner.stop()
                scanner.force_reset()
            except Exception as cleanup_exc:
                logger.warning(f"Failed to clean up scanner during multi-scan startup failure: {cleanup_exc}")
        _multi_scanners.clear()
        _set_manual_scan_lock(False)
        return JSONResponse(
            {"error": f"failed to start multi-scan: {exc}"},
            status_code=500,
        )


@app.post("/api/scanner/multi-stop", response_class=JSONResponse)
def api_scanner_multi_stop():
    for s in _multi_scanners.values():
        s.stop()
        s.force_reset()
    _multi_scanners.clear()
    _set_manual_scan_lock(False)
    try:
        _MULTI_SCAN_FILE.unlink(missing_ok=True)
    except Exception as exc:
        return JSONResponse(
            {"error": f"failed to clear saved multi-scan results: {exc}"},
            status_code=500,
        )
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


def _derive_scan_state(progress: dict | None) -> str:
    """S8: pure helper — return a canonical scan state for the UI.

    Returns one of: ``disabled``, ``scanning``, ``error``, ``stale``,
    ``complete``, ``idle``. The bot's own writer (``BotService._derive_progress_state``)
    sets the same field on disk; this helper reproduces the contract on the
    dashboard side so empty/missing-file paths can never silently imply
    ``idle`` when the truth is "we have no signal at all".
    """
    if not progress:
        return "idle"
    state = progress.get("state")
    if isinstance(state, str) and state in {
        "disabled", "scanning", "error", "stale", "complete", "idle",
    }:
        return state
    reason = progress.get("reason", "")
    if reason in {"auto_scan_disabled_flag", "auto_scan_enabled_false"}:
        return "disabled"
    if progress.get("scanning"):
        return "scanning"
    if progress.get("error"):
        return "error"
    total = progress.get("total", 0) or 0
    done = progress.get("done", 0) or 0
    if total > 0 and done >= total:
        return "complete"
    return "idle"


@app.get("/api/auto-scan-progress", response_class=JSONResponse)
def api_auto_scan_progress():
    """Return bot's auto-scan progress (read from file written by bot_service).

    S8: the response always carries a top-level ``state`` field so the
    scan UI can render the honest state (idle / scanning / disabled /
    complete / error / stale) instead of silently implying ``idle`` when
    the bot has not written a progress file yet.
    """
    def _build_multi_scan_progress_snapshot(multi_scan: dict[str, Any], extra_warning: str | None = None) -> dict[str, Any]:
        multi = multi_scan if isinstance(multi_scan, dict) else {}
        warnings: list[str] = []
        if extra_warning:
            warnings.append(extra_warning)
        for key in ("status_warnings", "warnings"):
            value = multi.get(key)
            if isinstance(value, list):
                for item in value:
                    if item is None:
                        continue
                    text = item if isinstance(item, str) else str(item)
                    if text not in warnings:
                        warnings.append(text)
        timeframes = multi.get("timeframes") if isinstance(multi.get("timeframes"), dict) else {}
        cross_ranking = multi.get("cross_ranking") if isinstance(multi.get("cross_ranking"), list) else []
        totals = [int(t.get("total_scanned", 0)) for t in timeframes.values() if isinstance(t, dict) and isinstance(t.get("total_scanned"), (int, float))]
        common_symbols = multi.get("common_symbols") if isinstance(multi.get("common_symbols"), list) else []
        scan_time = multi.get("scan_time") if isinstance(multi.get("scan_time"), str) else None
        payload = {
            "scanning": False,
            "pct": 100 if timeframes else 0,
            "done": len(timeframes) if timeframes else 0,
            "total": len(timeframes) if timeframes else 0,
            "state": "complete",
            "completed_at": scan_time,
            "last_auto_scan": scan_time,
            "last_scan_results": cross_ranking[:10] if cross_ranking else [],
            "last_scan_total": max(totals or [len(cross_ranking)]),
            "last_scan_hot_count": len(common_symbols),
        }
        if warnings:
            payload["warnings"] = list(dict.fromkeys(warnings))
            payload["status_warnings"] = list(payload["warnings"])
        return payload

    data = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    progress_error = data.pop("_json_error", None)
    multi_scan = _load_multi_results()
    multi_scan_useful = isinstance(multi_scan, dict) and bool(multi_scan.get("scan_time") or multi_scan.get("cross_ranking") or multi_scan.get("timeframes"))
    if progress_error:
        if multi_scan_useful:
            return _build_multi_scan_progress_snapshot(
                multi_scan,
                extra_warning=f"auto_scan_progress.json unreadable: {progress_error}",
            )
        return {
            "scanning": False,
            "pct": 0,
            "done": 0,
            "total": 0,
            "state": "error",
            "error": f"auto_scan_progress.json unreadable: {progress_error}",
            "status_warnings": [f"auto_scan_progress.json unreadable: {progress_error}"],
        }
    if data:
        if not isinstance(data.get("scanning"), bool):
            _status_warning_list(data).append(
                f"auto_scan_progress.json scanning has invalid type {type(data.get('scanning')).__name__}"
            )
            data["scanning"] = False
        for key in ("pct", "done", "total"):
            value = data.get(key)
            if value is not None and (not isinstance(value, (int, float)) or isinstance(value, bool)):
                _status_warning_list(data).append(
                    f"auto_scan_progress.json {key} has invalid type {type(value).__name__}"
                )
                data[key] = 0
        scan_results = data.get("last_scan_results")
        if scan_results is not None and not isinstance(scan_results, list):
            _status_warning_list(data).append(
                f"auto_scan_progress.json last_scan_results has invalid type {type(scan_results).__name__}"
            )
            data["last_scan_results"] = []
        last_auto_scan = data.get("last_auto_scan")
        if last_auto_scan is not None and not isinstance(last_auto_scan, str):
            _status_warning_list(data).append(
                f"auto_scan_progress.json last_auto_scan has invalid type {type(last_auto_scan).__name__}"
            )
            data["last_auto_scan"] = None
        scan_total = data.get("last_scan_total")
        if scan_total is not None and (not isinstance(scan_total, (int, float)) or isinstance(scan_total, bool)):
            _status_warning_list(data).append(
                f"auto_scan_progress.json last_scan_total has invalid type {type(scan_total).__name__}"
            )
            data["last_scan_total"] = 0
        scan_hot_count = data.get("last_scan_hot_count")
        if scan_hot_count is not None and (not isinstance(scan_hot_count, (int, float)) or isinstance(scan_hot_count, bool)):
            _status_warning_list(data).append(
                f"auto_scan_progress.json last_scan_hot_count has invalid type {type(scan_hot_count).__name__}"
            )
            data["last_scan_hot_count"] = 0
        if multi_scan_useful and not data.get("scanning"):
            data_ts = data.get("last_auto_scan") or data.get("completed_at")
            multi_ts = multi_scan.get("scan_time")
            data_dt = _parse_iso_datetime(data_ts) if isinstance(data_ts, str) else None
            multi_dt = _parse_iso_datetime(multi_ts) if isinstance(multi_ts, str) else None
            if multi_dt is not None and (data_dt is None or multi_dt > data_dt):
                return _build_multi_scan_progress_snapshot(multi_scan)
        state = data.get("state")
        if not isinstance(state, str) or state not in {
            "disabled", "scanning", "error", "stale", "complete", "idle",
        }:
            data["state"] = _derive_scan_state(data)
        return data
    return {"scanning": False, "pct": 0, "done": 0, "total": 0, "state": "idle"}


@app.get("/api/scanner/multi-progress", response_class=JSONResponse)
def api_scanner_multi_progress():
    """Return progress and results for all 12 timeframe scanners."""
    any_scanning = False
    all_idle = True
    tf_data = {}
    warnings: list[str] = []

    for tf in _MULTI_TFS:
        scanner = _multi_scanners.get(tf)
        if not scanner:
            tf_data[tf] = {"scanning": False, "progress": {"current": 0, "total": 0, "status": "idle"}, "top15": [], "total_scanned": 0}
            continue

        all_idle = False
        progress = scanner.progress if isinstance(scanner.progress, dict) else {"current": 0, "total": 0, "status": "error"}
        if not isinstance(scanner.progress, dict):
            warnings.append(f"{tf} progress has invalid type {type(scanner.progress).__name__}")
        else:
            for key in ("current", "total"):
                value = progress.get(key)
                if value is None or isinstance(value, bool) or not isinstance(value, (int, float)):
                    warnings.append(f"{tf} progress {key} has invalid type {type(value).__name__}")
                    progress[key] = 0
            status_value = progress.get("status")
            if not isinstance(status_value, str):
                warnings.append(f"{tf} progress status has invalid type {type(status_value).__name__}")
                progress["status"] = "error"
            progress_warnings = progress.get("warnings")
            if isinstance(progress_warnings, list):
                for warning in progress_warnings:
                    if warning is None:
                        continue
                    warnings.append(f"{tf} progress warning: {warning if isinstance(warning, str) else str(warning)}")
        results = scanner.results if isinstance(scanner.results, list) else []
        if not isinstance(scanner.results, list):
            warnings.append(f"{tf} results has invalid type {type(scanner.results).__name__}")

        if scanner.is_scanning:
            any_scanning = True

        # ZAK-weighted sorting: final_score = (conf²) × (ZAK/100)
        safe_results = [r for r in results if isinstance(r, dict)]
        normalized_results: list[dict[str, Any]] = []
        for r in safe_results:
            symbol = r.get("symbol")
            if not symbol:
                warnings.append(f"{tf} result missing symbol")
                continue
            confidence_raw = r.get("confidence", 0)
            if isinstance(confidence_raw, bool) or not isinstance(confidence_raw, (int, float)):
                confidence = 0.0
                warnings.append(f"{tf} result {symbol} has invalid confidence {confidence_raw!r}")
            else:
                confidence = float(confidence_raw)
            indicator_warnings = r.get("indicator_warnings")
            if isinstance(indicator_warnings, list):
                for warning in indicator_warnings:
                    if warning is None:
                        continue
                    warnings.append(
                        f"{tf} {symbol} indicator warning: {warning if isinstance(warning, str) else str(warning)}"
                    )
            signal = str(r.get("signal") or "NEUTRAL")
            r["symbol"] = symbol
            r["confidence"] = confidence
            r["signal"] = signal
            r["final_score"] = _calc_nss(confidence, tf)
            r["zak"] = ZAK.get(tf, 50)
            normalized_results.append(r)
        top15 = sorted(normalized_results, key=lambda r: r.get("final_score", 0), reverse=True)[:15] if normalized_results else []

        tf_data[tf] = {
            "scanning": scanner.is_scanning,
            "progress": progress,
            "top15": top15,
            "total_scanned": len(normalized_results),
        }

    # If no active scanners, try loading from disk
    if all_idle:
        saved = _load_multi_results()
        if saved:
            progress = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
            progress.pop("_json_error", None)
            return _prefer_fresher_multi_scan_snapshot(saved, progress)

    # Find coins that appear in ALL 12 completed top15 lists
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
    if warnings:
        result["status_warnings"] = list(dict.fromkeys(warnings))

    # Save to disk when all complete & clear manual scan lock
    if all_complete and not any_scanning:
        _save_multi_results(result)
        _set_manual_scan_lock(False)

    return result


# ─── Risk Preset ─────────────────────────────────────────────────

# Risk = how much of your balance you put on the line per trade
RISK_PRESETS = {
    "very_low":  {"risk_per_trade": 0.02, "confidence_threshold": 60, "label": "Very Low"},
    "low":       {"risk_per_trade": 0.05, "confidence_threshold": 50, "label": "Low"},
    "medium":    {"risk_per_trade": 0.10, "confidence_threshold": 35, "label": "Medium"},
    "high":      {"risk_per_trade": 0.20, "confidence_threshold": 30, "label": "High"},
    "very_high": {"risk_per_trade": 0.35, "confidence_threshold": 25, "label": "Very High"},
}

# Trading mode = TP/SL style (values are PRICE MOVEMENT percentages, NOT divided by leverage)
# Leverage already multiplies PnL via position size; SL/TP stay as price %
# Scalp: very short, tight TP/SL, fast in-and-out
# Normal: standard swing trading
# Long: long-term, wide TP/SL, patient
TRADING_MODES = {
    "scalp": {
        "label": "Scalp",
        "stop_loss_pct": 0.005,                # 0.5% price move
        "take_profit_pct": 0.01,               # 1% price move
        "trailing_stop_pct": 0.003,            # 0.3%
        "trailing_stop_activation_pct": 0.005, # 0.5%
        "break_even_trigger_pct": 0.004,       # 0.4%
        "desc": "Fast in-and-out, tight SL/TP",
    },
    "normal": {
        "label": "Normal",
        "stop_loss_pct": 0.015,                # 1.5% price move
        "take_profit_pct": 0.03,               # 3% price move
        "trailing_stop_pct": 0.01,             # 1%
        "trailing_stop_activation_pct": 0.015, # 1.5%
        "break_even_trigger_pct": 0.01,        # 1%
        "desc": "Standard swing trading",
    },
    "long_term": {
        "label": "Long Term",
        "stop_loss_pct": 0.04,                 # 4% price move
        "take_profit_pct": 0.10,               # 10% price move
        "trailing_stop_pct": 0.03,             # 3%
        "trailing_stop_activation_pct": 0.04,  # 4%
        "break_even_trigger_pct": 0.025,       # 2.5%
        "desc": "Wide SL/TP, patient position",
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
    payload = {"level": level, "label": preset["label"], "presets": RISK_PRESETS}
    if _config_read_error:
        payload["warning"] = f"Config read failed: {_config_read_error}"
    return payload


@app.post("/api/risk-level", response_class=JSONResponse)
def api_set_risk_level(level: str = Form(...)):
    """Set risk level from preset."""
    if level not in RISK_PRESETS:
        return JSONResponse({"error": f"Invalid level: {level}"}, status_code=400)

    preset = RISK_PRESETS[level]
    if preset["risk_per_trade"] > RESCUE_RISK_PER_TRADE_MAX:
        return JSONResponse(
            {
                "error": "Risk preset exceeds rescue safety cap",
                "level": level,
                "cap": RESCUE_RISK_PER_TRADE_MAX,
                "risk_per_trade": preset["risk_per_trade"],
            },
            status_code=400,
        )

    config = _read_config()
    if _config_read_error:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error}",
            },
            status_code=503,
        )
    if not config:
        return JSONResponse({"error": "Config file not found"}, status_code=404)

    config.setdefault("risk", {})
    config["risk"]["risk_per_trade"] = preset["risk_per_trade"]
    config["risk"]["confidence_threshold"] = preset["confidence_threshold"]
    is_valid, validation_errors = _validate_config_for_write(config)
    if not is_valid:
        return JSONResponse(
            {"error": "Invalid config", "details": validation_errors},
            status_code=400,
        )
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
    payload = {"enabled": enabled, "pct": pct}
    if _config_read_error:
        payload["warning"] = f"Config read failed: {_config_read_error}"
    return payload


@app.post("/api/daily-loss-limit", response_class=JSONResponse)
def api_set_daily_loss_limit(
    enabled: Optional[str] = Form(default=None),
    pct: Optional[float] = Form(default=None),
):
    """Toggle daily loss limit on/off and/or set percentage."""
    config = _read_config()
    if _config_read_error:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error}",
            },
            status_code=503,
        )
    config.setdefault("risk", {})

    if enabled is not None:
        try:
            config["risk"]["daily_loss_limit_enabled"] = _parse_bool_form(enabled, "enabled")
        except ValueError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
    if pct is not None:
        if pct <= 0 or pct > 1:
            return JSONResponse(
                {"error": "daily_loss_limit_pct must be between 0 and 1"},
                status_code=400,
            )
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
    payload = {"enabled": config.get("auto_select_enabled", False)}
    if _config_read_error:
        payload["warning"] = f"Config read failed: {_config_read_error}"
    return payload


@app.post("/api/auto-select", response_class=JSONResponse)
def api_set_auto_select(enabled: str = Form(...)):
    """Toggle auto coin selection on/off."""
    config = _read_config()
    if _config_read_error:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error}",
            },
            status_code=503,
        )
    try:
        is_enabled = _parse_bool_form(enabled, "enabled")
    except ValueError as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)
    original_config = deepcopy(config)
    config["auto_select_enabled"] = is_enabled
    _write_config(config)
    # Also manage runtime flag file as belt-and-suspenders for bot process
    flag_file = RUNTIME_DIR / "auto_select_disabled"
    try:
        if is_enabled:
            flag_file.unlink(missing_ok=True)
        else:
            _atomic_write_text(flag_file, "disabled")
    except Exception as exc:
        logger.warning(f"Failed to update auto_select_disabled flag {flag_file}: {exc}")
        try:
            _write_config(original_config)
        except Exception as rollback_exc:
            logger.warning(
                f"Failed to roll back auto_select config after flag error: {rollback_exc}"
            )
        if not is_enabled:
            try:
                if flag_file.exists():
                    flag_file.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale auto_select_disabled flag after write error: {cleanup_exc}"
                )
        return JSONResponse({"error": str(exc)}, status_code=500)
    return {"status": "ok", "enabled": is_enabled}


@app.get("/api/auto-scan-toggle", response_class=JSONResponse)
def api_get_auto_scan_toggle():
    """Get auto-scan enabled status."""
    config = _read_config()
    payload = {"enabled": config.get("auto_scan_enabled", False)}
    if _config_read_error:
        payload["warning"] = f"Config read failed: {_config_read_error}"
    return payload


@app.post("/api/auto-scan-toggle", response_class=JSONResponse)
def api_set_auto_scan_toggle(enabled: str = Form(...)):
    """Toggle auto-scan on/off."""
    config = _read_config()
    if _config_read_error:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error}",
            },
            status_code=503,
        )
    try:
        is_enabled = _parse_bool_form(enabled, "enabled")
    except ValueError as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)
    original_config = deepcopy(config)
    config["auto_scan_enabled"] = is_enabled
    _write_config(config)
    flag_file = RUNTIME_DIR / "auto_scan_disabled"
    try:
        if is_enabled:
            flag_file.unlink(missing_ok=True)
        else:
            _atomic_write_text(flag_file, "disabled")
    except Exception as exc:
        logger.warning(f"Failed to update auto_scan_disabled flag {flag_file}: {exc}")
        try:
            _write_config(original_config)
        except Exception as rollback_exc:
            logger.warning(
                f"Failed to rollback auto_scan config after flag error: {rollback_exc}"
            )
        if not is_enabled:
            try:
                if flag_file.exists():
                    flag_file.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale auto_scan_disabled flag after write error: {cleanup_exc}"
                )
        return JSONResponse({"error": str(exc)}, status_code=500)
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
    if _config_read_error:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error}",
            },
            status_code=503,
        )
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
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({"error": "Invalid JSON body"}, status_code=400)

    if not isinstance(body, dict):
        return JSONResponse({"error": "Request body must be a JSON object"}, status_code=400)

    config = _read_config()
    if _config_read_error:
        return JSONResponse(
            {
                "error": "Config file could not be loaded",
                "warning": f"Config read failed: {_config_read_error}",
            },
            status_code=503,
        )
    if not config:
        return JSONResponse({"error": "Config file not found"}, status_code=404)

    updated_config = _merge_config_updates(config, body)
    is_valid, validation_errors = _validate_config_for_write(updated_config)
    if not is_valid:
        return JSONResponse(
            {"error": "Invalid config", "details": validation_errors},
            status_code=400,
        )

    _write_config(updated_config)
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
    global _live_signal_error
    status = _read_json(STATUS_FILE)
    warnings: list[str] = []
    read_error = status.pop("_json_error", None)
    if read_error:
        warnings.append(f"Failed to read dashboard_status.json: {read_error}")
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            warnings.append(f"Failed to read state.json: {fallback_error}")
    elif not status:
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            warnings.append(f"Failed to read state.json: {fallback_error}")
    status = _normalize_dashboard_status(status)
    if warnings:
        _status_warning_list(status).extend(warnings)
    if _config_read_error:
        _status_warning_list(status).append(
            f"Config read failed: {_config_read_error}"
        )
    if _live_signal_error:
        _status_warning_list(status).append(
            f"Live signal refresh failed: {_live_signal_error}"
        )
    bot_running = _resolve_bot_running(status)
    bot_start = status.get("bot_start_time") if bot_running else None

    risk_level = _get_current_risk_level()
    trading_mode = _get_current_trading_mode()

    config = _read_config()
    if CONFIG_FILE.exists() and not config:
        _status_warning_list(status).append("Config file could not be loaded.")
    risk_cfg = config.get("risk", {})
    dll_enabled = risk_cfg.get("daily_loss_limit_enabled", True)
    dll_pct = risk_cfg.get("daily_loss_limit_pct", 0.05)
    auto_select_enabled = config.get("auto_select_enabled", False)
    auto_scan_enabled = config.get("auto_scan_enabled", False)

    # Merge auto-scan data from authoritative source (auto_scan_progress.json)
    scan_progress = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    scan_error = scan_progress.pop("_json_error", None)
    if scan_error:
        _status_warning_list(status).append(
            f"auto_scan_progress.json unreadable: {scan_error}"
        )
        scan_progress = {}
    scan_results = scan_progress.get("last_scan_results")
    if scan_results is not None and not isinstance(scan_results, list):
        _status_warning_list(status).append(
            f"auto_scan_progress.json last_scan_results has invalid type {type(scan_results).__name__}"
        )
        scan_progress["last_scan_results"] = []
    scan_total = scan_progress.get("last_scan_total")
    if scan_total is not None and (not isinstance(scan_total, (int, float)) or isinstance(scan_total, bool)):
        _status_warning_list(status).append(
            f"auto_scan_progress.json last_scan_total has invalid type {type(scan_total).__name__}"
        )
        scan_progress["last_scan_total"] = 0
    scan_hot_count = scan_progress.get("last_scan_hot_count")
    if scan_hot_count is not None and (not isinstance(scan_hot_count, (int, float)) or isinstance(scan_hot_count, bool)):
        _status_warning_list(status).append(
            f"auto_scan_progress.json last_scan_hot_count has invalid type {type(scan_hot_count).__name__}"
        )
        scan_progress["last_scan_hot_count"] = 0
    if "last_auto_scan" in scan_progress:
        last_auto_scan = scan_progress.get("last_auto_scan")
        if isinstance(last_auto_scan, str):
            status["last_auto_scan"] = last_auto_scan
            status["last_update"] = last_auto_scan
        elif last_auto_scan is not None:
            _status_warning_list(status).append(
                f"auto_scan_progress.json last_auto_scan has invalid type {type(last_auto_scan).__name__}"
            )
    if "last_scan_results" in scan_progress:
        status["last_scan_results"] = scan_progress["last_scan_results"]
    if scan_progress.get("last_scan_hot_count") is not None:
        status["last_scan_hot_count"] = scan_progress["last_scan_hot_count"]
    if scan_progress.get("last_scan_total") is not None:
        status["last_scan_total"] = scan_progress["last_scan_total"]
    multi_scan = _load_multi_results()
    if multi_scan:
        status = _merge_multi_scan_into_status(status, multi_scan)

    price_view = _price_display(status, bot_running=bot_running)

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
            "price_view": price_view,
            "data_stale": _is_stale(status),
        },
    )


@app.get("/scan", response_class=HTMLResponse)
def page_scan(request: Request):
    status = _read_json(STATUS_FILE)
    status_error = status.pop("_json_error", None)
    if status_error:
        _status_warning_list(status).append(
            f"Failed to read dashboard_status.json: {status_error}"
        )
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            _status_warning_list(status).append(
                f"Failed to read state.json: {fallback_error}"
            )
    elif not status:
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
    status = _normalize_dashboard_status(status)
    # Merge auto-scan data
    scan_progress = _read_json(RUNTIME_DIR / "auto_scan_progress.json")
    scan_error = scan_progress.pop("_json_error", None)
    if scan_error:
        _status_warning_list(status).append(
            f"auto_scan_progress.json unreadable: {scan_error}"
        )
        scan_progress = {
            "scanning": False,
            "pct": 0,
            "done": 0,
            "total": 0,
            "state": "error",
            "error": f"auto_scan_progress.json unreadable: {scan_error}",
            "status_warnings": [f"auto_scan_progress.json unreadable: {scan_error}"],
        }
    scan_results = scan_progress.get("last_scan_results")
    if scan_results is not None and not isinstance(scan_results, list):
        _status_warning_list(status).append(
            f"auto_scan_progress.json last_scan_results has invalid type {type(scan_results).__name__}"
        )
        scan_progress["last_scan_results"] = []
    scan_total = scan_progress.get("last_scan_total")
    if scan_total is not None and (not isinstance(scan_total, (int, float)) or isinstance(scan_total, bool)):
        _status_warning_list(status).append(
            f"auto_scan_progress.json last_scan_total has invalid type {type(scan_total).__name__}"
        )
        scan_progress["last_scan_total"] = 0
    scan_hot_count = scan_progress.get("last_scan_hot_count")
    if scan_hot_count is not None and (not isinstance(scan_hot_count, (int, float)) or isinstance(scan_hot_count, bool)):
        _status_warning_list(status).append(
            f"auto_scan_progress.json last_scan_hot_count has invalid type {type(scan_hot_count).__name__}"
        )
        scan_progress["last_scan_hot_count"] = 0
    if "last_auto_scan" in scan_progress:
        last_auto_scan = scan_progress.get("last_auto_scan")
        if isinstance(last_auto_scan, str):
            status["last_auto_scan"] = last_auto_scan
            status["last_update"] = last_auto_scan
        elif last_auto_scan is not None:
            _status_warning_list(status).append(
                f"auto_scan_progress.json last_auto_scan has invalid type {type(last_auto_scan).__name__}"
            )
    if "last_scan_results" in scan_progress:
        status["last_scan_results"] = scan_progress["last_scan_results"]
    if scan_progress.get("last_scan_hot_count") is not None:
        status["last_scan_hot_count"] = scan_progress["last_scan_hot_count"]
    if scan_progress.get("last_scan_total") is not None:
        status["last_scan_total"] = scan_progress["last_scan_total"]
    multi_scan = _load_multi_results()
    if multi_scan:
        status = _merge_multi_scan_into_status(status, multi_scan)
        scan_time = multi_scan.get("scan_time") if isinstance(multi_scan, dict) else None
        candidate_dt = _parse_iso_datetime(scan_time) if isinstance(scan_time, str) else None
        current_dt = _parse_iso_datetime(scan_progress.get("last_auto_scan") or status.get("last_update")) if isinstance(scan_progress, dict) else None
        should_replace = candidate_dt is not None and (current_dt is None or candidate_dt > current_dt)
        if should_replace or not scan_progress:
            cross_ranking = multi_scan.get("cross_ranking") if isinstance(multi_scan.get("cross_ranking"), list) else []
            timeframes = multi_scan.get("timeframes") if isinstance(multi_scan.get("timeframes"), dict) else {}
            totals = [int(t.get("total_scanned", 0)) for t in timeframes.values() if isinstance(t, dict) and isinstance(t.get("total_scanned"), (int, float))]
            scan_progress = {
                "scanning": False,
                "pct": 100,
                "done": len(timeframes) if timeframes else 0,
                "total": len(timeframes) if timeframes else 0,
                "state": "complete",
                "status": "complete",
                "last_auto_scan": scan_time,
                "last_scan_results": cross_ranking[:10] if cross_ranking else [],
                "last_scan_total": max(totals or [len(cross_ranking)]),
                "last_scan_hot_count": len(multi_scan.get("common_symbols", [])) if isinstance(multi_scan.get("common_symbols"), list) else 0,
                "status_warnings": _status_warning_list(status),
            }
    # S8: canonical scan state (idle/scanning/disabled/complete/error/stale).
    scan_state = _derive_scan_state(scan_progress)
    scan_reason = scan_progress.get("reason", "") if scan_progress else ""
    return templates.TemplateResponse(
        request=request,
        name="scan.html",
        context={
            "status": status,
            "page": "scan",
            "bot_running": _resolve_bot_running(status),
            "data_stale": _is_stale(status),
            "scan_state": scan_state,
            "scan_reason": scan_reason,
            "scan_progress": scan_progress,
        },
    )


@app.get("/positions", response_class=HTMLResponse)
def page_positions(request: Request):
    status = _read_json(STATUS_FILE)
    status_error = status.pop("_json_error", None)
    if status_error:
        _status_warning_list(status).append(
            f"Failed to read dashboard_status.json: {status_error}"
        )
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            _status_warning_list(status).append(
                f"Failed to read state.json: {fallback_error}"
            )
    elif not status:
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
    status = _normalize_dashboard_status(status)
    return templates.TemplateResponse(
        request=request,
        name="positions.html",
        context={
            "status": status,
            "page": "positions",
            "bot_running": _resolve_bot_running(status),
            "data_stale": _is_stale(status),
        },
    )


@app.get("/signals", response_class=HTMLResponse)
def page_signals(request: Request):
    status = _read_json(STATUS_FILE)
    status_error = status.pop("_json_error", None)
    if status_error:
        _status_warning_list(status).append(
            f"Failed to read dashboard_status.json: {status_error}"
        )
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            _status_warning_list(status).append(
                f"Failed to read state.json: {fallback_error}"
            )
    elif not status:
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
    status = _normalize_dashboard_status(status)
    return templates.TemplateResponse(
        request=request,
        name="signals.html",
        context={
            "status": status,
            "page": "signals",
            "bot_running": _resolve_bot_running(status),
            "data_stale": _is_stale(status),
        },
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
            "log_warning": _log_read_error,
        },
    )


@app.get("/performance", response_class=HTMLResponse)
def page_performance(request: Request):
    status = _read_json(STATUS_FILE)
    status_error = status.pop("_json_error", None)
    if status_error:
        _status_warning_list(status).append(
            f"Failed to read dashboard_status.json: {status_error}"
        )
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
        elif fallback_error:
            _status_warning_list(status).append(
                f"Failed to read state.json: {fallback_error}"
            )
    elif not status:
        fallback = _read_json(STATE_FILE)
        fallback_error = fallback.pop("_json_error", None)
        if fallback and not fallback_error:
            status = _normalize_dashboard_status(fallback)
            status["_source"] = "state.json (fallback)"
    status = _normalize_dashboard_status(status)
    trade_history = status.get("trade_history", [])
    if isinstance(trade_history, list):
        normalized_history: list[dict[str, Any]] = []
        for item in trade_history:
            if not isinstance(item, dict):
                continue
            record = dict(item)
            try:
                record["pnl"] = float(record.get("pnl", 0.0) or 0.0)
            except Exception:
                record["pnl"] = 0.0
            exit_time = record.get("exit_time")
            if exit_time is not None and not isinstance(exit_time, str):
                try:
                    record["exit_time"] = str(exit_time)
                except Exception:
                    record["exit_time"] = ""
            normalized_history.append(record)
    else:
        normalized_history = []
    status["trade_history"] = normalized_history
    daily_summaries = compute_daily_summary(normalized_history)

    return templates.TemplateResponse(
        request=request,
        name="performance.html",
        context={
            "status": status,
            "daily_summaries": daily_summaries,
            "page": "performance",
            "bot_running": _resolve_bot_running(status),
            "data_stale": _is_stale(status),
        },
    )


# ─── Settings Page ───────────────────────────────────────────────

@app.get("/settings", response_class=HTMLResponse)
def page_settings(request: Request, saved: Optional[str] = Query(default=None)):
    config = _read_config()
    config_warning = (
        f"Config read failed: {_config_read_error}"
        if _config_read_error
        else None
    )
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
            "config_warning": config_warning,
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
    daily_loss_limit_enabled: Optional[str] = Form(default=None),
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
    leverage_enabled: Optional[str] = Form(default=None),
    leverage_scale_ratio: float = Form(0.80),
    leverage_min: int = Form(2),
):
    """Save general config from the settings form."""
    config = _read_config()
    if not config:
        return RedirectResponse("/settings?saved=error_config", status_code=303)

    candidate = deepcopy(config)

    # Validate timeframe
    if timeframe not in VALID_TIMEFRAMES:
        return RedirectResponse("/settings?saved=error_timeframe", status_code=303)

    candidate["mode"] = mode
    candidate["market_type"] = market_type
    candidate["timeframe"] = timeframe
    candidate["polling_interval_seconds"] = polling_interval_seconds
    candidate["candle_limit"] = candle_limit

    candidate.setdefault("risk", {})
    candidate["risk"]["risk_per_trade"] = risk_per_trade
    candidate["risk"]["stop_loss_pct"] = stop_loss_pct
    candidate["risk"]["take_profit_pct"] = take_profit_pct
    candidate["risk"]["trailing_stop_pct"] = trailing_stop_pct
    candidate["risk"]["trailing_stop_activation_pct"] = trailing_stop_activation_pct
    candidate["risk"]["max_open_positions"] = max_open_positions
    if daily_loss_limit_enabled is not None:
        try:
            candidate["risk"]["daily_loss_limit_enabled"] = _parse_bool_form(
                daily_loss_limit_enabled, "daily_loss_limit_enabled"
            )
        except ValueError as exc:
            logger.warning("settings config validation failed: %s", exc)
            return RedirectResponse("/settings?saved=error_validation", status_code=303)
    candidate["risk"]["daily_loss_limit_pct"] = daily_loss_limit_pct
    candidate["risk"]["confidence_threshold"] = confidence_threshold
    candidate["risk"]["max_risk_level"] = max_risk_level
    candidate["risk"]["break_even_trigger_pct"] = break_even_trigger_pct

    candidate.setdefault("paper", {})
    candidate["paper"]["starting_balance"] = starting_balance
    candidate["paper"]["fee_pct"] = fee_pct

    candidate.setdefault("consensus", {})
    candidate["consensus"]["strong_buy_threshold"] = strong_buy_threshold
    candidate["consensus"]["buy_threshold"] = buy_threshold
    candidate["consensus"]["sell_threshold"] = sell_threshold
    candidate["consensus"]["strong_sell_threshold"] = strong_sell_threshold
    candidate["consensus"]["min_active_signals"] = min_active_signals
    candidate["consensus"]["conflict_ratio_threshold"] = conflict_ratio_threshold

    candidate.setdefault("no_trade", {})
    candidate["no_trade"]["adx_min"] = adx_min
    candidate["no_trade"]["atr_high_percentile"] = atr_high_percentile
    candidate["no_trade"]["min_confidence"] = min_confidence

    candidate.setdefault("leverage", {})
    if leverage_enabled is not None:
        try:
            candidate["leverage"]["enabled"] = _parse_bool_form(
                leverage_enabled, "leverage_enabled"
            )
        except ValueError as exc:
            logger.warning("settings config validation failed: %s", exc)
            return RedirectResponse("/settings?saved=error_validation", status_code=303)
    candidate["leverage"]["scale_ratio"] = leverage_scale_ratio
    candidate["leverage"]["min_leverage"] = leverage_min

    is_valid, validation_errors = _validate_config_for_write(candidate)
    if not is_valid:
        logger.warning("settings config validation failed: %s", validation_errors)
        return RedirectResponse("/settings?saved=error_validation", status_code=303)

    _write_config(candidate)
    return RedirectResponse("/settings?saved=config", status_code=303)


@app.post("/settings/weights", response_class=HTMLResponse)
async def save_weights(request: Request):
    """Save indicator weights from the settings form."""
    from math import isfinite

    try:
        form = await request.form()
    except Exception:
        return RedirectResponse("/settings?saved=error_validation", status_code=303)
    config = _read_config()
    if _config_read_error or not config:
        return RedirectResponse("/settings?saved=error_config", status_code=303)
    config.setdefault("indicator_weights", {})
    updated_weights = dict(config["indicator_weights"])
    invalid_keys: list[str] = []

    for key in updated_weights:
        form_key = f"weight_{key}"
        if form_key in form:
            try:
                parsed = float(form[form_key])
                if not isfinite(parsed):
                    raise ValueError("non-finite")
                updated_weights[key] = parsed
            except ValueError:
                invalid_keys.append(key)

    if invalid_keys:
        logger.warning("indicator weight validation failed: %s", invalid_keys)
        return RedirectResponse("/settings?saved=error_validation", status_code=303)

    config["indicator_weights"] = updated_weights
    _write_config(config)
    return RedirectResponse("/settings?saved=weights", status_code=303)


SECRET_WRITE_DISABLED_MESSAGE = (
    "Dashboard cannot edit API keys in rescue mode. "
    "Edit the .env file directly on disk; see .env.example for the documented format."
)


@app.post("/settings/env")
def save_env(request: Request) -> JSONResponse:
    """Rescue-mode: dashboard MUST NOT write API keys.

    Returns 403 without parsing the request body. The handler intentionally
    does NOT declare ``Form(...)`` parameters so submitted secrets are never
    bound to Python locals — they cannot leak into logs, the response body,
    or downstream handlers.

    Contract (S7):
        * Status is 403 (Forbidden).
        * Response body never echoes submitted form values.
        * No file under ``ENV_FILE`` is created or modified.
        * The on-disk ``.env`` file remains the only source of truth for
          Binance credentials.
    """
    return JSONResponse(
        status_code=403,
        content={
            "error": "dashboard_secret_write_disabled",
            "message": SECRET_WRITE_DISABLED_MESSAGE,
        },
    )


@app.post("/settings/symbol", response_class=HTMLResponse)
def save_symbol(
    request: Request,
    symbol: str = Form(...),
):
    """Update active trading symbol."""
    try:
        symbol = _normalize_symbol(symbol, "symbol")
    except ValueError:
        return RedirectResponse("/settings?saved=error_symbol", status_code=303)
    try:
        _atomic_write_text(SYMBOL_FILE, symbol + "\n")
    except Exception as exc:
        try:
            if SYMBOL_FILE.exists():
                SYMBOL_FILE.unlink()
        except Exception as cleanup_exc:
            logger.warning(
                f"Failed to remove stale symbol file after save_symbol error: {cleanup_exc}"
            )
        logger.warning(f"Failed to save symbol to {SYMBOL_FILE}: {exc}")
        return RedirectResponse("/settings?saved=error_symbol", status_code=303)
    return RedirectResponse("/settings?saved=symbol", status_code=303)
