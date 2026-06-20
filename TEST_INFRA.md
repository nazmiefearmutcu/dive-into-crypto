# E2E Test Infra: Dive Into Crypto

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on implementation design.
- Methodology: Category-Partition + BVA + Pairwise + Workload Testing.

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|:------:|:------:|:------:|
| 1 | Zero-Lag EMA (ZLEMA) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 2 | Bessel's Correction (N-1 Variance) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 3 | O(N+M) Two-Pointer Data Alignment | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 4 | Regex swapKeywords GC Optimization | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 5 | Binance WebSocket & Local Cache | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 6 | Secure Keystore Env Fallback | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 7 | Client-Server Parity (Z-Score/ADX) | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ |

## Test Architecture
- **Test runner**: `pytest` running with Python 3.12+. Invocation command: `pytest tests/` (or `uv run pytest tests/`). Pass/fail is determined by exit code 0.
- **Test case format**:
  - Inputs: Raw mock/simulated OHLCV data, Gradle build configurations, mock WebSocket frames, JSON payloads.
  - Expected outputs: Unpainted historical ZLEMA values, Bessel-corrected variance values, aligned series lengths, correctly replaced keywords, WebSocket cache update logs, parsed environment fallback, matching Kotlin and Python consensus outputs.
- **Directory layout**:
  ```
  tests/
  ├── __init__.py
  ├── conftest.py
  ├── e2e/
  │   ├── __init__.py
  │   ├── test_tier1_coverage.py
  │   ├── test_tier2_boundary.py
  │   ├── test_tier3_pairwise.py
  │   └── test_tier4_scenarios.py
  └── static_analysis/
      ├── __init__.py
      └── test_gradle_signing.py
  ```

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|---|---|---|
| 1 | High-volatility market scan cycle with WebSocket caching | F1, F2, F4, F5, F7 | High |
| 2 | Heavy parallel requests scan cache throttling | F5 | Medium |
| 3 | WebSocket network disconnection & cache recovery fallback | F5 | High |
| 4 | Clean room Gradle release build signing fallback | F6 | Medium |
| 5 | Dynamic regime shift (Chop vs Trend scaling) | F7 | High |

## Coverage Thresholds
- Tier 1: 35 tests (5 per feature across 7 features)
- Tier 2: 35 tests (5 per feature across 7 features)
- Tier 3: 7 tests (pairwise combinations of major features)
- Tier 4: 5 realistic application scenarios
- **Total E2E test cases: 82**
