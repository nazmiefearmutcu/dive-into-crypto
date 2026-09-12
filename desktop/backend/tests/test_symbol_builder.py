"""Symbol builder: offline assemble() on synthetic inputs + live build_symbol()."""

import asyncio
import math

import pytest

from diveintocrypto_desktop.data.http import close_session
from diveintocrypto_desktop.scan import symbol_builder as sb
from diveintocrypto_desktop.scan.constants import ALL_TFS

_SERIES_KEYS = {"oi", "glob", "acc", "pos", "taker", "funding", "price", "bias"}


def _candles(n=300):
    rows, price = [], 100.0
    for i in range(n):
        close = max(price + math.sin(i / 9.0) * 0.7 + 0.04, 1.0)
        rows.append(
            {"t": i * 3_600_000_000_000, "o": price, "h": max(price, close) + 0.3,
             "l": min(price, close) - 0.3, "c": close, "v": 1000 + i}
        )
        price = close
    return rows


def test_assemble_produces_full_data_contract():
    candles_by_tf = {tf: _candles() for tf in ALL_TFS}
    series_data = {k: [1.0 + 0.01 * i for i in range(48)] for k in ("oi", "glob", "acc", "pos", "taker", "price")}
    series_data["funding"] = [0.0001] * 48
    div_inputs = {"1d": ([100 + i for i in range(40)], [1.5 - i * 0.01 for i in range(40)])}

    from diveintocrypto_desktop.engine.loader import load_config
    from diveintocrypto_desktop.engine.signal_service import SignalService
    expected_indicator_count = len(SignalService(load_config()).indicators)

    obj = sb.assemble("BTCUSDT", "Bitcoin", 2.5, 68000.0, candles_by_tf, series_data, div_inputs)

    assert obj["s"] == "BTCUSDT" and obj["name"] == "Bitcoin"
    assert len(obj["multiTf"]) == 12
    assert all(m["signal"] in {"STRONG_BUY", "BUY", "NEUTRAL", "SELL", "STRONG_SELL"} for m in obj["multiTf"])
    assert obj["buy"] + obj["sell"] + obj["neutral"] == 12
    assert len(obj["indicators"]) == expected_indicator_count
    assert {i["name"] for i in obj["indicators"]} >= {"rsi", "macd", "adx_di"}
    assert obj["finalSignal"] in {"STRONG_BUY", "BUY", "NEUTRAL", "SELL", "STRONG_SELL"}
    assert obj["action"] in {"AL", "SAT", "BEKLE"}
    assert obj["risk"] in {"LOW", "MEDIUM", "HIGH"}
    assert set(obj["series"]) == _SERIES_KEYS
    assert len(obj["series"]["bias"]) == 48
    assert obj["whaleRegime"] in {"confirm", "adverse", "neutral"}
    assert "score" in obj["divergence"] and "coverage" in obj["divergence"]


@pytest.mark.live
def test_live_build_symbol_btc():
    from diveintocrypto_desktop.engine.loader import load_config
    from diveintocrypto_desktop.engine.signal_service import SignalService
    expected_indicator_count = len(SignalService(load_config()).indicators)

    async def run():
        try:
            obj = await sb.build_symbol("BTCUSDT")
            assert obj["s"] == "BTCUSDT"
            assert obj["price"] > 0
            assert len(obj["multiTf"]) == 12 and len(obj["indicators"]) == expected_indicator_count
            assert len(obj["candles"]) > 0
            assert any(len(obj["series"][k]) > 0 for k in ("oi", "pos", "taker"))
        finally:
            await close_session()

    asyncio.run(run())


