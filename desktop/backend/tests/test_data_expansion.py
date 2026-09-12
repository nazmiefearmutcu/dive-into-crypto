"""PART 1 data-expansion tests — basis math, funding lens, order-book bands,
sentiment adapters, spot lead/lag, L/S term structure, Deribit parser, index
constituents. Fully offline (fixture payloads + monkeypatched fetchers)."""

import pytest

from diveintocrypto_desktop.data import basis as bs
from diveintocrypto_desktop.data import deribit as drb
from diveintocrypto_desktop.data import funding as fnd
from diveintocrypto_desktop.data import index_info as ii
from diveintocrypto_desktop.data import orderbook as ob
from diveintocrypto_desktop.data import ratios as rat
from diveintocrypto_desktop.data import sentiment as senti
from diveintocrypto_desktop.data import spot as spot_mod


# ── basis ─────────────────────────────────────────────────────────────────────
def test_basis_label_bands():
    # half-open bands: [−∞,−50) deep_discount, [−50,−10) discount,
    # [−10,10) balanced, [10,50) premium, [50,∞) deep_premium
    assert bs.basis_label(-100.0) == "deep_discount"
    assert bs.basis_label(-50.0) == "discount"
    assert bs.basis_label(-10.0) == "balanced"
    assert bs.basis_label(0.0) == "balanced"
    assert bs.basis_label(9.999) == "balanced"
    assert bs.basis_label(10.0) == "premium"
    assert bs.basis_label(49.999) == "premium"
    assert bs.basis_label(50.0) == "deep_premium"


def test_basis_zscore_vs_trailing_history():
    import random

    rng = random.Random(7)
    hist = [rng.gauss(0.0, 0.01) for _ in range(100)]
    z = bs.basis_zscore(hist, hist[-1])
    assert z is not None and abs(z) < 3.0
    assert bs.basis_zscore(hist[:10], 0.0) is None          # <30 points → None
    assert bs.basis_zscore([0.0] * 40, 0.0) is None         # zero spread → None


def test_ann_premium_bps_hand_computed():
    # contract 5% above index with exactly 0.5y to expiry → 1000 bps annualized
    now = 1_700_000_000_000
    expiry = now + int(0.5 * 365 * 24 * 3600 * 1000)
    assert bs._ann_premium_bps(105.0, 100.0, expiry, now) == pytest.approx(1000.0)
    assert bs._ann_premium_bps(None, 100.0, expiry, now) is None
    assert bs._ann_premium_bps(105.0, 0.0, expiry, now) is None
    assert bs._ann_premium_bps(105.0, 100.0, now + 60_000, now) is None  # sub-half-hour


def test_expiry_parsed_from_symbol_suffix():
    ms = bs._expiry_from_symbol("BTCUSDT_250926")
    import datetime

    dt = datetime.datetime.fromtimestamp(ms / 1000, tz=datetime.timezone.utc)
    assert (dt.year, dt.month, dt.day, dt.hour) == (2025, 9, 26, 8)
    assert bs._expiry_from_symbol("BTCUSDT") == 0


@pytest.mark.asyncio
async def test_basis_block_partial_allows_missing_deliveries():
    perp_row = {"mark_price": 100.5, "index_price": 100.0, "last_funding_rate": 0.0001}
    block = await bs.basis_block("BTCUSDT", perp_row, {}, [0.0] * 40, now_ms=1_700_000_000_000)
    assert block["basis_bps"] == pytest.approx(50.0)  # 0.5% over index = 50 bps
    assert block["label"] == "deep_premium"
    assert block["partial"] is True                    # no CQ/NQ → partial allowed
    assert block["curve"]["perp"] == pytest.approx(50.0)
    assert block["curve"]["cq"] is None and block["curve"]["nq"] is None
    assert block["ann_funding"] == pytest.approx(0.0001 * 3 * 365 * 10_000)
    assert block["zscore"] is None                     # flat history → no z


