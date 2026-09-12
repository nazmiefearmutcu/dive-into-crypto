"""Claims registry — pre-registered, falsifiable statements about the engine's
graded performance. The point is honest science on a report-only archive:

  * a claim is REGISTERED first (``docs/claims/<claim_id>.yaml``, immutable by
    convention — an id can never be overwritten or edited in place);
  * it is EVALUATED only on grades whose verdict ``ts`` is AFTER
    ``registered_at`` (no peeking at the past the claim wasn't written for);
  * ``registered_at`` is VALIDATED at registration (tolerant ISO-8601 —
    fractional seconds and numeric offsets accepted, garbage rejected); a claim
    file that somehow predates the validation and carries an unparseable date
    is never evaluated against a wrong gate: it is skipped from grading and
    surfaces as ``PENDING`` with an ``invalid registered_at`` note — never
    ``CONFIRMED``;
  * status is one of ``PENDING`` (fewer than ``min_n`` post-registration
    grades), ``CONFIRMED`` (metric meets the threshold) or ``REFUTED`` (it
    doesn't).

Registry files live next to the docs so they ship with the repo; a sidecar
status file (``runtime/claims_status.json``) records the last evaluation for
the UI without ever mutating the registry itself.
"""

from __future__ import annotations

import json
import logging
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import yaml

from diveintocrypto_desktop.scan import evidence as ev

logger = logging.getLogger("trading_bot.scan.claims")

REQUIRED_FIELDS = {
    "claim_id", "registered_at", "engine_version", "claim",
    "metric", "filter", "horizon", "min_n", "threshold",
}
ALLOWED_METRICS = {"hit_rate", "avg_forward"}
ALLOWED_FILTER_KEYS = {"verdict", "regime", "divergence_tier", "session", "funding_proximity"}
ALLOWED_OPS = {">=", "<="}
CLAIM_ID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"

# Per-grade verdict labels mirrored from the evidence layer.
_VERDICT_BUCKETS = ("LONG", "SHORT", "NEUTRAL")


def claims_dir() -> Path:
    """Registry directory (env ``DIVE_CLAIMS_DIR`` overrides; defaults to the
    repo's ``docs/claims``)."""
    env = os.environ.get("DIVE_CLAIMS_DIR")
    if env:
        return Path(env)
    return Path(__file__).resolve().parents[5] / "docs" / "claims"


def status_path() -> Path:
    """Sidecar status file (env ``DIVE_CLAIMS_STATUS_PATH`` overrides)."""
    env = os.environ.get("DIVE_CLAIMS_STATUS_PATH")
    if env:
        return Path(env)
    return ev._default_dir() / "claims_status.json"


class ClaimError(ValueError):
    """Invalid claim payload (schema/validation failure)."""


class ClaimExistsError(ClaimError):
    """The claim_id already exists — registry files are immutable."""