# ── v0.3 additive contract fields ─────────────────────────────────────────────
def test_assemble_v3_additive_blocks():
    candles_by_tf = {tf: _candles() for tf in ALL_TFS}
    series_data = {k: [1.0 + 0.01 * i for i in range(48)] for k in ("oi", "glob", "acc", "pos", "taker", "price")}
    series_data["funding"] = [0.0001] * 48
    div_inputs = {"1d": ([100 + i for i in range(40)], [1.5 - i * 0.01 for i in range(40)])}

    obj = sb.assemble("BTCUSDT", "Bitcoin", 2.5, 68000.0, candles_by_tf, series_data, div_inputs)
    # divergence tier annotates every row
    assert obj["divergence_tier"] in {"NONE", "WEAK", "MODERATE", "STRONG"}
    # planning strip: informational, honest when ATR missing
    planning = obj["planning"]
    if "unavailable" in planning:
        assert planning["unavailable"] == "atr_missing"
    else:
        assert planning["sl_distance_pct"] == pytest.approx(1.5 * planning["atr_pct"], abs=1e-3)  # 4 dp rounding
        assert planning["tp_2r_pct"] == pytest.approx(2 * planning["tp_1r_pct"])
        assert planning["envelope"]["h24"] > planning["envelope"]["h1"]
    # vol term structure from the 1h candles it already has
    vol = obj["vol"]
    if "unavailable" in vol:
        assert vol["unavailable"] == "insufficient_history"
    else:
        assert set(vol["curve"]) == {"h1", "h4", "h12", "h24"}
        assert vol["curve"]["h1"] > 0
    # cascade proxy over the fetched OI series (present here)
    assert "cascade" in obj and ("proxy" in obj["cascade"] or "unavailable" in obj["cascade"])
    # weights hash = sha of the shipped weights map (replay keystone)
    from diveintocrypto_desktop.engine.loader import load_config
    from diveintocrypto_desktop.scan.evidence import weights_hash
    assert obj["weights_hash"] == weights_hash(load_config().get("indicator_weights", {}))


def test_assemble_v3_without_oi_omits_cascade_and_panel_extras():
    candles_by_tf = {"1h": _candles()}
    obj = sb.assemble("BTCUSDT", "Bitcoin", 1.0, 100.0, candles_by_tf, {}, {})
    assert "cascade" not in obj          # no OI fetched → field omitted entirely
    assert "book" not in obj and "ls_term" not in obj  # panel extras passed separately
    assert obj["divergence_tier"] == "NONE"


def test_record_from_row_v2_schema_fields():
    from diveintocrypto_desktop.scan.evidence import record_from_row
    row = {
        "s": "BTCUSDT", "finalSignal": "BUY", "confidence": 70, "risk": "LOW",
        "netNss": 10.0, "dominantDir": 1, "price": 100.0, "whaleRegime": "confirm",
        "multiTf": [{"tf": "1d", "signal": "BUY", "confidence": 80}], "indicators": [],
        "regime": {"regime": "TREND"}, "microstructure": {"score": 33.0},
        "mtfConfluence": {"gate": True},
        "divergence": {"score": 30.0, "tf": "1d", "coverage": 2},
        "cluster_id": 3, "cluster_agreement": 0.667, "weights_hash": "abc123",
    }
    rec = record_from_row(row, ts_ms=1_700_000_000_000)
    assert rec["regime"] == "TREND"
    assert rec["micro_score"] == 33.0
    assert rec["mtf_gate"] is True
    assert rec["divergence_score"] == 30.0
    assert rec["divergence_coverage"] == 2
    assert rec["divergence_tier"] == "MODERATE"  # 25 ≤ 30 < 55
    assert rec["cluster_id"] == 3 and rec["cluster_agreement"] == 0.667
    assert rec["weights_hash"] == "abc123"


def test_record_from_row_legacy_row_has_null_v2_fields():
    from diveintocrypto_desktop.scan.evidence import record_from_row
    row = {"s": "OLD", "finalSignal": "SELL", "confidence": 10, "netNss": 1.0,
           "dominantDir": -1, "price": 5.0, "multiTf": [], "indicators": []}
    rec = record_from_row(row, ts_ms=1)
    assert rec["regime"] is None and rec["divergence_tier"] is None
    assert rec["weights_hash"] is None and rec["mtf_gate"] is None


