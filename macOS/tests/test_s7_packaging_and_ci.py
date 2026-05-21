"""S7: pytest config and CI workflow shape.

Contract under test:
    * ``macOS/pyproject.toml`` exists, is parseable, and pins:
        - testpaths == ["tests"]
        - pythonpath == ["."]
        - addopts contains ``--strict-markers``
    * ``.github/workflows/macos-tests.yml`` exists at repo root, is parseable
      YAML, runs from ``working-directory: macOS``, references
      ``requirements.txt``, runs ``python -m pytest``, contains no secrets
      and no untrusted ``github.event.*`` interpolations in shell context.
"""

import re
from pathlib import Path

import pytest


try:
    import tomllib  # py311+
except ImportError:  # pragma: no cover
    import tomli as tomllib  # type: ignore[no-redef]

import yaml


MACOS_DIR = Path(__file__).parent.parent
PYPROJECT = MACOS_DIR / "pyproject.toml"
REPO_ROOT = MACOS_DIR.parent
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "macos-tests.yml"


class TestPyproject:
    def test_pyproject_exists(self):
        assert PYPROJECT.exists(), f"missing pyproject.toml at {PYPROJECT}"

    def test_pyproject_is_valid_toml(self):
        with PYPROJECT.open("rb") as f:
            data = tomllib.load(f)
        assert "tool" in data, data
        assert "pytest" in data["tool"], data["tool"]
        assert "ini_options" in data["tool"]["pytest"], data["tool"]["pytest"]

    def test_pytest_testpaths_pinned(self):
        with PYPROJECT.open("rb") as f:
            data = tomllib.load(f)
        ini = data["tool"]["pytest"]["ini_options"]
        assert ini["testpaths"] == ["tests"], ini["testpaths"]

    def test_pytest_pythonpath_includes_macos_root(self):
        with PYPROJECT.open("rb") as f:
            data = tomllib.load(f)
        ini = data["tool"]["pytest"]["ini_options"]
        assert "." in ini["pythonpath"], ini["pythonpath"]

    def test_pytest_addopts_has_strict_markers(self):
        with PYPROJECT.open("rb") as f:
            data = tomllib.load(f)
        ini = data["tool"]["pytest"]["ini_options"]
        addopts = ini["addopts"]
        # addopts can be a list or a single string — accept both.
        joined = " ".join(addopts) if isinstance(addopts, list) else addopts
        assert "--strict-markers" in joined, joined

    def test_pytest_minversion_floor(self):
        with PYPROJECT.open("rb") as f:
            data = tomllib.load(f)
        ini = data["tool"]["pytest"]["ini_options"]
        assert "minversion" in ini, ini


