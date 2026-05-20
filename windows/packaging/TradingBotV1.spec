# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec - Trading Bot v1 Windows binary.

Build:
    Windows: pyinstaller packaging/TradingBotV1.spec --clean --noconfirm
    Cikti  : dist/TradingBotV1/TradingBotV1.exe (+ baglim klasoru)

NOT: --onedir kullaniyoruz (--onefile degil); start-up suresi cok daha hizli ve
antivirus yanlis pozitif vermesi azalir. Kullanici dist/TradingBotV1/ klasorunun
TAMAMINI kopyalar.

Yapilandirma:
- Launcher: launcher/tbv1_launcher.py
- App kodu: app/ (src/, dashboard/, config/)
- Ikon: packaging/tbv1.ico
- Calistirilabilir adi: TradingBotV1
"""
from pathlib import Path

# ── Yol cozumleyici ─────────────────────────────────────────────────────────
SPEC_DIR = Path(SPECPATH).resolve()
PROJECT_ROOT = SPEC_DIR.parent
APP_DIR = PROJECT_ROOT / "app"
LAUNCHER_DIR = PROJECT_ROOT / "launcher"
ICON = SPEC_DIR / "tbv1.ico"

# ── Datas: dasbhoard sablonlari + static + config ──────────────────────────
# Format: (kaynak, hedef-klasor-bundle-icinde)
datas = [
    # Dashboard HTML/CSS/JS
    (str(APP_DIR / "dashboard" / "templates"), "app/dashboard/templates"),
    (str(APP_DIR / "dashboard" / "static"), "app/dashboard/static"),
    # Konfigurasyon
    (str(APP_DIR / "config"), "app/config"),
    # Runtime klasoru (bos placeholder ile)
    # PyInstaller bos klasoru kopyalayamaz, .placeholder dosyasi ekleyecegiz
]

# Runtime icin .placeholder dosyasi olustur (PyInstaller bos klasoru atlar)
runtime_placeholder = APP_DIR / "runtime" / ".placeholder"
runtime_placeholder.parent.mkdir(parents=True, exist_ok=True)
if not runtime_placeholder.exists():
    runtime_placeholder.write_text(
        "Bu dosya runtime/ klasorunun PyInstaller bundle'a dahil edilmesini saglar.\n"
        "Silmeyin. Trading Bot v1 buraya log/state yazar.\n",
        encoding="utf-8",
    )
datas.append((str(APP_DIR / "runtime"), "app/runtime"))

# Launcher icon + ico
datas.append((str(ICON), "."))
png_icon = SPEC_DIR / "tbv1_256.png"
if png_icon.exists():
    datas.append((str(png_icon), "."))

# Trading bot kaynak kodu - data olarak degil hiddenimport olarak alacagiz
# ama dosyalar PyInstaller analysis'i icin pathex'te olmali
pathex = [
    str(APP_DIR),
    str(LAUNCHER_DIR),
]

# ── Hidden imports: dinamik yuklenenler ────────────────────────────────────
hiddenimports = [
    # FastAPI / Starlette
    "fastapi",
    "fastapi.applications",
    "starlette",
    "starlette.routing",
    "starlette.responses",
    "starlette.middleware",
    "starlette.staticfiles",
    "starlette.templating",
    # Uvicorn (modul olarak invoke edilir)
    "uvicorn",
    "uvicorn.main",
    "uvicorn.loops.auto",
    "uvicorn.loops.asyncio",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.protocols.websockets.wsproto_impl",
    "uvicorn.lifespan.on",
    "uvicorn.logging",
    "uvicorn.workers",
    # Jinja2
    "jinja2",
    "jinja2.ext",
    # Pydantic v2
    "pydantic",
    "pydantic.deprecated.decorator",
    "pydantic_core",
    "pydantic.json_schema",
    # Pandas + numpy (botun consensus engine'i icin)
    "pandas",
    "pandas._libs",
    "pandas._libs.tslibs",
    "numpy",
    "numpy.core._dtype_ctypes",
    # TA-Lib / ta indicators
    "ta",
    "ta.trend",
    "ta.momentum",
    "ta.volatility",
    "ta.volume",
    # Binance client
    "binance",
    "binance.client",
    "binance.exceptions",
    # YAML / dotenv
    "yaml",
    "dotenv",
    # HTTPX
    "httpx",
    # Multipart
    "multipart",
    "python_multipart",
    # Application kodumuz
    "dashboard.app",
    "src.main",
    "src.services.bot_service",
    "src.consensus",
    "src.indicators",
    "src.data",
    "src.trading",
    "src.control",
    "src.persistence",
    "src.monitoring",
    "src.utils",
    "src.utils.logger",
    "src.control.config_watcher",
]

# Otomatik src alt-modul tespiti
import os
for root, dirs, files in os.walk(str(APP_DIR / "src")):
    rel = os.path.relpath(root, str(APP_DIR))
    if "__pycache__" in rel:
        continue
    for f in files:
        if f.endswith(".py") and f != "__init__.py":
            mod = rel.replace(os.sep, ".") + "." + f[:-3]
            if mod not in hiddenimports:
                hiddenimports.append(mod)

# Dashboard alt-modulleri
for root, dirs, files in os.walk(str(APP_DIR / "dashboard")):
    rel = os.path.relpath(root, str(APP_DIR))
    if "__pycache__" in rel:
        continue
    for f in files:
        if f.endswith(".py") and f != "__init__.py":
            mod = rel.replace(os.sep, ".") + "." + f[:-3]
            if mod not in hiddenimports:
                hiddenimports.append(mod)

# ── Excludes: gerek olmayan agir paketler ──────────────────────────────────
excludes = [
    "matplotlib",
    "scipy",
    "tornado",
    "PyQt5",
    "PyQt6",
    "PySide2",
    "PySide6",
    "IPython",
    "jupyter",
    "notebook",
    "pytest",
    "sphinx",
    "test",
    "tests",
]

# ── Analysis ─────────────────────────────────────────────────────────────────
block_cipher = None

a = Analysis(
    [str(LAUNCHER_DIR / "tbv1_launcher.py")],
    pathex=pathex,
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=excludes,
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="TradingBotV1",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,                  # UPX bazi antivirusleri tetikler; off
    console=False,              # GUI app, console penceresi acmasin
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(ICON),
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="TradingBotV1",
)
