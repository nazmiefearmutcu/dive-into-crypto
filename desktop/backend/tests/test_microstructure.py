import pytest

"""Unit tests for the futures-microstructure overlay (scan/microstructure.py)."""

from diveintocrypto_desktop.scan import microstructure as ms


def _bullish_series():
    # price and OI both rising (real-money confirmed uptrend), buy-side taker
    # aggression, negative funding (shorts pay longs), smart money leaning long.
    price = [100 + i * 0.5 for i in range(48)]
    oi = [1000 + i * 15 for i in range(48)]
    taker = [1.15 + (i % 3) * 0.01 for i in range(48)]
    funding = [-0.0002 - (i % 4) * 1e-5 for i in range(48)]
    glob = [1.0 + (i % 5) * 0.01 for i in range(48)]
    pos = [1.6 + (i % 3) * 0.02 for i in range(48)]
    return {"price": price, "oi": oi, "taker": taker, "funding": funding, "glob": glob, "pos": pos}


def _bearish_series():
    # price rising but OI falling (short-covering, weak rally), sell-side taker,
    # a funding spike (crowded longs), retail crowd spike long.
    price = [100 + i * 0.5 for i in range(48)]
    oi = [1800 - i * 15 for i in range(48)]
    taker = [0.85 for _ in range(48)]
    funding = [0.0001 for _ in range(47)] + [0.0025]     # last = extreme positive spike
    glob = [1.2 for _ in range(47)] + [3.4]              # retail piles long
    pos = [1.0 for _ in range(48)]
    return {"price": price, "oi": oi, "taker": taker, "funding": funding, "glob": glob, "pos": pos}


def test_bullish_bundle_positive():
    out = ms.evaluate(_bullish_series())
    assert out["label"] in ("BUY", "STRONG_BUY")
    assert out["direction"] == 1
    assert out["score"] > 0
    assert out["active"] >= 4


def test_bearish_bundle_negative():
    out = ms.evaluate(_bearish_series())
    assert out["direction"] == -1
    assert out["score"] < 0
    assert out["label"] in ("SELL", "STRONG_SELL")


def test_disabled_short_circuits():
    out = ms.evaluate(_bullish_series(), enabled=False)
    assert out["label"] == "OFF"
    assert out["active"] == 0
    assert out["score"] == 0.0


def test_empty_series_is_neutral_not_crash():
    out = ms.evaluate({})
    assert out["direction"] == 0
    assert out["active"] == 0
    assert out["score"] == 0.0


def test_partial_series_uses_available_signals():
    # only funding present (extreme positive) -> fade bearish, others skipped
    out = ms.evaluate({"funding": [0.0001] * 20 + [0.003]})
    assert out["active"] == 1
    assert out["score"] <= 0


def test_score_bounded():
    out = ms.evaluate(_bullish_series())
    assert -100.0 <= out["score"] <= 100.0
    for s in out["signals"]:
        assert -1.0 <= s["score"] <= 1.0


# ── additive lens signals (v0.3): basis_extreme + funding_acceleration ────────
def test_basis_extreme_contrarian_signal():
    base = _bullish_series()
    out = ms.evaluate({**base, "basis_zscore": 2.5})   # deep premium → fade bearish
    names = [s["name"] for s in out["signals"]]
    assert "basis_extreme" in names
    sig = next(s for s in out["signals"] if s["name"] == "basis_extreme")
    assert sig["score"] == pytest.approx(-1.0)          # −z/2 clamped
    out_neg = ms.evaluate({**base, "basis_zscore": -2.0})
    sig_neg = next(s for s in out_neg["signals"] if s["name"] == "basis_extreme")
    assert sig_neg["score"] > 0


def test_funding_acceleration_signal():
    base = _bullish_series()
    out = ms.evaluate({**base, "funding_predicted": 0.0005, "funding_last_settled": 0.0001})
    sig = next(s for s in out["signals"] if s["name"] == "funding_acceleration")
    assert sig["score"] == pytest.approx(-1.0)          # rising funding → fade (clamped)
    out2 = ms.evaluate({**base, "funding_predicted": 0.0, "funding_last_settled": 0.0004})
    sig2 = next(s for s in out2["signals"] if s["name"] == "funding_acceleration")
    assert sig2["score"] > 0


def test_lens_signals_skip_when_absent():
    base = _bullish_series()
    plain = ms.evaluate(base)
    assert all(s["name"] not in ("basis_extreme", "funding_acceleration") for s in plain["signals"])
    # partial keys: settled without predicted → skipped, never guessed
    partial = ms.evaluate({**base, "funding_last_settled": 0.0001})
    assert all(s["name"] != "funding_acceleration" for s in partial["signals"])


def test_lens_signals_never_touch_vote_mechanics():
    # the bundle still works with ONLY lens inputs (no OI/taker/etc.) — additive
    only_lens = ms.evaluate({"basis_zscore": -3.0, "funding_predicted": 0.0,
                             "funding_last_settled": 0.0005})
    assert only_lens["direction"] == 1
    assert only_lens["active"] == 2
