"""v0.3 API surface — pulse, macro, options, evidence depth endpoints, claims,
decisions, tf/end_ms symbol params. Offline (mocked data layer)."""

import pytest
from fastapi.testclient import TestClient

from diveintocrypto_desktop.api.app import create_app
from diveintocrypto_desktop.scan import evidence as ev


HOUR = 3_600_000
NOW = 100 * ev.HORIZONS["4h"]  # fixed "now" for archive fixtures
TS = NOW - 10 * HOUR


@pytest.fixture()
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("DIVE_EVIDENCE_PATH", str(tmp_path / "evidence.jsonl"))
    monkeypatch.setenv("DIVE_EVIDENCE_GRADES_PATH", str(tmp_path / "grades.jsonl"))
    monkeypatch.setenv("DIVE_WEIGHT_SUGGESTIONS_PATH", str(tmp_path / "weight_suggestions.json"))
    monkeypatch.setenv("DIVE_CLAIMS_DIR", str(tmp_path / "claims"))
    monkeypatch.setenv("DIVE_CLAIMS_STATUS_PATH", str(tmp_path / "claims_status.json"))
    yield TestClient(create_app())


def _rec(symbol: str, ts: int, verdict: str = "BUY") -> dict:
    d = 1 if "BUY" in verdict else -1 if "SELL" in verdict else 0
    return {
        "ts": ts, "symbol": symbol, "verdict": verdict, "confidence": 60, "risk": "LOW",
        "net_score": 5.0 * d, "dominant_dir": d, "price": 100.0, "tf_agreement": 0.5,
        "divergence_state": "neutral", "engine_version": ev.ENGINE_VERSION, "signals": {},
    }


# ── routes registered ─────────────────────────────────────────────────────────
def test_v3_routes_registered():
    paths = {r.path for r in create_app().routes}
    assert {
        "/api/macro", "/api/options", "/api/pulse", "/api/claims",
        "/api/evidence/stability", "/api/evidence/replay", "/api/evidence/ic",
        "/api/evidence/suggest-weights", "/api/evidence/decisions",
    } <= paths


def test_health_reports_new_version(client):
    assert client.get("/api/health").json()["version"] == "0.3.0"


# ── /api/pulse ────────────────────────────────────────────────────────────────
def test_pulse_rows_and_cache(monkeypatch, client):
    from diveintocrypto_desktop.api import app as app_mod

    calls = {"prem": 0, "oi": 0}

    async def fake_premium_index_all():
        calls["prem"] += 1
        return {
            "BTCUSDT": {"mark_price": 50000.0, "last_funding_rate": 0.0001,
                        "next_funding_time": 1_700_000_000_000},
            "ETHUSDT": {"mark_price": 3000.0, "last_funding_rate": -0.0001,
                        "next_funding_time": 0},
        }

    async def fake_oi_hist(symbol, period="5m", limit=24):
        calls["oi"] += 1
        return [{"t": 0, "oi": 100.0}, {"t": 1, "oi": 105.0}]  # +5%

    monkeypatch.setattr(app_mod.fnd, "premium_index_all", fake_premium_index_all)
    monkeypatch.setattr(app_mod.oi_mod, "fetch_oi_hist", fake_oi_hist)

    r = client.get("/api/pulse?symbols=BTC,ETH")
    assert r.status_code == 200
    rows = r.json()
    assert [row["s"] for row in rows] == ["BTCUSDT", "ETHUSDT"]
    assert rows[0]["price"] == 50000.0
    assert rows[0]["funding_rate"] == 0.0001
    assert rows[0]["next_funding_time_ms"] == 1_700_000_000_000
    assert rows[0]["oi_delta_pct"] == pytest.approx(5.0)
    assert rows[1]["next_funding_time_ms"] is None  # 0/absent → null, never a fake 0 countdown

    client.get("/api/pulse?symbols=BTC,ETH")  # 10s cache: no new upstream calls
    assert calls["prem"] == 1 and calls["oi"] == 2

    too_many = client.get("/api/pulse?symbols=" + ",".join(["BTC"] * 21))
    assert too_many.status_code == 422
    missing = client.get("/api/pulse")
    assert missing.status_code == 422


# ── /api/macro + /api/options ─────────────────────────────────────────────────
def test_macro_snapshot_ok_and_failure_domain(monkeypatch, client):
    from diveintocrypto_desktop.api import app as app_mod

    async def ok():
        return {"fng": {"value": 39}, "stablecoin": {}, "defillama": {}, "generated_at": "Z"}

    async def boom():
        raise RuntimeError("unreachable")

    monkeypatch.setattr(app_mod.senti, "macro_snapshot", ok)
    assert client.get("/api/macro").json()["fng"]["value"] == 39

    monkeypatch.setattr(app_mod.senti, "macro_snapshot", boom)
    r = client.get("/api/macro")
    assert r.status_code == 502 and r.json()["error"] == "macro_unavailable"


