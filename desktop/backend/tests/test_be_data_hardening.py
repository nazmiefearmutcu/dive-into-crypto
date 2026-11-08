"""Regression tests for the data-layer and API hardening pass.

Offline: upstream is faked at the session / fetcher seam, never the network.
Each test pins one verified defect (see the function docstrings).
"""

import asyncio
import sys

import aiohttp
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from diveintocrypto_desktop import __main__ as entry
from diveintocrypto_desktop.api import app as app_mod
from diveintocrypto_desktop.data import basis as bs
from diveintocrypto_desktop.data import binance_klines as kl
from diveintocrypto_desktop.data import cvd as cvd_mod
from diveintocrypto_desktop.data import deribit as drb
from diveintocrypto_desktop.data import http as http_mod
from diveintocrypto_desktop.data import ratios as rat
from diveintocrypto_desktop.data import sentiment as senti
from diveintocrypto_desktop.data import universe as uni
from diveintocrypto_desktop.data.http import BinanceApiError, prune_oldest


# ── HTTP: Binance error bodies survive as typed errors ───────────────────────
class _Resp:
    def __init__(self, status: int, body=None) -> None:
        self.status = status
        self.headers: dict = {}
        self._body = body

    def raise_for_status(self) -> None:
        if self.status >= 400:
            raise aiohttp.ClientResponseError(request_info=None, history=(), status=self.status, message="Bad")

    async def json(self, content_type=None):
        if self._body is None:
            raise ValueError("not json")
        return self._body


class _Session:
    def __init__(self, resp: _Resp) -> None:
        self.resp = resp
        self.calls = 0

    def get(self, url, params=None):
        self.calls += 1
        resp = self.resp

        class _Ctx:
            async def __aenter__(self):
                return resp

            async def __aexit__(self, *exc):
                return None

        return _Ctx()


def _use_session(monkeypatch, session: _Session) -> None:
    async def fake_get_session():
        return session

    monkeypatch.setattr(http_mod, "get_session", fake_get_session)


@pytest.mark.asyncio
async def test_binance_400_body_code_is_kept_on_a_typed_error(monkeypatch):
    """Binance answers an invalid spot symbol with HTTP 400 + body code -1121. The
    code must survive the HTTP layer (aiohttp's ClientResponseError string drops it)."""
    session = _Session(_Resp(400, {"code": -1121, "msg": "Invalid symbol."}))
    _use_session(monkeypatch, session)
    with pytest.raises(BinanceApiError) as info:
        await http_mod.get_json("https://api.example/api/v3/klines", {"symbol": "ODDUSDT"})
    assert info.value.code == -1121 and info.value.status == 400
    assert session.calls == 1  # client errors are not retried


@pytest.mark.asyncio
async def test_error_without_binance_body_stays_a_client_response_error(monkeypatch):
    _use_session(monkeypatch, _Session(_Resp(404, None)))
    with pytest.raises(aiohttp.ClientResponseError):
        await http_mod.get_json("https://api.example/x")


# ── bounded symbol-keyed caches ───────────────────────────────────────────────
def test_prune_oldest_drops_the_stalest_timestamped_entries():
    cache = {f"S{i}": (float(i), {}) for i in range(10)}
    prune_oldest(cache, 4)
    assert sorted(cache) == ["S6", "S7", "S8", "S9"]


def test_prune_oldest_without_timestamps_drops_in_insertion_order():
    cache = {f"k{i}": i for i in range(6)}
    prune_oldest(cache, 2)
    assert list(cache) == ["k4", "k5"]


@pytest.mark.asyncio
async def test_cvd_cache_is_bounded_for_client_supplied_symbols(monkeypatch):
    """Arbitrary symbol strings must not grow the CVD cache without bound."""
    cvd_mod.reset_cache()

    async def fake_fetch(symbol, limit=1000):
        return [{"p": "1", "q": "1", "T": 1_700_000_000_000, "m": False}]

    monkeypatch.setattr(cvd_mod, "fetch_agg_trades", fake_fetch)
    for i in range(cvd_mod.CACHE_MAX + 20):
        await cvd_mod.snapshot(f"X{i}USDT")
    assert len(cvd_mod._cache) == cvd_mod.CACHE_MAX


