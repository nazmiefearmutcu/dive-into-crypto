"""Trading Bot v1 - Windows Launcher.

Runs from a single double-click:
  1. Opens a Tkinter splash screen (informs the user while the app starts up).
  2. Runs preflight checks (Python, port, config, .env) and stops with an error code on failure.
  3. Starts uvicorn as a subprocess and waits until it returns HTTP 200.
  4. Opens http://127.0.0.1:8080 in a new tab of the default browser.
  5. Shows a "Running" status window; from here the user can stop the bot.

When packaged with PyInstaller it becomes a single .exe. Opening the .exe runs this file.

Dependencies:
  - tkinter        (stdlib)
  - urllib         (stdlib)
  - subprocess     (stdlib)
  - webbrowser     (stdlib)
  - threading      (stdlib)
  - Python embedded inside PyInstaller + the uvicorn + FastAPI bundle

REQUIREMENTS:
  - The "app/" folder MUST sit next to launcher.py (PyInstaller embeds it via --add-data).
  - "tbv1.ico" in the same folder.

USAGE:
  python launcher/tbv1_launcher.py            # developer mode
  TradingBotV1.exe                            # packaged version (Windows)
"""
from __future__ import annotations

import os
import sys
import time
import socket
import signal
import threading
import subprocess
import webbrowser
import traceback
import json
import platform
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Optional
from urllib.request import Request, urlopen
from urllib.error import URLError

# Tkinter - stdlib, no extra dependency
try:
    import tkinter as tk
    from tkinter import ttk, scrolledtext, messagebox
except Exception:  # pragma: no cover - tkinter is a system package
    sys.stderr.write("FATAL: Tkinter not found. This should never happen in this Windows .exe; your Python installation is broken.\n")
    sys.exit(2)

# Error catalog - in the same folder
try:
    from error_codes import ErrorCatalog, LauncherError
except ImportError:
    # Direct import may not work inside PyInstaller; add the same folder
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from error_codes import ErrorCatalog, LauncherError  # type: ignore


# ── Constants ─────────────────────────────────────────────────────────────────
APP_NAME = "Trading Bot v1"
APP_VERSION = "1.0.0"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8080
STARTUP_TIMEOUT_SECONDS = 30
HEALTH_POLL_INTERVAL = 0.4
LOCK_FILENAME = ".launcher.lock"
LOG_FILENAME = "launcher.log"
THEME_BG = "#1e1e1e"
THEME_FG = "#e8e8e8"
THEME_ACCENT = "#f5c518"  # matches the yellow tone in the icon
THEME_DANGER = "#e0524a"
THEME_OK = "#3ad28a"
THEME_MUTED = "#888888"
RUN_BOT_ARG = "--run-bot"


def _read_config_path(root: Path) -> Path:
    """Return the dashboard-config file path used by this packaged app."""
    return root / "config" / "default.yaml"


def _is_likely_bot_commandline(cmd: str) -> bool:
    """Heuristic matcher for trading-bot worker command lines."""
    if not cmd:
        return False
    lowered = cmd.lower()
    return "src.main" in lowered or "--run-bot" in lowered or "run_bot.py" in lowered


def _windows_process_commandline(pid: int) -> str:
    """Return command line for PID on Windows, or empty string on failure."""
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
    except Exception:
        pass

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
    except Exception:
        pass

    return ""


def _windows_process_name(pid: int) -> str:
    """Return process name for PID on Windows, or empty string on failure."""
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
    except Exception:
        pass
    return ""


def _allows_python_process_name_fallback() -> bool:
    """Allow python-family process-name matching only in a real Python runtime."""
    exe_name = Path(sys.executable).name.lower()
    return exe_name.startswith("python") or exe_name in {"py", "py.exe", "pyw", "pyw.exe", "pythonw.exe", "python3.exe"}


