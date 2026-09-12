"""Replay grid + IC + weight suggestions — determinism, weights_hash skip,
constant-column honesty, normalization/caps. Fully offline."""

import pytest

from diveintocrypto_desktop.scan import evidence as ev
from diveintocrypto_desktop.scan import replay as rp


H = ev.HORIZONS["4h"]
HOUR = 3_600_000
NOW = 100 * H
TS = NOW - 10 * HOUR


@pytest.fixture(autouse=True)
def _tmp_evidence(monkeypatch, tmp_path):
    monkeypatch.setenv("DIVE_EVIDENCE_PATH", str(tmp_path / "evidence.jsonl"))
    monkeypatch.setenv("DIVE_EVIDENCE_GRADES_PATH", str(tmp_path / "grades.jsonl"))
    yield


def _archive(signals: dict, hash_key: str, symbol: str = "AUSDT", ts: int = TS):
    return {
        "ts": ts, "symbol": symbol, "verdict": "BUY", "confidence": 60, "risk": "LOW",
        "net_score": 5.0, "dominant_dir": 1, "price": 100.0, "tf_agreement": 0.5,
        "divergence_state": "neutral", "engine_version": ev.ENGINE_VERSION,
        "signals": signals, "weights_hash": hash_key,
    }


def _grade(symbol: str = "AUSDT", ts: int = TS, forward: float = 0.02, hit: bool = True):
    return {"ts": ts, "symbol": symbol, "horizon": "4h", "hit": hit, "forward": forward}


def test_rescore_mirrors_engine_thresholds():
    signals = {"rsi": 1, "macd": 1, "adx_di": -1}
    weights = {"rsi": 1.0, "macd": 1.0, "adx_di": 1.0}
    # weighted avg = (1+1-1)/3 = 0.333 → BUY at 0.3, NEUTRAL at 0.4
    assert rp.rescore(signals, weights, buy=0.3, strong=1.0, conflict=0.6) == 1
    assert rp.rescore(signals, weights, buy=0.4, strong=1.0, conflict=0.6) == 0
    # conflict: 1 buy vs 1 sell of 3 active → 1/3 ≤ 0.4 → no forced NEUTRAL at 0.4
    assert rp.rescore(signals, weights, buy=0.1, strong=1.0, conflict=0.4) == 1
    # zero-weight indicators drop out of both sums
    assert rp.rescore(signals, {"rsi": 0, "macd": 0, "adx_di": 0}, 0.3, 1.0, 0.6) == 0


@pytest.mark.asyncio
async def test_replay_grid_deterministic_and_skips_foreign_hash(monkeypatch):
    weights = {"rsi": 1.0, "macd": 1.0}
    good = ev.weights_hash(weights)
    monkeypatch.setattr(rp, "_load_shipped_weights", lambda: (weights, good))
    for i in range(6):
        rec = _archive({"rsi": 1, "macd": 1}, good, symbol=f"S{i}", ts=TS - i * HOUR)
        ev.append_lines(ev.archive_path(), [rec])
        ev.append_lines(ev.grades_path(), [_grade(f"S{i}", ts=TS - i * HOUR)])
    # one record under a DIFFERENT weights map + one without any signals
    # (both with grades so they reach the skip counters)
    ev.append_lines(ev.archive_path(), [_archive({"rsi": 1, "macd": 1}, "deadbeef", symbol="X", ts=TS - 6 * HOUR)])
    ev.append_lines(ev.grades_path(), [_grade("X", ts=TS - 6 * HOUR)])
    ev.append_lines(ev.archive_path(), [{**_archive({}, good, symbol="Y", ts=TS - 7 * HOUR)}])
    ev.append_lines(ev.grades_path(), [_grade("Y", ts=TS - 7 * HOUR)])

    out1 = rp.replay_grid("4h")
    out2 = rp.replay_grid("4h")
    assert out1["generated_at"].endswith("Z")  # wall-clock stamp stays in the payload
    # generated_at has 1s resolution — two calls can straddle a second boundary,
    # so it is excluded from the equality check; everything else is deterministic
    out1 = {k: v for k, v in out1.items() if k != "generated_at"}
    out2 = {k: v for k, v in out2.items() if k != "generated_at"}
    assert out1 == out2  # deterministic (seeded bootstrap, fixed grid)
    assert out1["report_only"] is True
    assert len(out1["grid"]) == 4 * 3 * 3  # bounded grid
    assert out1["events"]["replayable"] == 6
    assert out1["events"]["skipped_weights_hash"] == 1
    assert out1["events"]["skipped_no_signals"] == 1
    assert out1["shipped_cell"] == {"buy": 0.4, "strong": 1.2, "conflict": 0.6}
    cell = next(c for c in out1["grid"]
                if c["buy"] == 0.4 and c["strong"] == 1.2 and c["conflict"] == 0.6)
    assert cell["n_directional"] == 6 and cell["hit_rate"] == 1.0
    assert cell["bootstrap_win_vs_shipped"] is not None


