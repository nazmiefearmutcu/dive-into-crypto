"""Evidence layer — archive append, grader hit-rate math on synthetic candles,
resumable backfill cap, aggregations, failure tolerance. Fully offline.
"""

import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from diveintocrypto_desktop.api.app import create_app
from diveintocrypto_desktop.scan import evidence


H = evidence.HORIZONS["4h"]
HOUR = 3_600_000


@pytest.fixture(autouse=True)
def _tmp_evidence(monkeypatch, tmp_path):
    monkeypatch.setenv("DIVE_EVIDENCE_PATH", str(tmp_path / "evidence.jsonl"))
    monkeypatch.setenv("DIVE_EVIDENCE_GRADES_PATH", str(tmp_path / "grades.jsonl"))
    yield


def _candles(start_ms: int, rows: list[tuple[float, float, float]]) -> list[dict]:
    """``[(high, low, close)]`` hourly candles starting at ``start_ms``."""
    return [{"t": start_ms + i * HOUR, "h": h, "l": lo, "c": c} for i, (h, lo, c) in enumerate(rows)]


def _rec(symbol: str, ts: int, verdict: str = "BUY", conf: int = 80, price: float = 100.0,
         dir: int | None = None, signals: dict | None = None) -> dict:
    d = dir if dir is not None else (1 if "BUY" in verdict else -1 if "SELL" in verdict else 0)
    return {
        "ts": ts, "symbol": symbol, "verdict": verdict, "confidence": conf, "risk": "LOW",
        "net_score": 12.0 * d, "dominant_dir": d, "price": price, "tf_agreement": 0.75,
        "divergence_state": "neutral", "engine_version": evidence.ENGINE_VERSION,
        "signals": signals or {},
    }


# ── record building + archive tolerance ───────────────────────────────────────
def test_record_from_row_shape_and_agreement():
    row = {
        "s": "BTCUSDT", "finalSignal": "STRONG_BUY", "confidence": 77, "risk": "MEDIUM",
        "netNss": 5000.0, "dominantDir": 1, "price": 68000.0, "whaleRegime": "confirm",
        "multiTf": [
            {"tf": "1d", "signal": "STRONG_BUY", "confidence": 80},
            {"tf": "4h", "signal": "BUY", "confidence": 60},
            {"tf": "1h", "signal": "SELL", "confidence": 50},
            {"tf": "5m", "signal": "NEUTRAL", "confidence": 0},
        ],
        "indicators": [{"name": "rsi", "signal": "BUY"}, {"name": "macd", "signal": "STRONG_SELL"}],
    }
    rec = evidence.record_from_row(row, ts_ms=1_700_000_000_000)
    assert rec["symbol"] == "BTCUSDT" and rec["verdict"] == "STRONG_BUY"
    assert rec["confidence"] == 77 and rec["risk"] == "MEDIUM"
    assert rec["dominant_dir"] == 1 and rec["net_score"] == 5000.0
    assert rec["tf_agreement"] == 0.5  # 2 of 4 TFs lean buy
    assert rec["divergence_state"] == "confirm"
    assert rec["signals"] == {"rsi": 1, "macd": -2}
    assert rec["engine_version"] == evidence.ENGINE_VERSION
    assert rec["price"] == 68000.0


def test_archive_append_and_tolerant_read(tmp_path):
    p = tmp_path / "evidence.jsonl"
    assert evidence.append_lines(p, [_rec("AUSDT", 1), _rec("BUSDT", 2)]) == 2
    # corrupt + blank lines must be skipped, never fatal
    with open(p, "a", encoding="utf-8") as f:
        f.write("{not json at all\n")
        f.write("\n")
        f.write('{"symbol": "CUSDT", "ts": 3}\n')  # valid minimal record
    recs = evidence.iter_archive(p)
    assert [r["symbol"] for r in recs] == ["AUSDT", "BUSDT", "CUSDT"]


def test_iter_archive_missing_file_is_empty(monkeypatch, tmp_path):
    monkeypatch.setenv("DIVE_EVIDENCE_PATH", str(tmp_path / "does_not_exist.jsonl"))
    assert evidence.iter_archive() == []


