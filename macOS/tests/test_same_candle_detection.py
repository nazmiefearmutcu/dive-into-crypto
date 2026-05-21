"""Same-candle detection contract.

The bot loop ticks every `polling_interval_seconds`. On a 4h timeframe with
polling_interval_seconds=1, that's 14 400 ticks per candle. Re-running the
full indicator+consensus+decision stack on every tick is wasteful AND wrong
— it lets late-arriving data inside the same candle nudge decisions.

`_compute_candle_key(df)` is the boundary that fixes this:

  - It returns a stable key derived from the latest OHLCV row's
    `open_time` (or `close_time` as a fallback).
  - It must NOT rely on `df.index[-1]`, because `MarketDataProvider`
    calls `reset_index(drop=True)` and the index becomes a RangeIndex —
    `df.index[-1]` is then just `len(df) - 1`, an integer that is
    effectively constant across cycles. The pre-fix code did exactly
    that, which silently broke same-candle detection.

These tests pin the new contract directly.
"""

import pandas as pd

from src.services.bot_service import _compute_candle_key


def _make_df(open_times):
    """Tiny OHLCV stub — only the columns the candle-key reads."""
    n = len(open_times)
    return pd.DataFrame(
        {
            "open_time": pd.to_datetime(open_times),
            "close_time": pd.to_datetime(open_times) + pd.Timedelta(minutes=59),
            "open": [1.0] * n,
            "high": [1.0] * n,
            "low": [1.0] * n,
            "close": [1.0] * n,
            "volume": [1.0] * n,
        }
    )


class TestCandleKey:
    def test_key_uses_open_time_of_last_row(self):
        df = _make_df(["2026-01-01 00:00", "2026-01-01 01:00", "2026-01-01 02:00"])
        key = _compute_candle_key(df)
        # The key must be derived from the LAST open_time (02:00), not the first.
        assert "2026-01-01" in key
        assert "02:00" in key

    def test_same_dataframe_same_key(self):
        df = _make_df(["2026-01-01 00:00", "2026-01-01 01:00", "2026-01-01 02:00"])
        assert _compute_candle_key(df) == _compute_candle_key(df)

    def test_two_dataframes_same_latest_candle_same_key(self):
        df1 = _make_df(["2026-01-01 00:00", "2026-01-01 01:00", "2026-01-01 02:00"])
        df2 = _make_df(["2026-01-01 01:00", "2026-01-01 02:00"])  # different length
        # Same latest open_time → same key, regardless of length.
        assert _compute_candle_key(df1) == _compute_candle_key(df2)

    def test_advancing_candle_changes_key(self):
        df_before = _make_df(["2026-01-01 00:00", "2026-01-01 01:00"])
        df_after = _make_df(["2026-01-01 00:00", "2026-01-01 01:00", "2026-01-01 02:00"])
        assert _compute_candle_key(df_before) != _compute_candle_key(df_after)

    def test_post_reset_index_does_not_break_key(self):
        """The canonical regression case: MarketDataProvider calls
        reset_index(drop=True). The pre-fix code looked at `df.index[-1]`,
        which after reset is just `len(df)-1` — an integer that doesn't
        change between cycles. The new key MUST still vary on open_time."""
        df_before = _make_df(["2026-01-01 00:00", "2026-01-01 01:00"]).reset_index(drop=True)
        df_after = _make_df(["2026-01-01 00:00", "2026-01-01 01:00", "2026-01-01 02:00"]).reset_index(drop=True)
        assert _compute_candle_key(df_before) != _compute_candle_key(df_after)

    def test_falls_back_to_close_time_if_open_time_missing(self):
        """Defensive: if some upstream provider strips open_time, the helper
        falls back to close_time rather than crashing."""
        df = pd.DataFrame(
            {
                "close_time": pd.to_datetime(["2026-01-01 00:59", "2026-01-01 01:59"]),
                "close": [1.0, 1.0],
            }
        )
        key = _compute_candle_key(df)
        assert key  # non-empty
        assert "01:59" in key

    def test_handles_empty_dataframe(self):
        # Empty df — must return an empty/sentinel key, not crash.
        df = pd.DataFrame(columns=["open_time", "close_time", "close"])
        assert _compute_candle_key(df) == ""

    def test_key_does_not_reduce_to_rangeindex(self):
        """Hard regression guard: the key must NOT be derivable from
        `str(df.index[-1])` alone for a reset-indexed df. If two different
        candle-snapshots produce the same key just because they're the same
        length, that's the old bug back."""
        df_a = _make_df(["2026-01-01 00:00"] * 200)  # 200 rows, all same open_time
        df_b = _make_df(["2026-01-01 01:00"] * 200)  # 200 rows, all later
        df_a = df_a.reset_index(drop=True)
        df_b = df_b.reset_index(drop=True)
        # Same length, both reset-indexed — but latest open_time differs.
        assert _compute_candle_key(df_a) != _compute_candle_key(df_b)
        # And neither should be the raw RangeIndex stringification.
        assert _compute_candle_key(df_a) != "199"
        assert _compute_candle_key(df_b) != "199"
