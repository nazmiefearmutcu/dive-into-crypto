#!/bin/bash
# Install launchd services for Trading Bot Dashboard and Bot
# This ensures both services survive terminal/Claude session closures
# and auto-restart on crash.

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="$(which python3)"
PLIST_DIR="$HOME/Library/LaunchAgents"

mkdir -p "$PLIST_DIR"
mkdir -p "$PROJECT_DIR/runtime"

# ─── Dashboard Service ───────────────────────────────────────────
DASH_LABEL="com.tradingbot.dashboard"
DASH_PLIST="$PLIST_DIR/${DASH_LABEL}.plist"

cat > "$DASH_PLIST" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${DASH_LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${PYTHON}</string>
        <string>${PROJECT_DIR}/scripts/run_dashboard.py</string>
    </array>
    <key>WorkingDirectory</key>
    <string>${PROJECT_DIR}</string>
    <key>RunAtLoad</key>
    <false/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>StandardOutPath</key>
    <string>${PROJECT_DIR}/runtime/dashboard_stdout.log</string>
    <key>StandardErrorPath</key>
    <string>${PROJECT_DIR}/runtime/dashboard_stderr.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PYTHONPATH</key>
        <string>${PROJECT_DIR}</string>
    </dict>
    <key>ThrottleInterval</key>
    <integer>5</integer>
</dict>
</plist>
EOF

# Unload if already loaded
launchctl bootout gui/$(id -u) "$DASH_PLIST" 2>/dev/null || true

# Load and start
launchctl bootstrap gui/$(id -u) "$DASH_PLIST"
launchctl kickstart -k gui/$(id -u)/${DASH_LABEL}

echo "✓ Dashboard service installed and started"
echo "  URL: http://127.0.0.1:8000"
echo "  Logs: $PROJECT_DIR/runtime/dashboard_stdout.log"
echo ""
echo "Commands:"
echo "  Start:   launchctl kickstart gui/$(id -u)/${DASH_LABEL}"
echo "  Stop:    launchctl kill SIGTERM gui/$(id -u)/${DASH_LABEL}"
echo "  Remove:  launchctl bootout gui/$(id -u) $DASH_PLIST && rm $DASH_PLIST"
