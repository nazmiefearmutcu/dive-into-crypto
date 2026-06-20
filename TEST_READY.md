# E2E Test Suite Ready

The comprehensive opaque-box E2E test suite for Dive Into Crypto has been successfully implemented and configured.

## Directory Structure Created

```
tests/
├── __init__.py
├── conftest.py
├── e2e/
│   ├── __init__.py
│   ├── test_tier1_coverage.py  # 35 feature coverage tests
│   ├── test_tier2_boundary.py  # 35 boundary & corner tests
│   ├── test_tier3_pairwise.py  # 7 pairwise combination tests
│   └── test_tier4_scenarios.py # 5 real-world scenario tests
└── static_analysis/
    ├── __init__.py
    └── test_gradle_signing.py  # 1 Gradle release signing config test
```

## Running the Tests

Ensure dependencies are installed using `uv`:
```bash
cd /Users/nazmi/dive-into-crypto/desktop/backend
uv pip install pytest httpx pytest-asyncio websockets
```

Execute all tests from the project root:
```bash
cd /Users/nazmi/dive-into-crypto
uv run pytest tests/
```

## Test Tiers and Features Covered

1. **Zero-Lag EMA (ZLEMA)**: Eliminates repaint/look-ahead bias. Verified causally and compared response time with standard EMA.
2. **Bessel's Correction (N-1 Variance)**: Division by N-1 for sample variance. Verified mathematical precision.
3. **O(N+M) Two-Pointer Alignment**: Matches time series in linear time. Verified speed and correct matching.
4. **GC Translation Optimization**: Compounded single-pass regex replacement map. Verified case preservation and lack of double-replacements.
5. **Binance WebSocket & Local Cache**: Active web socket streams updating brief caches. Verified connection, JSON formats, caching behavior, and error handling.
6. **Secure Keystore Env Fallback**: Fallback release signing logic in `build.gradle.kts` using environment variables. Statically analyzed code blocks.
7. **Client-Server Parity**: Z-Score thresholds and ADX regimes parity. Verified engine evaluations.
