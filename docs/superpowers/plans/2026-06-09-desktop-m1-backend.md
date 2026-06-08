# M1 — Desktop Backend (engine + data + API) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. The engine PORT + parity gate (Tasks 2–4) are executed inline by the lead (delicate, fixture-pinned); the new-code tasks (5–9) suit fresh subagents. Steps use checkbox (`- [ ]`).

**Goal:** A Crypcodile-fed Python backend that computes the canonical Dive Into Crypto consensus (Android-parity) over live Binance USDT-M futures and serves it to the desktop UI over local HTTP/WS.

**Architecture:** Port the original Python reference engine (15 indicators + consensus + risk) verbatim and pin it to the Android fixtures (parity gate). Build a new Crypcodile-backed data layer (klines/OI/funding/mark via Crypcodile's Binance parser + a small L/S-ratio fetcher), a scan/divergence module, and a FastAPI service emitting per-symbol objects in the UI's data contract.

**Tech Stack:** Python 3.12+, `uv`, pandas, numpy, pyyaml, `crypcodile`, aiohttp, FastAPI, uvicorn, pytest.

**Spec:** `docs/superpowers/specs/2026-06-09-dive-into-crypto-desktop-design.md` §5–§8.

**Reference engine source (read-only):** `/Volumes/disk 2/Desktop_Migrate_2026-05-28/Projeler/proje/TBV1_Windows/app/src` (+ `app/config/default.yaml`).
**Parity fixtures (in repo):** `android/app/src/commonTest/kotlin/com/diveintocrypto/android/testutil/FixtureData.kt` and the committed `btcusdt_1h_300_expected.json` (search `android/app/src/commonTest`).

---

### Task 1: Scaffold the backend package

**Files:**
- Create: `desktop/backend/pyproject.toml`, `desktop/backend/src/diveintocrypto_desktop/__init__.py`, `desktop/backend/README.md` (stub), `desktop/backend/.python-version`

- [ ] **Step 1: pyproject.toml**

```toml
[project]
name = "diveintocrypto-desktop"
version = "0.1.0"
description = "Dive Into Crypto — Desktop Edition backend (Crypcodile-fed scanner service)"
requires-python = ">=3.12"
dependencies = [
  "crypcodile",
  "pandas>=2.1",
  "numpy>=1.24",
  "pyyaml>=6.0",
  "aiohttp>=3.9",
  "aiolimiter>=1.1",
  "fastapi>=0.110",
  "uvicorn[standard]>=0.27",
]

[project.scripts]
dive-desktop = "diveintocrypto_desktop.__main__:main"

[tool.uv.sources]
crypcodile = { path = "../../../Crypcodile", editable = true }

[dependency-groups]
dev = ["pytest>=7.4", "pytest-asyncio>=0.23", "httpx>=0.27"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/diveintocrypto_desktop"]
```

Note: `tool.uv.sources` path is relative to `desktop/backend/`; `../../../Crypcodile` resolves to `~/Crypcodile`. Confirm at execution and adjust if the layout differs.

- [ ] **Step 2: Create package skeleton + install**

```bash
cd ~/dive-into-crypto/desktop/backend
mkdir -p src/diveintocrypto_desktop/{engine,data,scan,api} tests
touch src/diveintocrypto_desktop/__init__.py
printf '3.12\n' > .python-version
uv sync
```
Expected: `uv` resolves `crypcodile` (editable) + deps, creates `.venv`.

- [ ] **Step 3: Commit**

```bash
cd ~/dive-into-crypto
git add desktop/backend/pyproject.toml desktop/backend/src desktop/backend/.python-version desktop/backend/README.md
git commit -m "feat(desktop): scaffold backend package (crypcodile-fed)"
```

---

### Task 2: Port the engine — base + 15 indicators (inline)

**Files:**
- Create: `desktop/backend/src/diveintocrypto_desktop/engine/` ← copied from reference `src/indicators/` + `src/utils/{logger,helpers}.py`
- Create: `desktop/backend/src/diveintocrypto_desktop/engine/config/default.yaml` ← from reference `app/config/default.yaml`

- [ ] **Step 1: Copy indicator + util + config files from the reference**

```bash
REF="/Volumes/disk 2/Desktop_Migrate_2026-05-28/Projeler/proje/TBV1_Windows/app/src"
DST=~/dive-into-crypto/desktop/backend/src/diveintocrypto_desktop/engine
mkdir -p "$DST/indicators" "$DST/utils" "$DST/config"
cp "$REF/indicators/"*.py "$DST/indicators/"
cp "$REF/utils/logger.py" "$REF/utils/helpers.py" "$DST/utils/"
touch "$DST/__init__.py" "$DST/utils/__init__.py"
cp "/Volumes/disk 2/Desktop_Migrate_2026-05-28/Projeler/proje/TBV1_Windows/app/config/default.yaml" "$DST/config/"
```

- [ ] **Step 2: Rewrite imports `src.` → package namespace**

Replace every `from src.indicators` → `from diveintocrypto_desktop.engine.indicators`, `from src.utils` → `from diveintocrypto_desktop.engine.utils`, in all copied files:
```bash
cd ~/dive-into-crypto/desktop/backend/src/diveintocrypto_desktop/engine
grep -rl 'from src\.' . | xargs sed -i '' \
  -e 's/from src\.indicators/from diveintocrypto_desktop.engine.indicators/g' \
  -e 's/from src\.utils/from diveintocrypto_desktop.engine.utils/g' \
  -e 's/from src\.consensus/from diveintocrypto_desktop.engine.consensus/g'
```
Read each copied file after the rewrite to confirm no `from src.` remain and no `trading`/`api`/`data` imports leaked in. Strip any that did.

- [ ] **Step 3: Smoke-import every indicator**

Test `desktop/backend/tests/test_engine_imports.py`:
```python
import importlib, pkgutil
import diveintocrypto_desktop.engine.indicators as ind

def test_all_indicator_modules_import():
    names = [m.name for m in pkgutil.iter_modules(ind.__path__)]
    assert {"rsi","macd","bollinger","ema_cross","sma_cross","stochastic","adx_di",
            "cci","williams_r","roc","mfi","atr_filter","ichimoku","psar","obv"} <= set(names)
    for n in names:
        importlib.import_module(f"diveintocrypto_desktop.engine.indicators.{n}")
```
Run: `cd ~/dive-into-crypto/desktop/backend && uv run pytest tests/test_engine_imports.py -v` → PASS.

- [ ] **Step 4: Commit** — `git commit -m "feat(desktop/engine): port 15 indicators + utils + config from reference"`

---

### Task 3: Port consensus + signal service (inline)

**Files:**
- Create: `desktop/backend/.../engine/consensus/{__init__.py,engine.py,scorer.py,risk.py}` ← from reference `src/consensus/`
- Create: `desktop/backend/.../engine/signal_service.py` ← from reference `src/services/signal_service.py`

- [ ] **Step 1: Copy + rewrite imports**

```bash
REF="/Volumes/disk 2/Desktop_Migrate_2026-05-28/Projeler/proje/TBV1_Windows/app/src"
DST=~/dive-into-crypto/desktop/backend/src/diveintocrypto_desktop/engine
mkdir -p "$DST/consensus"
cp "$REF/consensus/"*.py "$DST/consensus/"
cp "$REF/services/signal_service.py" "$DST/signal_service.py"
cd "$DST" && grep -rl 'from src\.' . | xargs sed -i '' \
  -e 's/from src\.indicators/from diveintocrypto_desktop.engine.indicators/g' \
  -e 's/from src\.consensus/from diveintocrypto_desktop.engine.consensus/g' \
  -e 's/from src\.utils/from diveintocrypto_desktop.engine.utils/g'
```

- [ ] **Step 2: Config loader** `engine/loader.py`:
```python
from pathlib import Path
import yaml

def load_config() -> dict:
    p = Path(__file__).parent / "config" / "default.yaml"
    with p.open() as f:
        return yaml.safe_load(f)
```
Add a helper to map `indicator_thresholds` + `indicator_weights` into the per-indicator config dicts the indicator constructors expect (mirror how the reference `SignalService.__init__` builds them — read the reference `signal_service.py` constructor and replicate exactly).

- [ ] **Step 3: Smoke test** `tests/test_consensus_smoke.py` — build `SignalService(config)`, feed a synthetic 300-row OHLCV DataFrame, assert `calculate_all` returns 15 `IndicatorResult`s and `ConsensusEngine(config).evaluate(results)` returns a dict with keys `final_signal, confidence, risk_level, weighted_score, reason`. Run → PASS.

- [ ] **Step 4: Commit** — `git commit -m "feat(desktop/engine): port consensus engine + signal service"`

---

### Task 4: Parity gate — pin the ported engine to the Android fixtures (inline, GATE)

**Files:**
- Create: `desktop/backend/tests/fixtures/btcusdt_1h_300.json` (candles) + `btcusdt_1h_300_expected.json` (expected per-indicator outputs), extracted from `android/app/src/commonTest`.
- Create: `desktop/backend/tests/test_parity.py`

- [ ] **Step 1: Extract fixtures from the Android test source**

Locate the fixture candles + expected JSON under `android/app/src/commonTest` (e.g. `FixtureData.kt` embeds them, or a resource JSON exists). Convert to two JSON files under `tests/fixtures/`: the 300 BTCUSDT 1h candles `[{t,o,h,l,c,v}...]` and the expected `{indicator: {signal, score, raw_values...}}`.

- [ ] **Step 2: Parity test**

```python
import json, pathlib
import pandas as pd
from diveintocrypto_desktop.engine.loader import load_config
from diveintocrypto_desktop.engine.signal_service import SignalService

FX = pathlib.Path(__file__).parent / "fixtures"

def _df():
    candles = json.loads((FX / "btcusdt_1h_300.json").read_text())
    return pd.DataFrame([{ "open":c["o"],"high":c["h"],"low":c["l"],"close":c["c"],"volume":c["v"]} for c in candles])

def test_indicator_parity():
    expected = json.loads((FX / "btcusdt_1h_300_expected.json").read_text())
    results = {r.name: r for r in SignalService(load_config()).calculate_all(_df())}
    for name, exp in expected.items():
        got = results[name]
        assert got.signal.name == exp["signal"], f"{name}: {got.signal.name} != {exp['signal']}"
        assert got.score == exp["score"], f"{name}: score {got.score} != {exp['score']}"
        for k, v in (exp.get("raw_values") or {}).items():
            assert abs(float(got.raw_values[k]) - float(v)) <= max(1e-3, abs(float(v))*0.01), f"{name}.{k}"
```
Run: `uv run pytest tests/test_parity.py -v`.
Expected: PASS. **If any indicator diverges, fix the port (constructor params / formula) until it matches — do not relax tolerances beyond the Android test's own.** This task is the gate; M1 does not proceed past a red parity test.

- [ ] **Step 3: Commit** — `git commit -m "test(desktop/engine): parity gate pinned to Android fixtures (15/15)"`

---

### Task 5: Data layer — multi-timeframe klines via Crypcodile

**Files:**
- Create: `desktop/backend/src/diveintocrypto_desktop/data/binance_klines.py`
- Test: `desktop/backend/tests/test_klines.py`

- [ ] **Step 1: Failing test (offline, with a recorded sample)** — assert `to_dataframe(parsed)` yields columns `open,high,low,close,volume` and that `TF_LIST` has the 12 canonical timeframes `["1m","3m","5m","15m","30m","1h","2h","4h","6h","8h","12h","1d"]`.

- [ ] **Step 2: Implement** using Crypcodile's parser:
```python
import asyncio, datetime, aiohttp
from crypcodile.exchanges.binance.backfill import _live_fetch_klines, parse_klines_page

TF_LIST = ["1m","3m","5m","15m","30m","1h","2h","4h","6h","8h","12h","1d"]
FAPI = "https://fapi.binance.com/fapi/v1"

async def fetch_klines(symbol: str, interval: str, limit: int = 300) -> list[dict]:
    now_ms = int(datetime.datetime.now(datetime.UTC).timestamp()*1000)
    raw = await _live_fetch_klines(symbol=symbol, interval=interval,
            start_time_ms=None, end_time_ms=now_ms, limit=limit, rest_base=FAPI)
    local_ts = now_ms*1_000_000
    recs = parse_klines_page(raw, "binance-usdm", symbol, interval, local_ts)
    return [{"t": r.exchange_ts, "o": r.open, "h": r.high, "l": r.low, "c": r.close, "v": r.volume} for r in recs]

async def fetch_all_tf(symbol: str, limit: int = 300) -> dict[str, list[dict]]:
    res = await asyncio.gather(*[fetch_klines(symbol, tf, limit) for tf in TF_LIST])
    return dict(zip(TF_LIST, res))

def to_dataframe(candles: list[dict]):
    import pandas as pd
    return pd.DataFrame([{ "open":c["o"],"high":c["h"],"low":c["l"],"close":c["c"],"volume":c["v"]} for c in candles])
```
- [ ] **Step 3: Run tests** → PASS. **Step 4: Commit.**

---

### Task 6: Data layer — OI history, funding/mark, L/S ratios, symbol universe

**Files:**
- Create: `desktop/backend/src/diveintocrypto_desktop/data/{open_interest.py,ratios.py,universe.py,marketmeta.py}`
- Test: `desktop/backend/tests/test_data_layer.py`

- [ ] **Step 1: OI history** — reuse Crypcodile `_live_fetch_open_interest_hist` (rest_base `https://fapi.binance.com`, period in {5m,15m,30m,1h,2h,4h,6h,12h,1d}) → `[{t, oi, oi_value}]`.
- [ ] **Step 2: L/S ratio fetcher** — `aiohttp` + `aiolimiter.AsyncLimiter(40, 60)` for the four `futures/data` endpoints (`globalLongShortAccountRatio`, `topLongShortAccountRatio`, `topLongShortPositionRatio`, `takerlongshortRatio`); each returns the recent series for a symbol+period. Periods limited to the 9 Binance supports (`5m,15m,30m,1h,2h,4h,6h,12h,1d`).
- [ ] **Step 3: Funding/mark** — `premiumIndex` (`/fapi/v1/premiumIndex`) for mark/index/lastFunding + `/fapi/v1/fundingRate` for history.
- [ ] **Step 4: Universe** — `/fapi/v1/exchangeInfo` (perp + TRADING + quote USDT, minus stablecoin bases) joined with `/fapi/v1/ticker/24hr` for `priceChangePercent` + `quoteVolume`, sorted by quoteVolume desc.
- [ ] **Step 5: Tests** (network-gated marker `@pytest.mark.live`) hit BTCUSDT for each; an offline test parses recorded JSON samples. Run → PASS. **Step 6: Commit.**

---

### Task 7: Scan / divergence / per-symbol object builder

**Files:**
- Create: `desktop/backend/src/diveintocrypto_desktop/scan/{symbol_builder.py,divergence.py,scanner.py}`
- Test: `desktop/backend/tests/test_scan.py`

- [ ] **Step 1: `symbol_builder.build(symbol, data) -> dict`** — given the fetched 12-TF candles + OI/ratios/funding, produce the **data-contract** per-symbol object (spec §8): run `SignalService`+`ConsensusEngine` per TF → `multiTf[{tf,signal,confidence}]` + `buy/sell/neutral`; run the engine on the primary TF → `indicators[15]`, `finalSignal`, `confidence`, `action` (AL/SAT/BEKLE), `reason`, `risk`; assemble `series` (oi, glob, acc, pos, taker, funding, price, bias) from the ratio/OI/funding fetches aligned to a 48-point window; `candles` = primary-TF series.
- [ ] **Step 2: `divergence`** — port the whale long/short-vs-price divergence per the Android formula (`magnitude = 0.80·whaleStrength + 0.20·priceStrength`, TF-weighted, empirically contrarian sign; coverage = 9/12 TFs). Reference: `android/app/src/commonMain` divergence + `WhaleDivergenceTest.kt`/`DivergenceAlignmentTest.kt` (port the exact math, validate against those tests' vectors). Output `{score, tf, coverage, adverse}` → fills `quantBias`, `whaleRegime`, `divergence`.
- [ ] **Step 3: `scanner.scan(universe, size)`** — two-phase (coarse `1d/12h/8h` over universe, fine lower-TFs for survivors), rank by `netNss + 0.35·divergenceScore`, eliminate adverse + backfill (matches Android README). Returns `{survivors[], eliminated[], universeCount, scanned}`.
- [ ] **Step 4: Tests** — synthetic universe → ranking deterministic; divergence math matches the Android vectors. Run → PASS. **Step 5: Commit.**

---

### Task 8: FastAPI service

**Files:**
- Create: `desktop/backend/src/diveintocrypto_desktop/api/app.py`, `desktop/backend/src/diveintocrypto_desktop/__main__.py`
- Test: `desktop/backend/tests/test_api.py`

- [ ] **Step 1: FastAPI app** — endpoints per spec §7: `GET /api/universe`, `/api/scan`, `/api/symbol/{s}`, `/api/leaders`, `/api/logs`, `/api/health`, `WS /api/live`; mount `ui/dist` at `/` (StaticFiles, only if it exists). Localhost bind; CORS restricted to same origin; a small in-memory request-log ring buffer feeds `/api/logs` (real activity). Cache scan results between cycles to respect rate limits.
- [ ] **Step 2: `__main__.main()`** — parse `--port`/`--host`/`--no-open`; start uvicorn; `webbrowser.open` the URL unless `--no-open`.
- [ ] **Step 3: Tests** with FastAPI `TestClient`: `/api/health` 200; `/api/symbol/BTCUSDT` returns a data-contract object (live marker) or a mocked-data-layer object (offline). Run → PASS. **Step 4: Commit.**

---

### Task 9: End-to-end live smoke (network-gated)

- [ ] **Step 1:** `uv run dive-desktop --no-open &` then `curl -s localhost:<port>/api/health` → ok; `curl -s 'localhost:<port>/api/scan?size=5'` → 5 ranked real survivors with real verdicts; `curl -s localhost:<port>/api/symbol/BTCUSDT` → real candles + 15 indicators + finalSignal. Record the output in the commit message. Kill the server.
- [ ] **Step 2: Commit** — `git commit -m "feat(desktop/api): live end-to-end scan over Binance USDT-M via Crypcodile"`

---

## Self-Review

- **Spec coverage:** §5 engine→T2–T4; §6 data→T5–T6; §7 API→T8; §8 contract→T7; parity→T4; honesty (real data)→T5–T9. ✓
- **Placeholder scan:** copy/adapt steps name exact source+dest; new code shown; the only "read the reference and replicate" steps (config mapping in T3, divergence math in T7) point to exact files to port from — acceptable since the code is being *ported*, not invented. ✓
- **Type consistency:** `IndicatorResult` (name/signal/score/reason/raw_values), `ConsensusEngine.evaluate -> dict(final_signal,...)`, `SignalService.calculate_all -> list[IndicatorResult]`, data-contract object shape consistent across T7/T8. ✓
- **Parity gate** (T4) blocks progress on any indicator mismatch. ✓
