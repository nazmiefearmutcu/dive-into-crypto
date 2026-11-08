"""Full-universe scan depth: universe_limit validation, two-phase sweep,
async scan_id + progress lifecycle, adaptive gate pacing. Fully offline.
"""

import asyncio
import time
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from diveintocrypto_desktop.api.app import create_app
from diveintocrypto_desktop.scan import progress as progress_mod
from diveintocrypto_desktop.scan import scanner

# scan() must stay offline: every test here runs with real HTTP blocked and the
# market-extras / BTC candle lookups stubbed (see conftest.offline_scan_market).
pytestmark = pytest.mark.usefixtures("offline_scan_market")


@pytest.fixture(autouse=True)
def _clean_progress():
    progress_mod.reset()
    yield
    progress_mod.reset()


@pytest.fixture(autouse=True)
def _tmp_evidence(monkeypatch, tmp_path):
    # scans archive their verdicts — keep that off the repo's runtime/ dir
    monkeypatch.setenv("DIVE_EVIDENCE_PATH", str(tmp_path / "evidence.jsonl"))
    monkeypatch.setenv("DIVE_EVIDENCE_GRADES_PATH", str(tmp_path / "grades.jsonl"))
    yield


def _candles(n: int = 5) -> list[dict]:
    return [{"t": i * 3_600_000_000_000, "o": 1.0, "h": 1.1, "l": 0.9, "c": 1.0, "v": 1.0} for i in range(n)]


def _all_tf(candles: list[dict]) -> dict[str, list[dict]]:
    return {tf: list(candles) for tf in scanner.COARSE_TFS + scanner.DETAIL_TFS}


def _universe(n: int) -> list[dict]:
    return [
        {"s": f"{chr(ord('A') + i)}USDT", "name": chr(ord('A') + i), "price": 1.0, "ch": 0.0,
         "quote_volume": 1000.0 - i}
        for i in range(n)
    ]


# ── adaptive gate ──────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_adaptive_gate_shrinks_and_cools_on_pressure():
    gate = scanner.AdaptiveGate(initial=4, minimum=1, maximum=8, cooldown_base=0.05, cooldown_cap=0.1)
    await gate.acquire()
    await gate.release(RuntimeError("HTTP 429 too many requests"))
    assert gate.pressure_events == 1
    assert gate.width == 2  # halved
    t0 = time.monotonic()
    await gate.acquire()  # must wait out the global cooldown
    assert time.monotonic() - t0 >= 0.04
    await gate.release()


@pytest.mark.asyncio
async def test_adaptive_gate_backoff_doubles_and_recovers():
    gate = scanner.AdaptiveGate(initial=8, minimum=2, maximum=8, cooldown_base=0.01, cooldown_cap=0.02)
    for _ in range(3):
        await gate.acquire()
        await gate.release(RuntimeError("451 geo-block"))
    assert gate.width == 2  # clamped at minimum
    assert gate.pressure_events == 3
    # clean completions widen again (10 per +1)
    for _ in range(10):
        await gate.acquire()
        await gate.release()
    assert gate.width == 3


@pytest.mark.asyncio
async def test_adaptive_gate_status_attr_pressure():
    class FakeResp(Exception):
        status = 429

    gate = scanner.AdaptiveGate(initial=4)
    await gate.acquire()
    await gate.release(FakeResp())
    assert gate.pressure_events == 1 and gate.width == 2


