"""Static guards for the Android release pipeline and the Play-facing platform config.

These read the committed sources only (no Gradle, no network) so they run in the
backend job. They pin the release invariants that were fixed:
  - an unsigned release is never published, and signatures are verified;
  - CI uses the locked dependency sets and builds the release variant;
  - backups do not carry the local preferences file;
  - edge-to-edge is enabled and the window colour APIs are gone.
"""

from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[2]
_ANDROID = _REPO / "android"
_APP = _ANDROID / "app"
_MAIN = _APP / "src" / "androidMain"
_WORKFLOWS = _REPO / ".github" / "workflows"


def _read(path: Path) -> str:
    assert path.exists(), f"missing file: {path}"
    return path.read_text(encoding="utf-8")


def test_release_workflow_refuses_unsigned_builds_and_verifies_signatures():
    text = _read(_WORKFLOWS / "release.yml")
    # Signing secrets are required before anything is built.
    assert "Require release signing secrets" in text
    assert "Refusing to build an unsigned release" in text
    # The old "unsigned but still published" paths are gone.
    assert "APK will be unsigned" not in text
    assert "**Unsigned build**" not in text
    # Signatures are checked on the artifacts themselves.
    assert "jarsigner -verify" in text
    assert "apksigner" in text and "verify --print-certs" in text
    # Release assets include the R8 mapping, and the debug APK is never offered.
    assert "mapping.txt" in text
    assert "debug" not in text.lower()


def test_release_workflow_is_idempotent_and_locked():
    text = _read(_WORKFLOWS / "release.yml")
    assert "gh release view" in text, "re-runs must not fail on an existing release"
    assert "uv sync --locked" in text
    assert "uv sync\n" not in text


def test_release_workflow_has_no_dead_ui_mirror_step():
    text = _read(_WORKFLOWS / "release.yml")
    assert "Mirror UI bundle" not in text
    assert "dist\\ui" not in text, "the UI bundle ships once, inside dist\\dive-desktop"


def test_ci_locks_backend_and_checks_untracked_dist_files():
    text = _read(_WORKFLOWS / "ci.yml")
    assert "uv sync --locked" in text
    assert "git status --porcelain --untracked-files=all -- desktop/ui/dist" in text


def test_ci_builds_release_variant_and_lints():
    text = _read(_WORKFLOWS / "ci.yml")
    assert ":app:lintDebug" in text
    assert ":app:bundleRelease" in text
    assert "-PallowUnsignedRelease=true" in text


def test_gradle_release_guard_and_blank_env_handling():
    text = _read(_APP / "build.gradle.kts")
    assert "allowUnsignedRelease" in text
    assert "GradleException" in text
    assert "isShrinkResources = true" in text
    assert "takeIf { it.isNotBlank() }" in text
    assert "System.getenv" in text and "STORE_PASSWORD" in text and "KEY_PASSWORD" in text


def test_proguard_keeps_sealed_record_hierarchy_and_strips_debug_logs():
    text = _read(_APP / "proguard-rules.pro")
    assert "com.diveintocrypto.android.engine.schema.**" in text
    assert "-assumenosideeffects class android.util.Log" in text


def test_backups_exclude_local_preferences():
    for name in ("backup_rules.xml", "data_extraction_rules.xml"):
        text = _read(_MAIN / "res" / "xml" / name)
        assert 'path="dive_settings.xml"' in text, name
    assert "<cloud-backup>" in _read(_MAIN / "res" / "xml" / "data_extraction_rules.xml")
    assert "<device-transfer>" in _read(_MAIN / "res" / "xml" / "data_extraction_rules.xml")


def test_edge_to_edge_enabled_and_window_colour_apis_removed():
    activity = _read(_MAIN / "kotlin" / "com" / "diveintocrypto" / "android" / "MainActivity.kt")
    assert "enableEdgeToEdge()" in activity
    assert "statusBarColor" not in activity
    assert "navigationBarColor" not in activity
    themes = _read(_MAIN / "res" / "values" / "themes.xml")
    assert 'name="android:statusBarColor"' not in themes
    assert 'name="android:navigationBarColor"' not in themes


@pytest.mark.parametrize(
    "rel",
    [
        "mipmap-anydpi-v26/ic_launcher.xml",
        "mipmap-anydpi-v26/ic_launcher_round.xml",
        "drawable/ic_launcher_monochrome.xml",
    ],
)
def test_adaptive_launcher_icon_files_exist(rel):
    assert (_MAIN / "res" / rel).exists()


def test_notification_small_icon_is_referenced_and_monochrome():
    alert = _read(
        _MAIN / "kotlin" / "com" / "diveintocrypto" / "android" / "domain" / "alerts" / "AlertNotifier.android.kt"
    )
    assert "R.drawable.ic_stat_cool" in alert
    assert "ctx.applicationInfo.icon" not in alert
    assert "FLAG_IMMUTABLE" in alert
    icon = _read(_MAIN / "res" / "drawable" / "ic_stat_cool.xml")
    assert "android:tint" not in icon


def test_widget_and_manifest_labels_are_localized_resources():
    manifest = _read(_MAIN / "AndroidManifest.xml")
    assert 'android:label="@string/widget_label"' in manifest
    assert "Top Kararlar\"" not in manifest
    assert "widget_label" in _read(_MAIN / "res" / "values" / "strings.xml")
    assert "widget_label" in _read(_MAIN / "res" / "values-tr" / "strings.xml")
