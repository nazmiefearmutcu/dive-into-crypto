# Testing

Dive into Crypto has **four test suites** — two Python, one JavaScript, one Kotlin/Gradle.
All of them run offline by default; live-network tests are opt-in. This page consolidates the
former root-level `TEST_INFRA.md` and `TEST_READY.md`.

## Suite inventory

This page lists what each suite covers, not how many tests it has: counts change as features
land. For the current numbers, read the test output of a CI run
(`.github/workflows/ci.yml` runs every suite on push to `main` and on every pull request) or
run `pytest --collect-only -q` and `./gradlew :app:test` locally.

| # | Suite | Command (from repo root) | What it covers |
|---|---|---|---|
| 1 | Backend engine | `cd desktop/backend && uv sync && uv run pytest -q` | The reference engine: all 60 indicators + 3 overlays, consensus/verdict logic, data parsers, cross-language parity fixtures, per-endpoint client behaviour (offline) |
| 2 | Root E2E | `uv run --project desktop/backend pytest tests/ -q` | Opaque-box end-to-end behaviour against the packaged engine (offline: `tests/conftest.py` mocks the data layer), adversarial math, static checks of the Gradle signing setup and the Android release pipeline |
| 3 | UI | `cd desktop/ui && npm ci && npm test` (then `node build.mjs`) | The "nothing is synthesised" guarantees of the React front-end: failed fetches never reach the demo generator, and demo mode always renders its banner + per-value markers |
| 4 | Android | `cd android && ./gradlew :app:test` (JDK 17) | The Kotlin engine unit tests, including the fixture-verified parity mirror of the Python reference |

CI additionally runs lint, builds the debug APK (`:app:assembleDebug`) and an unsigned release
bundle as a smoke test, and enforces the **dist-drift gate**: `desktop/ui/dist/` is committed on
purpose (the backend serves it directly), so CI rebuilds it and fails if the committed bundle
differs from a fresh build.

## Running every suite locally

```bash
# 1 · Backend engine suite (offline)
cd desktop/backend && uv sync && uv run pytest -q

# 2 · Root E2E suite (offline — data layer is mocked)
uv run --project desktop/backend pytest tests/ -q

# 3 · UI suite + rebuild the committed bundle
cd desktop/ui && npm ci && npm test && node build.mjs

# 4 · Android unit tests (needs JDK 17)
cd android && ./gradlew :app:test
```

Or everything at once: `./run_tests.sh` (runs all four in order and prints a summary; it exits
non-zero if any suite fails).

## Windows build notes

Gradle and the JDK can fail on Windows when the user profile path contains non-ASCII characters
(for example `C:\Users\Kullanıcı`). The usual symptoms are:

- `Unable to establish loopback connection`
- `Could not find or load main class worker.org.gradle.process.internal.worker.GradleWorkerMain`

The fix is to point Gradle's home and the temp directories at an ASCII-only folder. Create the
folder first, then run the build from the same PowerShell session (PowerShell syntax; in Git Bash
use `export` with the same names):

```powershell
New-Item -ItemType Directory -Force "C:\dive-gradle\gradle-home", "C:\dive-gradle\tmp" | Out-Null
$env:GRADLE_USER_HOME = "C:\dive-gradle\gradle-home"
$env:TMP = "C:\dive-gradle\tmp"
$env:TEMP = "C:\dive-gradle\tmp"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\dive-gradle\tmp -Djava.io.tmpdir=C:\dive-gradle\tmp"
$env:JAVA_HOME = "<path-to-your-JDK-17>"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd C:\dive-into-crypto\android
.\gradlew.bat :app:test --console=plain
```

- **JDK 17 is required.** Point `JAVA_HOME` at a JDK 17 install (Temurin is recommended), not at a
  newer JDK.
- `JAVA_TOOL_OPTIONS` applies to every JVM the shell starts, so unset it in a shared session when
  you are done.
- The repository itself may stay where it is. If a build still fails, clone the repository into an
  ASCII-only folder as well.
- The same variables apply to `./run_tests.sh`; set `JAVA_HOME` as shown in the Git Bash example in
  [CONTRIBUTING.md](../CONTRIBUTING.md#platform-notes).

## Root E2E suite: tier methodology

The `tests/` suite is **opaque-box and requirement-driven** — it asserts behaviour through the
public interfaces, not implementation internals. Methodology: **Category-Partition + BVA +
Pairwise + Workload Testing**, organised in four tiers (plus static analysis):

| Tier | File | Focus |
|---|---|---|
| 1 | `tests/e2e/test_tier1_coverage.py` | Feature coverage — several tests per feature across the 7 tracked features |
| 2 | `tests/e2e/test_tier2_boundary.py` | Boundary & corner cases (BVA) — several tests per feature |
| 3 | `tests/e2e/test_tier3_pairwise.py` | Pairwise combinations of the major features |
| 4 | `tests/e2e/test_tier4_scenarios.py` | Real-world scenarios: high-volatility scan with WS caching, parallel-request cache throttling, WS disconnect + cache recovery, clean-room Gradle signing, regime shift (chop vs trend) |
| — | `tests/e2e/test_adversarial_math.py` | Adversarial/degenerate numeric inputs to the engine |
| — | `tests/static_analysis/test_gradle_signing.py` | Release-signing env fallback in `build.gradle.kts` (static analysis) |
| — | `tests/static_analysis/test_android_release_pipeline.py` | Static guards on the Android release pipeline and Play-facing platform config (no Gradle, no network) |

The 7 tracked features: Zero-Lag EMA (ZLEMA), Bessel's correction (N−1 variance), O(N+M)
two-pointer data alignment, regex `swapKeywords` GC optimization, Binance WebSocket + local
cache, secure-keystore env fallback, and client–server parity (Z-Score thresholds / ADX regimes).

## Offline by default; live tests are opt-in

Everything above runs **offline**: the backend and root suites mock/stub the network layer, and
the UI suite runs on plain Node with no backend. Tests that genuinely hit live Binance endpoints
are marked `live` (see the `live` marker in `desktop/backend/pyproject.toml`) and are **excluded
from the default run and from CI**.

Run them explicitly when you want to exercise the real network path:

```bash
cd desktop/backend
uv run pytest -m live -q
```

> Binance market data is geo-restricted in some regions — a live-marker failure may be a network
> condition, not a code bug. It is shown as unavailable, never faked (see
> ["Nothing is synthesised"](../README.md#nothing-is-synthesised) in the README).