# ── two-phase scan ─────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_scan_two_phase_full_universe_depth_top_n():
    universe = _universe(4)
    coarse_calls: list[tuple[str, tuple, int]] = []
    detail_calls: list[str] = []

    async def fetch_all_tf(symbol, limit=300, intervals=None):
        if intervals and set(intervals) == set(scanner.COARSE_TFS):
            coarse_calls.append((symbol, tuple(sorted(intervals)), limit))
            return {tf: _candles() for tf in intervals}
        detail_calls.append(symbol)
        return _all_tf(_candles())

    async def fake_universe(limit=None):
        return universe[:limit] if limit else universe

    async def no_divergence(symbol, candles_by_tf):
        return {}

    async def no_structure_fetch(symbol, candles_1h=None):
        return []

    with patch.object(scanner.uni, "list_universe", fake_universe), \
         patch.object(scanner.kl, "fetch_all_tf", fetch_all_tf), \
         patch.object(scanner.sb, "_divergence_inputs", no_divergence), \
         patch.object(scanner.st, "_one_hour_returns", no_structure_fetch):
        res = await scanner.scan(size=5, universe_limit=4, depth_top=2)

    assert len(coarse_calls) == 4, "phase 1 sweeps the whole requested universe"
    assert all(iv == tuple(sorted(scanner.COARSE_TFS)) for _, iv, _ in coarse_calls)
    assert sorted(detail_calls) == ["AUSDT", "BUSDT"], "phase 2 stays top-N (volume order on tie)"
    assert res["universeCount"] == 4
    assert res["coarseCount"] == 4
    assert res["scannedCount"] == 2
    assert res["scanned"] == 24  # legacy field: 2 fully-scanned rows × 12 TFs
    assert res["droppedCount"] == 0
    returned = res["survivors"] + res["eliminated"]
    assert len(returned) == 2
    for row in returned:
        assert len(row["multiTf"]) == 12
        assert "beta" in row and "corr_btc" in row and "cluster_id" in row
        assert "_candles_by_tf" not in row


@pytest.mark.asyncio
async def test_scan_depth_phase_failure_counts_as_dropped():
    universe = _universe(2)

    async def fetch_all_tf(symbol, limit=300, intervals=None):
        if symbol == "BUSDT" and intervals and set(intervals) == set(scanner.DETAIL_TFS):
            raise RuntimeError("detail fetch 429")
        if intervals and set(intervals) == set(scanner.COARSE_TFS):
            return {tf: _candles() for tf in intervals}
        return _all_tf(_candles())

    async def fake_universe(limit=None):
        return universe[:limit] if limit else universe

    async def no_divergence(symbol, candles_by_tf):
        return {}

    async def no_structure_fetch(symbol, candles_1h=None):
        return []

    with patch.object(scanner.uni, "list_universe", fake_universe), \
         patch.object(scanner.kl, "fetch_all_tf", fetch_all_tf), \
         patch.object(scanner.sb, "_divergence_inputs", no_divergence), \
         patch.object(scanner.st, "_one_hour_returns", no_structure_fetch):
        res = await scanner.scan(size=5, universe_limit=2, depth_top=5)

    assert res["coarseCount"] == 2
    assert res["scannedCount"] == 1
    assert res["droppedCount"] == 1  # the depth-phase failure is honest, not hidden


# ── progress record ─────────────────────────────────────────────────────────────
def test_progress_eta_from_rolling_rate():
    rec = progress_mod.start(meta={})
    rec.set_phase("phase1_coarse", total=100)
    rec._samples.clear()
    rec._samples.append((time.monotonic() - 10.0, 0))
    rec.tick(50)
    rec._samples.append((time.monotonic(), 50))
    eta = rec.snapshot()["eta_seconds"]
    assert eta == pytest.approx(10.0, abs=1.5)  # 50 done in 10s → 50 left ≈ 10s

    rec.finish({"survivors": 3})
    snap = rec.snapshot()
    assert snap["status"] == "done" and snap["eta_seconds"] is None
    assert snap["summary"] == {"survivors": 3}


def test_progress_registry_latest_and_get():
    a = progress_mod.start()
    b = progress_mod.start()
    assert progress_mod.get() is b
    assert progress_mod.get(a.scan_id) is a
    assert progress_mod.get("nope") is None


# ── async scan API lifecycle ────────────────────────────────────────────────────
def _scan_fixture(**overrides) -> dict:
    out = {
        "survivors": [{"s": "AUSDT", "finalSignal": "BUY", "confidence": 60, "risk": "LOW",
                       "netNss": 10.0, "dominantDir": 1, "price": 5.0, "whaleRegime": "neutral",
                       "beta": None, "corr_btc": None, "cluster_id": None}],
        "eliminated": [],
        "universeCount": 3, "scanned": 12, "scannedCount": 1, "droppedCount": 0,
        "coarseCount": 3, "depthTop": 50,
    }
    out.update(overrides)
    return out