@pytest.mark.asyncio
async def test_basis_block_unavailable_without_premium():
    assert await bs.basis_block("X", {"mark_price": 1.0}, {}, []) == \
        {"unavailable": "premium_index_unavailable"}


# ── funding lens ──────────────────────────────────────────────────────────────
def test_funding_lens_null_cadence_and_full():
    lens = fnd.funding_lens(0.0002, [0.0001, 0.00015], 0, now_ms=1_000_000_000_000)
    assert lens["seconds_to_funding"] is None  # nextFundingTime 0/absent → no fake countdown
    assert lens["predicted_funding"] == 0.0002
    assert lens["last_settled"] == 0.00015
    assert lens["apr"] == pytest.approx(0.0002 * 3 * 365)
    assert lens["regime"] == "long_crowding"

    lens2 = fnd.funding_lens(None, [], None, now_ms=1_000_000_000_000)
    assert lens2["regime"] == "unavailable" and lens2["apr"] is None

    # countdown: 30 min to settlement
    now = 1_000_000_000_000
    lens3 = fnd.funding_lens(0.0, [0.0], now + 1_800_000, now_ms=now)
    assert lens3["seconds_to_funding"] == pytest.approx(1800.0)
    # past settlement time → no negative countdown, honest None
    lens4 = fnd.funding_lens(0.0, [0.0], now - 1_800_000, now_ms=now)
    assert lens4["seconds_to_funding"] is None


def test_funding_regime_bands():
    assert fnd.funding_regime(None) == "unavailable"
    assert fnd.funding_regime(0.0) == "balanced"
    assert fnd.funding_regime(0.0001) == "long_crowding"        # ≈11% APR
    assert fnd.funding_regime(0.0003) == "extreme_long_crowding"  # ≈33% APR
    assert fnd.funding_regime(-0.0004) == "extreme_short_crowding"


# ── order book ────────────────────────────────────────────────────────────────
def test_book_panel_bands_hand_computed():
    # mid 100.05; 0.5% band [99.5499, 100.5503]; 1% adds 101.0 asks; 2% adds 98.1 bids
    bids = [(100.0, 1.0), (99.8, 1.0), (99.9, 10.0), (98.1, 100.0)]
    asks = [(100.1, 1.0), (100.3, 1.0), (100.5, 1.0), (101.0, 10.0)]
    panel = ob.book_panel(bids, asks, now_ms=1_700_000_000_000)
    assert panel["mid"] == pytest.approx(100.05)
    b05 = 100.0 + 99.8 + 999.0          # 1198.8 (98.1 sits outside the 0.5% band)
    a05 = 100.1 + 100.3 + 100.5         # 300.9
    assert panel["imbalance"]["0.5%"] == pytest.approx(round((b05 - a05) / (b05 + a05), 4))
    # 1% band adds the 101.0 × 10 asks → notional 1198.8 + 1310.9
    assert panel["notional_1pct"] == pytest.approx(round(b05 + 1310.9, 2))
    assert panel["imbalance"]["2%"] is not None  # 98.1 bids join only in the 2% band
    assert panel["ts"] == 1_700_000_000_000
    assert set(panel["imbalance"]) == {"0.5%", "1%", "2%"}


def test_book_panel_thin_book_is_honest():
    tiny = [(100.0, 0.001)], [(100.1, 0.001)]
    assert ob.book_panel(*tiny) == {"unavailable": "book_too_thin"}
    assert ob.book_panel([], []) == {"unavailable": "book_too_thin"}


def test_parse_levels_skips_corrupt_rows():
    levels = ob.parse_levels([["100", "1"], ["bad", "1"], ["99", "0"], [98, 2], ["x"]])
    assert levels == [(100.0, 1.0), (98.0, 2.0)]


