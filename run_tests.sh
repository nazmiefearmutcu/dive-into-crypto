#!/bin/bash
set -e

echo "=== Running Pytest in desktop/backend ==="
cd /Users/nazmi/dive-into-crypto/desktop/backend
pytest

echo "=== Running Gradle Tests in android ==="
cd /Users/nazmi/dive-into-crypto/android
./gradlew test

echo "=== Testing Release Signing Config with Environment Variables ==="
export STORE_FILE="release.keystore"
export STORE_PASSWORD="mock_store_password"
export KEY_ALIAS="mock_key_alias"
export KEY_PASSWORD="mock_key_password"
./gradlew :app:signingReport
