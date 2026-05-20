"""Trading Bot v1 - Windows Launcher.

Tek bir cift-tiklamayla calisir:
  1. Tkinter splash ekrani aciyor (uygulama acilirken kullaniciya bilgi verir).
  2. Preflight kontrolleri (Python, port, config, .env) yapip hata kodu ile durur.
  3. Uvicorn'u alt surec olarak baslatip HTTP 200'e kadar bekliyor.
  4. Varsayilan tarayicida yeni sekmede http://127.0.0.1:8080 aciyor.
  5. "Calisiyor" durum penceresini gosteriyor; kullanici buradan botu durdurabiliyor.

PyInstaller ile paketlenince tek .exe olur. .exe acilinca bu dosya calisir.

Bagimliliklar:
  - tkinter        (stdlib)
  - urllib         (stdlib)
  - subprocess     (stdlib)
  - webbrowser     (stdlib)
  - threading      (stdlib)
  - PyInstaller'in icine gomulu Python + uvicorn + FastAPI bundle

KOSULLAR:
  - "app/" klasoru launcher.py ile yan yana DURMALI (PyInstaller bunu --add-data ile gomer).
  - "tbv1.ico" ayni klasorde.

KULLANIM:
  python launcher/tbv1_launcher.py            # gelistirici modu
  TradingBotV1.exe                            # paketlenmis surum (Windows)
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
from typing import Optional
from urllib.request import urlopen
from urllib.error import URLError

# Tkinter - stdlib, ekstra bagimllik yok
try:
    import tkinter as tk
    from tkinter import ttk, scrolledtext, messagebox
except Exception:  # pragma: no cover - tkinter sistem paketi
    sys.stderr.write("FATAL: Tkinter bulunamadi. Bu Windows .exe icin imkansiz. Python kuruluminiz bozuk.\n")
    sys.exit(2)

# Hata katalogu - ayni klasorde
try:
    from error_codes import ErrorCatalog, LauncherError
except ImportError:
    # PyInstaller icinde direct import calismayabilir; ayni klasore ekle
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from error_codes import ErrorCatalog, LauncherError  # type: ignore


# ── Sabitler ────────────────────────────────────────────────────────────────
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
THEME_ACCENT = "#f5c518"  # ikondaki sari tona uyumlu
THEME_DANGER = "#e0524a"
THEME_OK = "#3ad28a"
THEME_MUTED = "#888888"


# ── PyInstaller path helper'lari ────────────────────────────────────────────
def resource_dir() -> Path:
    """PyInstaller --onefile ile paketlenmisse _MEIPASS, degilse bu dosyanin parent'i."""
    if hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS)  # type: ignore[attr-defined]
    return Path(__file__).resolve().parent


def app_dir() -> Path:
    """Calistirilabilirin yaninda 'app/' klasoru.

    PyInstaller spec dosyasi 'app' klasorunu _MEIPASS/app altina goem(uy)or; bu
    yaklasimda kullanici yazilabilir runtime/, config/, .env gibi dosyalari
    LAUNCHER YANINDAKI app/ klasorunde tutuyoruz, _MEIPASS read-only.
    """
    if hasattr(sys, "_MEIPASS"):
        # .exe yaninda olusturulan/var olan app/
        exe_dir = Path(sys.executable).resolve().parent
        external = exe_dir / "app"
        if external.exists():
            return external
        # ilk acilista MEIPASS'ten kopyala
        internal = Path(sys._MEIPASS) / "app"  # type: ignore[attr-defined]
        if internal.exists() and not external.exists():
            import shutil
            shutil.copytree(internal, external)
            return external
        return internal
    # Gelistirici modu
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
    """Hem dosyaya hem stdout'a hem de Tkinter Text widget'ina yazar."""

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


# ── Preflight checks ────────────────────────────────────────────────────────
def check_python_version() -> None:
    if sys.version_info < (3, 10):
        raise LauncherError("E001", detail=f"Mevcut: {sys.version.split()[0]}")


def check_port_free(host: str, port: int) -> None:
    """Loopback adresinde port bos mu? Bos degilse E003."""
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
    # YAML parse denemesi
    try:
        import yaml
        with open(cfg, "r", encoding="utf-8") as fh:
            yaml.safe_load(fh)
    except ImportError:
        raise LauncherError("E002", detail="pyyaml")
    except Exception as exc:
        raise LauncherError("E005", detail=str(exc))


