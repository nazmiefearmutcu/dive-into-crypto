"""Entry point: start the local service, serve the UI, open the browser.

    uv run dive-desktop                 # serve + open the UI in your browser
    uv run dive-desktop --no-open       # serve only (no browser)
    uv run dive-desktop --port 8888
    uv run dive-desktop --host 0.0.0.0 --allow-remote   # explicit opt-in only
"""

from __future__ import annotations

import argparse
import threading
import time
import webbrowser

# The service is localhost-only by contract and exposes unauthenticated write routes.
_LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}


def _browser_url(host: str, port: int) -> str:
    """The URL a local browser should open for a bind ``host``.

    A wildcard bind (``0.0.0.0`` / ``::``) is reached through loopback; an IPv6
    literal is bracketed so the authority parses (``http://[::1]:8780/``)."""
    target = host.strip()
    if target in ("", "0.0.0.0"):
        target = "127.0.0.1"
    elif target == "::":
        target = "::1"
    if ":" in target and not target.startswith("["):
        target = f"[{target}]"
    return f"http://{target}:{port}/"


def main() -> None:
    parser = argparse.ArgumentParser(prog="dive-desktop", description="DVCRY — Desktop Edition")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8780)
    parser.add_argument("--no-open", action="store_true", help="do not open a browser window")
    parser.add_argument(
        "--allow-remote", action="store_true",
        help="allow binding a non-loopback --host (exposes the unauthenticated API to the network)",
    )
    args = parser.parse_args()
    if args.host not in _LOOPBACK_HOSTS and not args.allow_remote:
        parser.error(
            f"refusing to bind non-loopback host {args.host!r}: the API has no authentication. "
            "Pass --allow-remote to expose it deliberately."
        )

    import uvicorn

    from diveintocrypto_desktop.api import app as app_module

    app_module.configure_bind_host(args.host)
    app = app_module.app
    url = _browser_url(args.host, args.port)
    if not args.no_open:
        def _open() -> None:
            time.sleep(1.2)
            try:
                webbrowser.open(url)
            except Exception:
                pass
        threading.Thread(target=_open, daemon=True).start()

    print(f"DVCRY — Desktop  →  {url}  (Ctrl+C to stop)")
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
