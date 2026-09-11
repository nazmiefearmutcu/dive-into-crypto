#!/bin/bash
# Run every test suite in the repo, in order, with a final summary.
#
# Deliberately NOT `set -e`: a failing suite must not hide the results of the
# suites after it. Each suite's exit code is accumulated in $FAILED and the
# script exits non-zero if any suite failed. Run it from anywhere — the repo
# root is resolved from this script's own location.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

FAILED=()

# ── 1/4 · Backend engine suite (indicators, consensus, parity, parsers) ─────
echo "=== SUITE 1/4 · backend pytest (desktop/backend) ==="
(
    cd desktop/backend
    uv sync
    uv run pytest -q
) || FAILED+=("backend (desktop/backend): uv sync && uv run pytest -q")

# ── 2/4 · Root E2E suite (offline; conftest mocks the data layer) ───────────
echo "=== SUITE 2/4 · root E2E pytest (tests/) ==="
uv run --project desktop/backend pytest tests/ -q \
    || FAILED+=("root E2E (tests/): uv run --project desktop/backend pytest tests/ -q")

# ── 3/4 · UI suite (no-fabricated-data guarantees) + fresh build ────────────
echo "=== SUITE 3/4 · UI (desktop/ui) npm ci + npm test + node build.mjs ==="
(
    cd desktop/ui
    npm ci
    npm test
    node build.mjs
) || FAILED+=("ui (desktop/ui): npm ci && npm test && node build.mjs")

# ── 4/4 · Android unit tests ────────────────────────────────────────────────
# Needs a JDK 17. gradlew picks JAVA_HOME up from the environment, so override
# it per-invocation if your default JDK differs, e.g. on Windows (Git Bash):
#   JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.x-hotspot" ./run_tests.sh
# (linux/macos: export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 first)
echo "=== SUITE 4/4 · android (./gradlew :app:test) ==="
(
    cd android
    chmod +x gradlew
    ./gradlew :app:test --console=plain
) || FAILED+=("android (android/): ./gradlew :app:test")

# ── Summary ─────────────────────────────────────────────────────────────────
echo
echo "========================================================"
echo "                  TEST SUITE SUMMARY"
echo "========================================================"
if [ "${#FAILED[@]}" -eq 0 ]; then
    echo "RESULT: all 4 suites PASSED"
    exit 0
fi
echo "RESULT: ${#FAILED[@]} of 4 suite(s) FAILED:"
for f in ${FAILED[@]+"${FAILED[@]}"}; do
    echo "  - $f"
done
exit 1