def test_async_scan_lifecycle_with_progress():
    started = asyncio.Event()

    async def fake_scan(size=10, universe_limit=30, depth_top=50, progress=None):
        if progress:
            progress.set_phase("phase1_coarse", total=4)
            for _ in range(4):
                await asyncio.sleep(0.02)
                progress.tick()
        started.set()
        return _scan_fixture(universeCount=universe_limit)

    with patch("diveintocrypto_desktop.scan.scanner.scan", fake_scan):
        with TestClient(create_app()) as client:
            r = client.get("/api/scan?size=3&universe_limit=4&async=1")
            assert r.status_code == 200
            body = r.json()
            scan_id = body["scan_id"]
            assert body["status"] == "started"
            assert body["poll"] == f"/api/scan/progress?scan_id={scan_id}"

            # running: phase/completed/total visible while the task works
            deadline = time.monotonic() + 5
            last = None
            while time.monotonic() < deadline:
                p = client.get(f"/api/scan/progress?scan_id={scan_id}").json()
                last = p
                if p["status"] == "done":
                    break
                time.sleep(0.02)
            assert last["status"] == "done"
            assert last["phase"] == "done"
            assert last["completed"] == last["total"] == 4
            assert last["meta"]["universe_limit"] == 4
            assert last["summary"]["survivors"] == 1
            assert last["result_available"] is True

            res = client.get(f"/api/scan/result?scan_id={scan_id}")
            assert res.status_code == 200
            assert res.json()["survivors"][0]["s"] == "AUSDT"

            # latest progress (no scan_id) resolves
            assert client.get("/api/scan/progress").json()["scan_id"] == scan_id
            assert started.is_set()


def test_async_scan_progress_unknown_id_404():
    client = TestClient(create_app())
    assert client.get("/api/scan/progress?scan_id=missing").status_code == 404
    assert client.get("/api/scan/result?scan_id=missing").status_code == 404


def _wait_scan_done(client: TestClient, scan_id: str, timeout: float = 5.0) -> dict:
    """Poll the async progress endpoint until the background scan reports done."""
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        last = client.get(f"/api/scan/progress?scan_id={scan_id}").json()
        if last["status"] == "done":
            break
        time.sleep(0.02)
    assert last.get("status") == "done", f"background scan never finished: {last}"
    return last


def test_scan_param_validation_caps():
    """Out-of-range params are rejected with 422 before any scan starts, and the
    documented caps are accepted AND really run the scan with those values. The
    background task swallows exceptions, so completion is asserted, not just meta."""
    calls: list[dict] = []

    async def recording_scan(size=10, universe_limit=30, depth_top=50, progress=None):
        calls.append({"universe_limit": universe_limit, "depth_top": depth_top})
        return _scan_fixture(universeCount=universe_limit, depthTop=depth_top)

    with patch("diveintocrypto_desktop.scan.scanner.scan", recording_scan):
        with TestClient(create_app()) as client:
            assert client.get("/api/scan?universe_limit=501").status_code == 422
            assert client.get("/api/scan?universe_limit=0").status_code == 422
            assert client.get("/api/scan?depth_top=201").status_code == 422
            assert client.get("/api/scan?size=0").status_code == 422
            assert calls == [], "validation failures must not run a scan"

            r500 = client.get("/api/scan?universe_limit=500&async=1")
            assert r500.status_code == 200
            done500 = _wait_scan_done(client, r500.json()["scan_id"])
            assert done500["meta"]["universe_limit"] == 500
            assert done500["result_available"] is True

            r200 = client.get("/api/scan?depth_top=200&async=1")
            assert r200.status_code == 200
            done200 = _wait_scan_done(client, r200.json()["scan_id"])
            assert done200["meta"]["depth_top"] == 200
            assert done200["result_available"] is True

    assert {"universe_limit": 500, "depth_top": scanner.DEFAULT_DEPTH_TOP} in calls
    assert {"universe_limit": 30, "depth_top": 200} in calls