@pytest.mark.asyncio
async def test_per_key_locks_drop_idle_keys_past_the_cap():
    locks = app_mod._PerKeyLocks()
    for i in range(app_mod._PerKeyLocks._MAX_KEYS + 50):
        async with locks.lock(f"K{i}"):
            pass  # released at once: every key is idle
    assert len(locks._by_loop[asyncio.get_running_loop()]) <= app_mod._PerKeyLocks._MAX_KEYS


@pytest.mark.asyncio
async def test_per_key_locks_never_prune_a_key_with_a_queued_waiter():
    """A waiter queued behind a holder keeps its key: pruning must not split the
    single-flight lock (a fresh lock would let the waiter run concurrently)."""
    locks = app_mod._PerKeyLocks()
    loop = asyncio.get_running_loop()
    for i in range(app_mod._PerKeyLocks._MAX_KEYS):  # fill the table to the cap
        async with locks.lock(f"K{i}"):
            pass

    entered = asyncio.Event()
    release = asyncio.Event()

    async def holder() -> None:
        async with locks.lock("K0"):
            entered.set()
            await release.wait()

    async def waiter() -> str:
        async with locks.lock("K0"):
            return "ran"

    holder_task = asyncio.create_task(holder())
    await entered.wait()
    waiter_task = asyncio.create_task(waiter())
    await asyncio.sleep(0.01)  # the waiter is now queued on K0's lock

    async with locks.lock("NEW"):  # a new key forces the idle-key prune
        pass
    assert "K0" in locks._by_loop[loop]  # held + queued: must survive the prune

    release.set()
    assert await waiter_task == "ran"
    await holder_task


@pytest.mark.asyncio
async def test_upstream_failures_are_described_by_stable_codes():
    assert http_mod.describe_failure(RuntimeError("binance raw text")) == "upstream_unavailable"

    class _Status(Exception):
        def __init__(self, status: int) -> None:
            self.status = status

    assert http_mod.describe_failure(_Status(451)) == "region_blocked"
    assert http_mod.describe_failure(_Status(403)) == "forbidden"
    assert http_mod.describe_failure(_Status(503)) == "upstream_unavailable (503)"
    binance_block = BinanceApiError(451, 0, "Service unavailable from a restricted location")
    assert http_mod.describe_failure(binance_block) == "region_blocked"
    assert "restricted" not in http_mod.describe_failure(binance_block)


# ── klines: sibling requests are cancelled on the first failure ──────────────
@pytest.mark.asyncio
async def test_fetch_all_tf_cancels_siblings_after_first_failure(monkeypatch):
    cancelled: list[str] = []

    class _Boom(Exception):
        pass

    async def fake_fetch_klines(symbol, tf, limit, end_ms=None):
        if tf == "1m":
            raise _Boom("429 retries exhausted")
        try:
            await asyncio.sleep(3600)
        except asyncio.CancelledError:
            cancelled.append(tf)
            raise
        return []

    monkeypatch.setattr(kl, "fetch_klines", fake_fetch_klines)
    with pytest.raises(_Boom):
        await kl.fetch_all_tf("BTCUSDT", limit=300, intervals=["1m", "3m", "5m"])
    assert sorted(cancelled) == ["3m", "5m"]


# ── basis: premiumIndexKlines is a fraction upstream; the series is percent ──
@pytest.mark.asyncio
async def test_premium_index_klines_is_converted_to_percent(monkeypatch):
    async def fake_get_json(url, params=None, **kw):
        # live shape: [openTime, open, high, low, close, ...] with the premium as a fraction
        return [[1794085200000, "-0.00045405", "0", "0", "0"], [1794088800000, None, "0", "0", "0"]]

    monkeypatch.setattr(bs, "get_json", fake_get_json)
    rows = await bs.premium_index_klines("BTCUSDT")
    assert rows == [{"t": 1794085200000, "premium_pct": pytest.approx(-0.045405)}]