def check_env_file(root: Path, log: Logger) -> None:
    """.env yoksa uyari (fatal degil — paper mode calismaya devam eder)."""
    env = root / ".env"
    if not env.exists():
        log.warn(f"[E006] .env bulunamadi: {env} -- paper-trading modunda devam edilebilir.")


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
    """Kullanilabilir RAM 200MB altindaysa E017 uyari (fatal degil)."""
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
                log.warn(f"[E017] Yetersiz bellek: {avail_mb:.0f}MB serbest.")
    except Exception:
        pass  # bellek kontrolu kritik degil


def acquire_lock(root: Path) -> Path:
    """Tek instance kilidi. Eskimisse (3 dk hareketsiz) sil ve tekrar al."""
    lockfile = root / "runtime" / LOCK_FILENAME
    lockfile.parent.mkdir(parents=True, exist_ok=True)
    if lockfile.exists():
        age = time.time() - lockfile.stat().st_mtime
        try:
            pid = int(lockfile.read_text(encoding="utf-8").strip() or "0")
        except Exception:
            pid = 0
        # Eski kilit (3 dk uzerinden gecmis) veya pid 0 -> stale
        if age > 180 or pid == 0:
            lockfile.unlink(missing_ok=True)
        else:
            raise LauncherError("E015", detail=f"PID={pid}, lock yas={age:.0f}s")
    lockfile.write_text(str(os.getpid()), encoding="utf-8")
    return lockfile


# ── Dashboard yonetimi ──────────────────────────────────────────────────────
class DashboardProcess:
    """Uvicorn alt surecini sarmalayan ince katman."""

    def __init__(self, root: Path, host: str, port: int, log: Logger):
        self.root = root
        self.host = host
        self.port = port
        self.log = log
        self.proc: Optional[subprocess.Popen] = None

    def start(self) -> None:
        # PyInstaller ile paketlenmis exe'de uvicorn modul olarak gomulu olur;
        # subprocess yerine ic-Python (worker thread) ile baslat.
        if hasattr(sys, "_MEIPASS"):
            self._start_inproc()
        else:
            self._start_subprocess()

    def _start_inproc(self) -> None:
        """Tek-exe modunda alt-Python ayrildigi icin uvicorn'u ayni surecte thread'de calistir.

        Tkinter mainloop ana thread'de; uvicorn worker thread'inde."""
        def runner():
            try:
                # PYTHONPATH'i ayarla
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
                # Server'i log dosyasina yonlendirmek icin stdout/stderr swap kullaniyoruz
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

        # stdout reader thread - log dosyasina aktar
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
        """HTTP /healthz veya / ye GET at, 200 gelene kadar bekle."""
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
        raise LauncherError("E008", detail=f"son hata: {last_err}, port: {self.port}")

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
        # in-proc thread'i nazikce durdurmak icin uvicorn server.should_exit setlenebilirdi;
        # ancak Tkinter kapanisinda zaten butun surec olur.


