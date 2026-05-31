"""File-backed control-plane command queue.

Dashboard endpoints enqueue. The bot's `CommandProcessor` drains and marks
processed. The queue file is `runtime/command_queue.json` and is read/written
atomically via `atomic_io.atomic_write_json`.

Idempotency: every `enqueue` carries an `idempotency_key`. Re-submission with
the same key returns the existing command instead of creating a duplicate.
This protects against double-click and dashboard retries.

Concurrency: a single in-process `threading.RLock` serialises read-modify-write.
File-level mutual exclusion is provided by atomic `os.replace` — the bot may
also hold its own lock when draining.
"""

from __future__ import annotations

import os
import time
import threading
import uuid
from pathlib import Path
from contextlib import contextmanager
from typing import Any, Iterator, Optional

from src.persistence.atomic_io import (
    RuntimeIOError,
    atomic_write_json,
    read_json_strict,
)
from src.persistence.schemas import (
    CommandKind,
    CommandQueueSchema,
    CommandSchema,
    CommandStatus,
    SchemaValidationError,
    _utc_now_iso,
)
from src.utils.logger import get_logger

logger = get_logger("persistence.command_queue")

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_COMMAND_QUEUE_PATH = str(PROJECT_ROOT / "runtime" / "command_queue.json")


def _resolve_queue_path(queue_path: str | Path) -> Path:
    text = str(queue_path).strip()
    if text in {"", "."}:
        return Path(DEFAULT_COMMAND_QUEUE_PATH)

    path = Path(text).expanduser()
    if not path.is_absolute():
        path = PROJECT_ROOT / path

    if path.exists() and path.is_dir():
        return path / "command_queue.json"
    if not path.suffix and not path.exists():
        return path / "command_queue.json"
    return path


