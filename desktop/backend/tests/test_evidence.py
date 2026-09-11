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
    sig_a = {"rsi": 1, "macd": 1, "adx_di": -1}
    sig_b = {"rsi": 1}  # LONG-leaning indicator on a SHORT verdict → disagree
    evidence.append_lines(evidence.archive_path(), [
        _rec("AUSDT", TS, verdict="BUY", conf=80, price=100.0, dir=1, signals=sig_a),
        _rec("BUSDT", TS, verdict="SELL", conf=30, price=50.0, dir=-1, signals=sig_b),
        _rec("CUSDT", TS, verdict="NEUTRAL", conf=10, price=2.0, dir=0, signals={}),
    ])
    grades = [
        {"ts": TS, "symbol": "AUSDT", "horizon": "4h", "hit": True, "forward": 0.02},
        {"ts": TS, "symbol": "BUSDT", "horizon": "4h", "hit": True, "forward": 0.01},
        {"ts": TS, "symbol": "CUSDT", "horizon": "4h", "hit": False, "forward": -0.02},
    ]
    evidence.append_lines(evidence.grades_path(), grades)
    s = evidence.summary("4h", now_ms=NOW)
    assert s["archived_count"] == 3 and s["graded_count"] == 3 and s["gradable_count"] == 3
    assert s["coverage"] == 1.0 and s["stale"] is False
    assert s["by_verdict"]["LONG"]["n"] == 1 and s["by_verdict"]["LONG"]["hit_rate"] == 1.0
    assert s["by_verdict"]["LONG"]["avg_forward"] == pytest.approx(0.02)
    assert s["by_verdict"]["SHORT"]["n"] == 1
    assert s["by_verdict"]["NEUTRAL"]["n"] == 1 and s["by_verdict"]["NEUTRAL"]["hit_rate"] == 0.0
    buckets = {b["bucket"]: b for b in s["by_confidence"]}
    assert buckets["75-100"]["n"] == 1 and buckets["25-50"]["n"] == 1 and buckets["0-25"]["n"] == 1
    assert buckets["50-75"]["n"] == 0
    # per-indicator association (report-only)
    ind = s["by_indicator"]
    assert ind["rsi"]["agree"]["n"] == 1 and ind["rsi"]["agree"]["hit_rate"] == 1.0
    assert ind["rsi"]["disagree"]["n"] == 1
    assert ind["macd"]["agree"]["n"] == 1
    assert ind["adx_di"]["disagree"]["n"] == 1


def test_summary_stale_when_mature_records_ungraded():
    evidence.append_lines(evidence.archive_path(), [_rec("AUSDT", TS, price=100.0)])
    s = evidence.summary("4h", now_ms=NOW)
    assert s["stale"] is True and s["graded_count"] == 0 and s["coverage"] == 0.0
    assert s["by_verdict"]["LONG"]["n"] == 0 and s["by_verdict"]["LONG"]["hit_rate"] is None


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
    assert {"n", "hit_rate", "avg_forward", "median_forward"} <= set(body["by_verdict"]["LONG"])
    assert [b["bucket"] for b in body["by_confidence"]] == ["0-25", "25-50", "50-75", "75-100"]
    assert body["generated_at"].endswith("Z")

    g = client.post("/api/evidence/grade?horizon=4h")
    assert g.status_code == 200
    gb = g.json()
    assert gb["graded"] == 1 and gb["remaining"] == 0
    assert gb["summary"]["stale"] is False

    e2 = client.get("/api/evidence")
    assert e2.json()["graded_count"] == 1

    bad = client.get("/api/evidence?horizon=3h")
    assert bad.status_code == 422
