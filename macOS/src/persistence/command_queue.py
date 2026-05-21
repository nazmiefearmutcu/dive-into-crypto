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

import threading
import uuid
from pathlib import Path
from typing import Any, Optional

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


class CommandQueue:
    """Idempotent control-plane queue persisted to a single JSON file."""

    def __init__(self, queue_path: str | Path = "runtime/command_queue.json") -> None:
        self.path = Path(queue_path)
        self._lock = threading.RLock()

    # ── Persistence ───────────────────────────────────────────

    def _load(self) -> CommandQueueSchema:
        if not self.path.exists():
            return CommandQueueSchema()
        try:
            raw = read_json_strict(self.path)
        except RuntimeIOError:
            # An empty/malformed queue file is a hard failure — the dashboard
            # must surface it, not silently drop commands.
            raise
        try:
            return CommandQueueSchema.model_validate(raw)
        except Exception as exc:  # pragma: no cover - defensive
            raise SchemaValidationError(
                f"command_queue.json failed schema validation: {exc}"
            ) from exc

    def _save(self, queue: CommandQueueSchema) -> None:
        queue.generated_at = _utc_now_iso()
        atomic_write_json(self.path, queue.model_dump(mode="json"))

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
            queue = self._load()
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
            self._save(queue)
            return cmd

    def list_pending(self) -> list[CommandSchema]:
        with self._lock:
            queue = self._load()
            return [c for c in queue.commands if c.status == CommandStatus.PENDING]

    def list_all(self) -> list[CommandSchema]:
        with self._lock:
            return list(self._load().commands)

    def get(self, command_id: str) -> Optional[CommandSchema]:
        with self._lock:
            for cmd in self._load().commands:
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
            queue = self._load()
            for cmd in queue.commands:
                if cmd.id == command_id:
                    cmd.status = status
                    cmd.processed_at = _utc_now_iso()
                    cmd.error = error
                    self._save(queue)
                    return
            raise KeyError(f"command not found: {command_id}")

    def purge_processed(self, keep_last: int = 50) -> int:
        """Trim the queue, keeping the last `keep_last` non-pending entries.
        Pending entries are always retained."""
        with self._lock:
            queue = self._load()
            pending = [c for c in queue.commands if c.status == CommandStatus.PENDING]
            done = [c for c in queue.commands if c.status != CommandStatus.PENDING]
            kept_done = done[-keep_last:] if keep_last > 0 else []
            new_commands = pending + kept_done
            removed = len(queue.commands) - len(new_commands)
            if removed > 0:
                queue.commands = new_commands
                self._save(queue)
            return removed
