# Contributing to Dive Into Crypto

Thanks for your interest. This repo has three editions sharing one engine, so contributions
touch one of four areas: the Python engine/backend (`desktop/backend`), the React UI
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

Four suites; test counts are approximate and grow as features land. See
[docs/testing.md](docs/testing.md) for what each suite covers.

```bash
# 1 · Backend engine suite (~112 tests, offline)
cd desktop/backend && uv sync && uv run pytest -q

# 2 · Root E2E suite (~95 tests, offline — data layer is mocked)
uv run --project desktop/backend pytest tests/ -q

# 3 · UI suite (5 tests) + rebuild the committed bundle
cd desktop/ui && npm ci && npm test && node build.mjs

# 4 · Android unit tests (~129 tests, JDK 17)
cd android && ./gradlew :app:test
```

`./run_tests.sh` runs all four in order and prints a summary. Live-network tests are opt-in:
`uv run pytest -m live` (never run in CI; see docs/testing.md).

CI (`.github/workflows/ci.yml`) runs backend + root suites, the UI suite with a **dist-drift
gate**, and Android tests + a debug APK build on every push to `main` and pull request.

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

**Windows:** Android builds require the project to live at an **ASCII-only path** — the
Android Gradle Plugin's path check fails on non-ASCII directories (e.g. paths containing
`ı`, `ş`, `ç`). If `C:\Users\<name>\...` contains non-ASCII characters, clone the repo
somewhere like `C:\dev\dive-into-crypto`.

**Windows (Git Bash):** `run_tests.sh` needs a JDK 17; point it at yours per-invocation:
`JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.x-hotspot" ./run_tests.sh`.

## Pull requests

- Keep PRs focused; run the relevant suites (ideally all four) before opening.
- CI must be green; a red dist-drift job means you forgot step 2 above.
- No API keys, keystore files, or secrets of any kind — the app reads public Binance data
  only, and `.gitignore` blocks keystores deliberately.

## Release process

Releases are tag-driven. Pushing a `v*` tag runs `.github/workflows/release.yml`, which:

- builds the **Android** release APK + AAB (R8-minified, signed when the
  `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` secrets are set —
  an unsigned build is still published otherwise) and attaches them to the GitHub Release;
- optionally packages the **desktop** edition with PyInstaller via the manual
  `package_desktop` workflow input (never on tags).

Secret names, the keystore generation how-to, the desktop packaging commands and the
size/AV expectations are documented in [docs/packaging.md](docs/packaging.md). Maintainer
note: on the owner's machine `git push` fails above ~1 MB, so create tags and releases
via the REST API (or let CI attach artefacts) rather than pushing them.

## License

By contributing you agree that your contributions are licensed under the [MIT
License](LICENSE).