# ── sentiment ─────────────────────────────────────────────────────────────────
def test_fng_parse_and_cache(monkeypatch):
    senti.reset_cache()
    payload = {"data": [{"value": "39", "value_classification": "Fear", "timestamp": "1700000000"}]}
    calls = []

    async def fake_get_json(url, params=None, **kw):
        calls.append(url)
        return payload

    monkeypatch.setattr(senti, "get_json", fake_get_json)
    import asyncio

    first = asyncio.run(senti.fear_greed())
    assert first["value"] == 39 and first["classification"] == "Fear"
    assert first["cadence"] == "daily"
    asyncio.run(senti.fear_greed())
    assert len(calls) == 1  # 1h cache: one upstream call
    assert senti.parse_fng({"data": []}) == {"unavailable": "fng_payload_empty"}


def test_stablecoin_proxy_pure():
    tickers = [
        {"symbol": "BTCUSDT", "quoteVolume": "800", "lastPrice": "50000"},
        {"symbol": "ETHUSDT", "quoteVolume": "100", "lastPrice": "3000"},
        {"symbol": "USDCUSDT", "quoteVolume": "100", "lastPrice": "0.999"},
        {"symbol": "FDUSDUSDT", "quoteVolume": "0.000001", "lastPrice": "1.0"},  # dust
    ]
    out = senti.stablecoin_proxy(tickers, {"BTCUSDT", "ETHUSDT", "USDCUSDT", "FDUSDUSDT"})
    assert out["stable_volume_share"] == pytest.approx(100.000001 / 1000.000001, abs=1e-4)
    assert out["usdc_usdt_ratio"] == pytest.approx(0.999)
    assert out["stable_pairs"][0]["s"] == "USDCUSDT"
    # rows outside the perp set are excluded (0-volume → honest None)
    empty = senti.stablecoin_proxy([{"symbol": "BTCUSDT", "quoteVolume": "5"}], {"ETHUSDT"})
    assert empty["stable_volume_share"] is None


def test_defillama_parse():
    payload = [
        {"name": "Tether", "circulating": {"peggedUSD": 100.0}},
        {"name": "USDC Coin", "circulating": {"peggedUSD": 50.0}},
        {"name": "junk", "circulating": 0},
    ]
    out = senti.parse_defillama(payload)
    assert out["total_mcap_usd"] == pytest.approx(150.0)
    assert out["usdt_share"] == pytest.approx(0.6667)  # 4 dp
    assert out["usdc_share"] == pytest.approx(0.3333)  # 4 dp
    assert senti.parse_defillama([]) == {"unavailable": "defillama_payload_empty"}


# ── spot lead/lag ─────────────────────────────────────────────────────────────
def test_spot_lead_classification():
    import math
    import random

    rng = random.Random(42)
    spot_rets = [rng.gauss(0, 0.001) for _ in range(40)]
    # perp[t+1] = spot[t] exactly → spot leads
    perp_rets = [0.001] + spot_rets[:-1]
    assert spot_mod.classify_lead(spot_rets, perp_rets) == "spot"
    # spot[t+1] = perp[t] exactly → perp leads
    assert spot_mod.classify_lead(perp_rets, spot_rets) == "perp"
    # identical series → symmetric → mixed
    assert spot_mod.classify_lead(spot_rets, list(spot_rets)) == "mixed"
    assert spot_mod.classify_lead([0.01] * 5, [0.01] * 5) is None  # too short
    flat = [0.001] * 30
    assert spot_mod.classify_lead(flat, list(flat)) is None        # zero variance


def test_spot_perp_block_math():
    spot_closes = [100.0 + i for i in range(49)]
    perp_closes = [200.0 + 2 * i for i in range(49)]
    blk = spot_mod.spot_perp_block(spot_closes, 148.0, 200.0 + 2 * 48, perp_closes)
    assert blk["premium_pct"] == pytest.approx((296.0 - 148.0) / 148.0 * 100, abs=1e-3)
    assert blk["ret_spread_48h"] == pytest.approx(0.0)  # identical 48h returns
    assert blk["lead"] in ("spot", "perp", "mixed")
    assert spot_mod.spot_perp_block([], None, 0.0, []) == {"unavailable": "insufficient_history"}