def test_options_endpoint_honest_unavailable(monkeypatch, client):
    from diveintocrypto_desktop.api import app as app_mod

    async def unreachable():
        return {
            "BTC": {"unavailable": "deribit_unreachable"},
            "ETH": {"dvol_level": 43.2, "put_call_oi_ratio": 1.1, "atm_iv_30d": 55.5,
                    "generated_at": "Z"},
        }

    monkeypatch.setattr(app_mod.drb, "options_overview", unreachable)
    body = client.get("/api/options").json()
    assert body["BTC"] == {"unavailable": "deribit_unreachable"}
    assert body["ETH"]["dvol_level"] == 43.2


# ── evidence depth endpoints ──────────────────────────────────────────────────
def test_stability_and_decisions_endpoints(client):
    rows = [_rec("AAA", TS - i * HOUR) for i in range(3)] + [_rec("BBB", TS - 5 * HOUR)]
    ev.append_lines(ev.archive_path(), rows)

    r = client.get("/api/evidence/stability")
    assert r.status_code == 200
    body = {row["s"]: row for row in r.json()}
    assert body["AAA"]["k"] == 3 and body["AAA"]["agree_frac"] == 1.0
    assert body["AAA"]["median_gap_min"] == 60.0

    r2 = client.get(f"/api/evidence/decisions?symbol=AAA&from_ms={TS - 2 * HOUR}&to_ms={TS - HOUR}")
    assert r2.status_code == 200
    got = r2.json()
    assert [g["ts"] for g in got] == [TS - 2 * HOUR, TS - HOUR]  # window filter, time order
    empty = client.get("/api/evidence/decisions?symbol=ZZZ")
    assert empty.json() == []


def test_replay_endpoint_report_only(client):
    r = client.post("/api/evidence/replay?horizon=4h")
    assert r.status_code == 200
    body = r.json()
    assert body["report_only"] is True
    assert len(body["grid"]) == 36
    bad = client.post("/api/evidence/replay?horizon=3h")
    assert bad.status_code == 422


def test_ic_and_suggest_weights_endpoints(client, tmp_path):
    r = client.get("/api/evidence/ic")
    assert r.status_code == 200
    assert r.json()["indicators"] == {}

    w = client.post("/api/evidence/suggest-weights")
    assert w.status_code == 200
    body = w.json()
    assert "disclaimer" in body and "NEVER read" in body["disclaimer"]
    assert body["written_to"] and body["written_to"].endswith("weight_suggestions.json")
    assert (tmp_path / "weight_suggestions.json").exists()


# ── claims endpoints ──────────────────────────────────────────────────────────
def _claim(claim_id="CL-API-1"):
    import time

    return {
        "claim_id": claim_id,
        "registered_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "engine_version": "desktop-0.3.0",
        "claim": "LONG 4h hit-rate stays above 50%.",
        "metric": "hit_rate",
        "filter": {"verdict": "LONG"},
        "horizon": "4h",
        "min_n": 5,
        "threshold": {"op": ">=", "value": 0.5},
    }


def test_claims_register_list_lifecycle(client):
    r = client.post("/api/claims", json=_claim())
    assert r.status_code == 201
    assert r.json()["registered"] is True

    dup = client.post("/api/claims", json=_claim())
    assert dup.status_code == 409 and dup.json()["error"] == "claim_exists"

    bad = client.post("/api/claims", json={"claim_id": "x"})
    assert bad.status_code == 422 and bad.json()["error"] == "invalid_claim"

    lst = client.get("/api/claims")
    assert lst.status_code == 200
    rows = lst.json()["claims"]
    assert len(rows) == 1
    assert rows[0]["status"] == "PENDING"  # no grades yet
    assert rows[0]["note"].startswith("evaluated only on grades")


# ── symbol tf / end_ms params ─────────────────────────────────────────────────
def test_symbol_tf_validation_and_passthrough(monkeypatch, client):
    from diveintocrypto_desktop.api import app as app_mod
    from unittest.mock import AsyncMock

    mock_build = AsyncMock(return_value={"s": "BTCUSDT", "finalSignal": "BUY", "confidence": 60})
    monkeypatch.setattr(app_mod.sb, "build_symbol", mock_build)
    r = client.get("/api/symbol/btcusdt?tf=4h")
    assert r.status_code == 200
    assert mock_build.call_args.kwargs == {"primary_tf": "4h", "end_ms": None}

    mock_build.reset_mock()
    r2 = client.get("/api/symbol/BTCUSDT?tf=4h&end_ms=1700000000000")
    assert r2.status_code == 200
    assert mock_build.call_args.kwargs == {"primary_tf": "4h", "end_ms": 1700000000000}

    bad = client.get("/api/symbol/BTCUSDT?tf=7h")
    assert bad.status_code == 422


def test_universe_and_scan_unaffected(client):
    assert client.get("/api/logs").status_code == 200
