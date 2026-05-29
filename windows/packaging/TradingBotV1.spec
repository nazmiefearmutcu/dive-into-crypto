# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec - Trading Bot v1 Windows binary.

Build:
    Windows: pyinstaller packaging/TradingBotV1.spec --clean --noconfirm
    Output : dist/TradingBotV1/TradingBotV1.exe (+ dependency folder)

NOTE: we use --onedir (not --onefile); start-up time is much faster and
antivirus false positives are reduced. The user copies the ENTIRE
dist/TradingBotV1/ folder.

Configuration:
- Launcher: launcher/tbv1_launcher.py
- App code: app/ (src/, dashboard/, config/)
- Icon: packaging/tbv1.ico
- Executable name: TradingBotV1
"""
from pathlib import Path

# ── Path resolver ───────────────────────────────────────────────────────────
SPEC_DIR = Path(SPECPATH).resolve()
PROJECT_ROOT = SPEC_DIR.parent
APP_DIR = PROJECT_ROOT / "app"
LAUNCHER_DIR = PROJECT_ROOT / "launcher"
ICON = SPEC_DIR / "tbv1.ico"

# ── Datas: dashboard templates + static + config ───────────────────────────
# Format: (source, target-folder-inside-bundle)
datas = [
    # Dashboard HTML/CSS/JS
    (str(APP_DIR / "dashboard" / "templates"), "app/dashboard/templates"),
    (str(APP_DIR / "dashboard" / "static"), "app/dashboard/static"),
    # Configuration
    (str(APP_DIR / "config"), "app/config"),
    # Runtime folder (with an empty placeholder)
    # PyInstaller can't copy an empty folder, so we add a .placeholder file
]

# Create a .placeholder file for runtime (PyInstaller skips empty folders)
runtime_placeholder = APP_DIR / "runtime" / ".placeholder"
runtime_placeholder.parent.mkdir(parents=True, exist_ok=True)
if not runtime_placeholder.exists():
    runtime_placeholder.write_text(
        "This file ensures the runtime/ folder is included in the PyInstaller bundle.\n"
        "Do not delete. Trading Bot v1 writes log/state here.\n",
        encoding="utf-8",
    )
datas.append((str(APP_DIR / "runtime"), "app/runtime"))

# Launcher icon + ico
datas.append((str(ICON), "."))
png_icon = SPEC_DIR / "tbv1_256.png"
if png_icon.exists():
    datas.append((str(png_icon), "."))

# Trading bot source code - taken as hiddenimport, not as data,
# but the files must be on pathex for PyInstaller's analysis
pathex = [
    str(APP_DIR),
    str(LAUNCHER_DIR),
]

# ── Hidden imports: dynamically loaded modules ─────────────────────────────
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
    # Uvicorn (invoked as a module)
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
    # Pandas + numpy (for the bot's consensus engine)
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
    # Our application code
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

# Automatic detection of src submodules
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

# Dashboard submodules
for root, dirs, files in os.walk(str(APP_DIR / "dashboard")):
    rel = os.path.relpath(root, str(APP_DIR))
    if "__pycache__" in rel:
        continue
    for f in files:
        if f.endswith(".py") and f != "__init__.py":
            mod = rel.replace(os.sep, ".") + "." + f[:-3]
            if mod not in hiddenimports:
                hiddenimports.append(mod)

# ── Excludes: heavy packages we don't need ─────────────────────────────────
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
    upx=False,                  # UPX triggers some antivirus tools; off
    console=False,              # GUI app, don't open a console window
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
