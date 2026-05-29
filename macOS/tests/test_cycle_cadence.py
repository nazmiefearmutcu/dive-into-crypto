"""Cycle cadence split: don't recompute the indicator stack on every poll.

Goal pinned by S3 spec: with polling_interval=1s and timeframe=4h, the bot
must NOT re-run the full indicator / consensus / decision / execute pipeline
14 400 times per candle. Once a candle closes, indicators don't change — so
the pipeline can be reused until the next candle.

These tests drive `BotService._cycle()` with controlled fakes and pin:

  1. First cycle on a fresh candle: full pipeline runs, results are cached.
  2. Second cycle on the SAME candle: indicators/consensus/decision/execute
     are NOT called again — cached results are reused for the dashboard.
  3. Third cycle on a NEW candle: full pipeline runs again.
  4. LivePriceService is still refreshed every cycle (price cadence).
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import pandas as pd
import pytest
import yaml

from src.market.live_price_service import FakePriceAdapter, LivePriceService
from src.services.bot_service import BotService


def _df_for(open_time: str, n: int = 200, close: float = 65000.0) -> pd.DataFrame:
    """Tiny OHLCV stub whose last open_time controls the candle key."""
    times = pd.date_range(open_time, periods=n, freq="4h")[::-1][:n]
    times = list(reversed(times))
    # Make sure the LAST row has the requested open_time
    times[-1] = pd.Timestamp(open_time)
    return pd.DataFrame(
        {
            "open_time": times,
            "close_time": [t + pd.Timedelta(hours=4) for t in times],
            "open": [close] * n,
            "high": [close] * n,
            "low": [close] * n,
            "close": [close] * n,
            "volume": [1.0] * n,
            "quote_volume": [1.0] * n,
            "trades": [1] * n,
            "taker_buy_base": [1.0] * n,
            "taker_buy_quote": [1.0] * n,
        }
    )


@pytest.fixture
def bot(tmp_path):
    """A BotService with every external dependency mocked. We only care
    about the cycle wiring."""
    cfg = {
        "mode": "paper", "market_type": "spot", "timeframe": "4h",
        "polling_interval_seconds": 1, "candle_limit": 200,
        "_config_path": str(tmp_path / "config.yaml"),
        "active_symbol_path": str(tmp_path / "active_symbol.txt"),
        "state_path": str(tmp_path / "state.json"),
        "dashboard_status_path": str(tmp_path / "ds.json"),
        "auto_scan_enabled": False,
        "risk": {"max_open_positions": 1, "confidence_threshold": 55,
                  "stop_loss_pct": 0.025, "take_profit_pct": 0.05,
                  "trailing_stop_pct": 0.02, "trailing_stop_activation_pct": 0.03,
                  "risk_per_trade": 0.02, "daily_loss_limit_pct": 0.05,
                  "max_risk_level": "MEDIUM", "break_even_trigger_pct": 0.02},
        "paper": {"starting_balance": 10000.0, "fee_pct": 0.001},
        "indicator_weights": {"rsi": 1.5},
        "consensus": {"strong_buy_threshold": 1.2, "buy_threshold": 0.4,
                       "sell_threshold": -0.4, "strong_sell_threshold": -1.2,
                       "min_active_signals": 1, "conflict_ratio_threshold": 0.6},
        "no_trade": {"adx_min": 15, "atr_high_percentile": 95, "min_confidence": 40},
    }
    Path(cfg["_config_path"]).write_text(yaml.dump(cfg))
    Path(cfg["active_symbol_path"]).write_text("BTCUSDT\n")

    b = BotService(cfg)

    # Replace components with mocks AFTER construction so we don't bother
    # initializing the real Binance client.
    b.binance_client = MagicMock()
    b.binance_client.get_ticker_price = MagicMock(return_value=None)
    b.market_data = MagicMock()
    b.signal_service = MagicMock()
    b.signal_service.calculate_all = MagicMock(return_value=[])
    b.consensus_engine = MagicMock()
    b.consensus_engine.evaluate = MagicMock(return_value={
        "final_signal": "NEUTRAL", "confidence": 10, "risk_level": "LOW",
        "weighted_score": 0.0, "should_trade": False, "score_data": {},
    })
    b.decision_engine = MagicMock()
    b.decision_engine.decide = MagicMock(return_value={
        "action": "HOLD", "symbol": "BTCUSDT", "leverage": 1, "reason": "ok",
        "timestamp": "now",
    })
    b.execution_engine = MagicMock()
    b.execution_engine.execute = MagicMock(return_value={"executed": False})
    b.execution_engine.get_balance = MagicMock(return_value=10000.0)
    b.execution_engine.paper_balance = 10000.0
    b.position_manager.get_positions_dict = lambda: {}
    b.position_manager.trade_history = []
    b.state_store.update(active_symbol="BTCUSDT", positions={}, trade_history=[],
                          daily_pnl=0.0, total_realized_pnl=0.0,
                          daily_start_balance=10000.0)
    # Inject a fake price adapter to avoid the REST path entirely.
    fake_adapter = FakePriceAdapter()
    fake_adapter.set("BTCUSDT", price=65010.0)
    b.live_price_service = LivePriceService(fake_adapter)

    # Neutralize orthogonal cycle steps so cadence is the only thing under test.
    b._auto_scan_market = lambda: None  # type: ignore[assignment]
    b._update_active_coin_signals = lambda symbol: None  # type: ignore[assignment]
    b._check_other_positions = lambda symbol, balance: None  # type: ignore[assignment]
    b._process_close_commands = lambda: None  # type: ignore[assignment]
    # S5: queue-draining hook — neutralized in cadence test (queue is empty).
    b._process_pending_commands = lambda: None  # type: ignore[assignment]
    b._refresh_status_warnings = lambda: None  # type: ignore[assignment]
    b._export_dashboard_status = lambda balance: None  # type: ignore[assignment]

    return b, fake_adapter


class TestCadenceSplit:
    def test_first_cycle_runs_full_pipeline(self, bot):
        b, _ = bot
        b.market_data.get_ohlcv.return_value = _df_for("2026-01-01 00:00")
        b._cycle()
        assert b.signal_service.calculate_all.call_count == 1
        assert b.consensus_engine.evaluate.call_count == 1
        assert b.decision_engine.decide.call_count == 1
        assert b.execution_engine.execute.call_count == 1

    def test_same_candle_skips_indicator_stack(self, bot):
        b, _ = bot
        same_df = _df_for("2026-01-01 00:00")
        b.market_data.get_ohlcv.return_value = same_df

        b._cycle()           # cycle 1 — full pipeline
        b._cycle()           # cycle 2 — same candle, must reuse cache

        # Indicators / consensus / decision / execute called ONCE total.
        assert b.signal_service.calculate_all.call_count == 1
        assert b.consensus_engine.evaluate.call_count == 1
        assert b.decision_engine.decide.call_count == 1
        # Execute is the most expensive: also exactly one call.
        assert b.execution_engine.execute.call_count == 1

    def test_new_candle_runs_pipeline_again(self, bot):
        b, _ = bot
        b.market_data.get_ohlcv.return_value = _df_for("2026-01-01 00:00")
        b._cycle()
        # Advance to the next 4h candle.
        b.market_data.get_ohlcv.return_value = _df_for("2026-01-01 04:00")
        b._cycle()
        # Pipeline ran twice — once per candle.
        assert b.signal_service.calculate_all.call_count == 2
        assert b.consensus_engine.evaluate.call_count == 2
        assert b.decision_engine.decide.call_count == 2

    def test_live_price_service_refreshed_every_cycle(self, bot):
        b, adapter = bot
        b.market_data.get_ohlcv.return_value = _df_for("2026-01-01 00:00")

        # Spy on refresh: replace the service with one that counts calls.
        real_svc = b.live_price_service
        refresh_calls = {"n": 0}
        original_refresh = real_svc.refresh

        def counting_refresh(symbol):
            refresh_calls["n"] += 1
            return original_refresh(symbol)

        real_svc.refresh = counting_refresh  # type: ignore[assignment]

        b._cycle()
        b._cycle()
        b._cycle()
        # Price cadence is independent of signal cadence — refresh runs
        # every cycle even though indicators are only computed once.
        assert refresh_calls["n"] == 3
        assert b.signal_service.calculate_all.call_count == 1