def parse_registered_at(raw: object) -> datetime | None:
    """Tolerant-but-VALIDATING ISO-8601 parser for ``registered_at``.

    Accepts ``YYYY-MM-DD[THH:MM[:SS]][.frac]`` with an optional trailing ``Z``
    or numeric UTC offset (``±HH:MM`` / ``±HHMM``); naive (offset-less) values
    are read as UTC. Returns an aware UTC datetime, or ``None`` when the value
    does not parse — the caller decides rejection (registration) or the safe
    ``PENDING`` state (evaluation of a pre-validation registry file).
    """
    if not isinstance(raw, str) or not raw.strip():
        return None
    text = raw.strip()
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def validate_claim(claim: dict) -> dict:
    """Validate a claim payload; returns the normalized dict or raises."""
    if not isinstance(claim, dict):
        raise ClaimError("claim must be an object")
    missing = REQUIRED_FIELDS - set(claim)
    if missing:
        raise ClaimError(f"missing fields: {sorted(missing)}")
    claim_id = claim.get("claim_id")
    if not isinstance(claim_id, str) or not claim_id or any(c not in CLAIM_ID_CHARS for c in claim_id):
        raise ClaimError("claim_id must be non-empty [A-Za-z0-9_-] only")
    if parse_registered_at(claim.get("registered_at")) is None:
        raise ClaimError(
            "registered_at must be an ISO-8601 timestamp "
            "(e.g. 2026-01-01T00:00:00Z, fractional seconds / ±HH:MM offset ok)"
        )
    if claim.get("metric") not in ALLOWED_METRICS:
        raise ClaimError(f"metric must be one of {sorted(ALLOWED_METRICS)}")
    if claim.get("horizon") not in ev.HORIZONS:
        raise ClaimError(f"horizon must be one of {sorted(ev.HORIZONS)}")
    try:
        if int(claim.get("min_n")) < 1:
            raise ValueError
    except (TypeError, ValueError):
        raise ClaimError("min_n must be a positive integer") from None
    flt = claim.get("filter")
    if not isinstance(flt, dict) or any(k not in ALLOWED_FILTER_KEYS for k in flt):
        raise ClaimError(f"filter keys must be within {sorted(ALLOWED_FILTER_KEYS)}")
    thr = claim.get("threshold")
    if (not isinstance(thr, dict) or thr.get("op") not in ALLOWED_OPS
            or not isinstance(thr.get("value"), (int, float))):
        raise ClaimError('threshold must be {"op": ">=" | "<=", "value": number}')
    if not isinstance(claim.get("claim"), str) or not claim["claim"].strip():
        raise ClaimError("claim text must be a non-empty string")
    return claim


def register(claim: dict, claims_dir_path: Path | None = None) -> Path:
    """Write a validated claim to a NEW registry file (immutable by convention)."""
    claim = validate_claim(claim)
    directory = claims_dir_path or claims_dir()
    path = directory / f"{claim['claim_id']}.yaml"
    if path.exists():
        raise ClaimExistsError(f"claim_id {claim['claim_id']} already registered")
    directory.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(claim, sort_keys=False, allow_unicode=True), encoding="utf-8")
    return path


def load_claims(directory: Path | None = None) -> list[dict]:
    """All registered claims (unparseable files are skipped, never fatal)."""
    directory = directory or claims_dir()
    out: list[dict] = []
    try:
        paths = sorted(directory.glob("*.yaml"))
    except OSError:
        return []
    for p in paths:
        try:
            data = yaml.safe_load(p.read_text(encoding="utf-8"))
        except Exception as e:
            logger.warning("claims: read %s failed — %s", p.name, str(e)[:80])
            continue
        if isinstance(data, dict) and data.get("claim_id"):
            data["_file"] = p.name
            out.append(data)
    return out


def _registered_ms(claim: dict) -> int | None:
    """Parse ``registered_at`` to ms (UTC); ``None`` when unparseable.

    Never returns 0: a 0 gate would admit EVERY grade as "post-registration" —
    exactly the peeking the date gate exists to prevent. Unparseable dates mean
    the claim is skipped from evaluation (see :func:`evaluate`).
    """
    dt = parse_registered_at(claim.get("registered_at"))
    return None if dt is None else int(dt.timestamp() * 1000)


def _bucket(verdict: str) -> str:
    if "BUY" in verdict:
        return "LONG"
    if "SELL" in verdict:
        return "SHORT"
    return "NEUTRAL"


def _session_bucket(ts_ms: int) -> str:
    hour = time.gmtime(ts_ms / 1000).tm_hour
    if hour < 8:
        return "0-8"
    if hour < 16:
        return "8-16"
    return "16-24"


def _funding_proximity_bucket(ts_ms: int) -> str:
    gm = time.gmtime(ts_ms / 1000)
    frac = gm.tm_hour + gm.tm_min / 60.0
    dist = min(abs(frac - b) for b in (0, 8, 16, 24))
    if dist <= 1.0:
        return "within_1h"
    if dist <= 4.0:
        return "1h_to_4h"
    return "over_4h"