def _resolve_bot_pid_path(root: Path) -> Path:
    """Resolve bot pid path from launcher-owned config, with safe fallback."""
    config_path = _read_config_path(root)
    fallback = root / "runtime" / "bot.pid"
    if not config_path.exists():
        return fallback

    try:
        import yaml
        raw = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
    except Exception:
        return fallback

    def _as_runtime_file(value: Any, default_filename: str) -> Path | None:
        if not isinstance(value, (str, Path)):
            return None
        text = str(value).strip()
        if not text or text == ".":
            return None
        try:
            p = Path(text)
        except Exception:
            return None
        if not p.is_absolute():
            p = root / p
        if p.suffix:
            return p
        return p / default_filename

    pid_path = _as_runtime_file(raw.get("pid_path"), default_filename="bot.pid")
    if pid_path is not None:
        return pid_path

    dashboard_status = _as_runtime_file(
        raw.get("dashboard_status_path"),
        default_filename="dashboard_status.json",
    )
    if dashboard_status is not None:
        return dashboard_status.parent / "bot.pid"
    return fallback


# ── PyInstaller path helpers ────────────────────────────────────────────────
def is_packaged_runtime() -> bool:
    """Return True when running from a packaged PyInstaller executable."""
    return bool(getattr(sys, "frozen", False))


def resource_dir() -> Path:
    """If packaged with PyInstaller --onefile, _MEIPASS; otherwise this file's parent."""
    if hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS)  # type: ignore[attr-defined]
    return Path(__file__).resolve().parent


def app_dir() -> Path:
    """The 'app/' folder next to the executable.

    The PyInstaller spec file places the 'app' folder under _MEIPASS/app; in this
    approach we keep the user-writable runtime/, config/, .env files in the app/
    folder NEXT TO THE LAUNCHER, since _MEIPASS is read-only.
    """
    if hasattr(sys, "_MEIPASS"):
        def _is_valid_app_root(candidate: Path) -> bool:
            checks = (
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
            return all(path.is_file() for path in checks)

        # app/ created/existing next to the .exe
        exe_dir = Path(sys.executable).resolve().parent
        external = exe_dir / "app"
        internal = Path(sys._MEIPASS) / "app"  # type: ignore[attr-defined]

        if _is_valid_app_root(external):
            return external

        # On first launch or when a stale/incomplete app cache exists, restore
        # it from the immutable bundle.
        if _is_valid_app_root(internal):
            if external.exists():
                try:
                    import shutil
                    if external.is_dir():
                        shutil.rmtree(external)
                    else:
                        external.unlink()
                except Exception:
                    pass
            try:
                import shutil
                shutil.copytree(internal, external)
            except Exception as exc:
                raise RuntimeError(f"Failed to prepare writable app bundle at {external}") from exc
            if _is_valid_app_root(external):
                return external
            raise RuntimeError(f"Copied app bundle at {external} is invalid.")
        raise RuntimeError(
            "Could not locate a valid app bundle in packaged resources or next to the launcher."
        )
    if is_packaged_runtime():
        return Path(sys.executable).resolve().parent / "app"
    # Developer mode
    return Path(__file__).resolve().parent.parent / "app"


def icon_path() -> Optional[Path]:
    for cand in ("tbv1.ico", "tbv1_256.png"):
        p = resource_dir() / cand
        if p.exists():
            return p
        p2 = Path(__file__).resolve().parent.parent / "packaging" / cand
        if p2.exists():
            return p2
    return None


# ── Logger ───────────────────────────────────────────────────────────────────
class Logger:
    """Writes to the file, to stdout, and to the Tkinter Text widget."""

    def __init__(self, log_file: Path):
        self.log_file = log_file
        self.log_file.parent.mkdir(parents=True, exist_ok=True)
        self._fh = open(log_file, "a", encoding="utf-8")
        self._lock = threading.Lock()
        self._text_widget: Optional[tk.Text] = None

    def attach_widget(self, widget: tk.Text) -> None:
        self._text_widget = widget

    def write(self, msg: str, level: str = "INFO") -> None:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{ts}] [{level}] {msg}"
        with self._lock:
            try:
                self._fh.write(line + "\n")
                self._fh.flush()
            except Exception:
                pass
            try:
                sys.stdout.write(line + "\n")
                sys.stdout.flush()
            except Exception:
                pass
        if self._text_widget is not None:
            try:
                self._text_widget.after(0, lambda: self._append_to_widget(line, level))
            except Exception:
                pass

    def _append_to_widget(self, line: str, level: str) -> None:
        try:
            self._text_widget.configure(state="normal")
            tag = level.lower()
            self._text_widget.insert("end", line + "\n", tag)
            self._text_widget.see("end")
            self._text_widget.configure(state="disabled")
        except tk.TclError:
            pass

    def info(self, msg: str) -> None: self.write(msg, "INFO")
    def warn(self, msg: str) -> None: self.write(msg, "WARN")
    def error(self, msg: str) -> None: self.write(msg, "ERROR")
    def debug(self, msg: str) -> None: self.write(msg, "DEBUG")

    def close(self) -> None:
        try:
            self._fh.close()
        except Exception:
            pass


