# Dive Into Crypto — Performance Benchmarks

These are **OFFLINE, compute-only micro-benchmarks** of the Kotlin Multiplatform
consensus engine that powers Dive Into Crypto. They run on the JVM under the
`androidUnitTest` source set with **no network, no Binance API calls, and no
Android framework** — every measurement is pure indicator math + consensus
scoring over a deterministic synthetic OHLCV series (sine + linear drift,
300 bars) built with the real `Candle` constructor.

Network/IO latency (Binance REST/WebSocket round-trips) is **excluded by
design**. The "500-symbol universe" projection below is therefore a
*compute-only* lower bound on a single thread; real-world wall time is dominated
by network fan-out and is parallelized across `scanParallelism` workers in the
app.

## Machine

| | |
| --- | --- |
| macOS | 26.5 |
| CPU | Apple M4 |
| Model | Mac16,10 |
| Arch | arm64 |
| JDK | OpenJDK 17.0.19 2026-04-21 (Homebrew, 64-Bit Server VM, mixed mode) |

## Method

- Deterministic synthetic candles (no RNG, no network): `sin` oscillation +
  linear drift, 300 × 1h bars, generated via the production `Candle` data class.
- Each indicator is constructed with its real `IndicatorConfig` defaults (the
  same maps the fixture-pinned tests use) and exercised through its real
  `calculate(candles: List<Candle>): IndicatorResult`.
- One **consensus eval** = all 15 indicators' `calculate(...)` collected into a
  `List<IndicatorResult>` and fed to `ConsensusEngine.evaluate(...)` (the F2
  weighted-score path, including regime matrix + risk assessment).
- One **full symbol scan** = 12 consecutive consensus evals (one per timeframe).
- Timing: `kotlin.system.measureNanoTime`, 50 warmup iterations then 500 timed
  iterations, reported as the **median** ns/op (robust to GC/JIT outliers),
  converted to ops/sec. Single-threaded.

## Results

| Benchmark | ops/sec |
| --- | ---: |
| indicator: `rsi` | 65,041 |
| indicator: `stochastic` | 13,514 |
| indicator: `williams_r` | 26,059 |
| indicator: `cci` | 12,746 |
| indicator: `macd` | 31,373 |
| indicator: `ema_cross` | 62,177 |
| indicator: `sma_cross` | 32,922 |
| indicator: `ichimoku` | 11,958 |
| indicator: `psar` | 123,077 |
| indicator: `bollinger` | 14,002 |
| indicator: `mfi` | 80,263 |
| indicator: `obv` | 44,444 |
| indicator: `roc` | 615,385 |
| indicator: `adx_di` | 6,495 |
| indicator: `atr_filter` | 40,269 |
| **consensus eval (15 indicators + engine)** | 1,593 |
| **full symbol scan (12 timeframes)** | 179.7 |

**Projection:** scanning a **500-symbol** universe (compute-only, network
excluded) takes **≈ 2.782 seconds** single-threaded — per-symbol = 12 consensus
evals, ≈ 179.7 symbols/sec. With the app's default `scanParallelism = 8` this
compute cost is effectively negligible next to network time.

> Numbers are from one representative run on the machine above and will vary
> run-to-run with thermal/JIT state; treat them as order-of-magnitude.

## Parity

61/61 fixture tests pass (indicator outputs pinned to the original Python
reference within per-test tolerances). Adding this benchmark brings the JVM unit
suite to **62 tests**, all green.

## App package

- APK size: **3.6 MB** (release, R8-minified)
- `minSdk` 26, `targetSdk` 34

## App runtime

Measured on the signed, R8‑minified `v0.1.0` release APK installed on an Android 14
(API 34) arm64 emulator with software GPU (`swiftshader`). A physical device is
typically faster, especially for cold start.

| Metric | Value |
| --- | ---: |
| APK size | 3.6 MB |
| Cold start (`am start -W`, TotalTime) | ≈ 2.18 s |
| Steady‑state memory (TOTAL PSS) | ≈ 31 MB |

Launch was crash‑free with R8 minification enabled — the kotlinx.serialization
keep‑rules preserve the Binance DTO serializers at runtime.

## Reproduce

From the repository root (JDK 17 required):

```bash
./gradlew :app:testDebugUnitTest --tests "com.diveintocrypto.android.benchmark.ScanBenchmark" -i \
  2>&1 | sed -n '/| Benchmark/,/Projected\|universe\|scan/p'
```
