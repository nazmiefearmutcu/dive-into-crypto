# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the Dive Into Crypto desktop backend (`dive-desktop`).

Build (from desktop/backend):

    cd desktop/backend && uv run --with pyinstaller pyinstaller dive.spec --noconfirm

Output: dist/dive-desktop/ — a ONEDIR bundle on purpose, NOT onefile. A onefile
self-extracting executable is a classic antivirus false-positive trigger and
pays a temp-dir extraction cost on every launch. Afterwards run
dist/dive-desktop/dive-desktop.exe; it serves 127.0.0.1:8780 and opens the UI.

How the frozen app finds the UI bundle
--------------------------------------
api/app.py resolves the prebuilt UI as:

    _UI_DIST = Path(__file__).resolve().parents[4] / "ui" / "dist"

In the checkout (app.py at desktop/backend/src/diveintocrypto_desktop/api/app.py)
parents[4] == desktop/, i.e. desktop/ui/dist. In a frozen onedir build the
module lives at <app>/_internal/diveintocrypto_desktop/api/app.pyc, so
parents[4] is one level ABOVE the app folder — the untouched path math can
never see inside the bundle. Two complementary measures close that gap:

1. This spec copies desktop/ui/dist to sys._MEIPASS/ui/dist (datas dest
   "ui/dist"), which is exactly where the sys._MEIPASS fallback in api/app.py
   looks for it.
2. The packaging job in .github/workflows/release.yml (`package-desktop`)
   mirrors the bundle to <dist-root>/ui/dist — the location parents[4]
   resolves to in the frozen tree — so the legacy resolution ALSO finds it
   when the whole dist/ tree is extracted together. See docs/packaging.md.

pandas / numpy / aiohttp / fastapi are picked up automatically by Analysis;
the hiddenimports below cover uvicorn's dynamically-selected standard extras,
which static analysis cannot see through uvicorn's import-time configuration.
"""

import os

# desktop/backend — the directory containing this spec (SPECPATH is injected
# by PyInstaller when it evaluates the spec).
_ROOT = os.path.abspath(SPECPATH)
# The committed prebuilt UI bundle that api/app.py serves at "/".
_UI_DIST = os.path.join(_ROOT, os.pardir, "ui", "dist")

a = Analysis(
    # Analyze the console-script target directly. __main__.py imports the rest
    # of the package absolutely (`from diveintocrypto_desktop.api.app import
    # app`), which resolves thanks to pathex below — no extra launcher script
    # needed for the `dive-desktop` entry point.
    [os.path.join(_ROOT, "src", "diveintocrypto_desktop", "__main__.py")],
    # src-layout: the package is importable from src/.
    pathex=[os.path.join(_ROOT, "src")],
    binaries=[],
    datas=[
        # Bundled under sys._MEIPASS as "ui/dist" (see header): the
        # frozen-build fallback location in api/app.py.
        (_UI_DIST, "ui/dist"),
    ],
    hiddenimports=[
        # uvicorn[standard] — dynamically imported, invisible to Analysis.
        "uvicorn.logging",
        "uvicorn.loops",
        "uvicorn.loops.auto",
        "uvicorn.loops.asyncio",
        "uvicorn.protocols.http.auto",
        "uvicorn.protocols.http.h11_impl",
        "uvicorn.protocols.http.httptools_impl",
        "uvicorn.protocols.websockets.auto",
        "uvicorn.protocols.websockets.websockets_impl",
        "uvicorn.protocols.websockets.wsproto_impl",
        "uvicorn.lifespan.on",
        "uvicorn.lifespan.off",
        # Imported directly by the app / root e2e suite surface.
        "websockets",
        "aiohttp",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,  # onedir: binaries go to COLLECT, not the exe
    name="dive-desktop",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,  # UPX packing triples AV false-positive rates; leave off
    console=True,  # terminal-launched service; logs to stdout (api/app.py)
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="dive-desktop",
)
