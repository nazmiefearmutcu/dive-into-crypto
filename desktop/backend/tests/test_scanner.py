"""Scanner: offline net_nss() math + a small live scan over real Binance."""

import asyncio

import pytest

from diveintocrypto_desktop.data.http import close_session
from diveintocrypto_desktop.scan import scanner


def test_net_nss_picks_dominant_side():
    multi = [
        {"tf": "1d", "signal": "BUY", "confidence": 80},
        {"tf": "4h", "signal": "BUY", "confidence": 60},
        {"tf": "1h", "signal": "SELL", "confidence": 50},
        {"tf": "5m", "signal": "NEUTRAL", "confidence": 0},
    ]
    dom, nss = scanner.net_nss(multi)
    # buy: 80²·95/100 + 60²·75/100 = 6080 + 2700 = 8780 ; sell: 50²·58/100 = 1450
    assert dom == 1
    assert abs(nss - 8780.0) < 1.0


def test_net_nss_sell_dominant():
    multi = [
        {"tf": "1d", "signal": "SELL", "confidence": 90},
        {"tf": "1h", "signal": "BUY", "confidence": 40},
    ]
    dom, nss = scanner.net_nss(multi)
    assert dom == -1  # 90²·95/100 = 7695 > 40²·58/100 = 928


def test_rank_score_blends_divergence():
    row = {"netNss": 50.0, "dominantDir": 1, "divergence": {"score": 20.0}}
    # norm = 50/100*100 = 50 ; aligned div = 20*1 ; +0.35*20 = 7 → 57
    assert abs(scanner._rank_score(row, max_net=100.0) - 57.0) < 1e-6


@pytest.mark.live
def test_live_scan_small():
    async def run():
        try:
            res = await scanner.scan(size=5, universe_limit=12)
            assert res["universeCount"] == 12
            assert 0 < len(res["survivors"]) <= 5
            top = res["survivors"][0]
            assert top["s"].endswith("USDT")
            assert len(top["multiTf"]) == 12 and len(top["indicators"]) == 15
            assert top["finalSignal"] in {"STRONG_BUY", "BUY", "NEUTRAL", "SELL", "STRONG_SELL"}
            assert "_candles_by_tf" not in top  # transient cache stripped
        finally:
            await close_session()

    asyncio.run(run())
