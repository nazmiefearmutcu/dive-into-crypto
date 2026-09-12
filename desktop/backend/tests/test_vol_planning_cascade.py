"""Vol term structure + cone, planning strip and cascade proxy — hand-computed
fixtures, pure functions, fully offline."""

import math

import pytest

from diveintocrypto_desktop.scan import cascade as cs
from diveintocrypto_desktop.scan import planning as pl
from diveintocrypto_desktop.scan import structure as st

HOUR_NS = 3_600_000_000


def _candles_from_returns(rets: list[float], start_close: float = 100.0, high_low_pct: float = 0.01):
    """1h candles (ns timestamps) realizing exactly ``rets`` close-to-close."""
    candles = []
    close = start_close
    t = 1_700_000_000_000 * 1_000_000
    for i, r in enumerate(rets):
        o = close
        close = close * (1 + r)
        candles.append({
            "t": t + i * HOUR_NS, "o": o, "c": close,
            "h": max(o, close) * (1 + high_low_pct),
            "l": min(o, close) * (1 - high_low_pct),
            "v": 1.0,
        })
    return candles


# ── planning strip ────────────────────────────────────────────────────────────
def test_planning_strip_hand_computed():
    out = pl.planning_strip(2.0)
    assert out["sl_distance_pct"] == pytest.approx(3.0)   # 1.5 × ATR%
    assert out["tp_1r_pct"] == pytest.approx(2.0)
    assert out["tp_2r_pct"] == pytest.approx(4.0)
    assert out["tp_3r_pct"] == pytest.approx(6.0)
    assert out["envelope"]["h1"] == pytest.approx(2.0)
    assert out["envelope"]["h4"] == pytest.approx(2.0 * 2.0)          # √4
    assert out["envelope"]["h24"] == pytest.approx(2.0 * math.sqrt(24), rel=1e-4)
    assert "no order semantics" in out["note"]


def test_planning_strip_non_hourly_tf_scales():
    out = pl.planning_strip(1.0, atr_tf_hours=4.0)
    assert out["envelope"]["h1"] == pytest.approx(0.5)   # 1h = quarter of the 4h ATR
    assert out["envelope"]["h4"] == pytest.approx(1.0)
    assert out["envelope"]["h24"] == pytest.approx(math.sqrt(6.0), rel=1e-4)


def test_planning_strip_atr_missing_is_honest():
    assert pl.planning_strip(None) == {"unavailable": "atr_missing"}
    assert pl.planning_strip(0.0) == {"unavailable": "atr_missing"}


# ── vol term structure ────────────────────────────────────────────────────────
def test_vol_term_structure_insufficient_history():
    candles = _candles_from_returns([0.001] * 20)
    assert st.vol_term_structure(candles) == {"unavailable": "insufficient_history"}


def test_vol_term_structure_curve_and_inversion():
    # alternating ±a returns: 1h σ = stdev of ln(1±a); every 4h/12h/24h
    # overlapping sum of this alternating series is exactly 0 → long legs 0
    a = 0.01
    import statistics

    log_rets = [math.log(1 + a), math.log(1 - a)] * 50
    candles = _candles_from_returns([a, -a] * 50)
    vol = st.vol_term_structure(candles)
    assert set(vol["curve"]) == {"h1", "h4", "h12", "h24"}
    expected_h1 = statistics.stdev(log_rets) * math.sqrt(365 * 24)
    assert vol["curve"]["h1"] == pytest.approx(round(expected_h1, 4))
    assert vol["curve"]["h4"] == 0.0
    assert vol["inverted"] is True            # long-horizon σ ≪ 1h σ on this fixture
    assert vol["vol_of_vol"] == 0.0           # rolling 24h σ is constant here
    # parkinson: bars span [min·0.99, max·1.01] with high/low_pct=0.01
    up = math.log((1 + a) * 1.01 / 0.99)      # up bar: max=close, min=open
    down = math.log(1.01 / (0.99 * (1 - a)))  # down bar: max=open, min=close
    pk_expect = math.sqrt(((up**2 + down**2) / 2) / (4 * math.log(2)) * 365 * 24)
    assert vol["parkinson"] == pytest.approx(round(pk_expect, 4), rel=1e-3)


def test_vol_term_structure_ratio_btc():
    a = 0.01
    btc_vol = a * math.sqrt(365 * 24)
    vol = st.vol_term_structure(_candles_from_returns([a, -a] * 50), btc_vol_1h=btc_vol)
    assert vol["ratio_btc"] == pytest.approx(1.0, abs=0.01)
    no_btc = st.vol_term_structure(_candles_from_returns([a, -a] * 50), btc_vol_1h=None)
    assert no_btc["ratio_btc"] is None


