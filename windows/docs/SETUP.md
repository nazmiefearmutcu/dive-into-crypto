# Trading Bot v1 - Windows Setup Guide

This document explains, step by step, how to install Trading Bot v1 on a Windows computer.

> **Prerequisite**: Windows 10 or 11 (64-bit), at least 4 GB RAM, 500 MB of free disk space.

---

## Method 1: Pre-built .exe (Recommended)

If you were given a ready-made `TradingBotV1` folder:

1. Copy the folder wherever you like. **Recommended: `C:\Users\<yourname>\Trading Bot v1\`**
   (Do not place it under `C:\Program Files\` — a write-permission problem will trigger `E010`.)

2. Open the folder. Inside you will find `TradingBotV1.exe` and other binary files.

3. **Double-click TradingBotV1.exe**.

4. On the first launch, a **Windows SmartScreen** warning may appear:
   - It reads "Windows protected your PC".
   - Click `More info` -> `Run anyway`.
   - This is the normal warning that every user sees for unsigned executables.

5. The Tkinter window opens; click the `Start Bot` button.

6. Within ~10 seconds the dashboard opens in your default browser at
   `http://127.0.0.1:8080`.

7. To create a **desktop shortcut**: double-click the `packaging\create_shortcut.vbs` file. A `Trading Bot v1.lnk` shortcut is created on the desktop.

---

## Method 2: Building From Source

If you were given only the project folder, you need to produce the `.exe` yourself.

### Step 1: Install Python

1. Go to https://www.python.org/downloads/.
2. Download the latest **Python 3.11** or **3.12** version.
3. When the installer starts, **be sure** to check the `Add Python to PATH` checkbox.
4. Open Command Prompt (cmd) and type `python --version` to confirm the version is detected.

### Step 2: Extract the project and build

1. Copy the `TBV1_Windows` project to the target computer (example: `C:\Trading Bot v1\`).
2. Open the project folder.
3. **Double-click** the `packaging\build_windows.bat` file.
4. The build script automatically does the following (5-10 minutes):
   - Creates a virtual environment (`.venv\`)
   - Installs all dependencies (`requirements.txt`)
   - Installs PyInstaller
   - Produces `dist\TradingBotV1\TradingBotV1.exe`

5. When finished, the script prints "SUCCESS!". You can press Enter to close it.

6. The `dist\TradingBotV1\` folder is now portable. Copy it wherever you like and double-click `TradingBotV1.exe`.

---

## Method 3: Developer Mode (without building)

If you don't want to build the `.exe`, you can run it directly with Python:

1. Complete Step 1 above (installing Python).
2. In the project folder, double-click the `packaging\run_dev.bat` file.
3. On the first launch, dependencies are installed (1-2 minutes).
4. After that, every launch brings up the launcher window and runs on a double-click.

---

## Configuration

### Adjusting the bot's behavior

The bot's weights, indicators, and risk parameters are defined in the `app/config/default.yaml` file.

You can edit this file by right-clicking it > `Open with Notepad`. You can also make changes from the dashboard's **Settings** tab (saved automatically).

### Adding Binance API keys (for live trading)

1. In the project folder, create a file named `app\.env` (in Notepad, "New text document" > rename it to `.env`).
2. Write the following into it:

```
BINANCE_API_KEY=your_key_here
BINANCE_API_SECRET=your_secret_key_here
```

3. To generate a key on Binance: https://www.binance.com/en/my/settings/api-management

> **Security**: Give your API keys in the `.env` file the **least privilege** possible (only "Read" + "Spot Trading" permissions). "Withdrawals" must ABSOLUTELY NOT be enabled.

If there is no `.env` file, the bot keeps running in **paper-trading** (simulated money) mode. It shows a warning but does not stop (code: `E006`).

---

## Common Issues

| Issue                          | Remedy                                 |
|--------------------------------|----------------------------------------|
| SmartScreen `Run anyway` doesn't appear | Right-click the exe > Properties > "Unblock" |
| `python` command not found      | Reinstall Python, check `Add to PATH` |
| Port 8080 already in use        | Task Manager > end the python.exe processes |
| Dependencies won't install      | The `pip install` error may be due to internet/firewall |
| Antivirus deletes the exe       | Add it to the "Whitelist" / "Exclusion" list |
| Browser didn't open             | Manually type the address `http://127.0.0.1:8080` |

For all error codes and their remedies: **[ERROR_CODES.md](ERROR_CODES.md)**

---

## Uninstalling

1. Delete the `dist\TradingBotV1\` folder.
2. Delete the source project folder.
3. Delete the desktop shortcut.
4. If you want to remove Python itself: Settings > Apps > Python 3.x > Uninstall.

Trading Bot v1 creates **no** registry entries or system files. All data is kept inside its own folder.
