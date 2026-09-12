# Requirements freeze (packaging note)

The authoritative dependency set for the desktop backend is **`uv.lock`**. Every
packaging build (PyInstaller via `dive.spec`) and every release must be produced
from the locked environment — never from a fresh resolution.

## Reproduce the locked environment

```bash
cd desktop/backend
uv sync
```

`uv sync` installs exactly the versions pinned in `uv.lock` (re-resolving only if
`pyproject.toml` changed). CI uses the same file, so what you package is what was
tested.

## Export a pinned requirements.txt (pip-only consumers)

```bash
cd desktop/backend
uv export --format requirements-txt --no-dev --frozen -o requirements-lock.txt
```

`requirements-lock.txt` is generated on demand and deliberately **not committed**:
`uv.lock` is the single source of truth, and a second frozen file would only drift
from it. Consumers that can only speak pip (some PyInstaller/analysis tooling,
air-gapped installs) should run the command above against the same commit.

## Why this matters for packaging

PyInstaller bakes whatever is importable in the build environment into the bundle.
An environment built from a stale or partial resolution silently ships different
library versions than the ones CI tested. Always:

```bash
cd desktop/backend
uv sync
uv run --with pyinstaller pyinstaller dive.spec --noconfirm
```

(`--with pyinstaller` overlays the packager onto the locked env — PyInstaller's
Analysis needs to import pandas/fastapi/uvicorn/crypcodile from that env to bundle
them; an isolated `uv tool run` environment cannot see them.)
