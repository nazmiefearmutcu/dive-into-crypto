"""Entry point: start the local service, serve the UI, open the browser.

    uv run dive-desktop                 # serve + open the UI in your browser
    uv run dive-desktop --no-open       # serve only (no browser)
    uv run dive-desktop --port 8888
"""

from __future__ import annotations

import argparse
import threading
import time
import webbrowser


def main() -> None:
    parser = argparse.ArgumentParser(prog="dive-desktop", description="Dive Into Crypto — Desktop Edition")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8780)
    parser.add_argument("--no-open", action="store_true", help="do not open a browser window")
    args = parser.parse_args()

    import uvicorn

    from diveintocrypto_desktop.api.app import app

    url = f"http://{args.host}:{args.port}/"
    if not args.no_open:
        def _open() -> None:
            time.sleep(1.2)
            try:
                webbrowser.open(url)
            except Exception:
                pass
        threading.Thread(target=_open, daemon=True).start()

    print(f"Dive Into Crypto — Desktop  →  {url}  (Ctrl+C to stop)")
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
