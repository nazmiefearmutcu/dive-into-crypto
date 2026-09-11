"""Scan progress registry — a lightweight in-memory surface so clients can watch
a long full-universe scan run in another task/request.

One :class:`ScanProgress` per scan; a module-level registry keeps the most recent
records (capped) so ``GET /api/scan/progress`` can serve the latest or a specific
``scan_id``. ETA is derived from a rolling completion rate (last ~15s of ticks) —
a real measurement of the scan's own pace, never a fabricated countdown.
"""

from __future__ import annotations

import time
import uuid
from collections import deque
from datetime import datetime, timezone

# Rolling window (seconds) used for the ETA rate estimate.
_RATE_WINDOW = 15.0
# Samples kept for the rolling rate (≥ window at 1 Hz).
_MAX_SAMPLES = 32
# Registry cap: enough to watch several scans, small enough to never grow.
_MAX_RECORDS = 32


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class ScanProgress:
    """Mutable progress record for one scan. All methods are synchronous and
    event-loop-atomic; ticks come from the scan coroutines themselves.
    """

    def __init__(self, scan_id: str | None = None, meta: dict | None = None) -> None:
        self.scan_id = scan_id or uuid.uuid4().hex[:12]
        self.status = "running"  # running | done | error
        self.phase = "starting"  # starting|universe|phase1_coarse|phase2_detail|divergence|structure|done|error
        self.completed = 0
        self.total = 0
        self.meta = dict(meta or {})
        self.error: str | None = None
        self.summary: dict | None = None
        self.started_at = time.time()
        self.finished_at: float | None = None
        self._samples: deque[tuple[float, int]] = deque(maxlen=_MAX_SAMPLES)
        self._samples.append((time.monotonic(), 0))

    # ── mutation (called by the scanner) ─────────────────────────────────────
    def set_phase(self, phase: str, total: int, completed: int = 0) -> None:
        self.phase = phase
        self.total = max(int(total), 0)
        self.completed = max(int(completed), 0)
        self._samples.clear()
        self._samples.append((time.monotonic(), self.completed))

    def tick(self, n: int = 1) -> None:
        self.completed += n
        t = time.monotonic()
        if not self._samples or t - self._samples[-1][0] >= 0.2:  # throttle samples
            self._samples.append((t, self.completed))

    def finish(self, summary: dict) -> None:
        self.status = "done"
        self.phase = "done"
        self.completed = self.total or self.completed
        self.summary = summary
        self.finished_at = time.time()

    def fail(self, error: str) -> None:
        self.status = "error"
        self.phase = "error"
        self.error = str(error)[:200]
        self.finished_at = time.time()

    # ── projection ───────────────────────────────────────────────────────────
    def _eta(self) -> float | None:
        """Rolling-rate ETA: remaining / rate over the last _RATE_WINDOW seconds."""
        if self.status != "running" or self.total <= 0:
            return None
        now = time.monotonic()
        cutoff = now - _RATE_WINDOW
        base_t, base_c = self._samples[0]
        for t, c in self._samples:  # oldest sample inside the window
            if t >= cutoff:
                base_t, base_c = t, c
                break
        span = now - base_t
        done = self.completed - base_c
        if span <= 0.0 or done <= 0:
            return None
        rate = done / span  # units/second
        remaining = max(self.total - self.completed, 0)
        return round(remaining / rate, 1)

    def snapshot(self) -> dict:
        out: dict = {
            "scan_id": self.scan_id,
            "status": self.status,
            "phase": self.phase,
            "completed": self.completed,
            "total": self.total,
            "eta_seconds": self._eta(),
            "started_at": _iso(self.started_at),
            "meta": self.meta,
        }
        if self.finished_at is not None:
            out["finished_at"] = _iso(self.finished_at)
            out["duration_seconds"] = round(self.finished_at - self.started_at, 2)
        if self.error is not None:
            out["error"] = self.error
        if self.summary is not None:
            out["summary"] = self.summary
        return out


_registry: dict[str, ScanProgress] = {}
_order: deque[str] = deque(maxlen=_MAX_RECORDS)


def start(meta: dict | None = None, scan_id: str | None = None) -> ScanProgress:
    rec = ScanProgress(scan_id=scan_id, meta=meta)
    _registry[rec.scan_id] = rec
    _order.appendleft(rec.scan_id)
    # prune finished records beyond the cap (drop oldest first)
    while len(_order) >= _MAX_RECORDS:
        oldest = _order[-1]
        if _registry[oldest].status == "running":
            break  # never evict a running scan
        _registry.pop(oldest, None)
        _order.pop()
    return rec


def get(scan_id: str | None = None) -> ScanProgress | None:
    """Return the record for ``scan_id`` (or the most recent scan when omitted)."""
    if scan_id:
        return _registry.get(scan_id)
    return _registry.get(_order[0]) if _order else None


def reset() -> None:
    """Drop all records (test hook)."""
    _registry.clear()
    _order.clear()