# ── GUI ─────────────────────────────────────────────────────────────────────
class LauncherApp:
    """Ana Tkinter uygulamasi."""

    def __init__(self):
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self.root.configure(bg=THEME_BG)
        self.root.geometry("760x540")
        self.root.minsize(640, 420)

        # Ikon
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
        log_path = (app_dir().parent if app_dir().name == "app" else app_dir()) / "runtime" / LOG_FILENAME
        # daha guvenli: uygulamanin yanindaki packaging/launcher.log
        try:
            log_path = (Path(sys.executable).resolve().parent if hasattr(sys, "_MEIPASS")
                        else Path(__file__).resolve().parent.parent / "packaging") / LOG_FILENAME
        except Exception:
            pass
        self.log = Logger(log_path)
        self.log.info(f"==== {APP_NAME} v{APP_VERSION} acildi ====")
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
        self.status_var = tk.StringVar(value="HAZIR")
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
        self.btn_open = ttk.Button(actions, text="Tarayicida Ac", command=self.open_browser, state="disabled")
        self.btn_open.pack(side="right", padx=4)
        self.btn_stop = ttk.Button(actions, text="Durdur", command=self.stop_bot, state="disabled", style="Danger.TButton")
        self.btn_stop.pack(side="right", padx=4)
        self.btn_start = ttk.Button(actions, text="Botu Calistir", command=self.start_bot, style="Accent.TButton")
        self.btn_start.pack(side="right", padx=4)

        # ── Log panel ──
        log_frame = tk.Frame(self.root, bg=THEME_BG)
        log_frame.pack(fill="both", expand=True, padx=20, pady=10)
        tk.Label(log_frame, text="Kayit defteri", bg=THEME_BG, fg=THEME_MUTED,
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
                 text="Hata olusursa ekranda hata kodu ve cozumu gosterilir. Detayli log: packaging/launcher.log",
                 bg=THEME_BG, fg=THEME_MUTED, font=("Segoe UI", 8)).pack(side="left", padx=20)

    # ── Olaylar ──────────────────────────────────────────────────────────
    def start_bot(self) -> None:
        if self.is_running:
            return
        self.btn_start.configure(state="disabled")
        self.status_var.set("BASLATILIYOR...")
        self.status_lbl.configure(fg=THEME_ACCENT)
        threading.Thread(target=self._startup_flow, daemon=True, name="startup").start()

    def _startup_flow(self) -> None:
        try:
            root = app_dir()
            self.log.info(f"Uygulama kok: {root}")

            self.log.info("[1/7] Python surumu kontrol ediliyor...")
            check_python_version()

            self.log.info("[2/7] Disk yazma izni kontrol ediliyor...")
            check_writable(root)

            self.log.info("[3/7] Bellek kontrol ediliyor...")
            check_memory(self.log)

            self.log.info("[4/7] Tek-instance kilidi aliniyor...")
            self.lock_file = acquire_lock(root)

            self.log.info("[5/7] Port 8080 bos mu?")
            check_port_free(DEFAULT_HOST, DEFAULT_PORT)

            self.log.info("[6/7] Konfigurasyon dosyalari okunuyor...")
            check_config_exists(root)
            check_env_file(root, self.log)

            self.log.info("[7/7] Dashboard sunucusu baslatiliyor...")
            self.dashboard = DashboardProcess(root, DEFAULT_HOST, DEFAULT_PORT, self.log)
            self.dashboard.start()
            self.dashboard.wait_until_responding(STARTUP_TIMEOUT_SECONDS)

            self.log.info(f"BAGLANTI HAZIR: {self.url}")
            self._mark_running()
            time.sleep(0.4)
            self.open_browser()

        except LauncherError as exc:
            self._show_error(exc)
        except Exception:
            self.log.error(traceback.format_exc())
            self._show_error(LauncherError("E014", detail="bkz. launcher.log"))

    def _mark_running(self) -> None:
        def update():
            self.is_running = True
            self.status_var.set("CALISIYOR")
            self.status_lbl.configure(fg=THEME_OK)
            self.btn_open.configure(state="normal")
            self.btn_stop.configure(state="normal")
        self.root.after(0, update)

    def open_browser(self) -> None:
        try:
            ok = webbrowser.open(self.url, new=2)  # new=2 yeni sekme
            if not ok:
                raise LauncherError("E009", detail="webbrowser.open returned False")
            self.log.info(f"Tarayici acildi: {self.url}")
            self._show_browser_notice()
        except Exception:
            self.log.warn(traceback.format_exc())
            self._show_error(LauncherError("E009", detail="webbrowser modulu"), fatal=False)

    def _show_browser_notice(self) -> None:
        """Tarayici acildigi anda bir bilgilendirme dialog goster."""
        def show():
            messagebox.showinfo(
                title="Trading Bot v1 - Tarayici acildi",
                message=(
                    "Dashboard tarayicinizda acildi.\n\n"
                    f"Adres: {self.url}\n\n"
                    "Bu pencereyi acik birakin; pencereyi kapatirsaniz bot durur.\n"
                    "Manuel olarak durdurmak icin 'Durdur' butonunu kullanin."
                ),
            )
        self.root.after(150, show)

    def stop_bot(self) -> None:
        if not self.is_running:
            return
        self.log.info("Durdurma istegi alindi...")
        self.status_var.set("DURDURULUYOR...")
        if self.dashboard:
            self.dashboard.stop()
        self.is_running = False
        self.btn_open.configure(state="disabled")
        self.btn_stop.configure(state="disabled")
        self.btn_start.configure(state="normal")
        self.status_var.set("DURDURULDU")
        self.status_lbl.configure(fg=THEME_MUTED)
        # Kilidi birak
        if self.lock_file and self.lock_file.exists():
            try:
                self.lock_file.unlink()
            except Exception:
                pass

    def _show_error(self, exc: LauncherError, fatal: bool = True) -> None:
        entry = exc.entry
        code = exc.code
        title = entry.title if entry else "Bilinmeyen hata"
        cause = entry.cause if entry else (exc.detail or "")
        remedy = entry.remedy if entry else "launcher.log dosyasina bakin."
        severity = entry.severity if entry else "fatal"

        self.log.error(f"[{code}] {title} -- detay: {exc.detail or 'yok'}")

        def show():
            self.status_var.set(f"HATA {code}")
            self.status_lbl.configure(fg=THEME_DANGER if severity == "fatal" else THEME_ACCENT)
            self.btn_start.configure(state="normal" if not fatal else "normal")

            dlg = tk.Toplevel(self.root)
            dlg.title(f"Hata {code}")
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

            tk.Label(body, text="Olasi neden:", bg=THEME_BG, fg=THEME_MUTED,
                     font=("Segoe UI", 9, "bold"), anchor="w").pack(fill="x")
            tk.Label(body, text=cause, bg=THEME_BG, fg=THEME_FG,
                     font=("Segoe UI", 10), wraplength=520, justify="left", anchor="w").pack(fill="x", pady=(0, 8))

            tk.Label(body, text="Cozum:", bg=THEME_BG, fg=THEME_MUTED,
                     font=("Segoe UI", 9, "bold"), anchor="w").pack(fill="x")
            tk.Label(body, text=remedy, bg=THEME_BG, fg=THEME_FG,
                     font=("Segoe UI", 10), wraplength=520, justify="left", anchor="w").pack(fill="x", pady=(0, 8))

            if exc.detail:
                tk.Label(body, text="Detay:", bg=THEME_BG, fg=THEME_MUTED,
                         font=("Segoe UI", 9, "bold"), anchor="w").pack(fill="x")
                tk.Label(body, text=str(exc.detail), bg=THEME_BG, fg=THEME_DANGER,
                         font=("Consolas", 9), wraplength=520, justify="left", anchor="w").pack(fill="x")

            btn_row = tk.Frame(dlg, bg=THEME_BG, pady=10)
            btn_row.pack(fill="x", side="bottom")
            ttk.Button(btn_row, text="Logu Ac", command=self._open_log_file).pack(side="left", padx=16)
            ttk.Button(btn_row, text="Tamam", command=dlg.destroy, style="Accent.TButton").pack(side="right", padx=16)
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
            self.log.warn(f"Log acilamadi: {exc}")

    def _on_close(self) -> None:
        if self.is_running:
            if not messagebox.askyesno(
                "Cikis Onayi",
                "Bot calisiyor. Cikmak gercekten istediginizden emin misiniz?\n"
                "Tum acik islemler kaydedilip kapatilacak."
            ):
                return
            self.stop_bot()
        self.log.info("==== Launcher kapaniyor ====")
        self.log.close()
        if self.lock_file and self.lock_file.exists():
            try:
                self.lock_file.unlink()
            except Exception:
                pass
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


# ── Giris noktasi ───────────────────────────────────────────────────────────
def main() -> int:
    try:
        app = LauncherApp()
        app.run()
        return 0
    except Exception:
        sys.stderr.write("FATAL launcher crash:\n" + traceback.format_exc())
        # Tkinter henuz yokken hatayi gostermek icin son care: messagebox
        try:
            tk_root = tk.Tk()
            tk_root.withdraw()
            messagebox.showerror(
                "Trading Bot v1 - Kritik Hata",
                f"Beklenmedik bir hata olustu (E014).\n\nDetay:\n{traceback.format_exc()[-800:]}",
            )
        except Exception:
            pass
        return 1


if __name__ == "__main__":
    sys.exit(main())