def run_as_bundled_bot() -> int:
    """Run the trading bot as a pure worker process when launched from packaged exe.

    The packaged exe itself becomes the runtime entrypoint. In this mode we:
      - move CWD to the writable app folder (where config/runtime live),
      - ensure app root is on sys.path for `import src.main`,
      - invoke `src.main.main` directly.
    """
    try:
        root = app_dir()
        os.chdir(str(root))
        if str(root) not in sys.path:
            sys.path.insert(0, str(root))

        from src.main import main  # type: ignore[import]

        config_path = "config/default.yaml"
        args = sys.argv[1:]
        if args:
            next_is_config = False
            for arg in args:
                if next_is_config:
                    config_path = arg
                    break
                if arg == RUN_BOT_ARG:
                    next_is_config = True
                    continue
                if arg.startswith("-"):
                    continue
                config_path = arg
                break
        if not Path(config_path).is_absolute():
            config_path = str(root / config_path)
        if not Path(config_path).is_file():
            raise FileNotFoundError(f"Bot config file not found: {config_path}")

        main(config_path)
        return 0
    except Exception:
        traceback.print_exc()
        return 1


# ── Preflight checks ────────────────────────────────────────────────────────
def check_python_version() -> None:
    if sys.version_info < (3, 10):
        raise LauncherError("E001", detail=f"Current: {sys.version.split()[0]}")