def test_basis_zscore_compares_like_with_like_in_percent():
    """Current premium (percent) against a percent series of the same scale gives a
    sane z-score; a fraction series would put it about 100x off."""
    series_frac = [-0.00045 + 0.00001 * (i % 5) for i in range(60)]  # as Binance returns it
    current_pct = (75874.52 - 75908.12) / 75908.12 * 100.0  # live BTCUSDT premium, percent
    z = bs.basis_zscore([x * 100.0 for x in series_frac], current_pct)
    assert z is not None and abs(z) < 3.0


# ── ratios: t and v stay index-aligned on a bad row ──────────────────────────
@pytest.mark.asyncio
async def test_position_ls_timeseries_keeps_timestamps_aligned(monkeypatch):
    rows = [
        {"longShortRatio": "1.1", "timestamp": 1000},
        {"longShortRatio": "1.2", "timestamp": "bad"},
        {"longShortRatio": "1.3", "timestamp": 3000},
    ]

    async def fake_get_json(url, params=None, **kw):
        return rows

    monkeypatch.setattr(rat, "get_json", fake_get_json)
    out = await rat.position_ls_timeseries("BTCUSDT", "1h")
    assert out == {"t": [1000, 3000], "v": [1.1, 1.3]}


# ── sentiment ────────────────────────────────────────────────────────────────
def test_defillama_parse_accepts_the_live_wrapper_shape():
    """The live endpoint answers {"peggedAssets": [...]}; that wrapper must parse."""
    payload = {"peggedAssets": [
        {"name": "Tether", "symbol": "USDT", "circulating": {"peggedUSD": 100.0}},
        {"name": "USD Coin", "symbol": "USDC", "circulating": {"peggedUSD": 50.0}},
    ]}
    out = senti.parse_defillama(payload)
    assert out["total_mcap_usd"] == pytest.approx(150.0)
    assert out["usdt_share"] == pytest.approx(0.6667)
    assert out["usdc_share"] == pytest.approx(0.3333)  # live name is "USD Coin"
    assert senti.parse_defillama({"peggedAssets": []}) == {"unavailable": "defillama_payload_empty"}
    assert senti.parse_defillama({}) == {"unavailable": "defillama_payload_empty"}


@pytest.mark.asyncio
async def test_macro_snapshot_universe_failure_keeps_the_other_blocks(monkeypatch):
    """A failed ticker/exchangeInfo fetch degrades the stablecoin block only."""

    async def boom_tickers():
        raise RuntimeError("binance tickers down")

    async def perps():
        return {"BTCUSDT": {"base": "BTC", "quote": "USDT"}}

    async def fng():
        return {"value": 40, "classification": "Fear", "updated_at": "x", "cadence": "daily"}

    async def llama():
        return {"total_mcap_usd": 1.0, "usdt_share": 0.0, "usdc_share": 0.0, "top": []}

    monkeypatch.setattr(uni, "all_tickers", boom_tickers)
    monkeypatch.setattr(uni, "perp_symbols", perps)
    monkeypatch.setattr(senti, "fear_greed", fng)
    monkeypatch.setattr(senti, "defillama_stablecoins", llama)
    out = await senti.macro_snapshot()
    assert out["fng"]["value"] == 40
    assert out["defillama"]["total_mcap_usd"] == 1.0
    assert "unavailable" in out["stablecoin"]


# ── deribit ──────────────────────────────────────────────────────────────────
def test_book_summary_non_list_payload_is_unavailable_not_a_crash():
    assert drb.parse_book_summary({"result": "oops"}, "BTC") == {"unavailable": "no_options_data"}
    assert drb.parse_book_summary(["junk", 3, None], "BTC") == {"unavailable": "no_options_data"}