# ── vol cone ──────────────────────────────────────────────────────────────────
def test_vol_cone_log_normal_envelope():
    import statistics

    a = 0.01
    candles = _candles_from_returns([a, -a] * 50)
    cone = st.vol_cone(candles)
    sigma = statistics.stdev([math.log(1 + a), math.log(1 - a)] * 50)  # ddof=1, as in code
    expected_up = math.exp(1.0 * sigma * math.sqrt(24)) - 1.0
    expected_down = math.exp(-1.0 * sigma * math.sqrt(24)) - 1.0
    assert cone["sigma_1h"] == pytest.approx(round(sigma, 6))
    assert cone["env_24h"]["up"] == pytest.approx(round(expected_up, 4))
    assert cone["env_24h"]["down"] == pytest.approx(round(expected_down, 4))
    assert cone["env_48h"]["up"] > cone["env_24h"]["up"]
    assert 0.0 <= cone["percentile"] <= 100.0


def test_vol_cone_omitted_when_insufficient():
    assert st.vol_cone(_candles_from_returns([0.001] * 10)) is None


# ── cascade proxy ─────────────────────────────────────────────────────────────
def test_cascade_long_flush_composite():
    # OI contracts 12% while price wicks down hard on sell-taker flow
    n = 24
    oi = [1000.0 - 5.0 * i for i in range(n)]              # −5·23/1000 ≈ −11.5%
    candles = []
    t0 = 1_700_000_000_000_000  # ns
    for i in range(n):
        close = 100.0 - 1.0 * i
        candles.append({"t": t0 + i * 300_000_000_000, "o": close + 1.0, "c": close,
                        "h": close + 1.5, "l": close - 3.0})  # big lower wick
    taker = [0.6] * n                                       # heavy sell aggression
    out = cs.cascade_proxy(oi, candles, taker)
    assert out.get("proxy") is True
    assert out["direction"] == "long_flush"
    assert 45.0 <= out["score"] <= 100.0
    assert out["since_min"] >= 0.0


def test_cascade_short_flush_direction():
    n = 24
    oi = [1000.0 - 5.0 * i for i in range(n)]
    candles = []
    t0 = 1_700_000_000_000_000
    for i in range(n):
        close = 100.0 + 1.0 * i
        candles.append({"t": t0 + i * 300_000_000_000, "o": close - 1.0, "c": close,
                        "h": close + 3.0, "l": close - 1.5})  # big upper wick, price UP
    out = cs.cascade_proxy(oi, candles, [1.4] * n)
    assert out["direction"] == "short_flush"


def test_cascade_expansion_into_drop_is_not_a_flush():
    # OI EXPANDING (+12%) into a falling, wicking market: the OI leg is
    # directional — expansion is trend continuation, never flush evidence →
    # no long_flush may be scored (the old abs() OI leg scored ~90 here).
    n = 24
    oi = [1000.0 + 5.0 * i for i in range(n)]              # +5·23/1000 ≈ +11.5%
    candles = []
    for i in range(n):
        close = 100.0 - 1.0 * i
        candles.append({"t": 1_700_000_000_000_000 + i * 300_000_000_000,
                        "o": close + 1.0, "c": close,
                        "h": close + 1.5, "l": close - 3.0})  # big lower wick
    out = cs.cascade_proxy(oi, candles, [0.6] * n)
    assert out == {"unavailable": "oi_stable"}
    assert "direction" not in out and "score" not in out


def test_cascade_unavailable_paths():
    assert "unavailable" in cs.cascade_proxy([1.0] * 5, [{"t": 1, "h": 1, "l": 1, "c": 1}] * 5, [1.0])
    oi_stable = [1000.0] * 24
    candles = [{"t": 1_700_000_000_000_000 + i * 300_000_000_000, "o": 100, "c": 100,
                "h": 100.1, "l": 99.9} for i in range(24)]
    assert cs.cascade_proxy(oi_stable, candles, [1.0] * 24)["unavailable"] == "oi_stable"


def test_cascade_below_threshold_reports_score():
    # mild OI contraction + mild wick → below the cascade bar
    n = 24
    oi = [1000.0 - 0.5 * i for i in range(n)]               # ≈ −1.2%
    candles = [{"t": 1_700_000_000_000_000 + i * 300_000_000_000, "o": 100, "c": 99.98,
                "h": 100.02, "l": 99.96} for i in range(n)]
    out = cs.cascade_proxy(oi, candles, [1.0] * n)
    assert out["unavailable"] == "below_threshold"
    assert out["score"] < 45.0
