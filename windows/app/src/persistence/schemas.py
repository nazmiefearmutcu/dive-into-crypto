"""Versioned runtime payload schemas.

These wrap the on-disk JSON shapes that the bot and dashboard exchange.
Schemas carry an explicit `schema_version` so future migrations are explicit
instead of silent. Pydantic v2 is already a project dependency; using it
here gives strict validation, JSON round-trip, and clear error messages.
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, ConfigDict, Field, ValidationError

RUNTIME_SCHEMA_VERSION = 1


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class SchemaValidationError(ValueError):
    """Raised when a payload cannot be coerced into its schema."""


class CommandKind(str, Enum):
    """Recognised command kinds.

    Only commands the bot is wired to handle are listed. Anything else
    must be rejected at enqueue time so the queue stays small and auditable.
    """

    MANUAL_CLOSE = "manual_close"
    PAPER_RESET = "paper_reset"


class CommandStatus(str, Enum):
    PENDING = "pending"
    PROCESSED = "processed"
    FAILED = "failed"


class CommandSchema(BaseModel):
    """One control-plane command flowing dashboard → bot."""

    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=8, description="UUID4 of the command")
    idempotency_key: str = Field(min_length=4, description="Stable key for dedup")
    kind: CommandKind
    payload: dict[str, Any] = Field(default_factory=dict)
    created_at: str = Field(default_factory=_utc_now_iso)
    status: CommandStatus = CommandStatus.PENDING
    processed_at: Optional[str] = None
    error: Optional[str] = None


class CommandQueueSchema(BaseModel):
    """Top-level shape of `runtime/command_queue.json`."""

    model_config = ConfigDict(extra="forbid")

    schema_version: int = RUNTIME_SCHEMA_VERSION
    generated_at: str = Field(default_factory=_utc_now_iso)
    commands: list[CommandSchema] = Field(default_factory=list)


class StateSchema(BaseModel):
    """Minimum shape the bot's authoritative `state.json` must carry."""

    model_config = ConfigDict(extra="allow")

    schema_version: int = RUNTIME_SCHEMA_VERSION
    active_symbol: str
    positions: dict[str, dict[str, Any]] = Field(default_factory=dict)
    paper_balance: float

    @classmethod
    def from_legacy(cls, data: dict[str, Any]) -> "StateSchema":
        """Construct from an unversioned/legacy state.json shape.

        S1-S3 contracts: existing state files have no `schema_version`.
        Migrating in-place: stamp the current version on load.
        """
        if "schema_version" not in data:
            data = {**data, "schema_version": RUNTIME_SCHEMA_VERSION}
        try:
            return cls.model_validate(data)
        except ValidationError as exc:
            raise SchemaValidationError(
                f"state.json failed schema validation: {exc}"
            ) from exc


class DashboardStatusSchema(BaseModel):
    """Minimum shape of `runtime/dashboard_status.json` the dashboard exports."""

    model_config = ConfigDict(extra="allow")

    schema_version: int = RUNTIME_SCHEMA_VERSION
    bot_status: str = "stopped"
    active_symbol: str = ""
    last_update: str = Field(default_factory=_utc_now_iso)
    balance: float = 0.0
    open_positions_count: int = 0
    open_positions: list[dict[str, Any]] = Field(default_factory=list)

    @classmethod
    def from_legacy(cls, data: dict[str, Any]) -> "DashboardStatusSchema":
        if "schema_version" not in data:
            data = {**data, "schema_version": RUNTIME_SCHEMA_VERSION}
        try:
            return cls.model_validate(data)
        except ValidationError as exc:
            raise SchemaValidationError(
                f"dashboard_status.json failed schema validation: {exc}"
            ) from exc
