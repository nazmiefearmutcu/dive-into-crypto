"""Claims registry lifecycle — validation, immutability, and the
post-registration-only evaluation rule. Fully offline (tmp dirs)."""

from pathlib import Path

import pytest

from diveintocrypto_desktop.scan import claims as cl
from diveintocrypto_desktop.scan import evidence as ev


HOUR = 3_600_000
REG_MS = 1_700_000_000_000  # registration instant (ms)


@pytest.fixture()
def claims_env(monkeypatch, tmp_path):
    monkeypatch.setenv("DIVE_CLAIMS_DIR", str(tmp_path / "claims"))
    monkeypatch.setenv("DIVE_CLAIMS_STATUS_PATH", str(tmp_path / "status.json"))
    monkeypatch.setenv("DIVE_EVIDENCE_PATH", str(tmp_path / "evidence.jsonl"))
    monkeypatch.setenv("DIVE_EVIDENCE_GRADES_PATH", str(tmp_path / "grades.jsonl"))
    yield tmp_path


def _claim(claim_id: str = "CL-TEST-1", threshold: float = 0.5, op: str = ">=") -> dict:
    import time

    return {
        "claim_id": claim_id,
        "registered_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(REG_MS / 1000)),
        "engine_version": "desktop-0.3.0",
        "claim": "LONG verdicts hit more often than a coin at the 4h horizon.",
        "metric": "hit_rate",
        "filter": {"verdict": "LONG"},
        "horizon": "4h",
        "min_n": 2,
        "threshold": {"op": op, "value": threshold},
    }


def _record(symbol: str, ts: int) -> dict:
    return {
        "ts": ts, "symbol": symbol, "verdict": "BUY", "confidence": 60, "risk": "LOW",
        "net_score": 5.0, "dominant_dir": 1, "price": 100.0, "tf_agreement": 0.5,
        "divergence_state": "neutral", "engine_version": ev.ENGINE_VERSION, "signals": {},
    }


def _grade(symbol: str, ts: int, hit: bool) -> dict:
    return {"ts": ts, "symbol": symbol, "horizon": "4h", "hit": hit, "forward": 0.02 if hit else -0.02}


def test_register_validates_schema(claims_env):
    with pytest.raises(cl.ClaimError):
        cl.register({"claim_id": "X"})  # missing fields
    bad = _claim()
    bad["metric"] = "sharpe"
    with pytest.raises(cl.ClaimError):
        cl.register(bad)
    bad2 = _claim()
    bad2["horizon"] = "3h"
    with pytest.raises(cl.ClaimError):
        cl.register(bad2)
    bad3 = _claim(claim_id="bad id!")
    with pytest.raises(cl.ClaimError):
        cl.register(bad3)
    bad4 = _claim()
    bad4["filter"] = {"nonsense": 1}
    with pytest.raises(cl.ClaimError):
        cl.register(bad4)
    bad5 = _claim()
    bad5["threshold"] = {"op": "==", "value": 1}
    with pytest.raises(cl.ClaimError):
        cl.register(bad5)


def test_register_writes_yaml_and_is_immutable(claims_env):
    path = cl.register(_claim())
    assert path.exists() and path.parent == Path(claims_env) / "claims"
    with pytest.raises(cl.ClaimExistsError):
        cl.register(_claim())  # same id → refuses (registry immutable by convention)


def test_lifecycle_pending_then_confirmed_post_registration_only(claims_env):
    cl.register(_claim())
    claim = cl.load_claims()[0]

    # pre-registration grades exist but must NEVER count
    ev.append_lines(ev.archive_path(), [_record("OLD", REG_MS - HOUR)])
    ev.append_lines(ev.grades_path(), [_grade("OLD", REG_MS - HOUR, hit=True)])
    out = cl.evaluate(claim)
    assert out["status"] == "PENDING" and out["n"] == 0

    # one post-registration hit → n=1 < min_n=2 → still PENDING
    ev.append_lines(ev.archive_path(), [_record("A", REG_MS + HOUR)])
    ev.append_lines(ev.grades_path(), [_grade("A", REG_MS + HOUR, hit=True)])
    out = cl.evaluate(claim)
    assert out["status"] == "PENDING" and out["n"] == 1

    # second post-registration hit → CONFIRMED (2/2 ≥ 0.5)
    ev.append_lines(ev.archive_path(), [_record("B", REG_MS + 2 * HOUR)])
    ev.append_lines(ev.grades_path(), [_grade("B", REG_MS + 2 * HOUR, hit=True)])
    out = cl.evaluate(claim)
    assert out["status"] == "CONFIRMED" and out["n"] == 2 and out["value"] == 1.0

    # same data, impossible threshold → REFUTED (value 1.0 < 1.01)
    strict = cl.evaluate({**claim, "threshold": {"op": ">=", "value": 1.01}, "min_n": 2})
    assert strict["status"] == "REFUTED"
    # <= threshold form works too
    low = cl.evaluate({**claim, "threshold": {"op": "<=", "value": 1.0}, "min_n": 2})
    assert low["status"] == "CONFIRMED"


