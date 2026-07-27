#!/bin/bash
set -e

# Resolve the repo root from this script's own location so the script runs from
# any checkout, and from any working directory.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Running Pytest in desktop/backend ==="
cd "$REPO_ROOT/desktop/backend"
pytest

echo "=== Running Gradle Tests in android ==="
cd "$REPO_ROOT/android"
./gradlew test

echo "=== Testing Release Signing Config with Environment Variables ==="
export STORE_FILE="release.keystore"
export STORE_PASSWORD="mock_store_password"
export KEY_ALIAS="mock_key_alias"
export KEY_PASSWORD="mock_key_password"
./gradlew :app:signingReport
