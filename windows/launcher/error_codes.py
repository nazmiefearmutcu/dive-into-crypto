"""Trading Bot v1 - Error Code Catalog.

Each error code consists of five components:
  - code:     unique identifier in the range E001..E099
  - title:    short English title (shown in the UI)
  - cause:    explanation of what may have caused it
  - remedy:   the steps the user should take
  - severity: 'fatal' | 'warning' | 'info'

Used via `raise LauncherError(code='E007', ...)` or `ErrorCatalog.get('E007')`.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional


@dataclass(frozen=True)
class ErrorEntry:
    code: str
    title: str
    cause: str
    remedy: str
    severity: str  # 'fatal' | 'warning' | 'info'


_CATALOG: Dict[str, ErrorEntry] = {
    "E001": ErrorEntry(
        code="E001",
        title="Incompatible Python version",
        cause="Trading Bot v1 requires at least Python 3.10. The current version is too old.",
        remedy="Install Python 3.11+ from https://www.python.org/downloads/ and check the 'Add to PATH' option.",
        severity="fatal",
    ),
    "E002": ErrorEntry(
        code="E002",
        title="Required package not found",
        cause="One or more dependencies are not installed (fastapi, uvicorn, pandas, etc.).",
        remedy="On the command line, run: pip install -r requirements.txt. You won't see this error when running the packaged .exe; it only appears when running from source.",
        severity="fatal",
    ),
    "E003": ErrorEntry(
        code="E003",
        title="Port 8080 is in use by another process",
        cause="Another program on the computer has opened port 8080. (A previous TBV1 instance may still be running.)",
        remedy="In Task Manager > Details tab, end the 'python.exe' or 'TradingBotV1.exe' process. Then try again. Alternative: change the Port field in the launcher window to 8081, etc.",
        severity="fatal",
    ),
    "E004": ErrorEntry(
        code="E004",
        title="Configuration file missing",
        cause="config/default.yaml was not found. This file defines the bot's behavior.",
        remedy="The application folder may be corrupted. Reinstall, or copy a backup default.yaml file into place.",
        severity="fatal",
    ),
    "E005": ErrorEntry(
        code="E005",
        title="Configuration file cannot be read",
        cause="default.yaml contains invalid YAML syntax (indentation error, missing colon, etc.).",
        remedy="Open the file in a text editor (Notepad++, VS Code) and fix the broken line. A detailed message is in the log file.",
        severity="fatal",
    ),
    "E006": ErrorEntry(
        code="E006",
        title=".env file missing",
        cause="The .env file containing the API keys was not found. The bot can keep running in paper-trading mode.",
        remedy="For live trading: create a file named .env in the application folder and add the lines BINANCE_API_KEY=... and BINANCE_API_SECRET=... to it.",
        severity="warning",
    ),
    "E007": ErrorEntry(
        code="E007",
        title="Dashboard server failed to start",
        cause="The uvicorn subprocess exited as soon as it started. Antivirus may have blocked it, or a critical file may have been deleted.",
        remedy="(1) Add TradingBotV1.exe to the Windows Defender > 'Allowed apps' list. (2) Open packaging/launcher.log and share the Python traceback from the last lines.",
        severity="fatal",
    ),
    "E008": ErrorEntry(
        code="E008",
        title="Dashboard not responding",
        cause="The server started but did not produce an HTTP response within 30 seconds. This may be a slow disk, low memory, or an infinite loop.",
        remedy="First restart the computer and try again. If it persists: check RAM usage in Task Manager > Performance tab (if >90%, close other programs).",
        severity="fatal",
    ),
    "E009": ErrorEntry(
        code="E009",
        title="Browser did not open",
        cause="The webbrowser module could not launch the default browser. A browser may not be installed, or the registry may be corrupted.",
        remedy="Open your browser (Chrome/Edge/Firefox) manually and type into the address bar: http://127.0.0.1:8080  -- The bot is already running in the background; only the automatic browser launch failed.",
        severity="warning",
    ),
    "E010": ErrorEntry(
        code="E010",
        title="No write permission for folder",
        cause="The application cannot write log/state to the runtime/ folder. This folder may be read-only or locked by antivirus.",
        remedy="(1) Right-click TradingBotV1.exe and choose 'Run as administrator'. (2) Move the application folder out of C:\\Program Files and into your Documents folder.",
        severity="fatal",
    ),
    "E011": ErrorEntry(
        code="E011",
        title="Disk full",
        cause="There is not enough space left on the disk. The trading bot writes a log every minute; about 10MB of free space is required.",
        remedy="Run the Disk Cleanup tool. Delete the files in C:\\Users\\<yourname>\\AppData\\Local\\Temp. Leave at least 500MB of free space.",
        severity="fatal",
    ),
    "E012": ErrorEntry(
        code="E012",
        title="Invalid Binance API key",
        cause="Binance returned HTTP 401/403. The key is wrong, expired, or IP whitelisting is active.",
        remedy="Update the BINANCE_API_KEY and BINANCE_API_SECRET values in the .env file by generating and copying a new key from your Binance account. If there is an IP restriction, disable it or add your public IP.",
        severity="warning",
    ),
    "E013": ErrorEntry(
        code="E013",
        title="No connection to Binance",
        cause="api.binance.com is unreachable. The internet may be down, there may be a DNS issue, or Binance access may be restricted in your region.",
        remedy="Open https://api.binance.com/api/v3/ping in your browser. If no JSON is returned: (1) Try a VPN (within legal limits). (2) Restart your modem. (3) Grant Python.exe internet access in your antivirus/firewall.",
        severity="warning",
    ),
    "E014": ErrorEntry(
        code="E014",
        title="Unexpected internal error",
        cause="An uncaught Python exception occurred. This should be reported as a bug.",
        remedy="Copy the LAST 100 lines of packaging/launcher.log and share them with the developer. The bot can be stopped and restarted, but the same error may recur.",
        severity="fatal",
    ),
    "E015": ErrorEntry(
        code="E015",
        title="Multiple instances detected",
        cause="TradingBotV1 is already running (a lockfile exists). If two copies run at the same time, the data will be corrupted.",
        remedy="Switch to the existing instance and use that. If an old copy is stuck holding the lock: end TradingBotV1.exe in Task Manager and delete the runtime/.launcher.lock file.",
        severity="fatal",
    ),
    "E016": ErrorEntry(
        code="E016",
        title="Windows Firewall is blocking",
        cause="Windows Firewall is preventing the dashboard from connecting to itself (loopback 127.0.0.1).",
        remedy="In Windows Defender Firewall > 'Allow an app or feature', check both the 'Private' and 'Public' columns for TradingBotV1.exe.",
        severity="fatal",
    ),
    "E017": ErrorEntry(
        code="E017",
        title="Insufficient memory",
        cause="Less than 200MB of available memory on the system. Windows may freeze if the bot is started.",
        remedy="Close other programs (especially browsers, Discord, Slack). Restart the system and try again. This is common on very old PCs (under 4GB RAM).",
        severity="warning",
    ),
    "E018": ErrorEntry(
        code="E018",
        title="Clock setting is off",
        cause="The Windows system clock differs from the Binance server clock by more than 1 minute. Binance is rejecting signature verification.",
        remedy="In Windows Time settings > enable 'Set time automatically' and click 'Sync now'. If NTP server access is blocked, use time.windows.com for time synchronization.",
        severity="warning",
    ),
    "E019": ErrorEntry(
        code="E019",
        title="State file corrupted",
        cause="runtime/state.json contains invalid JSON. It may have been left half-written on the previous exit.",
        remedy="Automatic recovery is attempted. If it fails: delete the runtime/state.json file (existing positions are lost in paper mode; in live mode only tracking metadata is lost).",
        severity="warning",
    ),
    "E020": ErrorEntry(
        code="E020",
        title="Run permission denied",
        cause="Windows SmartScreen or antivirus is blocking the unsigned .exe.",
        remedy="On the SmartScreen warning screen, click 'More info' > 'Run anyway'. For antivirus: add TradingBotV1.exe to the 'Safe apps' (whitelist / exclusion) list. Most are false positives.",
        severity="fatal",
    ),
}


class LauncherError(Exception):
    """Launcher exception with an error code.

    Usage:
        raise LauncherError("E003", detail="Port 8080 is already in use")
    """

    def __init__(self, code: str, detail: Optional[str] = None) -> None:
        self.code = code
        self.entry = _CATALOG.get(code)
        self.detail = detail
        if self.entry is None:
            super().__init__(f"[{code}] Unknown error: {detail or ''}")
        else:
            super().__init__(f"[{code}] {self.entry.title}: {detail or self.entry.cause}")


class ErrorCatalog:
    """Static access point - shared by the launcher and the dashboard."""

    @staticmethod
    def get(code: str) -> Optional[ErrorEntry]:
        return _CATALOG.get(code)

    @staticmethod
    def all() -> Dict[str, ErrorEntry]:
        return dict(_CATALOG)

    @staticmethod
    def render_markdown() -> str:
        """Return all errors as a Markdown table (for ERROR_CODES.md)."""
        rows = ["# Trading Bot v1 - Error Codes", "", "| Code | Title | Severity | Remedy |", "|---|---|---|---|"]
        for code in sorted(_CATALOG.keys()):
            e = _CATALOG[code]
            rows.append(f"| **{e.code}** | {e.title} | {e.severity} | {e.remedy[:80]}... |")
        rows.append("")
        rows.append("## Detailed Descriptions")
        rows.append("")
        for code in sorted(_CATALOG.keys()):
            e = _CATALOG[code]
            rows.append(f"### {e.code} - {e.title}")
            rows.append(f"")
            rows.append(f"**Severity:** `{e.severity}`")
            rows.append(f"")
            rows.append(f"**Possible Cause:** {e.cause}")
            rows.append(f"")
            rows.append(f"**Remedy:** {e.remedy}")
            rows.append(f"")
        return "\n".join(rows)


if __name__ == "__main__":
    # When run from the command line, print the Markdown output
    print(ErrorCatalog.render_markdown())
