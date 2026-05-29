"""S4 — Bot-side CommandProcessor seam.

Covers:
- Pending commands are routed to the registered handler exactly once.
- Duplicate idempotency keys within a single run are skipped at processor level.
- Handler exceptions surface as mark_failed without taking down the loop.
- Manual-close command shape carries the symbol payload through to the handler.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from src.persistence.command_queue import CommandQueue
from src.persistence.schemas import (
    CommandKind,
    CommandSchema,
    CommandStatus,
)
from src.services.command_processor import CommandProcessor


@pytest.fixture
def queue(tmp_path: Path) -> CommandQueue:
    return CommandQueue(tmp_path / "command_queue.json")


def test_processor_dispatches_to_manual_close_handler(queue: CommandQueue) -> None:
    seen: list[CommandSchema] = []

    def close_handler(cmd: CommandSchema) -> dict[str, Any]:
        seen.append(cmd)
        return {"executed": True}

    proc = CommandProcessor(queue, handlers={CommandKind.MANUAL_CLOSE: close_handler})
    queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="proc-close-1",
    )
    touched = proc.process_pending()
    assert len(touched) == 1
    assert len(seen) == 1
    assert seen[0].payload["symbol"] == "BTCUSDT"
    assert seen[0].status == CommandStatus.PENDING  # handler sees pre-mark snapshot
    pending_after = queue.list_pending()
    assert pending_after == []


def test_processor_marks_failed_when_no_handler(queue: CommandQueue) -> None:
    proc = CommandProcessor(queue, handlers={})
    cmd = queue.enqueue(
        CommandKind.PAPER_RESET,
        {"balance": 10000.0},
        idempotency_key="proc-noh-1",
    )
    proc.process_pending()
    after = queue.get(cmd.id)
    assert after is not None
    assert after.status == CommandStatus.FAILED
    assert "no handler" in (after.error or "")


def test_processor_marks_failed_on_handler_exception(queue: CommandQueue) -> None:
    def boom(cmd: CommandSchema) -> dict[str, Any]:
        raise RuntimeError("position store offline")

    proc = CommandProcessor(queue, handlers={CommandKind.MANUAL_CLOSE: boom})
    cmd = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "ETHUSDT"},
        idempotency_key="proc-boom-1",
    )
    proc.process_pending()
    after = queue.get(cmd.id)
    assert after is not None
    assert after.status == CommandStatus.FAILED
    assert "position store offline" in (after.error or "")


def test_processor_dispatches_fresh_command_with_same_key_after_terminal(
    queue: CommandQueue,
) -> None:
    """S5 — a processed terminal command must NOT suppress a later fresh
    command carrying the same stable idempotency_key.

    Stable dashboard keys like `close::BTCUSDT::pending` are reused for
    every operator click. After the previous request is terminal, the queue
    issues a new command id; the processor MUST dispatch that new id, even
    though the idempotency_key matches a historical command. Otherwise
    every later close button silently no-ops.
    """
    seen: list[CommandSchema] = []

    def handler(cmd: CommandSchema) -> dict[str, Any]:
        seen.append(cmd)
        return {}

    proc = CommandProcessor(queue, handlers={CommandKind.MANUAL_CLOSE: handler})

    key = "close::BTCUSDT::pending"
    first = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key=key,
    )
    proc.process_pending()
    assert len(seen) == 1
    assert seen[0].id == first.id

    # Same stable key, fresh operator click — queue gives a new id.
    second = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key=key,
    )
    assert second.id != first.id

    proc.process_pending()
    # Handler called twice — the fresh command was honored.
    assert len(seen) == 2
    assert seen[1].id == second.id
    assert seen[1].idempotency_key == key

    after = queue.get(second.id)
    assert after is not None
    assert after.status == CommandStatus.PROCESSED


def test_processor_does_not_re_dispatch_same_command_id_within_tick(
    queue: CommandQueue,
) -> None:
    """Defensive: if the same `command.id` somehow appears twice in a
    single pending snapshot (parallel writer race, malformed direct write),
    the processor must NOT call the handler twice for that id.

    Distinct from the test above — here the *id itself* repeats, not the
    idempotency_key. We protect against pathological double-listings by
    tracking ids touched in this run.
    """
    seen: list[CommandSchema] = []

    def handler(cmd: CommandSchema) -> dict[str, Any]:
        seen.append(cmd)
        return {}

    proc = CommandProcessor(queue, handlers={CommandKind.MANUAL_CLOSE: handler})

    cmd = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="dedup-id-1",
    )
    proc.process_pending()
    assert len(seen) == 1

    # Manually rewrite the queue file so the SAME id is pending again
    # — simulates a malformed direct write or parallel race.
    import json

    raw = json.loads(queue.path.read_text())
    for c in raw["commands"]:
        if c["id"] == cmd.id:
            c["status"] = "pending"
            c["processed_at"] = None
    queue.path.write_text(json.dumps(raw))

    proc.process_pending()
    # Handler not called again — the id has already been touched this run.
    assert len(seen) == 1
    after = queue.get(cmd.id)
    assert after is not None
    assert after.status == CommandStatus.PROCESSED


def test_processor_respects_max_per_tick(queue: CommandQueue) -> None:
    def noop(cmd: CommandSchema) -> dict[str, Any]:
        return {}

    proc = CommandProcessor(queue, handlers={CommandKind.MANUAL_CLOSE: noop})
    for i in range(5):
        queue.enqueue(
            CommandKind.MANUAL_CLOSE,
            {"symbol": f"X{i}USDT"},
            idempotency_key=f"tick-key-{i}",
        )
    touched = proc.process_pending(max_per_tick=2)
    assert len(touched) == 2
    assert len(queue.list_pending()) == 3
