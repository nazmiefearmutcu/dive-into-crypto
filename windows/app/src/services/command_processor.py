"""Bot-side reader for the runtime command queue.

`CommandProcessor` is a thin orchestrator: it reads pending commands from
the queue, looks up a handler by `CommandKind`, and marks the command as
processed or failed. It is intentionally testable in isolation — production
wiring (PositionManager, ExecutionEngine, StateStore) is injected as
handler callables, not imported here.

Idempotency contract:
    Dedupe is owned by the queue at enqueue time (PENDING-only collapse).
    Terminal commands (PROCESSED / FAILED) do NOT block a fresh enqueue with
    the same idempotency_key — the queue gives the new request a new uuid,
    and the processor MUST dispatch it. Otherwise stable dashboard keys like
    `close::BTCUSDT::pending` would silently bind every later legitimate
    request to the first historical command.

The processor's in-memory dedupe set tracks `command.id` (not the
idempotency_key) for the active tick so we are robust against the rare case
where the same command appears twice in a single pending snapshot — e.g. if a
parallel writer beats `mark_processed` to flush. We never re-dispatch
work for an id we've already touched in this run, but identical keys
across distinct command ids are always honored.
"""

from __future__ import annotations

from typing import Any, Callable, Optional

from src.persistence.command_queue import CommandQueue
from src.persistence.schemas import CommandKind, CommandSchema
from src.utils.logger import get_logger

logger = get_logger("services.command_processor")

CommandHandler = Callable[[CommandSchema], dict[str, Any]]


class CommandProcessor:
    """Drains the command queue and dispatches to per-kind handlers."""

    def __init__(
        self,
        queue: CommandQueue,
        handlers: Optional[dict[CommandKind, CommandHandler]] = None,
    ) -> None:
        self.queue = queue
        self.handlers: dict[CommandKind, CommandHandler] = dict(handlers or {})

    def register(self, kind: CommandKind, handler: CommandHandler) -> None:
        self.handlers[kind] = handler

    def process_pending(self, *, max_per_tick: int = 16) -> list[CommandSchema]:
        """Drain at most `max_per_tick` pending commands.

        Returns the list of commands the processor *touched* in this tick
        (processed or failed). Commands without a registered handler are
        marked failed and skipped — they will not be retried.
        """
        touched: list[CommandSchema] = []
        seen_command_ids: set[str] = set()
        pending = self.queue.list_pending()
        for cmd in pending[:max_per_tick]:
            if cmd.id in seen_command_ids:
                # Same command already handled in this run; mark processed
                # to clear it. (Should not occur in normal operation — the
                # queue's mark_processed flips status atomically — but is
                # defensive against pathological double-listings.)
                logger.info(
                    f"command {cmd.id} ({cmd.kind.value}) already handled "
                    f"this run; marking processed without re-dispatch"
                )
                self.queue.mark_processed(cmd.id)
                touched.append(cmd)
                continue
            handler = self.handlers.get(cmd.kind)
            if handler is None:
                msg = f"no handler for command kind={cmd.kind.value}"
                logger.error(msg)
                self.queue.mark_failed(cmd.id, msg)
                seen_command_ids.add(cmd.id)
                touched.append(cmd)
                continue
            try:
                handler(cmd)
            except Exception as exc:  # noqa: BLE001 — surfaced via mark_failed
                logger.exception(
                    f"command {cmd.id} ({cmd.kind.value}) failed: {exc}"
                )
                self.queue.mark_failed(cmd.id, f"{type(exc).__name__}: {exc}")
                seen_command_ids.add(cmd.id)
                touched.append(cmd)
                continue
            seen_command_ids.add(cmd.id)
            self.queue.mark_processed(cmd.id)
            touched.append(cmd)
        return touched
