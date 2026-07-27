import pytest
from pathlib import Path

# Resolved from this file so the check runs from any checkout.
_GRADLE_KTS = Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"

def test_gradle_signing_configuration_secure_fallback():
    """Verify that build.gradle.kts has secure key/keystore management falling back to environment variables.
    
    Specifically, it must check and use environment variables (e.g., STORE_PASSWORD, KEY_PASSWORD) 
    as fallback or primary configurations, rather than relying exclusively on plain text in keystore.properties.
    """
    gradle_path = _GRADLE_KTS
    assert gradle_path.exists(), f"Gradle build file not found at {gradle_path}"
    
    content = gradle_path.read_text()
    
    # Assert that environment variables are checked/used in build.gradle.kts
    assert "System.getenv" in content, (
        "build.gradle.kts does not reference environment variables fallback (System.getenv)."
    )
    
    # Assert that standard signing parameters fall back to environment variables
    assert "STORE_PASSWORD" in content, (
        "build.gradle.kts does not fallback to STORE_PASSWORD environment variable."
    )
    
    assert "KEY_PASSWORD" in content, (
        "build.gradle.kts does not fallback to KEY_PASSWORD environment variable."
    )
