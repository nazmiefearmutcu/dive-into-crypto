#!/usr/bin/env python3
"""Run the read-only trading bot dashboard with auto-restart on crash.

Usage:
    python scripts/run_dashboard.py
    python scripts/run_dashboard.py --host 0.0.0.0 --port 8080
"""

import argparse
import sys
import time
import subprocess
import signal
from pathlib import Path

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))
import os
os.chdir(project_root)

# Supervisor settings
MAX_RESTART_ATTEMPTS = 100      # max restarts before giving up
RESTART_DELAY_SECONDS = 2       # wait before restarting
RAPID_CRASH_WINDOW = 5          # if process dies within this many seconds, it's a rapid crash
RAPID_CRASH_MAX = 5             # max rapid crashes before longer cooldown
RAPID_CRASH_COOLDOWN = 30       # longer cooldown after too many rapid crashes

_child_proc = None


def _signal_handler(signum, frame):
    """Forward signals to child process and exit cleanly."""
    global _child_proc
    if _child_proc and _child_proc.poll() is None:
        _child_proc.terminate()
        _child_proc.wait(timeout=5)
    sys.exit(0)


def main():
    global _child_proc

    parser = argparse.ArgumentParser(description="Start the trading bot dashboard (auto-restarts on crash)")
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8080, help="Bind port (default: 8080)")
    parser.add_argument("--reload", action="store_true", help="Enable auto-reload for development")
    parser.add_argument("--no-supervisor", action="store_true", help="Run without auto-restart supervisor")
    args = parser.parse_args()

    # If --no-supervisor, run directly (used by the supervisor subprocess itself)
    if args.no_supervisor:
        import uvicorn
        uvicorn.run(
            "dashboard.app:app",
            host=args.host,
            port=args.port,
            reload=args.reload,
            log_level="info",
        )
        return

    # ── Supervisor mode: launch uvicorn as subprocess, restart on crash ──
    signal.signal(signal.SIGTERM, _signal_handler)
    signal.signal(signal.SIGINT, _signal_handler)

    python = sys.executable
    cmd = [
        python, "-m", "uvicorn", "dashboard.app:app",
        "--host", args.host,
        "--port", str(args.port),
        "--log-level", "info",
    ]
    if args.reload:
        cmd.append("--reload")

    restart_count = 0
    rapid_crash_count = 0

    print(f"[supervisor] Starting dashboard at http://{args.host}:{args.port}")
    print(f"[supervisor] Auto-restart enabled. Will restart on crash.\n")

    while restart_count < MAX_RESTART_ATTEMPTS:
        start_time = time.time()

        try:
            _child_proc = subprocess.Popen(cmd, cwd=str(project_root))
            exit_code = _child_proc.wait()
        except KeyboardInterrupt:
            if _child_proc and _child_proc.poll() is None:
                _child_proc.terminate()
            print("\n[supervisor] Interrupted. Shutting down.")
            break

        elapsed = time.time() - start_time

        # Clean exit (0) means intentional stop
        if exit_code == 0:
            print("[supervisor] Dashboard exited cleanly.")
            break

        restart_count += 1

        # Detect rapid crashes
        if elapsed < RAPID_CRASH_WINDOW:
            rapid_crash_count += 1
        else:
            rapid_crash_count = 0

        if rapid_crash_count >= RAPID_CRASH_MAX:
            print(f"[supervisor] Too many rapid crashes ({rapid_crash_count}). "
                  f"Waiting {RAPID_CRASH_COOLDOWN}s before retry...")
            time.sleep(RAPID_CRASH_COOLDOWN)
            rapid_crash_count = 0
        else:
            delay = RESTART_DELAY_SECONDS
            print(f"[supervisor] Dashboard crashed (exit code {exit_code}). "
                  f"Restarting in {delay}s... (attempt {restart_count}/{MAX_RESTART_ATTEMPTS})")
            time.sleep(delay)

    if restart_count >= MAX_RESTART_ATTEMPTS:
        print(f"[supervisor] Max restart attempts ({MAX_RESTART_ATTEMPTS}) reached. Giving up.")
        sys.exit(1)


if __name__ == "__main__":
    main()