def test_scan_sync_mode_reports_progress_and_finishes():
    async def fake_scan(size=10, universe_limit=30, depth_top=50, progress=None):
        if progress:
            progress.set_phase("phase2_detail", total=2)
            progress.tick(2)
        return _scan_fixture(universeCount=universe_limit, depthTop=depth_top)

    with patch("diveintocrypto_desktop.scan.scanner.scan", fake_scan):
        with TestClient(create_app()) as client:
            r = client.get("/api/scan?size=3&universe_limit=2&depth_top=5")
            assert r.status_code == 200
            assert r.json()["universeCount"] == 2

            snap = client.get("/api/scan/progress").json()
            assert snap["status"] == "done" and snap["meta"]["mode"] == "sync"


def test_scan_size_is_clamped_to_the_divergence_cap():
    """size is accepted up to 100 but the effective size is capped by the rows that
    ever get whale divergence (candidatesCap): the scan receives the clamped value."""
    seen: list[int] = []

    async def recording_scan(size=10, universe_limit=30, depth_top=50, progress=None):
        seen.append(size)
        return _scan_fixture(universeCount=universe_limit, depthTop=depth_top)

    with patch("diveintocrypto_desktop.scan.scanner.scan", recording_scan):
        with TestClient(create_app()) as client:
            assert client.get("/api/scan?size=100&universe_limit=7&depth_top=5").status_code == 200
            assert progress_mod.get().meta["size"] == scanner.DIVERGENCE_CANDIDATE_CAP
            assert client.get("/api/scan?size=3&universe_limit=8&depth_top=5").status_code == 200
    assert seen == [scanner.DIVERGENCE_CANDIDATE_CAP, 3]


def test_sync_scan_cancellation_never_leaves_progress_running():
    """CancelledError is not an Exception: the progress record must still be closed."""

    async def cancelled_scan(size=10, universe_limit=30, depth_top=50, progress=None):
        if progress:
            progress.set_phase("phase1_coarse", total=4)
        raise asyncio.CancelledError()

    with patch("diveintocrypto_desktop.scan.scanner.scan", cancelled_scan):
        with TestClient(create_app(), raise_server_exceptions=False) as client:
            client.get("/api/scan?size=2&universe_limit=9&depth_top=5")

    snap = progress_mod.get().snapshot()
    assert snap["status"] == "error" and snap["error"] == "scan cancelled"
    assert "finished_at" in snap


# ── rate-limit pressure must reach the gate (integration, not just unit) ───────
def test_is_pressure_classification():
    from diveintocrypto_desktop.data.http import TransientUpstreamError

    class StatusErr(Exception):
        status = 400

    assert scanner._is_pressure(RuntimeError("HTTP 429 too many requests"))
    assert scanner._is_pressure(RuntimeError("451 geo-block"))
    assert scanner._is_pressure(TransientUpstreamError(429))
    assert not scanner._is_pressure(TransientUpstreamError(500))
    assert not scanner._is_pressure(StatusErr("429 in the message but status 400"))
    # digits that merely contain 429/451 are not rate-limit signals
    assert not scanner._is_pressure(RuntimeError("timeout after 4510 ms"))
    assert not scanner._is_pressure(RuntimeError("GET /klines?startTime=1700000000451 failed"))
    assert not scanner._is_pressure(None)


@pytest.mark.asyncio
async def test_gate_slot_releases_with_parked_fetch_error():
    gate = scanner.AdaptiveGate(initial=4, minimum=1, maximum=8, cooldown_base=0.0, cooldown_cap=0.0)
    slot = scanner._GateSlot(gate)
    async with slot:
        slot.exc = RuntimeError("HTTP 429 too many requests")  # parked, as _fetch_phase does
    assert gate.pressure_events == 1 and gate.width == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["text-429", "status-429"])