class TestCIWorkflow:
    """The CI workflow shape — no real network calls, validated as YAML."""

    @pytest.fixture
    def workflow(self):
        if not WORKFLOW.exists():
            pytest.fail(f"missing workflow at {WORKFLOW}")
        return yaml.safe_load(WORKFLOW.read_text())

    @pytest.fixture
    def workflow_text(self):
        if not WORKFLOW.exists():
            pytest.fail(f"missing workflow at {WORKFLOW}")
        return WORKFLOW.read_text()

    def test_workflow_yaml_parses(self, workflow):
        # ``on:`` is YAML keyword ``True`` after parsing — accept either.
        assert ("on" in workflow) or (True in workflow), workflow
        assert "jobs" in workflow, workflow

    def test_workflow_has_pytest_job(self, workflow):
        jobs = workflow["jobs"]
        assert "pytest" in jobs, jobs

    def test_workflow_runs_from_macos_directory(self, workflow):
        job = workflow["jobs"]["pytest"]
        assert job.get("defaults", {}).get("run", {}).get("working-directory") == "macOS", job

    def test_workflow_python_matrix_pinned(self, workflow):
        job = workflow["jobs"]["pytest"]
        versions = job["strategy"]["matrix"]["python-version"]
        # Stored as strings in YAML to avoid floating-point rounding ("3.10" vs 3.1).
        assert all(isinstance(v, str) for v in versions), versions
        assert "3.11" in versions, versions
        assert "3.12" in versions, versions

    def test_workflow_installs_requirements(self, workflow_text):
        assert "pip install -r requirements.txt" in workflow_text

    def test_workflow_runs_pytest(self, workflow_text):
        assert "python -m pytest" in workflow_text

    def test_workflow_does_not_use_secrets(self, workflow_text):
        # No secrets context anywhere — CI is offline-only.
        assert "${{ secrets." not in workflow_text, (
            "workflow references secrets — rescue-build CI must run offline"
        )
        # Catch case-insensitive variants.
        assert "secrets." not in workflow_text.lower().replace("- name: ", ""), (
            "workflow contains a `secrets.` reference"
        )

    def test_workflow_does_not_interpolate_untrusted_event_payloads_in_run_blocks(self, workflow_text):
        """``run:`` blocks must not interpolate untrusted ``github.event.*`` values.

        We allow ``matrix.python-version`` because it's a closed enum set
        in the same file.
        """
        unsafe_patterns = [
            "github.event.issue.title", "github.event.issue.body",
            "github.event.pull_request.title", "github.event.pull_request.body",
            "github.event.comment.body", "github.event.head_commit.message",
            "github.head_ref",
        ]
        # Crudely scan run: blocks only.
        in_run = False
        offenders: list[str] = []
        for line in workflow_text.splitlines():
            stripped = line.lstrip()
            if stripped.startswith("run:"):
                in_run = True
                # First line may contain a single-line run command — check it.
                inline = line.split("run:", 1)[1]
                for pat in unsafe_patterns:
                    if pat in inline:
                        offenders.append(f"inline run: {line.strip()}")
                continue
            if in_run:
                # A new YAML key at lower indentation ends the run block.
                if stripped and not line.startswith(" "):
                    in_run = False
                    continue
                for pat in unsafe_patterns:
                    if pat in line:
                        offenders.append(f"run-block line: {line.strip()}")
        assert offenders == [], f"unsafe ``github.event.*`` interpolation in run: {offenders}"

    def test_workflow_has_minimal_permissions(self, workflow):
        # ``contents: read`` is the principle-of-least-privilege default
        # for a pytest-only workflow.
        perms = workflow.get("permissions")
        assert perms == {"contents": "read"}, perms

    def test_workflow_artifact_path_targets_macos(self, workflow_text):
        # The artifact paths must be workflow-root relative (i.e. start
        # with "macOS/") because actions/upload-artifact ignores the job
        # working-directory.
        assert "macOS/runtime/*.log" in workflow_text, workflow_text


class TestPytestStrictRuns:
    """Belt-and-braces: prove the strict-markers config doesn't break the suite."""

    def test_no_undeclared_markers_in_suite(self):
        """Walk every test file and ensure all ``@pytest.mark.<name>`` are
        either the builtin set or declared in pyproject.toml ``markers``.
        """
        builtin = {
            "parametrize", "skip", "skipif", "xfail",
            "usefixtures", "filterwarnings", "asyncio",
        }
        with PYPROJECT.open("rb") as f:
            data = tomllib.load(f)
        declared = set()
        for entry in data["tool"]["pytest"]["ini_options"].get("markers", []):
            name = entry.split(":", 1)[0].strip()
            declared.add(name)
        allowed = builtin | declared

        marker_re = re.compile(r"@pytest\.mark\.([A-Za-z_][A-Za-z0-9_]*)")
        used: set[str] = set()
        for test_file in (MACOS_DIR / "tests").glob("test_*.py"):
            for m in marker_re.finditer(test_file.read_text()):
                used.add(m.group(1))

        undeclared = used - allowed
        assert undeclared == set(), (
            f"markers used in suite but neither builtin nor declared: {undeclared}. "
            f"Declare them in pyproject.toml [tool.pytest.ini_options] markers."
        )
