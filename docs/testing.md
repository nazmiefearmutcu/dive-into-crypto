# Testing

Dive Into Crypto has **four test suites** — two Python, one JavaScript, one Kotlin/Gradle.
All of them run offline by default; live-network tests are opt-in. This page consolidates the
former root-level `TEST_INFRA.md` and `TEST_READY.md`.

## Suite inventory

Test counts are **approximate** — they move as features land. The CI workflow
(`.github/workflows/ci.yml`) runs every suite on push to `main` and on every pull request.

| # | Suite | Command (from repo root) | ~Tests | What it covers |
|---|---|---|---|---|
| 1 | Backend engine | `cd desktop/backend && uv sync && uv run pytest -q` | ~186 | The reference engine: all 60 indicators + 3 overlays, consensus/verdict logic, data parsers, cross-language parity fixtures, per-endpoint client behaviour (offline) |
| 2 | Root E2E | `uv run --project desktop/backend pytest tests/ -q` | ~95 | Opaque-box end-to-end behaviour against the packaged engine (offline: `tests/conftest.py` mocks the data layer), adversarial math, Gradle signing static analysis |
| 3 | UI | `cd desktop/ui && npm ci && npm test` (then `node build.mjs`) | 5 | The "nothing is synthesised" guarantees of the React front-end: failed fetches never reach the demo generator, and demo mode always renders its banner + per-value markers |
| 4 | Android | `cd android && ./gradlew :app:test` (JDK 17) | ~129 | The Kotlin engine unit tests, including the fixture-verified parity mirror of the Python reference |

CI additionally builds the debug APK (`:app:assembleDebug`) and enforces the **dist-drift
gate**: `desktop/ui/dist/` is committed on purpose (the backend serves it directly), so CI
rebuilds it and fails if the committed bundle differs from a fresh build.

## Running every suite locally

```bash
# 1 · Backend engine suite (~186 tests, offline)
cd desktop/backend && uv sync && uv run pytest -q

# 2 · Root E2E suite (~95 tests, offline — data layer is mocked)
uv run --project desktop/backend pytest tests/ -q

# 3 · UI suite (5 tests) + rebuild the committed bundle
cd desktop/ui && npm ci && npm test && node build.mjs

# 4 · Android unit tests (~129 tests, needs JDK 17)
cd android && ./gradlew :app:test
```

Or everything at once: `./run_tests.sh` (runs all four in order and prints a summary).

## Root E2E suite: tier methodology

The `tests/` suite is **opaque-box and requirement-driven** — it asserts behaviour through the
public interfaces, not implementation internals. Methodology: **Category-Partition + BVA +
Pairwise + Workload Testing**, organised in four tiers (plus static analysis):

| Tier | File | Focus | ~Tests |
|---|---|---|---|
| 1 | `tests/e2e/test_tier1_coverage.py` | Feature coverage — 5 tests per feature across the 7 tracked features | 35 |
| 2 | `tests/e2e/test_tier2_boundary.py` | Boundary & corner cases (BVA) — 5 tests per feature | 35 |
| 3 | `tests/e2e/test_tier3_pairwise.py` | Pairwise combinations of the major features | 7 |
| 4 | `tests/e2e/test_tier4_scenarios.py` | Real-world scenarios: high-volatility scan with WS caching, parallel-request cache throttling, WS disconnect + cache recovery, clean-room Gradle signing, regime shift (chop vs trend) | 5 |
| — | `tests/e2e/test_adversarial_math.py` | Adversarial/degenerate numeric inputs to the engine | — |
| — | `tests/static_analysis/test_gradle_signing.py` | Release-signing env fallback in `build.gradle.kts` (static analysis) | 1 |

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
