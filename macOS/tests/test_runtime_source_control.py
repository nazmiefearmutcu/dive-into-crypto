"""Regression tests for the Recovery Session 1 runtime/ scrub.

These tests pin two invariants so the 2026-04-22 incident cannot silently
recur:

1. Live runtime outputs (PID, scan progress, balances, flags) are NOT tracked
   in git. If someone re-adds the old whitelist exceptions or stages a runtime
   file, this suite fails before the bot is ever launched.

2. The 2026-04-22 incident snapshot is preserved verbatim under
   `tests/fixtures/runtime_snapshots/incident_2026_04_22/` and remains
   readable, so future regression work always has the original bad state
   (negative paper balance, stale dashboard, sentinel flags) to anchor on.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]  # TRADING_BOT/
MACOS_DIR = REPO_ROOT / "macOS"
RUNTIME_DIR = MACOS_DIR / "runtime"
INCIDENT_DIR = (
    MACOS_DIR / "tests" / "fixtures" / "runtime_snapshots" / "incident_2026_04_22"
)

LIVE_RUNTIME_FILES = (
    "active_coin_signals.json",
    "active_symbol.txt",
    "auto_scan_disabled",
    "auto_scan_progress.json",
    "auto_select_disabled",
    "dashboard_status.json",
    "manual_scan_active.json",
    "multi_scan_results.json",
    "state.json",
)


def _git_available() -> bool:
    try:
        subprocess.run(
            ["git", "rev-parse", "--is-inside-work-tree"],
            cwd=REPO_ROOT,
            capture_output=True,
            check=True,
            timeout=5,
        )
        return True
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired):
        return False


pytestmark = pytest.mark.skipif(
    not _git_available(),
    reason="Not in a git checkout; runtime tracking invariants only apply in-repo.",
)


class TestRuntimeNotTracked:
    """Live runtime outputs must never be tracked by git."""

    def test_runtime_dir_contains_only_gitkeep_in_index(self):
        """`git ls-files macOS/runtime` should return only `.gitkeep`."""
        result = subprocess.run(
            ["git", "ls-files", "macOS/runtime"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=10,
            check=True,
        )
        tracked = sorted(p for p in result.stdout.splitlines() if p)
        # `.gitkeep` is allowed, nothing else from runtime should be in the index.
        assert tracked == ["macOS/runtime/.gitkeep"], (
            "Runtime files leaked back into git index. Tracked: "
            f"{tracked!r}. Live runtime is process output, not source. "
            "Do not whitelist *.json or flag files in .gitignore."
        )

    @pytest.mark.parametrize("name", LIVE_RUNTIME_FILES)
    def test_runtime_file_is_gitignored(self, name: str):
        """Every known live runtime file path must match a .gitignore rule."""
        path = f"macOS/runtime/{name}"
        result = subprocess.run(
            ["git", "check-ignore", "-v", path],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=5,
        )
        # `check-ignore` exits 0 when the path is ignored, 1 when not.
        assert result.returncode == 0, (
            f"{path} is NOT ignored by .gitignore. Recovery Session 1 "
            "scrubbed these files from tracking; if you need a runtime "
            "snapshot, add it under tests/fixtures/runtime_snapshots/ "
            "instead of re-tracking the live file."
        )

    def test_gitkeep_remains_tracked(self):
        """The placeholder must stay tracked so the dir survives clean checkouts."""
        result = subprocess.run(
            ["git", "ls-files", "macOS/runtime/.gitkeep"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=5,
            check=True,
        )
        assert result.stdout.strip() == "macOS/runtime/.gitkeep"


class TestIncidentSnapshotPreserved:
    """The 2026-04-22 incident must remain queryable as a regression anchor."""

    def test_incident_directory_exists(self):
        assert INCIDENT_DIR.is_dir(), (
            f"Incident snapshot directory missing: {INCIDENT_DIR}. "
            "Do NOT delete — this is the only record of the negative "
            "paper-balance + stale-dashboard incident from 2026-04-22."
        )

    def test_incident_readme_exists(self):
        readme = INCIDENT_DIR / "README.md"
        assert readme.is_file(), f"Incident README missing: {readme}"
        body = readme.read_text(encoding="utf-8")
        assert "2026-04-22" in body
        assert "paper_balance" in body
        assert "dashboard_status.json" in body

    @pytest.mark.parametrize("name", LIVE_RUNTIME_FILES)
    def test_incident_file_present(self, name: str):
        path = INCIDENT_DIR / name
        assert path.is_file(), (
            f"Incident snapshot missing {name}: {path}. The full set of "
            "nine runtime artifacts must be preserved verbatim."
        )
        assert path.stat().st_size > 0, f"Incident snapshot {name} is empty."

    def test_incident_state_paper_balance_is_negative(self):
        """Anchor the regression: paper_balance went negative on 2026-04-22.

        If this assertion ever changes, you have either fixed the incident
        snapshot (don't!) or the bot finally produced a balanced state.json
        in production — in which case the regression test belongs elsewhere.
        """
        state = json.loads((INCIDENT_DIR / "state.json").read_text(encoding="utf-8"))
        # The exact number is in the README. We assert the failure mode
        # (negative balance) rather than the literal value so the test stays
        # readable if the fixture format ever evolves.
        balance = state.get("paper_balance")
        assert balance is not None, "state.json must record paper_balance"
        assert balance < 0, (
            "Incident anchor: paper_balance was negative on 2026-04-22. "
            f"Got {balance!r}. If you've normalized the fixture, restore it."
        )

    def test_incident_dashboard_status_is_stale(self):
        """Anchor: dashboard_status.json had null price + zero cycle count."""
        status = json.loads(
            (INCIDENT_DIR / "dashboard_status.json").read_text(encoding="utf-8")
        )
        assert status.get("current_price") is None
        assert status.get("cycle_count") == 0
        assert status.get("last_update", "").startswith("2026-04-22"), (
            "Incident anchor: last_update should still be the original "
            "2026-04-22 timestamp."
        )