def check_port_free(host: str, port: int) -> None:
    """Is the port free on the loopback address? If not, E003."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.5)
    try:
        result = s.connect_ex((host, port))
        if result == 0:
            raise LauncherError("E003", detail=f"{host}:{port}")
    finally:
        s.close()


def check_config_exists(root: Path) -> None:
    cfg = root / "config" / "default.yaml"
    if not cfg.exists():
        raise LauncherError("E004", detail=str(cfg))
    # Attempt to parse the YAML
    try:
        import yaml
        with open(cfg, "r", encoding="utf-8") as fh:
            yaml.safe_load(fh)
    except ImportError:
        raise LauncherError("E002", detail="pyyaml")
    except Exception as exc:
        raise LauncherError("E005", detail=str(exc))


def check_env_file(root: Path, log: Logger) -> None:
    """If .env is missing, warn (not fatal — paper mode keeps running)."""
    env = root / ".env"
    if not env.exists():
        log.warn(f"[E006] .env not found: {env} -- the bot can keep running in paper-trading mode.")


def check_writable(root: Path) -> None:
    runtime = root / "runtime"
    runtime.mkdir(parents=True, exist_ok=True)
    probe = runtime / ".launcher_writeprobe"
    try:
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
    except PermissionError:
        raise LauncherError("E010", detail=str(runtime))
    except OSError as exc:
        if "No space" in str(exc) or "disk" in str(exc).lower():
            raise LauncherError("E011", detail=str(exc))
        raise LauncherError("E010", detail=str(exc))


def check_memory(log: Logger) -> None:
    """If available RAM is below 200MB, warn with E017 (not fatal)."""
    try:
        if platform.system() == "Windows":
            import ctypes
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]
            mem = MEMORYSTATUSEX()
            mem.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(mem))
            avail_mb = mem.ullAvailPhys / (1024 * 1024)
            if avail_mb < 200:
                log.warn(f"[E017] Insufficient memory: {avail_mb:.0f}MB free.")
    except Exception:
        pass  # the memory check is not critical


def acquire_lock(root: Path) -> Path:
    """Single-instance lock. If stale (3 min idle), delete it and re-acquire."""
    lockfile = root / "runtime" / LOCK_FILENAME
    lockfile.parent.mkdir(parents=True, exist_ok=True)
    if lockfile.exists():
        age = time.time() - lockfile.stat().st_mtime
        try:
            pid = int(lockfile.read_text(encoding="utf-8").strip() or "0")
        except Exception:
            pid = 0
        # Stale lock (older than 3 min) or pid 0 -> stale
        if age > 180 or pid == 0:
            lockfile.unlink(missing_ok=True)
        else:
            raise LauncherError("E015", detail=f"PID={pid}, lock age={age:.0f}s")
    lockfile.write_text(str(os.getpid()), encoding="utf-8")
    return lockfile


# ── Dashboard management ────────────────────────────────────────────────────
class DashboardProcess:
    """A thin layer wrapping the uvicorn subprocess."""

    def __init__(self, root: Path, host: str, port: int, log: Logger):
        self.root = root
        self.host = host
        self.port = port
        self.log = log
        self.proc: Optional[subprocess.Popen] = None

    def start(self) -> None:
        # In an exe packaged with PyInstaller, uvicorn is embedded as a module;
        # start it with in-process Python (worker thread) instead of a subprocess.
        if is_packaged_runtime():
            self._start_inproc()
        else:
            self._start_subprocess()

    def _start_inproc(self) -> None:
        """In single-exe mode there is no separate Python to spawn, so run uvicorn on a worker thread within this process.

        Tkinter mainloop on the main thread; uvicorn on a worker thread."""
        def runner():
            try:
                # Set up PYTHONPATH
                sys.path.insert(0, str(self.root))
                os.chdir(self.root)
                import uvicorn
                cfg = uvicorn.Config(
                    "dashboard.app:app",
                    host=self.host,
                    port=self.port,
                    log_level="info",
                    access_log=False,
                )
                server = uvicorn.Server(cfg)
                # Use a stdout/stderr swap to redirect the server into the log file
                server.run()
            except Exception:
                self.log.error("Dashboard inproc crash:\n" + traceback.format_exc())

        t = threading.Thread(target=runner, daemon=True, name="uvicorn-runner")
        t.start()
        self._thread = t
        self.proc = None

    def _start_subprocess(self) -> None:
        cmd = [
            sys.executable,
            "-m", "uvicorn",
            "dashboard.app:app",
            "--host", self.host,
            "--port", str(self.port),
            "--log-level", "info",
        ]
        kwargs = dict(
            cwd=str(self.root),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        if platform.system() == "Windows":
            kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW  # type: ignore[assignment]
        self.proc = subprocess.Popen(cmd, **kwargs)  # type: ignore[arg-type]

        # stdout reader thread - forward into the log file
        def reader():
            assert self.proc and self.proc.stdout
            for line in self.proc.stdout:
                line = line.rstrip()
                if line:
                    self.log.write(f"[uvicorn] {line}", "INFO")
        threading.Thread(target=reader, daemon=True, name="uvicorn-reader").start()

    def is_alive(self) -> bool:
        if self.proc is not None:
            return self.proc.poll() is None
        return getattr(self, "_thread", None) is not None and self._thread.is_alive()

    def wait_until_responding(self, timeout: float) -> None:
        """Send a GET to HTTP /healthz or /, and wait until a 200 comes back."""
        deadline = time.time() + timeout
        url = f"http://{self.host}:{self.port}/"
        last_err: Optional[str] = None
        while time.time() < deadline:
            if not self.is_alive():
                raise LauncherError("E007", detail=last_err or "process exited early")
            try:
                with urlopen(url, timeout=1.5) as resp:
                    if resp.status == 200:
                        return
                    last_err = f"HTTP {resp.status}"
            except URLError as exc:
                last_err = str(exc.reason)
            except Exception as exc:
                last_err = repr(exc)
            time.sleep(HEALTH_POLL_INTERVAL)
        raise LauncherError("E008", detail=f"last error: {last_err}, port: {self.port}")

    def stop(self) -> None:
        if self.proc is not None:
            try:
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
            except Exception:
                pass
        # To stop the in-proc thread gracefully we could set uvicorn server.should_exit;
        # but on Tkinter shutdown the whole process exits anyway.


# ── GUI ─────────────────────────────────────────────────────────────────────
class LauncherApp:
    """The main Tkinter application."""

    def __init__(self):
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self.root.configure(bg=THEME_BG)
        self.root.geometry("760x540")
        self.root.minsize(640, 420)

        # Icon
        ico = icon_path()
        if ico:
            try:
                if ico.suffix == ".ico" and platform.system() == "Windows":
                    self.root.iconbitmap(str(ico))
                else:
                    self._icon_image = tk.PhotoImage(file=str(ico))
                    self.root.iconphoto(True, self._icon_image)
            except Exception:
                pass

        # Logger
        # Prefer runtime directory near writable app root, fallback to exe root.
        try:
            base_dir = app_dir()
            if (base_dir / "runtime").exists() or is_packaged_runtime():
                (base_dir / "runtime").mkdir(parents=True, exist_ok=True)
                log_path = base_dir / "runtime" / LOG_FILENAME
            else:
                log_path = Path(__file__).resolve().parent.parent / "packaging" / LOG_FILENAME
        except Exception:
            # Final fallback: place next to executable
            log_path = Path(sys.executable).resolve().parent / LOG_FILENAME
        self.log = Logger(log_path)
        self.log.info(f"==== {APP_NAME} v{APP_VERSION} opened ====")
        self.log.info(f"Python {sys.version.split()[0]} on {platform.platform()}")

        self.lock_file: Optional[Path] = None
        self.dashboard: Optional[DashboardProcess] = None
        self.is_running = False
        self.url = f"http://{DEFAULT_HOST}:{DEFAULT_PORT}/"

        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    # ── Layout ───────────────────────────────────────────────────────────
    def _build_ui(self) -> None:
        style = ttk.Style()
        style.theme_use("default")
        style.configure("Accent.TButton", background=THEME_ACCENT, foreground="#101010", padding=10, font=("Segoe UI", 11, "bold"))
        style.map("Accent.TButton", background=[("active", "#e0b218")])
        style.configure("Danger.TButton", background=THEME_DANGER, foreground="white", padding=8)
        style.configure("Dark.TFrame", background=THEME_BG)

        # ── Header ──
        header = tk.Frame(self.root, bg=THEME_BG, pady=18)
        header.pack(fill="x")
        tk.Label(header, text="Trading Bot v1", bg=THEME_BG, fg=THEME_FG,
                 font=("Segoe UI", 20, "bold")).pack(side="left", padx=20)
        tk.Label(header, text=f"v{APP_VERSION}", bg=THEME_BG, fg=THEME_MUTED,
                 font=("Segoe UI", 10)).pack(side="left", pady=(10, 0))

        # ── Status strip ──
        self.status_var = tk.StringVar(value="READY")
        self.status_lbl = tk.Label(self.root, textvariable=self.status_var,
                                    bg=THEME_BG, fg=THEME_ACCENT,
                                    font=("Segoe UI", 12, "bold"))
        self.status_lbl.pack(anchor="w", padx=20)

        # ── URL + actions row ──
        actions = tk.Frame(self.root, bg=THEME_BG, pady=10)
        actions.pack(fill="x", padx=20)
        self.url_var = tk.StringVar(value=self.url)
        tk.Label(actions, textvariable=self.url_var, bg=THEME_BG, fg=THEME_FG,
                 font=("Consolas", 11)).pack(side="left")
        self.btn_open = ttk.Button(actions, text="Open in Browser", command=self.open_browser, state="disabled")
        self.btn_open.pack(side="right", padx=4)
        self.btn_stop = ttk.Button(actions, text="Stop", command=self.stop_bot, state="disabled", style="Danger.TButton")
        self.btn_stop.pack(side="right", padx=4)
        self.btn_start = ttk.Button(actions, text="Start Bot", command=self.start_bot, style="Accent.TButton")
        self.btn_start.pack(side="right", padx=4)

        # ── Log panel ──
        log_frame = tk.Frame(self.root, bg=THEME_BG)
        log_frame.pack(fill="both", expand=True, padx=20, pady=10)
        tk.Label(log_frame, text="Log", bg=THEME_BG, fg=THEME_MUTED,
                 font=("Segoe UI", 9)).pack(anchor="w")
        self.log_text = scrolledtext.ScrolledText(
            log_frame, bg="#0d0d0d", fg=THEME_FG, font=("Consolas", 9),
            state="disabled", wrap="word", borderwidth=0, highlightthickness=0,
        )
        self.log_text.pack(fill="both", expand=True, pady=(4, 0))
        self.log_text.tag_configure("info", foreground=THEME_FG)
        self.log_text.tag_configure("warn", foreground=THEME_ACCENT)
        self.log_text.tag_configure("error", foreground=THEME_DANGER)
        self.log_text.tag_configure("debug", foreground=THEME_MUTED)
        self.log.attach_widget(self.log_text)

        # ── Footer ──
        footer = tk.Frame(self.root, bg=THEME_BG, pady=8)
        footer.pack(fill="x", side="bottom")
        tk.Label(footer,
                 text="If an error occurs, the error code and remedy are shown on screen. Detailed log: packaging/launcher.log",
                 bg=THEME_BG, fg=THEME_MUTED, font=("Segoe UI", 8)).pack(side="left", padx=20)

    # ── Events ───────────────────────────────────────────────────────────
    def start_bot(self) -> None:
        if self.is_running:
            return
        self.btn_start.configure(state="disabled")
        self.status_var.set("STARTING...")
        self.status_lbl.configure(fg=THEME_ACCENT)
        threading.Thread(target=self._startup_flow, daemon=True, name="startup").start()

    def _startup_flow(self) -> None:
        try:
            root = app_dir()
            self.log.info(f"Application root: {root}")

            self.log.info("[1/7] Checking Python version...")
            check_python_version()

            self.log.info("[2/7] Checking disk write permission...")
            check_writable(root)

            self.log.info("[3/7] Checking memory...")
            check_memory(self.log)

            self.log.info("[4/7] Acquiring single-instance lock...")
            self.lock_file = acquire_lock(root)

            self.log.info("[5/7] Checking whether port 8080 is free...")
            check_port_free(DEFAULT_HOST, DEFAULT_PORT)

            self.log.info("[6/7] Reading configuration files...")
            check_config_exists(root)
            check_env_file(root, self.log)

            self.log.info("[7/7] Starting the dashboard server...")
            self.dashboard = DashboardProcess(root, DEFAULT_HOST, DEFAULT_PORT, self.log)
            self.dashboard.start()
            self.dashboard.wait_until_responding(STARTUP_TIMEOUT_SECONDS)

            self.log.info(f"CONNECTION READY: {self.url}")
            self._mark_running()
            time.sleep(0.4)
            self.open_browser()

        except LauncherError as exc:
            self._show_error(exc)
        except Exception:
            self.log.error(traceback.format_exc())
            self._show_error(LauncherError("E014", detail="see launcher.log"))

    def _mark_running(self) -> None:
        def update():
            self.is_running = True
            self.status_var.set("RUNNING")
            self.status_lbl.configure(fg=THEME_OK)
            self.btn_open.configure(state="normal")
            self.btn_stop.configure(state="normal")
        self.root.after(0, update)

    def open_browser(self) -> None:
        try:
            ok = webbrowser.open(self.url, new=2)  # new=2 new tab
            if not ok:
                raise LauncherError("E009", detail="webbrowser.open returned False")
            self.log.info(f"Browser opened: {self.url}")
            self._show_browser_notice()
        except Exception:
            self.log.warn(traceback.format_exc())
            self._show_error(LauncherError("E009", detail="webbrowser module"), fatal=False)

    def _show_browser_notice(self) -> None:
        """Show an info dialog the moment the browser opens."""
        def show():
            messagebox.showinfo(
                title="Trading Bot v1 - Browser opened",
                message=(
                    "The dashboard opened in your browser.\n\n"
                    f"Address: {self.url}\n\n"
                    "Keep this window open; if you close it the bot stops.\n"
                    "To stop manually, use the 'Stop' button."
                ),
            )
        self.root.after(150, show)

    def stop_bot(self) -> None:
        if not self.is_running:
            return
        self.log.info("Stop request received...")
        self.status_var.set("STOPPING...")
        if not self._stop_bot_process():
            self.log.warn("Bot stop did not complete; leaving launcher active")
            self.status_var.set("STOP FAILED")
            self.status_lbl.configure(fg=THEME_DANGER)
            self.btn_open.configure(state="normal")
            self.btn_stop.configure(state="normal")
            self.btn_start.configure(state="disabled")
            return
        if self.dashboard:
            self.dashboard.stop()
        self.is_running = False
        self.btn_open.configure(state="disabled")
        self.btn_stop.configure(state="disabled")
        self.btn_start.configure(state="normal")
        self.status_var.set("STOPPED")
        self.status_lbl.configure(fg=THEME_MUTED)
        # Release the lock
        if self.lock_file and self.lock_file.exists():
            try:
                self.lock_file.unlink()
            except Exception:
                pass

    def _stop_bot_process(self) -> bool:
        """Ask the dashboard API to stop the bot worker process."""
        stop_request_ok = False
        try:
            req = Request(
                f"{self.url}api/bot/stop",
                data=b"",
                method="POST",
            )
            try:
                with urlopen(req, timeout=2.0) as response:
                    raw_body = response.read().decode("utf-8", errors="replace").strip()
                    payload: dict[str, Any] = {}
                    if raw_body:
                        try:
                            parsed = json.loads(raw_body)
                            if isinstance(parsed, dict):
                                payload = parsed
                        except Exception:
                            payload = {}
                    response_status = payload.get("status")
                    if response.status == 200 and response_status in {"stopped", "not_running"}:
                        stop_request_ok = True
                    else:
                        self.log.warn(
                            f"Bot stop request returned HTTP {response.status} status={response_status!r}"
                        )
            except TimeoutError as exc:
                self.log.warn(f"Bot stop request timed out: {exc}")
            except Exception as exc:  # pragma: no cover - network edge
                # Dashboard may already be offline or unreachable; still continue
                # so launcher shutdown is predictable.
                self.log.warn(f"Bot stop request failed: {exc}")
        except Exception as exc:
            self.log.warn(f"Unable to issue bot stop request: {exc}")
        if not stop_request_ok:
            stop_request_ok = self._stop_bot_via_pidfile()
        return stop_request_ok

    def _stop_bot_via_pidfile(self) -> bool:
        """Best-effort fallback when dashboard API is unavailable."""
        root = app_dir()
        pid_file = _resolve_bot_pid_path(root)
        pid: Optional[int] = None
        try:
            if not pid_file.exists():
                return False
            pid_raw = pid_file.read_text(encoding="utf-8").strip()
            if not pid_raw:
                return False
            pid = int(pid_raw)
            if pid <= 0:
                return False
            try:
                command_line = _windows_process_commandline(pid)
                process_name = _windows_process_name(pid)
                if command_line and not _is_likely_bot_commandline(command_line):
                    self.log.warn(
                        f"PID {pid} does not appear to be a bot process (cmd mismatch), skipping"
                    )
                    return False
                if not command_line:
                    if not process_name:
                        return False
                    lowered_name = process_name.lower()
                    if not (
                        lowered_name.startswith("tradingbotv1")
                        or (_allows_python_process_name_fallback() and lowered_name in {"python", "pythonw", "python3", "py", "pyw"})
                    ):
                        self.log.warn(
                            f"PID {pid} does not appear to be a bot process (name mismatch), skipping"
                        )
                        return False
            except Exception:
                # If we cannot inspect either command line or process name, avoid
                # a potentially unsafe kill.
                return False
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                return True
            # Give the process a short window to exit before removing the marker.
            deadline = time.time() + 3.0
            while time.time() < deadline:
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    break
                except Exception:
                    time.sleep(0.25)
                    continue
                time.sleep(0.25)
            else:
                # SIGTERM did not make progress; on Windows escalate to a
                # hard stop so the launcher does not leave the bot running.
                if platform.system() == "Windows":
                    try:
                        subprocess.run(
                            ["taskkill", "/F", "/T", "/PID", str(pid)],
                            capture_output=True,
                            text=True,
                            check=False,
                        )
                    except Exception:
                        pass
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    pass
                except Exception:
                    return False
                else:
                    return False
        except ValueError as exc:
            self.log.warn(f"Fallback bot pidfile stop failed: {exc}")
            return False
        except ProcessLookupError:
            return True
        except Exception as exc:
            self.log.warn(f"Fallback bot pidfile stop failed: {exc}")
            return False
        finally:
            # Remove marker only when process is confirmed gone.
            if pid is not None:
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    try:
                        pid_file.unlink(missing_ok=True)
                    except Exception:
                        pass
                except Exception:
                    pass
        return True

    def _show_error(self, exc: LauncherError, fatal: bool = True) -> None:
        entry = exc.entry
        code = exc.code
        title = entry.title if entry else "Unknown error"
        cause = entry.cause if entry else (exc.detail or "")
        remedy = entry.remedy if entry else "See the launcher.log file."
        severity = entry.severity if entry else "fatal"

        self.log.error(f"[{code}] {title} -- detail: {exc.detail or 'none'}")

        def show():
            self.status_var.set(f"ERROR {code}")
            self.status_lbl.configure(fg=THEME_DANGER if severity == "fatal" else THEME_ACCENT)
            self.btn_start.configure(state="normal" if not fatal else "normal")

            dlg = tk.Toplevel(self.root)
            dlg.title(f"Error {code}")
            dlg.configure(bg=THEME_BG)
            dlg.geometry("560x360")
            dlg.transient(self.root)
            dlg.grab_set()

            top_color = THEME_DANGER if severity == "fatal" else THEME_ACCENT

            tk.Label(dlg, text=f"  {code}  ", bg=top_color, fg="white",
                     font=("Segoe UI", 14, "bold")).pack(fill="x")
            tk.Label(dlg, text=title, bg=THEME_BG, fg=THEME_FG,
                     font=("Segoe UI", 13, "bold"), pady=12).pack()

            body = tk.Frame(dlg, bg=THEME_BG)
            body.pack(fill="both", expand=True, padx=18, pady=4)

            tk.Label(body, text="Possible cause:", bg=THEME_BG, fg=THEME_MUTED,
                     font=("Segoe UI", 9, "bold"), anchor="w").pack(fill="x")
            tk.Label(body, text=cause, bg=THEME_BG, fg=THEME_FG,
                     font=("Segoe UI", 10), wraplength=520, justify="left", anchor="w").pack(fill="x", pady=(0, 8))

            tk.Label(body, text="Remedy:", bg=THEME_BG, fg=THEME_MUTED,
                     font=("Segoe UI", 9, "bold"), anchor="w").pack(fill="x")
            tk.Label(body, text=remedy, bg=THEME_BG, fg=THEME_FG,
                     font=("Segoe UI", 10), wraplength=520, justify="left", anchor="w").pack(fill="x", pady=(0, 8))

            if exc.detail:
                tk.Label(body, text="Detail:", bg=THEME_BG, fg=THEME_MUTED,
                         font=("Segoe UI", 9, "bold"), anchor="w").pack(fill="x")
                tk.Label(body, text=str(exc.detail), bg=THEME_BG, fg=THEME_DANGER,
                         font=("Consolas", 9), wraplength=520, justify="left", anchor="w").pack(fill="x")

            btn_row = tk.Frame(dlg, bg=THEME_BG, pady=10)
            btn_row.pack(fill="x", side="bottom")
            ttk.Button(btn_row, text="Open Log", command=self._open_log_file).pack(side="left", padx=16)
            ttk.Button(btn_row, text="OK", command=dlg.destroy, style="Accent.TButton").pack(side="right", padx=16)
        self.root.after(0, show)

    def _open_log_file(self) -> None:
        try:
            log_file = self.log.log_file
            if platform.system() == "Windows":
                os.startfile(str(log_file))  # type: ignore[attr-defined]
            elif platform.system() == "Darwin":
                subprocess.Popen(["open", str(log_file)])
            else:
                subprocess.Popen(["xdg-open", str(log_file)])
        except Exception as exc:
            self.log.warn(f"Could not open log: {exc}")

    def _on_close(self) -> None:
        if self.is_running:
            if not messagebox.askyesno(
                "Exit Confirmation",
                "The bot is running. Are you sure you want to exit?\n"
                "Any open positions will be saved before shutdown."
            ):
                return
            self.stop_bot()
        else:
            self._stop_bot_process()
        self.log.info("==== Launcher closing ====")
        self.log.close()
        if self.lock_file and self.lock_file.exists():
            try:
                self.lock_file.unlink()
            except Exception:
                pass
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


# ── Entry point ───────────────────────────────────────────────────────────────
def main() -> int:
    try:
        app = LauncherApp()
        app.run()
        return 0
    except Exception:
        sys.stderr.write("FATAL launcher crash:\n" + traceback.format_exc())
        # Last resort to show the error while Tkinter isn't up yet: messagebox
        try:
            tk_root = tk.Tk()
            tk_root.withdraw()
            messagebox.showerror(
                "Trading Bot v1 - Critical Error",
                f"An unexpected error occurred (E014).\n\nDetail:\n{traceback.format_exc()[-800:]}",
            )
        except Exception:
            pass
        return 1


if __name__ == "__main__":
    if RUN_BOT_ARG in sys.argv[1:]:
        sys.exit(run_as_bundled_bot())
    sys.exit(main())
