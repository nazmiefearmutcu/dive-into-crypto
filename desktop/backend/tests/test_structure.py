"""Market structure — beta/corr math vs hand-computed fixtures, greedy
clustering on a synthetic correlation matrix, alignment. Fully offline.
"""

import math

import pytest

from diveintocrypto_desktop.scan import structure as st


# ── returns + alignment ────────────────────────────────────────────────────────
def test_returns_from_candles_includes_flat_bars():
    base = 1_700_000_000_000 * 1000  # ns
    candles = [
        {"t": base + i * 3_600_000_000_000, "c": c}
        for i, c in enumerate([100.0, 101.0, 101.0, 99.0])
    ]
    rets = st.returns_from_candles(candles)
    assert [(t, round(v, 6)) for t, v in rets] == [
        (base // 1_000_000 + 3_600_000, 0.01),
        (base // 1_000_000 + 2 * 3_600_000, 0.0),  # flat bar kept — alignment matters
        (base // 1_000_000 + 3 * 3_600_000, round(99.0 / 101.0 - 1, 6)),
    ]


def test_returns_window_capped():
    candles = [{"t": i * 3_600_000_000_000, "c": 100.0 + i} for i in range(500)]
    rets = st.returns_from_candles(candles)
    assert len(rets) == st.BETA_WINDOW


def test_align_inner_joins_timestamps():
    a = [(1000, 0.01), (2000, 0.02), (3000, 0.03)]
    b = [(1000, 0.05), (3000, -0.01), (4000, 0.09)]
    ra, rb = st._align(a, b)
    assert ra == [0.01, 0.03]
    assert rb == [0.05, -0.01]


# ── beta/corr vs hand-computed fixture ─────────────────────────────────────────
def test_beta_corr_perfect_linear_match():
    # symbol = 2 × btc returns (every bar) → beta 2.0, corr 1.0
    btc = [0.01, -0.02, 0.015, 0.005, -0.01] * 8  # 40 ≥ MIN_RETURNS
    sym = [2 * r for r in btc]
    beta, corr = st.beta_corr(sym, btc)
    assert beta == pytest.approx(2.0, abs=1e-6)
    assert corr == pytest.approx(1.0, abs=1e-6)


def test_beta_corr_inverted_series():
    btc = [0.01, -0.02, 0.015, 0.005, -0.01] * 8
    sym = [-r for r in btc]
    beta, corr = st.beta_corr(sym, btc)
    assert beta == pytest.approx(-1.0, abs=1e-6)
    assert corr == pytest.approx(-1.0, abs=1e-6)


def test_beta_corr_uncorrelated_is_zero():
    btc = [0.01, -0.01] * 20
    sym = ([0.02] * 20) + ([-0.02] * 20)
    beta, corr = st.beta_corr(sym, btc)
    assert corr == pytest.approx(0.0, abs=1e-9)
    assert beta == pytest.approx(0.0, abs=1e-9)


def test_beta_corr_too_few_bars_is_none():
    beta, corr = st.beta_corr([0.01] * 10, [0.01] * 10)
    assert beta is None and corr is None


def test_beta_corr_zero_variance_btc_is_none():
    beta, corr = st.beta_corr([0.01] * 40, [0.0] * 40)
    assert beta is None and corr is None


def test_pair_correlation_self_is_one():
    rets = [0.01, -0.005, 0.008] * 15
    assert st.pair_correlation(rets, rets) == pytest.approx(1.0, abs=1e-9)
    assert st.pair_correlation(rets[:5], rets[:5]) is None  # below MIN_RETURNS


# ── greedy clustering on a synthetic correlation matrix ────────────────────────
def test_greedy_clusters_two_blocks():
    # Block 1: A-B-C tightly coupled; Block 2: D-E; F isolated.
    symbols = ["A", "B", "C", "D", "E", "F"]
    high = 0.9
    corr = {}
    for x, y in [("A", "B"), ("A", "C"), ("B", "C")]:
        corr[tuple(sorted((x, y)))] = high
    for x, y in [("D", "E")]:
        corr[tuple(sorted((x, y)))] = high
    ids = st.greedy_clusters(symbols, corr)
    assert ids["A"] == ids["B"] == ids["C"] == 1
    assert ids["D"] == ids["E"] == 2
    assert ids["F"] == 3  # uncorrelated symbol still seeds its own (size-1) cluster


def test_greedy_clusters_threshold_respected():
    symbols = ["A", "B"]
    corr = {("A", "B"): 0.5}  # below 0.6 → separate clusters, never merged
    ids = st.greedy_clusters(symbols, corr)
    assert ids["A"] == 1 and ids["B"] == 2 and ids["A"] != ids["B"]


def test_greedy_clusters_top_n_labeled():
    symbols = [f"S{i}" for i in range(10)]
    corr = {}
    # five pairs, each its own cluster → 5 clusters of size 2 (≤ top 8)
    for i in range(0, 10, 2):
        corr[tuple(sorted((f"S{i}", f"S{i+1}")))] = 0.95
    ids = st.greedy_clusters(symbols, corr, top=8)
    pair_ids = [ids[f"S{i}"] for i in range(0, 10, 2)]
    assert pair_ids == [1, 2, 3, 4, 5]
    assert all(ids[f"S{i+1}"] == ids[f"S{i}"] for i in range(0, 10, 2))


def test_greedy_clusters_size_ordering():
    # cluster seeded by "big" collects 3 members; "solo" pair stays size 2.
    symbols = ["big", "m1", "m2", "solo", "sp"]
    corr = {
        ("big", "m1"): 0.9, ("big", "m2"): 0.85,
        ("solo", "sp"): 0.8,
    }
    ids = st.greedy_clusters(symbols, corr)
    assert ids["big"] == ids["m1"] == ids["m2"] == 1  # largest cluster gets id 1
    assert ids["solo"] == ids["sp"] == 2