def test_replay_grid_empty_archive_is_honest():
    out = rp.replay_grid("4h")
    assert out["events"]["replayable"] == 0
    assert all(c["hit_rate"] is None for c in out["grid"])


def test_ic_table_constant_column_and_min_n():
    records, grades = [], []
    for i in range(rp.IC_MIN_N + 5):
        sig = {"rsi": 1, "macd": (1 if i % 2 else -1), "flat": 1}  # flat = constant column
        records.append(_archive(sig, "any", symbol=f"S{i}", ts=TS - i * HOUR))
        grades.append(_grade(f"S{i}", ts=TS - i * HOUR, forward=0.01 * (1 if i % 2 else -1)))
    ic = rp.ic_table(records, grades)
    assert ic["n"] == rp.IC_MIN_N + 5
    # constant column carries no information → honest "n/a"
    assert ic["indicators"]["flat"]["ic"] == "n/a"
    # perfect sign alignment → |IC| high for macd
    assert abs(ic["indicators"]["macd"]["ic"]) > 0.9
    assert ic["indicators"]["macd"]["n"] == rp.IC_MIN_N + 5


def test_ic_table_below_min_n_is_na():
    records = [_archive({"rsi": 1}, "h", symbol=f"S{i}", ts=TS - i * HOUR) for i in range(10)]
    grades = [_grade(f"S{i}", ts=TS - i * HOUR) for i in range(10)]
    ic = rp.ic_table(records, grades)
    assert ic["indicators"]["rsi"]["ic"] == "n/a"


def test_suggest_weights_normalized_and_capped():
    shipped = {"a": 1.0, "b": 10.0, "c": 1.0, "zero": 0.0}
    ic = {"indicators": {
        "a": {"ic": 0.2, "ic_ir": 2.0, "n": 100},    # strong positive → up (cap binds)
        "b": {"ic": -0.2, "ic_ir": -2.0, "n": 100},  # negative → pulled down
        "c": {"ic": 0.0, "ic_ir": None, "n": 100},   # unmeasurable → keeps shipped weight
    }}
    out = rp.suggest_weights(ic, shipped=shipped)
    s = out["suggestions"]
    assert "zero" not in s                       # zero-weight indicators stay out
    assert s["a"]["suggested"] == pytest.approx(3.0)  # cap: exactly 3× shipped(1.0)
    assert s["c"]["suggested"] > 0.9             # normalization only nudges it
    total = sum(v["suggested"] for v in s.values())
    assert 10.0 < total <= 12.0 + 1e-9           # near the shipped total (12); cap trims
    assert "NEVER read by the engine" in out["disclaimer"]
    assert out["cap_multiple"] == 3.0


def test_spearman_known_values():
    assert rp.spearman([1, 2, 3, 4, 5], [1, 2, 3, 4, 5]) == pytest.approx(1.0)
    assert rp.spearman([1, 2, 3, 4, 5], [5, 4, 3, 2, 1]) == pytest.approx(-1.0)
    assert rp.spearman([1, 1, 1], [1, 2, 3]) is None  # constant → None
