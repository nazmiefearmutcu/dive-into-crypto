# Trading Bot v1 - Error Codes

| Code | Title | Severity | Remedy |
|---|---|---|---|
| **E001** | Incompatible Python version | fatal | Install Python 3.11+ from https://www.python.org/downloads/ and check 'Add to PATH'... |
| **E002** | Required package not found | fatal | On the command line, run: pip install -r requirements.txt. When running the package... |
| **E003** | Port 8080 is in use by another process | fatal | In Task Manager > Details tab, end the 'python.exe' or 'TradingBotV1.exe' process... |
| **E004** | Configuration file missing | fatal | The application folder may be corrupted. Reinstall, or copy a backup default.ya... |
| **E005** | Configuration file cannot be read | fatal | Open the file in a text editor (Notepad++, VS Code) and fix the broken line.... |
| **E006** | .env file missing | warning | For live trading: create a file named .env in the application folder and add BI... |
| **E007** | Dashboard server failed to start | fatal | (1) Add TradingBotV1.exe to the Windows Defender > 'Allowed apps' list. (2) Open... |
| **E008** | Dashboard not responding | fatal | First restart the computer and try again. If it persists: in Task Manager... |
| **E009** | Browser did not open | warning | Open your browser (Chrome/Edge/Firefox) manually and type into the address bar: h... |
| **E010** | No write permission for folder | fatal | (1) Right-click TradingBotV1.exe and choose 'Run as administrator'. (2... |
| **E011** | Disk full | fatal | Run the Disk Cleanup tool. Delete the files in C:\Users\<yourname>\AppData\Local\Temp... |
| **E012** | Invalid Binance API key | warning | Update the BINANCE_API_KEY and BINANCE_API_SECRET values in the .env file from your Binance... |
| **E013** | No connection to Binance | warning | Open https://api.binance.com/api/v3/ping in your browser. If no JSON is returned: ... |
| **E014** | Unexpected internal error | fatal | Copy the LAST 100 lines of packaging/launcher.log and share them with the developer... |
| **E015** | Multiple instances detected | fatal | Switch to the existing instance and use that. If an old copy is stuck holding the lock: Task... |
| **E016** | Windows Firewall is blocking | fatal | In Windows Defender Firewall > 'Allow an app or feature', check TradingB... |
| **E017** | Insufficient memory | warning | Close other programs (especially browsers, Discord, Slack). Restart the system... |
| **E018** | Clock setting is off | warning | In Windows Time settings > enable 'Set time automatically' and click 'Sync no... |
| **E019** | State file corrupted | warning | Automatic recovery is attempted. If it fails: delete the runtime/state.json file (exi... |
| **E020** | Run permission denied | fatal | On the SmartScreen warning screen, click 'More info' > 'Run anyway'. For antivirus ... |

## Detailed Descriptions

### E001 - Incompatible Python version

**Severity:** `fatal`

**Possible Cause:** Trading Bot v1 requires at least Python 3.10. The current version is too old.

**Remedy:** Install Python 3.11+ from https://www.python.org/downloads/ and check the 'Add to PATH' option.

### E002 - Required package not found

**Severity:** `fatal`

**Possible Cause:** One or more dependencies are not installed (fastapi, uvicorn, pandas, etc.).

**Remedy:** On the command line, run: pip install -r requirements.txt. You won't see this error when running the packaged .exe; it only appears when running from source.

### E003 - Port 8080 is in use by another process

**Severity:** `fatal`

**Possible Cause:** Another program on the computer has opened port 8080. (A previous TBV1 instance may still be running.)

**Remedy:** In Task Manager > Details tab, end the 'python.exe' or 'TradingBotV1.exe' process. Then try again. Alternative: change the Port field in the launcher window to 8081, etc.

### E004 - Configuration file missing

**Severity:** `fatal`

**Possible Cause:** config/default.yaml was not found. This file defines the bot's behavior.

**Remedy:** The application folder may be corrupted. Reinstall, or copy a backup default.yaml file into place.

### E005 - Configuration file cannot be read

**Severity:** `fatal`

**Possible Cause:** default.yaml contains invalid YAML syntax (indentation error, missing colon, etc.).

**Remedy:** Open the file in a text editor (Notepad++, VS Code) and fix the broken line. A detailed message is in the log file.

### E006 - .env file missing

**Severity:** `warning`

**Possible Cause:** The .env file containing the API keys was not found. The bot can keep running in paper-trading mode.

**Remedy:** For live trading: create a file named .env in the application folder and add the lines BINANCE_API_KEY=... and BINANCE_API_SECRET=... to it.

### E007 - Dashboard server failed to start

**Severity:** `fatal`

**Possible Cause:** The uvicorn subprocess exited as soon as it started. Antivirus may have blocked it, or a critical file may have been deleted.

**Remedy:** (1) Add TradingBotV1.exe to the Windows Defender > 'Allowed apps' list. (2) Open packaging/launcher.log and share the Python traceback from the last lines.

### E008 - Dashboard not responding

**Severity:** `fatal`

**Possible Cause:** The server started but did not produce an HTTP response within 30 seconds. This may be a slow disk, low memory, or an infinite loop.

**Remedy:** First restart the computer and try again. If it persists: check RAM usage in Task Manager > Performance tab (if >90%, close other programs).

### E009 - Browser did not open

**Severity:** `warning`

**Possible Cause:** The webbrowser module could not launch the default browser. A browser may not be installed, or the registry may be corrupted.

**Remedy:** Open your browser (Chrome/Edge/Firefox) manually and type into the address bar: http://127.0.0.1:8080  -- The bot is already running in the background; only the automatic browser launch failed.

### E010 - No write permission for folder

**Severity:** `fatal`

**Possible Cause:** The application cannot write log/state to the runtime/ folder. This folder may be read-only or locked by antivirus.

**Remedy:** (1) Right-click TradingBotV1.exe and choose 'Run as administrator'. (2) Move the application folder out of C:\Program Files and into your Documents folder.

### E011 - Disk full

**Severity:** `fatal`

**Possible Cause:** There is not enough space left on the disk. The trading bot writes a log every minute; about 10MB of free space is required.

**Remedy:** Run the Disk Cleanup tool. Delete the files in C:\Users\<yourname>\AppData\Local\Temp. Leave at least 500MB of free space.

### E012 - Invalid Binance API key

**Severity:** `warning`

**Possible Cause:** Binance returned HTTP 401/403. The key is wrong, expired, or IP whitelisting is active.

**Remedy:** Update the BINANCE_API_KEY and BINANCE_API_SECRET values in the .env file by generating and copying a new key from your Binance account. If there is an IP restriction, disable it or add your public IP.

### E013 - No connection to Binance

**Severity:** `warning`

**Possible Cause:** api.binance.com is unreachable. The internet may be down, there may be a DNS issue, or Binance access may be restricted in your region.

**Remedy:** Open https://api.binance.com/api/v3/ping in your browser. If no JSON is returned: (1) Try a VPN (within legal limits). (2) Restart your modem. (3) Grant Python.exe internet access in your antivirus/firewall.

### E014 - Unexpected internal error

**Severity:** `fatal`

**Possible Cause:** An uncaught Python exception occurred. This should be reported as a bug.

**Remedy:** Copy the LAST 100 lines of packaging/launcher.log and share them with the developer. The bot can be stopped and restarted, but the same error may recur.

### E015 - Multiple instances detected

**Severity:** `fatal`

**Possible Cause:** TradingBotV1 is already running (a lockfile exists). If two copies run at the same time, the data will be corrupted.

**Remedy:** Switch to the existing instance and use that. If an old copy is stuck holding the lock: end TradingBotV1.exe in Task Manager and delete the runtime/.launcher.lock file.

### E016 - Windows Firewall is blocking

**Severity:** `fatal`

**Possible Cause:** Windows Firewall is preventing the dashboard from connecting to itself (loopback 127.0.0.1).

**Remedy:** In Windows Defender Firewall > 'Allow an app or feature', check both the 'Private' and 'Public' columns for TradingBotV1.exe.

### E017 - Insufficient memory

**Severity:** `warning`

**Possible Cause:** Less than 200MB of available memory on the system. Windows may freeze if the bot is started.

**Remedy:** Close other programs (especially browsers, Discord, Slack). Restart the system and try again. This is common on very old PCs (under 4GB RAM).

### E018 - Clock setting is off

**Severity:** `warning`

**Possible Cause:** The Windows system clock differs from the Binance server clock by more than 1 minute. Binance is rejecting signature verification.

**Remedy:** In Windows Time settings > enable 'Set time automatically' and click 'Sync now'. If NTP server access is blocked, use time.windows.com for time synchronization.

### E019 - State file corrupted

**Severity:** `warning`

**Possible Cause:** runtime/state.json contains invalid JSON. It may have been left half-written on the previous exit.

**Remedy:** Automatic recovery is attempted. If it fails: delete the runtime/state.json file (existing positions are lost in paper mode; in live mode only tracking metadata is lost).

### E020 - Run permission denied

**Severity:** `fatal`

**Possible Cause:** Windows SmartScreen or antivirus is blocking the unsigned .exe.

**Remedy:** On the SmartScreen warning screen, click 'More info' > 'Run anyway'. For antivirus: add TradingBotV1.exe to the 'Safe apps' (whitelist / exclusion) list. Most are false positives.