class CommandQueue:
    """Idempotent control-plane queue persisted to a single JSON file."""

    def __init__(self, queue_path: str | Path = DEFAULT_COMMAND_QUEUE_PATH) -> None:
        if isinstance(queue_path, (str, Path)):
            self.path = _resolve_queue_path(queue_path)
        else:
            self.path = Path(DEFAULT_COMMAND_QUEUE_PATH)
        self._lock = threading.RLock()
        self._lock_timeout_seconds = 5.0
        self._lock_poll_seconds = 0.05
        self._stale_lock_seconds = 60.0

    def _lock_path(self) -> Path:
        return self.path.with_suffix(f"{self.path.suffix}.lock")

    @contextmanager
    def _file_lock(self) -> Iterator[None]:
        """Serialize queue read/write across processes.

        A lock file created with O_EXCL blocks concurrent readers/writers.
        Stale locks are removed after a grace window.
        """
        lock_path = self._lock_path()
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        token = f"{os.getpid()}-{threading.get_ident()}-{time.time_ns()}"
        deadline = time.monotonic() + self._lock_timeout_seconds

        while True:
            try:
                fd = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    f.write(token)
                break
            except FileExistsError:
                if time.monotonic() > deadline:
                    if self._is_stale_lock(lock_path):
                        try:
                            lock_path.unlink()
                        except OSError:
                            raise RuntimeError(
                                f"command queue lock timeout for {self.path}"
                            ) from None
                        deadline = time.monotonic() + self._lock_timeout_seconds
                        continue
                    raise RuntimeError(f"command queue lock timeout for {self.path}")
                time.sleep(self._lock_poll_seconds)

        try:
            yield
        finally:
            try:
                if not lock_path.exists():
                    return
                try:
                    lock_token = lock_path.read_text(encoding="utf-8")
                except Exception as exc:
                    logger.warning(f"Failed to read command queue lock {lock_path}: {exc}")
                    try:
                        lock_path.unlink()
                    except Exception as cleanup_exc:
                        logger.warning(
                            f"Failed to remove unreadable command queue lock {lock_path}: {cleanup_exc}"
                        )
                    return
                if lock_token == token:
                    lock_path.unlink()
            except OSError as exc:
                logger.warning(f"Failed to release command queue lock {lock_path}: {exc}")

    def _is_stale_lock(self, lock_path: Path) -> bool:
        try:
            return (time.time() - lock_path.stat().st_mtime) > self._stale_lock_seconds
        except OSError:
            return True

    def _load_unlocked(self) -> CommandQueueSchema:
        if not self.path.exists():
            return CommandQueueSchema()
        try:
            raw = read_json_strict(self.path)
        except RuntimeIOError:
            # An empty/malformed queue file is a hard failure — the dashboard
            # must surface it, not silently drop commands.
            try:
                if self.path.exists():
                    self.path.unlink()
            except OSError as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale command queue after load error: {cleanup_exc}"
                )
            raise
        try:
            return CommandQueueSchema.model_validate(raw)
        except Exception as exc:  # pragma: no cover - defensive
            try:
                if self.path.exists():
                    self.path.unlink()
            except OSError as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale command queue after validation error: {cleanup_exc}"
                )
            raise SchemaValidationError(
                f"command_queue.json failed schema validation: {exc}"
            ) from exc

    # ── Persistence ───────────────────────────────────────────

    def _save_unlocked(self, queue: CommandQueueSchema) -> None:
        queue.generated_at = _utc_now_iso()
        try:
            atomic_write_json(self.path, queue.model_dump(mode="json"))
        except Exception:
            try:
                if self.path.exists():
                    self.path.unlink()
            except OSError as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale command queue after write error: {cleanup_exc}"
                )
            raise

    # ── Public API ────────────────────────────────────────────

    def enqueue(
        self,
        kind: CommandKind | str,
        payload: dict[str, Any],
        idempotency_key: str,
    ) -> CommandSchema:
        """Append a new command; collapse with an existing PENDING command of
        the same idempotency_key.

        Terminal commands (PROCESSED / FAILED) do NOT block a fresh enqueue —
        otherwise stable dashboard default keys like `close::BTC::pending`
        would silently bind every later request to the first historical
        command. Only an active, not-yet-handled command absorbs duplicates.
        """
        if isinstance(kind, str):
            kind = CommandKind(kind)
        if not idempotency_key or len(idempotency_key) < 4:
            raise ValueError("idempotency_key must be at least 4 chars")

        with self._lock:
            with self._file_lock():
                queue = self._load_unlocked()
                for cmd in queue.commands:
                    if (
                        cmd.idempotency_key == idempotency_key
                        and cmd.status == CommandStatus.PENDING
                    ):
                        # Active duplicate. Collapse to the existing command.
                        return cmd
                cmd = CommandSchema(
                    id=str(uuid.uuid4()),
                    idempotency_key=idempotency_key,
                    kind=kind,
                    payload=payload,
                )
                queue.commands.append(cmd)
                self._save_unlocked(queue)
                return cmd

    def list_pending(self) -> list[CommandSchema]:
        with self._lock:
            with self._file_lock():
                queue = self._load_unlocked()
                return [c for c in queue.commands if c.status == CommandStatus.PENDING]

    def list_all(self) -> list[CommandSchema]:
        with self._lock:
            with self._file_lock():
                return list(self._load_unlocked().commands)

    def get(self, command_id: str) -> Optional[CommandSchema]:
        with self._lock:
            with self._file_lock():
                for cmd in self._load_unlocked().commands:
                    if cmd.id == command_id:
                        return cmd
        return None

    def mark_processed(self, command_id: str) -> None:
        self._update_status(command_id, CommandStatus.PROCESSED, error=None)

    def mark_failed(self, command_id: str, error: str) -> None:
        self._update_status(command_id, CommandStatus.FAILED, error=error)

    def _update_status(
        self,
        command_id: str,
        status: CommandStatus,
        *,
        error: Optional[str],
    ) -> None:
        with self._lock:
            with self._file_lock():
                queue = self._load_unlocked()
                for cmd in queue.commands:
                    if cmd.id == command_id:
                        cmd.status = status
                        cmd.processed_at = _utc_now_iso()
                        cmd.error = error
                        self._save_unlocked(queue)
                        return
            # Command may disappear due concurrent queue writer rotation.
            return

    def purge_processed(self, keep_last: int = 50) -> int:
        """Trim the queue, keeping the last `keep_last` non-pending entries.
        Pending entries are always retained."""
        with self._lock:
            with self._file_lock():
                queue = self._load_unlocked()
                pending = [
                    c for c in queue.commands if c.status == CommandStatus.PENDING
                ]
                done = [c for c in queue.commands if c.status != CommandStatus.PENDING]
                kept_done = done[-keep_last:] if keep_last > 0 else []
                new_commands = pending + kept_done
                removed = len(queue.commands) - len(new_commands)
                if removed > 0:
                    queue.commands = new_commands
                    self._save_unlocked(queue)
                return removed