@pytest.mark.asyncio
async def test_options_snapshot_malformed_summary_degrades_to_partial(monkeypatch):
    """A malformed book summary must keep the real DVOL level, not raise (500)."""
    drb.reset_state()

    async def fake_get(path, params):
        if "index_price" in path:
            return {"index_price": 40.0, "estimated_delivery_price": 40.0}
        return {"unexpected": "shape"}

    monkeypatch.setattr(drb, "_get", fake_get)
    out = await drb.options_snapshot("BTC")
    assert out["dvol_level"] == pytest.approx(40.0)
    assert out["partial"] is True and "unavailable" not in out


# ── API: validation and the cross-origin guard ───────────────────────────────
def test_symbol_end_ms_zero_is_refused_so_it_cannot_share_the_live_cache_slot():
    client = TestClient(app_mod.create_app())
    assert client.get("/api/symbol/BTCUSDT?end_ms=0").status_code == 422


def test_limit_parameters_are_validated():
    client = TestClient(app_mod.create_app())
    assert client.get("/api/universe?limit=0").status_code == 422
    assert client.get("/api/universe?limit=-1").status_code == 422
    assert client.get("/api/leaders?limit=-1").status_code == 422
    assert client.get("/api/leaders?limit=0").status_code == 422


def _patch_grade(monkeypatch, calls: list) -> None:
    async def fake_grade(horizon):
        calls.append(horizon)
        return {"graded": 0, "horizon": horizon, "remaining": 0}

    monkeypatch.setattr(app_mod.evidence, "grade", fake_grade)
    monkeypatch.setattr(app_mod.evidence, "summary", lambda horizon: {"horizon": horizon})


def test_cross_site_post_is_refused_before_the_route_runs(monkeypatch):
    calls: list = []
    _patch_grade(monkeypatch, calls)
    client = TestClient(app_mod.create_app())
    r = client.post("/api/evidence/grade?horizon=4h", headers={"Origin": "https://evil.example"})
    assert r.status_code == 403
    assert calls == []


def test_loopback_origin_and_non_browser_clients_can_still_post(monkeypatch):
    calls: list = []
    _patch_grade(monkeypatch, calls)
    client = TestClient(app_mod.create_app())
    assert client.post("/api/evidence/grade?horizon=4h",
                       headers={"Origin": "http://127.0.0.1:8780"}).status_code == 200
    assert client.post("/api/evidence/grade?horizon=4h").status_code == 200  # no Origin: local tool
    assert calls == ["4h", "4h"]


def test_cross_site_websocket_handshake_is_refused():
    client = TestClient(app_mod.create_app())
    with pytest.raises(WebSocketDisconnect):  # closed before accept: no frame is ever delivered
        with client.websocket_connect("/api/live", headers={"Origin": "https://evil.example"}) as ws:
            ws.receive_json()


# ── --allow-remote: same-origin LAN UI is allowed, foreign origins are not ───
_LAN = "192.168.1.5"


def _lan_client(monkeypatch, calls: list) -> TestClient:
    _patch_grade(monkeypatch, calls)
    monkeypatch.setattr(app_mod, "_BIND_HOST", _LAN)  # what `--host 192.168.1.5` configures
    return TestClient(app_mod.create_app())


def test_lan_same_origin_post_is_allowed_for_the_bound_address(monkeypatch):
    calls: list = []
    client = _lan_client(monkeypatch, calls)
    r = client.post("/api/evidence/grade?horizon=4h", headers={
        "Host": f"{_LAN}:8780", "Origin": f"http://{_LAN}:8780",
    })
    assert r.status_code == 200 and calls == ["4h"]


