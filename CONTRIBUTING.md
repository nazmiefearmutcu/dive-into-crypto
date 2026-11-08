# Contributing to Dive into Crypto

Thanks for your interest. This repo has two editions (desktop and Android) sharing one engine, so
contributions touch one of four areas: the Python engine/backend (`desktop/backend`), the React UI
(`desktop/ui`), the Kotlin/Compose Android edition (`android/`), or the root E2E suite
(`tests/`).

## Development setup

| Tool | Version | Needed for |
|---|---|---|
| [Python](https://www.python.org/) | 3.12+ | backend + root E2E suite |
| [uv](https://docs.astral.sh/uv/) | latest | Python dependency management (`uv sync`) |
| [Node.js](https://nodejs.org/) | 18+ | UI tests + rebuilding the committed bundle |
| JDK | 17 (Temurin recommended) | Android builds |
| Android SDK | via Android Studio or command-line tools | Android builds (Gradle finds it via `ANDROID_HOME` or `local.properties`) |

Clone, then verify your environment by running the full gate (see below) — everything is
offline by default.

## Running the tests

Four suites. Test counts change as features land, so take the current numbers from the CI run
or from the test runners themselves rather than from the docs. See
[docs/testing.md](docs/testing.md) for what each suite covers.

```bash
# 1 · Backend engine suite (offline)
cd desktop/backend && uv sync && uv run pytest -q

# 2 · Root E2E suite (offline — data layer is mocked)
uv run --project desktop/backend pytest tests/ -q

# 3 · UI suite + rebuild the committed bundle
cd desktop/ui && npm ci && npm test && node build.mjs

# 4 · Android unit tests (JDK 17)
cd android && ./gradlew :app:test
```

`./run_tests.sh` runs all four in order and prints a summary; it exits non-zero if any suite
fails. Live-network tests are opt-in and run from the backend directory:
`cd desktop/backend && uv run pytest -m live -q` (never run in CI; see docs/testing.md).

CI (`.github/workflows/ci.yml`) runs the backend and root suites, the UI suite with a **dist-drift
gate**, and on every push to `main` and every pull request, the Android job: unit tests, lint,
a debug APK, and an unsigned release build used as a smoke test.

## The committed-dist convention

`desktop/ui/dist/` — the esbuild output — **is committed on purpose**. The backend serves it
directly (`api/app.py` → `_UI_DIST`), so the desktop app runs with no Node installed; it is a
runtime artefact, not a build leftover (see the note in `.gitignore`).

If you change anything under `desktop/ui/src/`:

1. `cd desktop/ui && node build.mjs`
2. **Commit the updated `dist/` files together with your source change.**

CI rebuilds the bundle and fails with a dist-drift error if the committed `dist/` differs from
a fresh build. Don't hand-edit files in `dist/`.

## The honesty doctrine — nothing is synthesised

This project's core promise: **no fabricated market data, ever.** When a data source is
unavailable it is shown as unavailable, never faked — a failed fetch renders an explicit
"DATA SOURCE UNAVAILABLE" state naming the underlying error, not plausible-looking numbers.
Binance market data is geo-restricted in some regions (Türkiye included), so the failure path
is a *normal* condition here, not an edge case.

Concretely:

- API failures produce explicit error envelopes (`symbol_fetch_failed`,
  `live_fetch_failed`) — never zero-filled or placeholder data. See [docs/api.md](docs/api.md).
- The one fabricated dataset, `desktop/ui/src/app/mock.js`, exists only for manual UI
  development without a backend; nothing in the app calls it, and while active the UI shows a
  fixed `DEMO DATA — NOT LIVE MARKET DATA` banner plus a `DEMO` marker on every fabricated
  value. Both properties are pinned by tests (`desktop/ui/test/demo-mode.test.mjs`).
- Any feature that would blur this line — inventing a value, silently reusing stale data,
  auto-falling back to demo mode — is a bug by definition, even if it "looks better".

If your change affects failure paths, add a test that pins the honest behaviour.

## Cross-language parity

The Kotlin engine is a fixture-verified mirror of the Python reference: every indicator must
reproduce the Python signal (and score, where pinned) exactly, per indicator, against shared
fixtures. If you change engine behaviour, update the Python reference **and** the Kotlin port
**and** the parity fixtures in the same PR. See the [Parity section of the
README](README.md#parity).

## Platform notes

**Windows:** if your user profile path contains non-ASCII characters (for example
`C:\Users\Kullanıcı`), Gradle and the JDK can fail with `Unable to establish loopback connection`
or `Could not find or load main class ...GradleWorkerMain`. Point `GRADLE_USER_HOME`, `TMP`/`TEMP`
and `JAVA_TOOL_OPTIONS` at an ASCII-only folder, with JDK 17. The steps are in
[Windows build notes](docs/testing.md#windows-build-notes).

**Windows (Git Bash):** `run_tests.sh` needs a JDK 17; point it at yours per-invocation:
`JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.x-hotspot" ./run_tests.sh`.

## Pull requests

- Keep PRs focused; run the relevant suites (ideally all four) before opening.
- CI must be green; a red dist-drift job means you forgot step 2 above.
- No API keys, keystore files, or secrets of any kind — the app reads only public market data
  (see the Data sources table in the README), and `.gitignore` blocks keystores deliberately.

## Release process

Releases are tag-driven. Pushing a `v*` tag runs `.github/workflows/release.yml`, which:

- builds the **Android** release AAB (for Google Play), a signed APK and `mapping.txt`
  (R8-minified). It requires the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
  `KEY_PASSWORD` secrets and stops before building if any of them is missing, so an unsigned
  release is never published. It attaches the files to the GitHub Release;
- optionally packages the **desktop** edition with PyInstaller via the manual
  `package_desktop` workflow input (never on tags).

Secret names, the keystore generation how-to, the desktop packaging commands and the
size/AV expectations are documented in [docs/packaging.md](docs/packaging.md). Maintainer
note: on the owner's machine `git push` fails above ~1 MB, so create tags and releases
via the REST API (or let CI attach artefacts) rather than pushing them.

## License

By contributing you agree that your contributions are licensed under the [MIT
License](LICENSE).
