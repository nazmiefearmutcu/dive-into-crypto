# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the Dive Into Crypto desktop backend (`dive-desktop`).

Build (from desktop/backend):

    cd desktop/backend && uv run --with pyinstaller pyinstaller dive.spec --noconfirm

Output: dist/dive-desktop/ — a ONEDIR bundle on purpose, NOT onefile. A onefile
self-extracting executable is a classic antivirus false-positive trigger and
pays a temp-dir extraction cost on every launch. Afterwards run
dist/dive-desktop/dive-desktop.exe; it serves 127.0.0.1:8780 and opens the UI.

How the frozen app finds its bundled resources
----------------------------------------------
Bundled, read-only resources live under ``sys._MEIPASS`` (the ``_internal``
folder of the onedir build). Each one is listed in ``datas`` below and resolved
at runtime from that root:

* UI bundle — api/app.py: ``_BASE_DIR = Path(getattr(sys, "_MEIPASS", <checkout>))``
  and ``_UI_DIST = _BASE_DIR / "ui" / "dist"``. This spec copies
  desktop/ui/dist to ``ui/dist`` under sys._MEIPASS.
* Engine config — engine/loader.py reads ``engine/config/default.yaml`` next to
  the package (copied by the ``datas`` entry for ``engine/config``).
* Built-in claim registry (CL-001) — scan/claims.py reads
  ``docs/claims`` under sys._MEIPASS (copied by the ``datas`` entry for
  ``docs/claims``). Newly registered claims are NOT written there: the frozen
  build writes them to the writable runtime folder (``<app>/runtime/claims``).

No separate mirror of the UI bundle is needed: the packaging job
(.github/workflows/release.yml, ``package-desktop``) runs this spec and zips
``dist/dive-desktop`` as-is.

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
        # Engine config read via Path(__file__).parent / "config" / "default.yaml"
        # in engine/loader.py. PyInstaller does not collect non-Python data files
        # on its own, so without this entry every symbol build fails with
        # FileNotFoundError in the frozen app.
        (
            os.path.join(_ROOT, "src", "diveintocrypto_desktop", "engine", "config", "default.yaml"),
            "diveintocrypto_desktop/engine/config",
        ),
        # Built-in claims registry (CL-001, docs/claims/*.yaml). Bundled under
        # sys._MEIPASS/docs/claims so the frozen app ships the pre-registered claims
        # (scan/claims.py reads them from there; new claims go to the runtime dir).
        (os.path.join(_ROOT, os.pardir, os.pardir, "docs", "claims"), "docs/claims"),
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