@pytest.mark.asyncio
async def test_spot_snapshot_no_spot_market_is_normal(monkeypatch):
    spot_mod.reset_cache()

    async def boom(path, params):
        raise RuntimeError('400, message=\'...\', url=... code=-1121, msg="Invalid symbol."')

    monkeypatch.setattr(spot_mod, "_spot_json", boom)
    out = await spot_mod.snapshot("ODDUSDT", 1.0, [1.0, 1.1])
    assert out == {"unavailable": "no_spot_market"}


# ── L/S term structure ────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_ratio_term_structure_sparse_history(monkeypatch):
    async def full(symbol, period, limit=48):
        return [1.1 + 0.001 * i for i in range(40)]  # ≥3 points → latest

    async def boom(symbol, period, limit=48):
        raise RuntimeError("451 geo-blocked")

    monkeypatch.setattr(rat, "global_account_ls", full)
    monkeypatch.setattr(rat, "top_position_ls", full)
    monkeypatch.setattr(rat, "taker_ls", boom)      # failures → None
    monkeypatch.setattr(rat, "top_account_ls", boom)

    out = await rat.ratio_term_structure("BTCUSDT")
    assert set(out["periods"]) == {"5m", "1h", "4h", "1d"}
    cell = out["periods"]["5m"]
    assert cell["glob"] == pytest.approx(round(1.1 + 0.001 * 39, 4))
    assert cell["pos"] == pytest.approx(round(1.1 + 0.001 * 39, 4))
    assert cell["acc"] is None and cell["taker"] is None
    assert out["cells_unavailable"] == 8  # 2 families × 4 periods


@pytest.mark.asyncio
async def test_ratio_term_structure_insufficient_history(monkeypatch):
    async def short(symbol, period, limit=48):
        return [1.0, 1.1]  # 2 points < min_history=3 → None

    for name in ("global_account_ls", "top_account_ls", "top_position_ls", "taker_ls"):
        monkeypatch.setattr(rat, name, short)
    out = await rat.ratio_term_structure("BTCUSDT")
    assert out["cells_unavailable"] == 16
    assert all(v is None for row in out["periods"].values() for v in row.values())


# ── Deribit parser (fixture payload) ──────────────────────────────────────────
_FIXTURE_ROWS = [
    # ~30 DTE chain (dte anchored to now_ms below): expiry 2026-10-12 ≈ 30d
    {"instrument_name": "BTC-12OCT26-60000-P", "open_interest": "120", "mark_iv": 55.0,
     "underlying_price": 60000.0},
    {"instrument_name": "BTC-12OCT26-60000-C", "open_interest": "80", "mark_iv": 56.0,
     "underlying_price": 60000.0},
    {"instrument_name": "BTC-12OCT26-70000-C", "open_interest": "10", "mark_iv": 60.0,
     "underlying_price": 60000.0},
    # ~7 DTE chain — NOT the 30d pick
    {"instrument_name": "BTC-19SEP26-60000-P", "open_interest": "500", "mark_iv": 70.0,
     "underlying_price": 60000.0},
    {"instrument_name": "BTC-19SEP26-60000-C", "open_interest": "500", "mark_iv": 71.0,
     "underlying_price": 60000.0},
]

NOW_MS = int(__import__("datetime").datetime(2026, 9, 12, tzinfo=__import__("datetime").timezone.utc)
             .timestamp() * 1000)


def test_deribit_parse_book_summary():
    out = drb.parse_book_summary(_FIXTURE_ROWS, "BTC", now_ms=NOW_MS)
    assert out["put_call_oi_ratio"] == pytest.approx(620 / 590, abs=1e-3)
    # ATM = strikes nearest 60000 on the ~30d expiry → mean(55, 56)
    assert out["atm_iv_30d"] == pytest.approx(55.5)
    assert "unavailable" not in out


def test_deribit_parse_book_summary_empty():
    assert drb.parse_book_summary([], "BTC", now_ms=NOW_MS) == {"unavailable": "no_options_data"}


