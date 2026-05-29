# Trading Bot v1 - Windows Edition

Trading Bot v1 packaged as a one-click, iconed, native Windows application.

> **Existing TBV1 source**: copied unchanged from the local TBV1 backup folder.
> **This package**: `~/Desktop/Projeler/proje/TBV1_Windows/` (new packaging layer for the Windows .exe)

## Quick Overview

```
TBV1_Windows/
├── app/                    Trading bot source code (copied from TBV1_backup)
│   ├── src/                Consensus engine with 15+ indicators
│   ├── dashboard/          FastAPI dashboard (HTML/CSS/JS)
│   ├── config/             default.yaml
│   ├── runtime/            (log/state goes here when run)
│   └── requirements.txt
├── launcher/
│   ├── tbv1_launcher.py    Tkinter splash + preflight + uvicorn + browser
│   └── error_codes.py      E001..E020 error catalog (English)
├── packaging/
│   ├── TradingBotV1.spec   PyInstaller spec (for the Windows .exe)
│   ├── build_windows.bat   1-click build script
│   ├── run_dev.bat         Developer mode (no build)
│   ├── create_shortcut.vbs Desktop shortcut creator
│   ├── tbv1.ico            Windows icon (7 resolutions)
│   ├── tbv1_256.png        256x256 PNG (Tkinter fallback)
│   └── icon_source.png     Original source image
└── docs/
    ├── SETUP.md            Step-by-step Windows setup guide
    └── ERROR_CODES.md      E001..E020 English descriptions
```

## Running

### If a ready-made .exe is available on Windows
1. Double-click `dist\TradingBotV1\TradingBotV1.exe`
2. Press the `Start Bot` button
3. The browser opens automatically; the dashboard is ready

### Building from source on Windows
1. Double-click `packaging\build_windows.bat` (5-10 minutes)
2. Double-click `dist\TradingBotV1\TradingBotV1.exe`

### Developer mode (no build)
1. Double-click `packaging\run_dev.bat`

Detailed setup: **[docs/SETUP.md](docs/SETUP.md)**

## Features

- ✓ **One-click run** - behaves like a native Windows application
- ✓ **TB icon** - at every size (16/24/32/48/64/128/256 px), crisp in the task bar
- ✓ **Automatic browser launch** - a new tab in your default browser
- ✓ **Browser notice screen** - a "Dashboard opened in your browser" info dialog
- ✓ **Tkinter status window** - live Running / Stopped / Error state
- ✓ **20 distinct error codes** - an English title + cause + remedy for each error
- ✓ **Single-instance lock** - if two copies are opened at once, it warns with `E015`
- ✓ **Stale-lock recovery** - locks older than 3 minutes are cleared automatically
- ✓ **Crash supervisor** - if the dashboard crashes, it auto-restarts
- ✓ **Writable runtime** - log/state in a user-writable folder next to the .exe
- ✓ **launcher.log** - all events are logged with timestamp + level

## Error Handling

The screen shown to the user when an error occurs:

```
┌─────────────────────────────────────────────┐
│ ▓▓▓ E003 ▓▓▓                                 │  ← red (fatal) / yellow (warning)
├─────────────────────────────────────────────┤
│ Port 8080 is in use by another process      │
│                                              │
│ Possible cause:                              │
│ Another program on the computer has opened   │
│ port 8080. (A previous TBV1 is still up.)    │
│                                              │
│ Remedy:                                      │
│ In Task Manager > Details tab, end the       │
│ 'python.exe' or 'TradingBotV1.exe'           │
│ process. Then try again.                     │
│                                              │
│ Detail:                                      │
│ 127.0.0.1:8080                              │
│                                              │
│              [Open Log]     [OK]            │
└─────────────────────────────────────────────┘
```

Full list of error codes: **[docs/ERROR_CODES.md](docs/ERROR_CODES.md)**

## System Architecture

```
Double-click (.exe)
    │
    ▼
TradingBotV1.exe         (PyInstaller --onedir bundle)
    │
    ├── tbv1_launcher.py (Tkinter main process, GUI)
    │       │
    │       ├── Preflight checks (Python, port, config, permissions, RAM, lock)
    │       │       ↓ if any fails → LauncherError(E0xx)
    │       │
    │       ├── uvicorn (worker thread, same process)
    │       │       └── dashboard.app:app (FastAPI)
    │       │
    │       └── webbrowser.open("http://127.0.0.1:8080")
    │
    ▼
Default browser → Dashboard UI
```

## Limitations

- A Windows .exe **cannot be built** from macOS (PyInstaller does not support cross-compilation). The build must be done on Windows.
- In `--onedir` mode the total size is ~250-350 MB (including pandas/numpy). This is the practical minimum with PyInstaller.
- An unsigned .exe triggers a SmartScreen warning on first launch; the user must click "Run anyway". (This warning can be removed with a code-signing certificate.)

## License and Liability

This package is Trading Bot v1 packaged into a Windows .exe format. The user is responsible for trading decisions. It has been tested by the developer in paper-trading mode; live trading is done at the user's own risk.
