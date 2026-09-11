"""Scan honesty: symbols whose data fetch fails are counted (droppedCount) and
logged with the reason — never silently folded into universeCount.
"""

import asyncio
import logging
from unittest.mock import patch

import pytest

from diveintocrypto_desktop.scan import scanner


def _tiny_candles(n: int = 5) -> list[dict]:
    return [{"t": i * 3_600_000_000_000, "o": 1.0, "h": 1.1, "l": 0.9, "c": 1.0, "v": 1.0} for i in range(n)]


def _all_tf(candles: list[dict]) -> dict[str, list[dict]]:
    """12-TF dict with too few candles for real signals (keeps assemble offline-fast)."""
    return {"1m": [], "3m": [], "5m": [], "15m": [], "30m": [], "1h": candles,
            "2h": [], "4h": [], "6h": [], "8h": [], "12h": [], "1d": candles}


@pytest.mark.asyncio
async def test_scan_counts_and_logs_dropped_symbols(caplog):
    universe = [
        {"s": "GOODUSDT", "name": "GOOD", "price": 1.0, "ch": 1.0, "quote_volume": 2.0},
        {"s": "BADUSDT", "name": "BAD", "price": 1.0, "ch": 1.0, "quote_volume": 1.0},
    ]

    async def fetch_all_tf(symbol, limit=300, intervals=None):
        if symbol == "BADUSDT":
            raise RuntimeError("binance 451 unreachable")
        return _all_tf(_tiny_candles())

    async def fake_universe(limit=None):
        return universe[:limit] if limit else universe

    async def no_divergence(symbol, candles_by_tf):
        return {}

    with patch.object(scanner.uni, "list_universe", fake_universe), \
         patch.object(scanner.kl, "fetch_all_tf", fetch_all_tf), \
         patch.object(scanner.sb, "_divergence_inputs", no_divergence):
        with caplog.at_level(logging.WARNING, logger="trading_bot.scan.scanner"):
            res = await scanner.scan(size=5, universe_limit=2)

    assert res["universeCount"] == 2      # full universe...
    assert res["scannedCount"] == 1       # ...but only one symbol actually scanned
    assert res["droppedCount"] == 1
    assert res["scanned"] == 12           # legacy field: 12 TF verdicts for 1 scanned row
    assert len(res["survivors"]) + len(res["eliminated"]) == 1
    assert res["survivors"][0]["s"] == "GOODUSDT"
    assert "_candles_by_tf" not in res["survivors"][0]

    drop_logs = [r for r in caplog.records if "BADUSDT" in r.getMessage()]
    assert drop_logs, "every dropped symbol must be logged"
    assert "binance 451 unreachable" in drop_logs[0].getMessage()


@pytest.mark.asyncio
async def test_scan_with_no_drops_reports_zero():
    async def fetch_all_tf(symbol, limit=300, intervals=None):
        return _all_tf(_tiny_candles())

    async def fake_universe(limit=None):
        return [{"s": "ONEUSDT", "name": "ONE", "price": 1.0, "ch": 0.0, "quote_volume": 1.0}][:limit]

    async def no_divergence(symbol, candles_by_tf):
        return {}

    with patch.object(scanner.uni, "list_universe", fake_universe), \
         patch.object(scanner.kl, "fetch_all_tf", fetch_all_tf), \
         patch.object(scanner.sb, "_divergence_inputs", no_divergence):
        res = await scanner.scan(size=5, universe_limit=1)

    assert res["droppedCount"] == 0 and res["scannedCount"] == 1