def test_deribit_parse_index_price_and_instruments():
    assert drb.parse_index_price({"index_name": "btc_dvol", "price": "43.21"}) == pytest.approx(43.21)
    assert drb.parse_index_price({}) is None
    assert drb._parse_instrument("BTC-12OCT26-60000-P") is not None
    assert drb._parse_instrument("BTC-PERPETUAL") is None


# ── index constituents ────────────────────────────────────────────────────────
def test_index_info_parsers():
    info = [{"symbol": "btcdomusdt", "baseAssetList": [{"baseAsset": "btc"}, {"baseAsset": "eth"}]}]
    assert ii.parse_index_info(info) == {"BTCDOMUSDT": {"BTC", "ETH"}}
    cons = {"constituents": [{"symbol": "btcusdt"}, {"symbol": "ethusdt"}, {"symbol": None}]}
    assert ii.parse_constituents(cons) == {"BTCUSDT", "ETHUSDT"}


@pytest.mark.asyncio
async def test_index_verify_cluster_labels(monkeypatch):
    ii.reset_cache()
    membership = {"BTCDOMUSDT": {"BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"}}

    async def fake_membership():
        return membership

    monkeypatch.setattr(ii, "index_membership", fake_membership)
    out = await ii.verify_cluster_labels({1: ["BTCUSDT", "ETHUSDT", "SOLUSDT"], 2: ["DOGEUSDT"]})
    assert out[1] == {"index_verified": True, "verified_name": "BTCDOMUSDT"}
    assert out[2] == {"index_verified": False, "verified_name": None}


def test_index_membership_failure_returns_stale(monkeypatch):
    ii.reset_cache()

    async def boom():
        raise RuntimeError("unreachable")

    monkeypatch.setattr(ii, "_refresh", boom)
    import asyncio

    assert asyncio.run(ii.index_membership()) == {}


# ── errors are NEVER cached — failures re-attempt on the next call ────────────
@pytest.mark.asyncio
async def test_fear_greed_failure_not_cached(monkeypatch):
    senti.reset_cache()
    calls = []

    async def flaky(url, params=None, **kw):
        calls.append(url)
        if len(calls) == 1:
            raise RuntimeError("alternative.me down")
        return {"data": [{"value": "39", "value_classification": "Fear",
                          "timestamp": "1700000000"}]}

    monkeypatch.setattr(senti, "get_json", flaky)
    first = await senti.fear_greed()
    assert "unavailable" in first
    assert senti._fng_cache is None                    # failure must not poison the cache
    second = await senti.fear_greed()                  # re-attempted, not served stale
    assert second["value"] == 39 and len(calls) == 2
    third = await senti.fear_greed()
    assert third == second and len(calls) == 2         # only the SUCCESS holds the TTL cache


@pytest.mark.asyncio
async def test_defillama_failure_not_cached(monkeypatch):
    senti.reset_cache()
    attempts = []

    class _FakeResp:
        def __init__(self, payload):
            self._p = payload

        def raise_for_status(self):
            pass

        async def json(self, content_type=None):
            return self._p

    class _FakeCtx:
        def __init__(self, payload):
            self._p = payload

        async def __aenter__(self):
            return _FakeResp(self._p)

        async def __aexit__(self, *exc):
            return False

    class _FakeSession:
        def __init__(self, *a, **kw):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

        def get(self, url):
            attempts.append(url)
            if len(attempts) == 1:
                raise RuntimeError("llama.fi down")
            return _FakeCtx([{"name": "Tether", "circulating": {"peggedUSD": 100.0}}])

    monkeypatch.setattr(senti.aiohttp, "ClientSession", _FakeSession)
    first = await senti.defillama_stablecoins()
    assert "unavailable" in first and senti._defillama_cache is None  # not cached
    second = await senti.defillama_stablecoins()
    assert second["total_mcap_usd"] == pytest.approx(100.0) and len(attempts) == 2
    third = await senti.defillama_stablecoins()
    assert third == second and len(attempts) == 2                      # success cached