def test_filter_and_metric_paths(claims_env):
    claim = _claim()
    claim["metric"] = "avg_forward"
    claim["filter"] = {"verdict": "SHORT"}  # records are BUY → no matches
    cl.register(claim)
    ev.append_lines(ev.archive_path(), [_record("A", REG_MS + HOUR)])
    ev.append_lines(ev.grades_path(), [_grade("A", REG_MS + HOUR, hit=True)])
    out = cl.evaluate(cl.load_claims()[0])
    assert out["status"] == "PENDING" and out["n"] == 0  # filter excluded the BUY

    claim2 = _claim(claim_id="CL-AVG-FWD")
    claim2["metric"] = "avg_forward"
    claim2["threshold"] = {"op": ">=", "value": 0.015}
    ev.append_lines(ev.archive_path(), [_record("C", REG_MS + 3 * HOUR)])
    ev.append_lines(ev.grades_path(), [_grade("C", REG_MS + 3 * HOUR, hit=True)])  # +0.02
    out2 = cl.evaluate(claim2)
    assert out2["status"] == "CONFIRMED" and out2["value"] == pytest.approx(0.02)


def test_list_evaluations_writes_status_sidecar(claims_env):
    cl.register(_claim())
    rows = cl.list_evaluations()
    assert len(rows) == 1 and rows[0]["claim_id"] == "CL-TEST-1"
    status_file = cl.status_path()
    assert status_file.exists()
    assert "CL-TEST-1" in status_file.read_text(encoding="utf-8")


def test_register_rejects_malformed_registered_at(claims_env):
    bad = _claim()
    bad["registered_at"] = "not-a-date"
    with pytest.raises(cl.ClaimError):
        cl.register(bad)
    bad2 = _claim()
    bad2["registered_at"] = "2026-13-45T99:00:00Z"  # impossible calendar fields
    with pytest.raises(cl.ClaimError):
        cl.register(bad2)
    bad3 = _claim()
    bad3["registered_at"] = ""
    with pytest.raises(cl.ClaimError):
        cl.register(bad3)
    # tolerant-but-validating: fractional seconds and numeric offsets are accepted
    frac = _claim(claim_id="CL-FRAC")
    frac["registered_at"] = "2023-11-14T22:13:20.500Z"
    assert cl.register(frac).exists()
    assert cl._registered_ms(cl.load_claims()[0]) == 1_700_000_000_500
    offs = _claim(claim_id="CL-OFFS")
    offs["registered_at"] = "2023-11-15T00:13:20+02:00"  # same instant as REG_MS
    assert cl.register(offs).exists()
    rows = {r["claim_id"]: r for r in cl.load_claims()}
    assert cl._registered_ms(rows["CL-OFFS"]) == REG_MS


def test_existing_file_with_malformed_date_evaluates_safe(claims_env):
    # a registry file that predates validation (malformed registered_at) must
    # land in the SAFE state: skipped from grading, PENDING, never CONFIRMED
    # from grades it cannot be date-gated against.
    import yaml

    d = Path(claims_env) / "claims"
    d.mkdir(parents=True)
    bad = _claim()
    bad["registered_at"] = "15/11/2023 (hand-edited)"
    (d / "CL-BAD-DATE.yaml").write_text(yaml.safe_dump(bad), encoding="utf-8")
    # grades exist and would CONFIRM (min_n=1, trivially-met threshold) if the
    # old `_registered_ms → 0` bug let them through as "post-registration"
    ev.append_lines(ev.archive_path(), [_record("A", REG_MS + HOUR)])
    ev.append_lines(ev.grades_path(), [_grade("A", REG_MS + HOUR, hit=True)])
    out = cl.evaluate(cl.load_claims()[0])
    assert out["status"] == "PENDING" and out["n"] == 0 and out["value"] is None
    assert "invalid registered_at" in out["note"]
    listing = cl.list_evaluations()
    assert listing[0]["status"] == "PENDING" and "invalid registered_at" in listing[0]["note"]


def test_load_claims_skips_corrupt_files(claims_env):
    d = Path(claims_env) / "claims"
    d.mkdir(parents=True)
    (d / "broken.yaml").write_text("{not yaml: [", encoding="utf-8")
    cl.register(_claim())
    rows = cl.load_claims()
    assert [r["claim_id"] for r in rows] == ["CL-TEST-1"]
