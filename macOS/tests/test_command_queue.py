"""S4 — Command queue + atomic IO behaviour.

Covers:
- Atomic JSON write produces the new file and never leaves a tmp dangling.
- `read_json_strict` refuses malformed/empty/missing/non-object payloads.
- Command enqueue assigns a uuid + status pending and persists via schema.
- Duplicate idempotency_key collapses to the same command.
- mark_processed / mark_failed update status + processed_at.
- Malformed command_queue.json fails loudly (no silent {}).
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from src.persistence.atomic_io import (
    RuntimeIOError,
    atomic_write_json,
    read_json_strict,
)
from src.persistence.command_queue import CommandQueue
from src.persistence.schemas import (
    CommandKind,
    CommandStatus,
    RUNTIME_SCHEMA_VERSION,
    StateSchema,
    SchemaValidationError,
)


# ── atomic_io ────────────────────────────────────────────────────────


def test_atomic_write_json_replaces_atomically(tmp_path: Path) -> None:
    target = tmp_path / "state.json"
    atomic_write_json(target, {"hello": "world"})
    assert json.loads(target.read_text()) == {"hello": "world"}

    # Overwrite — no tmp file should remain.
    atomic_write_json(target, {"hello": "again"})
    assert json.loads(target.read_text()) == {"hello": "again"}
    leftovers = list(tmp_path.glob("*.tmp"))
    assert leftovers == []


def test_atomic_write_json_creates_parent_dir(tmp_path: Path) -> None:
    target = tmp_path / "nested" / "deeper" / "state.json"
    atomic_write_json(target, {"ok": True})
    assert target.exists()


def test_read_json_strict_raises_on_missing(tmp_path: Path) -> None:
    with pytest.raises(RuntimeIOError, match="missing"):
        read_json_strict(tmp_path / "absent.json")


def test_read_json_strict_raises_on_empty(tmp_path: Path) -> None:
    p = tmp_path / "empty.json"
    p.write_text("")
    with pytest.raises(RuntimeIOError, match="empty"):
        read_json_strict(p)


def test_read_json_strict_raises_on_partial_json(tmp_path: Path) -> None:
    p = tmp_path / "partial.json"
    p.write_text("{\"a\":1,")
    with pytest.raises(RuntimeIOError, match="malformed JSON"):
        read_json_strict(p)


def test_read_json_strict_rejects_non_object(tmp_path: Path) -> None:
    p = tmp_path / "arr.json"
    p.write_text("[1, 2, 3]")
    with pytest.raises(RuntimeIOError, match="not a JSON object"):
        read_json_strict(p)


def test_read_json_strict_ok(tmp_path: Path) -> None:
    p = tmp_path / "ok.json"
    p.write_text(json.dumps({"a": 1, "b": "two"}))
    assert read_json_strict(p) == {"a": 1, "b": "two"}


# ── CommandQueue ─────────────────────────────────────────────────────


@pytest.fixture
def queue(tmp_path: Path) -> CommandQueue:
    return CommandQueue(tmp_path / "command_queue.json")


def test_enqueue_creates_uuid_and_persists(queue: CommandQueue) -> None:
    cmd = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="close::BTCUSDT::1",
    )
    assert cmd.kind == CommandKind.MANUAL_CLOSE
    assert cmd.status == CommandStatus.PENDING
    assert len(cmd.id) >= 8
    assert cmd.payload == {"symbol": "BTCUSDT"}

    raw = json.loads(queue.path.read_text())
    assert raw["schema_version"] == RUNTIME_SCHEMA_VERSION
    assert len(raw["commands"]) == 1
    assert raw["commands"][0]["id"] == cmd.id


def test_enqueue_uses_atomic_write(queue: CommandQueue) -> None:
    queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "ETHUSDT"},
        idempotency_key="close::ETHUSDT::1",
    )
    # No tmp leftovers next to the queue file.
    parent = queue.path.parent
    leftovers = [p for p in parent.iterdir() if p.suffix == ".tmp"]
    assert leftovers == []


def test_enqueue_dedupes_pending_idempotency_key(queue: CommandQueue) -> None:
    """Two enqueues with the same key while the first is still PENDING
    collapse to one command."""
    a = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="dup-key-1234",
    )
    b = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="dup-key-1234",
    )
    # Second submit collapses into the first.
    assert a.id == b.id
    assert len(queue.list_all()) == 1


def test_enqueue_after_processed_creates_new_command(queue: CommandQueue) -> None:
    """A stable dashboard key like `close::BTC::pending` must NOT bind a
    later legitimate request to the previously processed command — once the
    first is terminal, the next enqueue creates a fresh command id."""
    key = "close::BTCUSDT::pending"
    a = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key=key,
    )
    queue.mark_processed(a.id)

    b = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key=key,
    )

    assert b.id != a.id
    assert b.status == CommandStatus.PENDING
    all_cmds = queue.list_all()
    assert len(all_cmds) == 2
    # The new command is the one that surfaces as pending.
    pending = queue.list_pending()
    assert len(pending) == 1
    assert pending[0].id == b.id


def test_enqueue_after_failed_creates_new_command(queue: CommandQueue) -> None:
    """A previous FAILED command must not absorb a fresh retry with the
    same idempotency key — failures are terminal and the operator should
    be able to retry by issuing a new command."""
    key = "paper_reset::10000.0000::pending"
    a = queue.enqueue(
        CommandKind.PAPER_RESET,
        {"balance": 10000.0},
        idempotency_key=key,
    )
    queue.mark_failed(a.id, "engine offline")

    b = queue.enqueue(
        CommandKind.PAPER_RESET,
        {"balance": 10000.0},
        idempotency_key=key,
    )

    assert b.id != a.id
    assert b.status == CommandStatus.PENDING
    assert len(queue.list_all()) == 2
    pending = queue.list_pending()
    assert len(pending) == 1
    assert pending[0].id == b.id


def test_enqueue_dedupes_only_against_pending_when_mixed(
    queue: CommandQueue,
) -> None:
    """Mixed history: one processed, one pending under same key. A third
    enqueue must collapse with the active pending one, not start a third."""
    key = "mix-key-7777"
    a = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key=key,
    )
    queue.mark_processed(a.id)
    b = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key=key,
    )
    # Third enqueue while b is still pending — must return b, not create c.
    c = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key=key,
    )

    assert b.id != a.id
    assert c.id == b.id
    assert len(queue.list_all()) == 2  # a (processed) + b (pending)


def test_enqueue_rejects_short_key(queue: CommandQueue) -> None:
    with pytest.raises(ValueError):
        queue.enqueue(
            CommandKind.MANUAL_CLOSE,
            {"symbol": "BTCUSDT"},
            idempotency_key="ab",
        )


def test_mark_processed_updates_status(queue: CommandQueue) -> None:
    cmd = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="proc-key-1",
    )
    queue.mark_processed(cmd.id)
    again = queue.get(cmd.id)
    assert again is not None
    assert again.status == CommandStatus.PROCESSED
    assert again.processed_at is not None


def test_mark_failed_records_error(queue: CommandQueue) -> None:
    cmd = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="fail-key-1",
    )
    queue.mark_failed(cmd.id, "engine offline")
    after = queue.get(cmd.id)
    assert after is not None
    assert after.status == CommandStatus.FAILED
    assert after.error == "engine offline"


def test_list_pending_excludes_done(queue: CommandQueue) -> None:
    a = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="pending-a-1",
    )
    queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "ETHUSDT"},
        idempotency_key="pending-b-1",
    )
    queue.mark_processed(a.id)
    pending = queue.list_pending()
    assert len(pending) == 1
    assert pending[0].idempotency_key == "pending-b-1"


def test_malformed_queue_file_fails_loudly(queue: CommandQueue) -> None:
    queue.path.parent.mkdir(parents=True, exist_ok=True)
    queue.path.write_text("{\"schema_version\": 1, \"commands\":")
    with pytest.raises(RuntimeIOError):
        queue.list_pending()


def test_empty_queue_file_fails_loudly(queue: CommandQueue) -> None:
    queue.path.parent.mkdir(parents=True, exist_ok=True)
    queue.path.write_text("")
    with pytest.raises(RuntimeIOError):
        queue.list_pending()


def test_purge_processed_keeps_pending(queue: CommandQueue) -> None:
    a = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "BTCUSDT"},
        idempotency_key="purge-a-1",
    )
    b = queue.enqueue(
        CommandKind.MANUAL_CLOSE,
        {"symbol": "ETHUSDT"},
        idempotency_key="purge-b-1",
    )
    queue.mark_processed(a.id)
    removed = queue.purge_processed(keep_last=0)
    assert removed == 1
    remaining = queue.list_all()
    assert len(remaining) == 1
    assert remaining[0].id == b.id


# ── Schema layer ─────────────────────────────────────────────────────


def test_state_schema_stamps_version_on_legacy() -> None:
    state = StateSchema.from_legacy({
        "active_symbol": "BTCUSDT",
        "positions": {},
        "paper_balance": 10000.0,
    })
    assert state.schema_version == RUNTIME_SCHEMA_VERSION


def test_state_schema_rejects_missing_required_field() -> None:
    with pytest.raises(SchemaValidationError):
        StateSchema.from_legacy({"positions": {}, "paper_balance": 10000.0})