def test_same_origin_post_from_a_foreign_host_name_is_refused(monkeypatch):
    calls: list = []
    client = _lan_client(monkeypatch, calls)
    # DNS rebinding: evil.example resolves to the LAN address, so Origin == Host
    r = client.post("/api/evidence/grade?horizon=4h", headers={
        "Host": "evil.example:8780", "Origin": "http://evil.example:8780",
    })
    assert r.status_code == 403 and calls == []


def test_foreign_origin_is_refused_even_on_a_lan_host(monkeypatch):
    calls: list = []
    client = _lan_client(monkeypatch, calls)
    r = client.post("/api/evidence/grade?horizon=4h", headers={
        "Host": f"{_LAN}:8780", "Origin": "http://evil.example:8780",
    })
    assert r.status_code == 403 and calls == []


def test_origin_rule_pure_cases(monkeypatch):
    monkeypatch.setattr(app_mod, "_BIND_HOST", _LAN)
    allowed = app_mod._origin_allowed
    assert allowed(f"http://{_LAN}:8780", f"{_LAN}:8780")            # same-origin LAN UI
    assert not allowed(f"https://{_LAN}:8780", f"{_LAN}:8780")       # scheme must match http
    assert not allowed(f"http://{_LAN}:9999", f"{_LAN}:8780")        # port is part of the authority
    assert not allowed("http://evil.example:8780", "evil.example:8780")  # Host is not ours
    assert not allowed(f"http://{_LAN}:8780", "evil.example:8780")   # foreign Host
    assert not allowed("null", f"{_LAN}:8780")                        # opaque origin
    assert not allowed(f"http://{_LAN}:8780", None)                   # no Host, no proof


def test_browser_url_maps_wildcard_and_ipv6_binds():
    assert entry._browser_url("127.0.0.1", 8780) == "http://127.0.0.1:8780/"
    assert entry._browser_url("localhost", 8780) == "http://localhost:8780/"
    assert entry._browser_url("0.0.0.0", 8780) == "http://127.0.0.1:8780/"
    assert entry._browser_url("::", 8780) == "http://[::1]:8780/"
    assert entry._browser_url("::1", 8780) == "http://[::1]:8780/"


def test_a_wildcard_bind_adds_no_address_to_the_origin_rule(monkeypatch):
    monkeypatch.setattr(app_mod, "_BIND_HOST", None)
    app_mod.configure_bind_host("0.0.0.0")
    assert app_mod._BIND_HOST is None
    assert not app_mod._origin_allowed("http://0.0.0.0:8780", "0.0.0.0:8780")
    app_mod.configure_bind_host(_LAN)
    assert app_mod._BIND_HOST == _LAN
    app_mod._BIND_HOST = None  # restore the default for the rest of the suite


def test_cross_site_get_under_api_is_refused_and_same_origin_get_passes(monkeypatch):
    monkeypatch.setattr(app_mod.evidence, "summary", lambda horizon: {"horizon": horizon})
    client = TestClient(app_mod.create_app())
    assert client.get("/api/evidence/stability",
                      headers={"Sec-Fetch-Site": "cross-site"}).status_code == 403
    assert client.get("/api/evidence/stability",
                      headers={"Sec-Fetch-Site": "same-origin"}).status_code == 200
    assert client.get("/api/evidence/stability").status_code == 200  # no Fetch Metadata: local tool


# ── __main__: a non-loopback bind needs an explicit opt-in ───────────────────
def test_non_loopback_host_requires_explicit_allow_remote(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["dive-desktop", "--host", "0.0.0.0", "--no-open"])
    with pytest.raises(SystemExit) as info:
        entry.main()
    assert info.value.code == 2
    assert "--allow-remote" in capsys.readouterr().err


def test_loopback_host_starts_without_the_flag(monkeypatch):
    import uvicorn

    seen: dict = {}
    monkeypatch.setattr(uvicorn, "run", lambda app, **kw: seen.update(kw))
    monkeypatch.setattr(sys, "argv", ["dive-desktop", "--no-open"])
    entry.main()
    assert seen["host"] == "127.0.0.1"