@pytest.mark.asyncio
async def test_spot_snapshot_failure_not_cached(monkeypatch):
    spot_mod.reset_cache()
    calls = []

    async def flaky(path, params):
        calls.append(path)
        if len(calls) == 1:
            raise RuntimeError("spot api down")
        if path == "/klines":
            return [[0, 0, 0, 0, "100.0", 0, 0]] * 10
        return {"lastPrice": "101.0"}

    monkeypatch.setattr(spot_mod, "_spot_json", flaky)
    first = await spot_mod.snapshot("BTCUSDT", 101.0, [])
    assert "unavailable" in first and "BTCUSDT" not in spot_mod._cache  # not cached
    second = await spot_mod.snapshot("BTCUSDT", 101.0, [])              # re-attempted
    assert "unavailable" not in second and second["premium_pct"] is not None
    calls_after_success = len(calls)
    third = await spot_mod.snapshot("BTCUSDT", 101.0, [])
    assert third == second and len(calls) == calls_after_success        # success cached


@pytest.mark.asyncio
async def test_orderbook_snapshot_failure_not_cached(monkeypatch):
    ob.reset_cache()
    calls = []

    async def flaky(symbol, limit=ob.DEPTH_LIMIT):
        calls.append(symbol)
        if len(calls) == 1:
            raise RuntimeError("depth endpoint down")
        return {"bids": [["100", "10"]], "asks": [["100.1", "10"]]}

    monkeypatch.setattr(ob, "fetch_depth", flaky)
    first = await ob.snapshot("BTCUSDT")
    assert "unavailable" in first and "BTCUSDT" not in ob._cache        # not cached
    second = await ob.snapshot("BTCUSDT")
    assert "imbalance" in second and len(calls) == 2                    # re-attempted
    third = await ob.snapshot("BTCUSDT")
    assert third == second and len(calls) == 2                          # success cached


# ── Deribit partial honesty + error caching ───────────────────────────────────
@pytest.mark.asyncio
async def test_deribit_partial_dvol_survives_summary_failure(monkeypatch):
    drb.reset_state()

    async def summary_leg_down(path, params):
        if "index_price" in path:
            return {"index_name": "btc_dvol", "price": "43.2"}
        raise RuntimeError("book summary 500")

    monkeypatch.setattr(drb, "_get", summary_leg_down)
    out = await drb.options_snapshot("BTC")
    # the real DVOL level must NOT be hidden behind an unavailable marker
    assert out["dvol_level"] == pytest.approx(43.2)
    assert out["put_call_oi_ratio"] is None and out["atm_iv_30d"] is None
    assert out["partial"] is True and "unavailable" not in out
    assert "generated_at" in out


@pytest.mark.asyncio
async def test_deribit_full_failure_not_cached_and_reattempted(monkeypatch):
    drb.reset_state()
    calls = []

    async def boom(path, params):
        calls.append(path)
        raise RuntimeError("deribit down")

    monkeypatch.setattr(drb, "_get", boom)
    first = await drb.options_snapshot("BTC")
    assert first == {"unavailable": "deribit_unreachable"}
    assert drb._cache == {}                                  # failure not cached
    second = await drb.options_snapshot("BTC")
    assert second == {"unavailable": "deribit_unreachable"}  # re-attempted
    assert len(calls) > 0


@pytest.mark.asyncio
async def test_deribit_success_cached_for_ttl(monkeypatch):
    drb.reset_state()
    calls = []

    async def fake_get(path, params):
        calls.append(path)
        if "index_price" in path:
            return {"index_name": "btc_dvol", "price": "43.2"}
        return _FIXTURE_ROWS

    monkeypatch.setattr(drb, "_get", fake_get)
    out1 = await drb.options_snapshot("BTC")
    out2 = await drb.options_snapshot("BTC")
    assert "unavailable" not in out1 and "partial" not in out1
    assert out1 == out2 and len(calls) == 2  # one dvol + one summary fetch total
