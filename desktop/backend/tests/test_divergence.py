"""Whale divergence + alignment parity — ported from the Android commonTest
vectors (WhaleDivergenceTest.kt, DivergenceAlignmentTest.kt) to prove the Python
port reproduces the same contrarian sign, detection gates, and bucket alignment.
"""

from diveintocrypto_desktop.scan import divergence as dv

P = 3_600_000  # 1h period (ms)


# ── fixtures (verbatim from WhaleDivergenceTest.kt) ──────────────────────────
def rising_price():
    out = []
    for i in range(60):
        if i < 30:
            out.append(100.0)
        elif i < 40:
            out.append(100.0 - (i - 30) * 0.5)
        else:
            out.append(95.0 + (i - 40) * 1.2)
    return out


def distributing_whale():
    return [1.4 + (i / 20.0) * 0.5 if i <= 20 else 1.9 - ((i - 20) / 39.0) * 0.75 for i in range(60)]


def chasing_whale():
    return [1.0 + i * 0.015 for i in range(60)]


def falling_price():
    return [88.0 + i * 0.85 if i <= 20 else 105.0 - (i - 20) * 0.55 for i in range(60)]


def accumulating_whale():
    return [1.5 - i * 0.04 if i <= 10 else 1.1 + (i - 10) * 0.013 for i in range(60)]


# ── divergence sign/detection parity ─────────────────────────────────────────
def test_distribution_is_contrarian_positive():
    r = dv.per_tf(rising_price(), distributing_whale(), tf_weight=95)
    assert r.detected
    assert r.direction == +1
    assert r.pattern_direction == -1
    assert 35.0 < r.score <= 100.0


def test_accumulation_is_contrarian_negative():
    r = dv.per_tf(falling_price(), accumulating_whale(), tf_weight=95)
    assert r.detected
    assert r.direction == -1
    assert r.pattern_direction == +1
    assert r.score < -35.0


def test_opposite_signs():
    bear = dv.per_tf(rising_price(), distributing_whale(), tf_weight=95)
    bull = dv.per_tf(falling_price(), accumulating_whale(), tf_weight=95)
    assert bear.score > 0.0 and bull.score < 0.0


def test_no_divergence_when_whale_chases_price():
    assert not dv.per_tf(rising_price(), chasing_whale(), tf_weight=95).detected


def test_flat_and_insufficient_and_equal_give_zero():
    assert not dv.per_tf([100.0] * 60, distributing_whale(), tf_weight=95).detected
    assert not dv.per_tf([100.0 + i for i in range(10)], [1.5 - i * 0.05 for i in range(10)], tf_weight=95).detected
    assert not dv.per_tf([100.0] * 40, [1.5] * 40, tf_weight=95).detected


def test_higher_tf_scores_larger():
    hi = dv.per_tf(rising_price(), distributing_whale(), tf_weight=95)
    lo = dv.per_tf(rising_price(), distributing_whale(), tf_weight=25)
    assert abs(hi.score) > abs(lo.score)


def test_for_symbol_picks_highest_tf_and_keeps_sign():
    hi = dv.per_tf(rising_price(), distributing_whale(), tf_weight=95)
    lo = dv.per_tf(rising_price(), distributing_whale(), tf_weight=25)
    res = dv.for_symbol({"1d": hi, "5m": lo})
    assert res.best_tf == "1d"
    assert res.score > 0.0  # contrarian-positive sign preserved


# ── alignment parity (DivergenceAlignmentTest.kt) ────────────────────────────
def test_co_bucket_aligns_1_to_1():
    times = [i * P for i in range(30)]
    prices = [100.0 + i for i in range(30)]
    ls_vals = [1.5 + i * 0.01 for i in range(30)]
    price, whale, matched = dv.align(times, prices, times, ls_vals, P)
    assert len(price) == 30 and matched == 30
    assert price == prices and whale == ls_vals


def test_forward_fills_missing_buckets():
    times = [i * P for i in range(10)]
    prices = [100.0 + i for i in range(10)]
    ls_kept = [0, 1, 2, 6, 7, 8, 9]
    ls_times = [i * P for i in ls_kept]
    ls_vals = [2.0 + i * 0.1 for i in ls_kept]
    price, whale, matched = dv.align(times, prices, ls_times, ls_vals, P)
    assert len(price) == 10 and matched == 7
    b2 = 2.0 + 2 * 0.1
    assert abs(whale[3] - b2) < 1e-9 and abs(whale[5] - b2) < 1e-9


def test_skips_candles_before_ls_starts():
    times = [i * P for i in range(10)]
    prices = [100.0 + i for i in range(10)]
    ls_kept = list(range(4, 10))
    price, whale, matched = dv.align(times, prices, [i * P for i in ls_kept], [1.0] * len(ls_kept), P)
    assert len(price) == 6 and matched == 6
    assert price == prices[4:10]


def test_zero_period_and_empty_safe():
    assert dv.align([0], [1.0], [0], [1.0], 0)[2] == 0
    assert dv.align([], [], [], [], P)[0] == []
    times = [i * P for i in range(5)]
    price, _, matched = dv.align(times, [1.0] * 5, [], [], P)
    assert len(price) == 0 and matched == 0


def test_zlema_correctness_and_parity():
    input_vals = [10.0, 12.0, 15.0, 14.0, 16.0, 18.0, 20.0, 19.0, 21.0, 23.0]
    expected = [
        10.0,
        11.333333333333334,
        14.222222222222221,
        14.814814814814815,
        15.54320987654321,
        17.695473251028807,
        19.79698216735254,
        19.864654778235025,
        20.57643651882335,
        22.717624345882236
    ]
    result = dv._zlema(input_vals, 5)
    assert len(result) == len(expected)
    for r, e in zip(result, expected):
        assert abs(r - e) < 1e-9