async def test_scan_rate_limit_reaches_adaptive_gate(kind):
    from diveintocrypto_desktop.data.http import TransientUpstreamError

    def failure():
        if kind == "status-429":
            return TransientUpstreamError(429)  # what get_json raises after its retries
        return RuntimeError("HTTP 429 too many requests")

    universe = _universe(2)
    gates: list[scanner.AdaptiveGate] = []
    base = scanner.AdaptiveGate

    class RecordingGate(base):
        def __init__(self, *a, **kw):
            kw.setdefault("cooldown_base", 0.01)
            kw.setdefault("cooldown_cap", 0.02)
            super().__init__(*a, **kw)
            gates.append(self)

    async def fetch_all_tf(symbol, limit=300, intervals=None):
        if symbol == "BUSDT":
            raise failure()
        return {tf: _candles() for tf in intervals}

    async def fake_universe(limit=None):
        return universe[:limit] if limit else universe

    async def no_divergence(symbol, candles_by_tf):
        return {}

    async def no_structure_fetch(symbol, candles_1h=None):
        return []

    with patch.object(scanner, "AdaptiveGate", RecordingGate), \
         patch.object(scanner.uni, "list_universe", fake_universe), \
         patch.object(scanner.kl, "fetch_all_tf", fetch_all_tf), \
         patch.object(scanner.sb, "_divergence_inputs", no_divergence), \
         patch.object(scanner.st, "_one_hour_returns", no_structure_fetch):
        res = await scanner.scan(size=5, universe_limit=2, depth_top=5)

    gate = gates[0]
    assert gate.pressure_events == 1, "a 429/451 must halve the gate, not be released as clean"
    assert gate.width == 4  # halved from the initial 8
    assert res["droppedCount"] == 1 and res["candidatesCap"] == scanner.DIVERGENCE_CANDIDATE_CAP


@pytest.mark.asyncio
async def test_assemble_failure_drops_one_symbol_not_the_sweep(monkeypatch):
    universe = _universe(2)
    real_assemble = scanner.sb.assemble

    def flaky_assemble(symbol, *a, **kw):
        if symbol == "BUSDT":
            raise ValueError("engine blew up on BUSDT")
        return real_assemble(symbol, *a, **kw)

    async def fetch_all_tf(symbol, limit=300, intervals=None):
        return {tf: _candles() for tf in intervals}

    async def fake_universe(limit=None):
        return universe[:limit] if limit else universe

    async def no_divergence(symbol, candles_by_tf):
        return {}

    async def no_structure_fetch(symbol, candles_1h=None):
        return []

    monkeypatch.setattr(scanner.sb, "assemble", flaky_assemble)
    with patch.object(scanner.uni, "list_universe", fake_universe), \
         patch.object(scanner.kl, "fetch_all_tf", fetch_all_tf), \
         patch.object(scanner.sb, "_divergence_inputs", no_divergence), \
         patch.object(scanner.st, "_one_hour_returns", no_structure_fetch):
        res = await scanner.scan(size=5, universe_limit=2, depth_top=5)

    assert res["droppedCount"] == 1 and res["coarseCount"] == 1
    assert [r["s"] for r in res["survivors"] + res["eliminated"]] == ["AUSDT"]


@pytest.mark.asyncio
async def test_deep_row_is_assembled_once_not_twice(monkeypatch):
    universe = _universe(1)
    calls: list[str] = []
    real_assemble = scanner.sb.assemble

    def counting_assemble(symbol, *a, **kw):
        calls.append(symbol)
        return real_assemble(symbol, *a, **kw)

    async def fetch_all_tf(symbol, limit=300, intervals=None):
        return {tf: _candles() for tf in intervals}

    async def fake_universe(limit=None):
        return universe[:limit] if limit else universe

    async def no_divergence(symbol, candles_by_tf):
        return {}

    async def no_structure_fetch(symbol, candles_1h=None):
        return []

    monkeypatch.setattr(scanner.sb, "assemble", counting_assemble)
    with patch.object(scanner.uni, "list_universe", fake_universe), \
         patch.object(scanner.kl, "fetch_all_tf", fetch_all_tf), \
         patch.object(scanner.sb, "_divergence_inputs", no_divergence), \
         patch.object(scanner.st, "_one_hour_returns", no_structure_fetch):
        res = await scanner.scan(size=5, universe_limit=1, depth_top=5)

    assert res["scannedCount"] == 1
    assert calls == ["AUSDT", "AUSDT"], "one coarse assembly + one merged 12-TF assembly"


# ── progress registry stays consistent under pressure ───────────────────────────
def test_progress_registry_consistent_and_live_scan_kept():
    running = progress_mod.start()
    for _ in range(40):
        progress_mod.start().finish({})
    assert progress_mod.get(running.scan_id) is running  # a live scan is never evicted
    assert len(progress_mod._order) == len(progress_mod._registry) <= 32  # no orphan ids
