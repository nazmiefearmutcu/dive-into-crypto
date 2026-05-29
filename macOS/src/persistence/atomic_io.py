"""Atomic JSON IO helpers shared by all runtime persistence code.

`atomic_write_json` writes via tmp + fsync + os.replace so a partial write
can never overwrite the authoritative file.

`read_json_strict` raises `RuntimeIOError` on missing / unreadable / malformed
input. Callers that want a silent default must catch explicitly. Authoritative
runtime state must never silently fall back to `{}`.
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any


class RuntimeIOError(RuntimeError):
    """Raised when a runtime JSON file cannot be loaded safely."""


def atomic_write_json(path: Path, data: Any, *, indent: int = 2) -> None:
    """Write `data` as JSON to `path` atomically.

    Uses a tmp file in the same directory + fsync + os.replace so readers
    either see the previous version or the new one, never a torn write.
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # NamedTemporaryFile lets us fsync the fd before replace.
    fd, tmp_name = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=str(path.parent),
    )
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, indent=indent, default=str)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def read_json_strict(path: Path) -> dict[str, Any]:
    """Read JSON from `path` or raise `RuntimeIOError`.

    Does not fall back to `{}` on parse failure. The caller decides whether
    a missing/corrupt file is fatal.
    """
    path = Path(path)
    if not path.exists():
        raise RuntimeIOError(f"runtime file missing: {path}")
    try:
        raw = path.read_text()
    except OSError as exc:
        raise RuntimeIOError(f"runtime file unreadable: {path}: {exc}") from exc
    if not raw.strip():
        raise RuntimeIOError(f"runtime file empty: {path}")
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeIOError(f"runtime file malformed JSON: {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise RuntimeIOError(
            f"runtime file is not a JSON object (got {type(data).__name__}): {path}"
        )
    return data