# ── historical view (end_ms): no live current-state extras ────────────────────
END_MS = 1_700_000_000_000  # historical "as of" instant (ms)


def _hist_candles(n: int, step_ms: int) -> list[dict]:
    """Candles strictly BEFORE ``END_MS`` (ns timestamps, like the real feed)."""
    rows, price = [], 100.0
    for i in range(n):
        close = price + 0.05
        t_ms = END_MS - (n - i) * step_ms
        rows.append({"t": t_ms * 1_000_000, "o": price, "h": max(price, close) + 0.3,
                     "l": min(price, close) - 0.3, "c": close, "v": 1000 + i})
        price = close
    return rows


@pytest.mark.asyncio
async def test_build_symbol_historical_view_has_no_live_extras(monkeypatch):
    candles_by_tf = {"1h": _hist_candles(120, 3_600_000), "5m": _hist_candles(200, 300_000)}
    last_5m_close = candles_by_tf["5m"][-1]["c"]

    async def fake_all_tf(symbol, limit=300, intervals=None, end_ms=None):
        assert end_ms == END_MS
        return candles_by_tf

    async def fake_oi(symbol, period="5m", limit=48):
        return [{"t": END_MS * 1_000_000 - i * 300_000_000_000, "oi": 100.0 + i} for i in range(48)]

    async def fake_ratios(symbol, period="5m", limit=48):
        return {}

    async def fake_funding(symbol, limit=48):
        return [{"funding_rate": 0.0001} for _ in range(48)]

    async def fake_div_inputs(symbol, candles):
        return {}

    calls: list[str] = []

    def live_feed(name):
        async def boom(*a, **kw):
            calls.append(name)  # recorded even if the caller swallows the raise
            raise AssertionError(f"historical view must not call live feed {name}")

        return boom

    monkeypatch.setattr(sb.kl, "fetch_all_tf", fake_all_tf)
    monkeypatch.setattr(sb.oi_mod, "fetch_oi_hist", fake_oi)
    monkeypatch.setattr(sb.rat, "fetch_ratio_series", fake_ratios)
    monkeypatch.setattr(sb.fnd, "funding_hist", fake_funding)
    monkeypatch.setattr(sb, "_divergence_inputs", fake_div_inputs)
    # every LIVE current-state feed: fetching it for end_ms is a leak
    monkeypatch.setattr(sb, "_ticker_24hr", live_feed("ticker_24hr"))
    monkeypatch.setattr(sb.cvd_mod, "snapshot", live_feed("cvd.snapshot"))
    monkeypatch.setattr(sb.fnd, "premium_index", live_feed("premium_index"))
    monkeypatch.setattr(sb.bs, "premium_index_klines", live_feed("premium_index_klines"))
    monkeypatch.setattr(sb.bs, "delivery_symbols", live_feed("delivery_symbols"))
    monkeypatch.setattr(sb.ob, "snapshot", live_feed("ob.snapshot"))
    monkeypatch.setattr(sb.rat, "ratio_term_structure", live_feed("ratio_term_structure"))
    monkeypatch.setattr(sb.spot_mod, "snapshot", live_feed("spot.snapshot"))

    obj = await sb.build_symbol("BTCUSDT", end_ms=END_MS)

    assert calls == []  # none of the live feeds were even fetched
    live_extras = ("funding_lens", "book", "ls_term", "spot_perp", "basis")
    for key in live_extras + ("cvd",):
        if key in obj:  # only an explicit-unavailable marker is allowed
            assert "unavailable" in obj[key], f"live {key} leaked into historical view"
    assert obj["cvd"] == {"unavailable": "historical_view"}
    assert "ch" not in obj                       # live 24h change omitted
    assert obj["price"] == pytest.approx(last_5m_close)  # honest truncated-window close
    assert "historical" in obj["price_note"] and "end_ms" in obj["price_note"]
    # klines are truncated at end_ms (primary-TF candles never cross it)
    assert all(c["t"] // 1_000_000 <= END_MS for c in obj["candles"])