def _matches(flt: dict, rec: dict) -> bool:
    for key, want in (flt or {}).items():
        if key == "verdict":
            got = _bucket(rec.get("verdict", "NEUTRAL"))
        elif key == "regime":
            got = rec.get("regime") or "unclassified"
        elif key == "divergence_tier":
            got = rec.get("divergence_tier") or "unclassified"
        elif key == "session":
            got = _session_bucket(int(rec["ts"]))
        elif key == "funding_proximity":
            got = _funding_proximity_bucket(int(rec["ts"]))
        else:
            return False
        if str(got) != str(want):
            return False
    return True


def evaluate(claim: dict, records: list[dict] | None = None,
             grades: list[dict] | None = None, now_ms: int | None = None) -> dict:
    """Evaluate one claim — ONLY on grades with ts > registered_at.

    Date-gate safety: when ``registered_at`` does not parse (possible only for
    registry files written before validation existed), the gate cannot be
    applied, so the claim is SKIPPED from evaluation entirely and surfaces as
    the safe state ``PENDING`` (n=0, ``value=None``) with an
    ``invalid registered_at`` note — it can never be CONFIRMED or REFUTED from
    grades it cannot be gated against.
    """
    from datetime import datetime, timezone

    records = ev.iter_archive() if records is None else records
    grades = ev.load_grades() if grades is None else grades
    horizon = claim.get("horizon")
    by_key = {(r["symbol"], int(r["ts"])): r for r in records}

    reg_ms = _registered_ms(claim)
    values: list[float] = []
    n = 0
    if horizon in ev.HORIZONS and reg_ms is not None:
        for g in grades:
            if g.get("horizon") != horizon:
                continue
            if int(g["ts"]) <= reg_ms:
                continue  # pre-registration grades never count
            rec = by_key.get((g["symbol"], int(g["ts"])))
            if rec is None or not _matches(claim.get("filter") or {}, rec):
                continue
            n += 1
            if claim["metric"] == "hit_rate":
                values.append(1.0 if g.get("hit") else 0.0)
            else:
                try:
                    values.append(float(g.get("forward")))
                except (TypeError, ValueError):
                    values.append(0.0)

    value = round(sum(values) / len(values), 4) if values else None
    min_n = int(claim.get("min_n") or 1)
    if n < min_n:
        status = "PENDING"
    else:
        thr = claim["threshold"]
        ok = value >= thr["value"] if thr["op"] == ">=" else value <= thr["value"]
        status = "CONFIRMED" if ok else "REFUTED"

    note = (
        "invalid registered_at — the date gate cannot be applied; "
        "claim skipped from evaluation (safe PENDING)"
        if reg_ms is None else
        "evaluated only on grades with ts > registered_at"
    )
    now_dt = datetime.fromtimestamp((now_ms or time.time() * 1000) / 1000, tz=timezone.utc)
    return {
        "claim_id": claim["claim_id"],
        "claim": claim.get("claim"),
        "metric": claim.get("metric"),
        "filter": claim.get("filter"),
        "horizon": horizon,
        "min_n": min_n,
        "threshold": claim.get("threshold"),
        "registered_at": claim.get("registered_at"),
        "engine_version": claim.get("engine_version"),
        "status": status,
        "n": n,
        "value": value,
        "evaluated_at": now_dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": note,
    }


def list_evaluations() -> list[dict]:
    """All claims + their current evaluation; persists the sidecar status file."""
    out = [evaluate(c) for c in load_claims()]
    try:
        status_file = status_path()
        status_file.parent.mkdir(parents=True, exist_ok=True)
        status_file.write_text(json.dumps(out, indent=2), encoding="utf-8")
    except OSError as e:
        logger.warning("claims: status write failed — %s", str(e)[:100])
    return out