def test_archive_scan_writes_all_rows(tmp_path):
    res = {
        "survivors": [{"s": "AUSDT", "finalSignal": "BUY", "confidence": 60, "risk": "LOW",
                       "netNss": 10.0, "dominantDir": 1, "price": 5.0, "whaleRegime": "neutral",
                       "multiTf": [], "indicators": []}],
        "eliminated": [{"s": "BUSDT", "finalSignal": "SELL", "confidence": 55, "risk": "HIGH",
                        "netNss": 8.0, "dominantDir": -1, "price": 7.0, "whaleRegime": "adverse",
                        "multiTf": [], "indicators": []}],
    }
    n = evidence.archive_scan(res)
    assert n == 2
    recs = evidence.iter_archive()
    assert {r["symbol"] for r in recs} == {"AUSDT", "BUSDT"}


# ── grader math on synthetic candles ──────────────────────────────────────────
NOW = 100 * H  # a fixed "now": verdicts at NOW - 10h are mature for the 4h horizon
TS = NOW - 10 * HOUR


def test_grade_long_hit():
    rec = _rec("AUSDT", TS, verdict="BUY", dir=1, price=100.0)
    candles = _candles(TS, [(100.5, 99.5, 100.2), (103.0, 100.0, 102.0), (102.5, 101.0, 101.5)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g is not None and g["hit"] is True
    assert g["dir"] == 1 and g["entry"] == 100.0
    assert g["exit"] == 101.5  # horizon-end candle close
    assert g["forward"] == pytest.approx(0.015)


def test_grade_long_miss_on_opposite_move():
    rec = _rec("AUSDT", TS, verdict="BUY", dir=1, price=100.0)
    candles = _candles(TS, [(100.4, 95.0, 96.0), (97.0, 94.0, 95.0), (96.0, 95.0, 95.5)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g["hit"] is False
    assert g["forward"] == pytest.approx(-0.045)  # dir-signed horizon-end return


def test_grade_long_undecided_counts_as_miss():
    rec = _rec("AUSDT", TS, verdict="BUY", dir=1, price=100.0)
    candles = _candles(TS, [(100.5, 99.5, 100.3), (100.6, 99.6, 100.4), (100.5, 99.5, 100.0)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g["hit"] is False  # never reached ±1% before horizon end


def test_grade_both_thresholds_one_candle_conservative_miss():
    rec = _rec("AUSDT", TS, verdict="BUY", dir=1, price=100.0)
    candles = _candles(TS, [(103.0, 95.0, 96.0), (96.5, 95.5, 96.2)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g["hit"] is False  # order unknowable from OHLC — never claim a hit


def test_grade_short_hit():
    rec = _rec("AUSDT", TS, verdict="SELL", dir=-1, price=100.0)
    candles = _candles(TS, [(100.5, 96.0, 97.0), (98.0, 95.0, 96.0), (97.0, 96.0, 96.5)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g["hit"] is True
    assert g["forward"] == pytest.approx(0.035)  # exit 96.5 → dir-signed: (96.5-100)·-1/100


def test_grade_neutral_flat_is_correct():
    rec = _rec("AUSDT", TS, verdict="NEUTRAL", dir=0, price=100.0)
    candles = _candles(TS, [(100.5, 99.5, 100.2), (100.6, 99.6, 100.3), (100.4, 99.4, 100.3)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g["hit"] is True  # no decisive move — the NEUTRAL call was correct
    assert g["forward"] == pytest.approx(0.003)


def test_grade_neutral_decisive_move_is_wrong():
    rec = _rec("AUSDT", TS, verdict="NEUTRAL", dir=0, price=100.0)
    candles = _candles(TS, [(101.0, 99.5, 100.8), (103.0, 100.5, 102.5), (103.5, 102.0, 103.0)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g["hit"] is False
    assert g["forward"] == pytest.approx(0.03)  # raw (unsigned) for NEUTRAL


def test_grade_without_candles_is_ungradeable():
    rec = _rec("AUSDT", TS, price=100.0)
    assert evidence._grade_one(rec, [], "4h", NOW) is None
    rec_zero = _rec("AUSDT", TS, price=0.0)
    candles = _candles(TS, [(101.0, 99.0, 100.5)])
    assert evidence._grade_one(rec_zero, candles, "4h", NOW) is None


# ── resumable backfill cap ─────────────────────────────────────────────────────
def _mk_fetcher(closes_by_symbol: dict[str, list[tuple[float, float, float]]], start: int):
    calls: list[str] = []

    async def fetcher(symbol: str, start_ms: int, end_ms: int) -> list[dict]:
        calls.append(symbol)
        return _candles(start, closes_by_symbol[symbol])

    fetcher.calls = calls
    return fetcher


@pytest.mark.asyncio
async def test_grade_resumes_across_calls_with_cap():
    evidence.append_lines(evidence.archive_path(), [
        _rec("AUSDT", TS, price=100.0),
        _rec("BUSDT", TS + HOUR, price=100.0),
    ])
    fetcher = _mk_fetcher(
        {"AUSDT": [(100.5, 99.5, 100.2), (103.0, 100.0, 102.0), (102.5, 101.0, 102.0)],
         "BUSDT": [(100.5, 95.0, 96.0), (96.0, 95.0, 95.5), (96.0, 95.0, 95.5)]},
        start=TS,
    )
    out1 = await evidence.grade("4h", cap=1, fetcher=fetcher, now_ms=NOW)
    assert out1["graded"] == 1 and out1["remaining"] == 1
    assert fetcher.calls == ["AUSDT"]  # oldest verdict first

    out2 = await evidence.grade("4h", cap=1, fetcher=fetcher, now_ms=NOW)
    assert out2["graded"] == 1 and out2["remaining"] == 0

    out3 = await evidence.grade("4h", cap=1, fetcher=fetcher, now_ms=NOW)
    assert out3["graded"] == 0 and out3["remaining"] == 0  # resumable + idempotent


@pytest.mark.asyncio
async def test_grade_records_failed_symbols_honestly():
    evidence.append_lines(evidence.archive_path(), [_rec("DEADUSDT", TS, price=100.0)])

    async def boom(symbol, start_ms, end_ms):
        raise RuntimeError("binance 451 unreachable")

    out = await evidence.grade("4h", cap=10, fetcher=boom, now_ms=NOW)
    assert out["graded"] == 0 and out["remaining"] == 1
    assert out["failed"] == [{"symbol": "DEADUSDT", "reason": "binance 451 unreachable"}]


@pytest.mark.asyncio
async def test_grade_skips_immature_records():
    evidence.append_lines(evidence.archive_path(), [_rec("NEWUSDT", NOW - HOUR, price=100.0)])

    async def fetcher(symbol, start_ms, end_ms):
        raise AssertionError("immature records must not trigger fetches")

    out = await evidence.grade("4h", cap=10, fetcher=fetcher, now_ms=NOW)
    assert out["graded"] == 0 and out["remaining"] == 0 and out["gradable"] == 0


# ── summary aggregation ────────────────────────────────────────────────────────
def test_summary_aggregates_by_verdict_confidence_indicator():
    # v0.3 stats gates: n<5 reports {n} only, so each bucket below gets exactly
    # 5 graded events to exercise the real aggregation math (gated: true).
    sig_a = {"rsi": 1, "macd": 1, "adx_di": -1}
    sig_b = {"rsi": 1}  # LONG-leaning indicator on a SHORT verdict → disagree
    recs = [
        _rec("A1", TS - 0 * HOUR, verdict="BUY", conf=80, price=100.0, dir=1, signals=sig_a),
        _rec("A2", TS - 1 * HOUR, verdict="BUY", conf=80, price=100.0, dir=1, signals=sig_a),
        _rec("A3", TS - 2 * HOUR, verdict="BUY", conf=80, price=100.0, dir=1, signals=sig_a),
        _rec("A4", TS - 3 * HOUR, verdict="BUY", conf=80, price=100.0, dir=1, signals=sig_a),
        _rec("A5", TS - 4 * HOUR, verdict="BUY", conf=80, price=100.0, dir=1, signals=sig_a),
        _rec("B1", TS - 0 * HOUR, verdict="SELL", conf=30, price=50.0, dir=-1, signals=sig_b),
        _rec("B2", TS - 1 * HOUR, verdict="SELL", conf=30, price=50.0, dir=-1, signals=sig_b),
        _rec("B3", TS - 2 * HOUR, verdict="SELL", conf=30, price=50.0, dir=-1, signals=sig_b),
        _rec("B4", TS - 3 * HOUR, verdict="SELL", conf=30, price=50.0, dir=-1, signals=sig_b),
        _rec("B5", TS - 4 * HOUR, verdict="SELL", conf=30, price=50.0, dir=-1, signals=sig_b),
        _rec("C1", TS - 0 * HOUR, verdict="NEUTRAL", conf=10, price=2.0, dir=0, signals={}),
        _rec("C2", TS - 1 * HOUR, verdict="NEUTRAL", conf=10, price=2.0, dir=0, signals={}),
        _rec("C3", TS - 2 * HOUR, verdict="NEUTRAL", conf=10, price=2.0, dir=0, signals={}),
        _rec("C4", TS - 3 * HOUR, verdict="NEUTRAL", conf=10, price=2.0, dir=0, signals={}),
        _rec("C5", TS - 4 * HOUR, verdict="NEUTRAL", conf=10, price=2.0, dir=0, signals={}),
    ]
    evidence.append_lines(evidence.archive_path(), recs)
    # LONG all hit, SHORT all hit, NEUTRAL none hit
    grades = (
        [{"ts": r["ts"], "symbol": r["symbol"], "horizon": "4h", "hit": True, "forward": 0.02}
         for r in recs[:5]]
        + [{"ts": r["ts"], "symbol": r["symbol"], "horizon": "4h", "hit": True, "forward": 0.01}
           for r in recs[5:10]]
        + [{"ts": r["ts"], "symbol": r["symbol"], "horizon": "4h", "hit": False, "forward": -0.02}
           for r in recs[10:]]
    )
    evidence.append_lines(evidence.grades_path(), grades)
    s = evidence.summary("4h", now_ms=NOW)
    assert s["archived_count"] == 15 and s["graded_count"] == 15 and s["gradable_count"] == 15
    assert s["coverage"] == 1.0 and s["stale"] is False
    # n=5 → values present but gated (below GATE_FLAG_N=20)
    lv = s["by_verdict"]["LONG"]
    assert lv["n"] == 5 and lv["hit_rate"] == 1.0 and lv["gated"] is True
    assert lv["avg_forward"] == pytest.approx(0.02)
    assert 0.0 <= lv["wilson_lo"] <= lv["wilson_hi"] <= 1.0
    sv = s["by_verdict"]["SHORT"]
    assert sv["n"] == 5 and sv["hit_rate"] == 1.0
    nv = s["by_verdict"]["NEUTRAL"]
    assert nv["n"] == 5 and nv["hit_rate"] == 0.0
    buckets = {b["bucket"]: b for b in s["by_confidence"]}
    assert buckets["75-100"]["n"] == 5 and buckets["25-50"]["n"] == 5 and buckets["0-25"]["n"] == 5
    assert buckets["50-75"]["n"] == 0 and set(buckets["50-75"]) == {"bucket", "n"}  # gated
    # per-indicator association (report-only): rsi agrees 5× (A) + disagrees 5× (B)
    ind = s["by_indicator"]
    assert ind["rsi"]["agree"]["n"] == 5 and ind["rsi"]["agree"]["hit_rate"] == 1.0
    assert ind["rsi"]["disagree"]["n"] == 5 and ind["rsi"]["disagree"]["hit_rate"] == 1.0
    assert ind["macd"]["agree"]["n"] == 5
    assert ind["adx_di"]["disagree"]["n"] == 5
    # v2 blocks present
    assert s["calibration"]["bins"] and len(s["calibration"]["bins"]) == 4
    assert s["brier"]["n"] == 15
    assert set(s["windows"]) == {"7d", "30d", "all"}
    assert s["windows"]["all"]["LONG"]["n"] == 5
    for sl in ("by_regime", "by_session", "by_funding_proximity", "by_divergence_tier"):
        assert s[sl]["note"].startswith("multiple comparisons")
    assert set(s["by_session"]["buckets"]) <= {"0-8", "8-16", "16-24", "unclassified"}
    assert s["provenance"]["graded_count"] == 15 and s["provenance"]["failed_grades"] == 0


def test_summary_stale_when_mature_records_ungraded():
    evidence.append_lines(evidence.archive_path(), [_rec("AUSDT", TS, price=100.0)])
    s = evidence.summary("4h", now_ms=NOW)
    assert s["stale"] is True and s["graded_count"] == 0 and s["coverage"] == 0.0
    # gate: n=0 → {n} only (never a fabricated 0% hit-rate)
    assert s["by_verdict"]["LONG"] == {"n": 0}


# ── stats gates + Wilson interval (v0.3 contract) ─────────────────────────────
def test_wilson_interval_known_values():
    # Wilson 95% on 7/10 ≈ [0.397, 0.892] (standard reference value)
    lo, hi = evidence.wilson_interval(7, 10)
    assert lo == pytest.approx(0.3968, abs=5e-4) and hi == pytest.approx(0.8922, abs=5e-4)
    lo0, hi0 = evidence.wilson_interval(0, 5)
    assert lo0 == 0.0 and 0 < hi0 < 0.6  # honest non-zero upper bound at p̂=0
    assert evidence.wilson_interval(1, 0) == (None, None)


def test_stats_gates_suppress_gated_and_full():
    def g(hit):
        return {"hit": hit, "forward": 0.01}

    assert evidence._stats([g(True)] * 4) == {"n": 4}  # n<5 → {n} only
    nine = evidence._stats([g(True)] * 6 + [g(False)] * 3)
    assert nine["n"] == 9 and nine["hit_rate"] == pytest.approx(0.6667)  # 4 dp rounding
    assert nine["gated"] is True and "wilson_lo" in nine  # n<20 → flagged
    fifty = evidence._stats([g(True)] * 30 + [g(False)] * 20)
    assert fifty["n"] == 50 and fifty["gated"] is False
    assert fifty["hit_rate"] == 0.6 and fifty["wilson_lo"] < 0.6 < fifty["wilson_hi"]


# ── API surface ────────────────────────────────────────────────────────────────
def test_evidence_api_endpoints(monkeypatch):
    evidence.append_lines(evidence.archive_path(), [
        _rec("AUSDT", TS, verdict="BUY", price=100.0, dir=1, signals={"rsi": 1}),
    ])

    async def fetcher(symbol, start_ms, end_ms):
        return _candles(TS, [(100.5, 99.5, 100.2), (103.0, 100.0, 102.0), (103.0, 102.0, 102.5)])

    monkeypatch.setattr(evidence, "_default_fetcher", fetcher)
    client = TestClient(create_app())
    r = client.get("/api/evidence")
    assert r.status_code == 200
    body = r.json()
    assert body["archived_count"] == 1
    assert body["stale"] is True
    # gate: 1 record, no grades → LONG bucket is {n: 0}
    assert body["by_verdict"]["LONG"] == {"n": 0}
    assert [b["bucket"] for b in body["by_confidence"]] == ["0-25", "25-50", "50-75", "75-100"]
    assert body["generated_at"].endswith("Z")
    assert body["engine_version"] == evidence.ENGINE_VERSION

    g = client.post("/api/evidence/grade?horizon=4h")
    assert g.status_code == 200
    gb = g.json()
    assert gb["graded"] == 1 and gb["remaining"] == 0
    assert gb["summary"]["stale"] is False

    e2 = client.get("/api/evidence")
    assert e2.json()["graded_count"] == 1

    bad = client.get("/api/evidence?horizon=3h")
    assert bad.status_code == 422


def test_grade_stores_v2_event_fields():
    rec = _rec("AUSDT", TS, verdict="BUY", dir=1, price=100.0)
    candles = _candles(TS, [(100.5, 99.5, 100.2), (103.0, 100.0, 102.0), (102.5, 101.0, 101.5)])
    g = evidence._grade_one(rec, candles, "4h", NOW)
    assert g["hit_close"] is True            # close-to-close +1.5% ≥ 1%
    assert g["mfe"] == pytest.approx(0.03)   # max favorable excursion
    assert g["mae"] == pytest.approx(0.005)  # max adverse excursion
    assert g["bnh_forward"] == pytest.approx(0.015)  # unsigned buy-and-hold
    assert g["momentum_hit"] is None         # no pre-window → honest null


def test_summary_calibration_brier_baselines():
    recs = [_rec(f"S{i}", TS - i * HOUR, verdict="BUY", conf=80, price=100.0, dir=1, signals={})
            for i in range(6)]
    grades = [{"ts": r["ts"], "symbol": r["symbol"], "horizon": "4h",
               "hit": i % 2 == 0, "forward": 0.01 if i % 2 == 0 else -0.02}
              for i, r in enumerate(recs)]
    evidence.append_lines(evidence.archive_path(), recs)
    evidence.append_lines(evidence.grades_path(), grades)
    s = evidence.summary("4h", now_ms=NOW)

    cal = s["calibration"]
    b75 = next(b for b in cal["bins"] if b["bucket"] == "75-100")
    assert b75["n"] == 6 and b75["mean_conf"] == 80.0
    assert b75["hit_rate"] == pytest.approx(0.5)
    # ECE = |0.5 - 0.8| (one populated bin, full weight)
    assert cal["ece"] == pytest.approx(0.3, abs=1e-3)

    br = s["brier"]
    assert br["n"] == 6
    # conf 0.8, alternating hit/miss → mean((0.2)², (0.8)²) = 0.34
    assert br["score"] == pytest.approx(0.34, abs=1e-3)
    assert br["ref"] > 0 and -1.0 <= br["skill"] <= 1.0

    base = s["baselines"]
    assert base["coin_mean"] == 0.0
    assert base["coin_sd"] > 0
    assert 0.0 < base["p_value"] <= 1.0  # add-one floor: never a fake 0.0
    assert base["seed"] == 42 and base["permutations"] == 1000


def test_baselines_p_value_add_one_floor():
    import random

    # one graded forward: the seeded sign-flip null is fully replicable, so the
    # exact ge is known and the p-value must follow (1+ge)/(K+1) — not ge/K
    rec = _rec("PF", TS, verdict="BUY", dir=1, price=100.0)
    grade = {"ts": TS, "symbol": "PF", "horizon": "4h", "hit": True, "forward": 0.02}
    base = evidence.baselines([(rec, grade)])
    rng = random.Random(evidence.PERMUTATION_SEED)
    ge = sum(1 for _ in range(evidence.PERMUTATION_K) if rng.random() < 0.5)
    assert base["p_value"] == round((1 + ge) / (evidence.PERMUTATION_K + 1), 4)
    assert base["p_value"] >= 1.0 / (evidence.PERMUTATION_K + 1)


def test_calibration_gating_and_zero_sample_ece():
    def pair(conf: int, hit: bool):
        r = _rec("CUSDT", TS, verdict="BUY", conf=conf, price=100.0, dir=1)
        return (r, {"ts": r["ts"], "symbol": "CUSDT", "horizon": "4h", "hit": hit,
                    "forward": 0.01})

    # zero graded samples → ece is None (never a fabricated 0.0)
    empty = evidence.calibration([])
    assert empty["ece"] is None
    assert len(empty["bins"]) == 4 and all(b["n"] == 0 for b in empty["bins"])

    # one populated bin with n=3 (< GATE_SUPPRESS_N): gated:true, feeds NO ece
    small = evidence.calibration([pair(80, True), pair(80, False), pair(80, True)])
    assert small["ece"] is None  # no bin large enough → honest None, not 0.0
    b = next(x for x in small["bins"] if x["bucket"] == "75-100")
    assert b["n"] == 3 and b["gated"] is True and b["hit_rate"] == pytest.approx(0.6667)

    # n=5 bin: feeds the ECE and renders gated:false
    fed = evidence.calibration([pair(80, True)] * 5)
    b5 = next(x for x in fed["bins"] if x["bucket"] == "75-100")
    assert b5["n"] == 5 and b5["gated"] is False
    assert fed["ece"] == pytest.approx(abs(1.0 - 0.8), abs=1e-3)


def test_summary_slices_classify_unclassified():
    # one v2 record with regime/tier, one legacy record without them
    r_new = _rec("NEW1", TS, verdict="BUY", conf=60, price=100.0, dir=1)
    r_new.update({"regime": "TREND", "divergence_tier": "STRONG"})
    r_old = _rec("OLD1", TS - HOUR, verdict="BUY", conf=60, price=100.0, dir=1)  # no v2 fields
    grades = [
        {"ts": r_new["ts"], "symbol": "NEW1", "horizon": "4h", "hit": True, "forward": 0.02},
        {"ts": r_old["ts"], "symbol": "OLD1", "horizon": "4h", "hit": True, "forward": 0.02},
    ]
    evidence.append_lines(evidence.archive_path(), [r_new, r_old])
    evidence.append_lines(evidence.grades_path(), grades)
    s = evidence.summary("4h", now_ms=NOW)
    reg_buckets = s["by_regime"]["buckets"]
    assert reg_buckets["TREND"]["n"] == 1 and reg_buckets["unclassified"]["n"] == 1
    tier_buckets = s["by_divergence_tier"]["buckets"]
    assert tier_buckets["STRONG"]["n"] == 1 and tier_buckets["unclassified"]["n"] == 1


def test_stability_endpoint_math():
    base_ts = NOW - 48 * HOUR
    rows = []
    for i in range(8):
        v = "BUY" if i % 4 != 3 else "SELL"  # 6 BUY / 2 SELL
        rows.append(_rec("AGREE", base_ts + i * HOUR, verdict=v, price=100.0))
    evidence.append_lines(evidence.archive_path(), rows)
    out = evidence.stability(limit_per_symbol=8)
    row = next(r for r in out if r["s"] == "AGREE")
    assert row["k"] == 8
    assert row["agree_frac"] == pytest.approx(0.75)  # 6/8 on the BUY side
    assert row["median_gap_min"] == 60.0
    assert row["last_ts"] == rows[-1]["ts"]


def test_stability_measures_directional_rows_only():
    # 3 BUY + 1 NEUTRAL used to read 0.75 (the NEUTRAL diluted the denominator);
    # agreement now runs over DIRECTIONAL rows only: 3/3 on the BUY side, k=3
    rows = [
        _rec("MIX", NOW - 4 * HOUR, verdict="BUY", price=100.0),
        _rec("MIX", NOW - 3 * HOUR, verdict="BUY", price=100.0),
        _rec("MIX", NOW - 2 * HOUR, verdict="NEUTRAL", price=100.0),
        _rec("MIX", NOW - HOUR, verdict="BUY", price=100.0),
    ]
    evidence.append_lines(evidence.archive_path(), rows)
    row = next(r for r in evidence.stability(limit_per_symbol=8) if r["s"] == "MIX")
    assert row["k"] == 3
    assert row["agree_frac"] == pytest.approx(1.0)

    # all-neutral tail: nothing directional to agree with → honest null, k=0
    neutral_rows = [_rec("FLAT", NOW - (i + 1) * HOUR, verdict="NEUTRAL", price=100.0)
                    for i in range(3)]
    evidence.append_lines(evidence.archive_path(), neutral_rows)
    flat = next(r for r in evidence.stability(limit_per_symbol=8) if r["s"] == "FLAT")
    assert flat["k"] == 0 and flat["agree_frac"] is None
