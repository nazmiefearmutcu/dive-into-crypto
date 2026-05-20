"""Shared test fixtures."""

import pytest
import pandas as pd
import numpy as np
from pathlib import Path


@pytest.fixture
def sample_ohlcv_bullish() -> pd.DataFrame:
    """Generate a bullish trending OHLCV DataFrame for testing."""
    np.random.seed(42)
    n = 200
    base_price = 50000.0
    trend = np.linspace(0, 3000, n)  # Upward trend
    noise = np.random.normal(0, 200, n)
    closes = base_price + trend + noise

    data = {
        "open_time": pd.date_range("2024-01-01", periods=n, freq="1h"),
        "open": closes - np.random.uniform(50, 200, n),
        "high": closes + np.random.uniform(100, 500, n),
        "low": closes - np.random.uniform(100, 500, n),
        "close": closes,
        "volume": np.random.uniform(100, 10000, n),
        "close_time": pd.date_range("2024-01-01", periods=n, freq="1h") + pd.Timedelta(hours=1),
        "quote_volume": np.random.uniform(5000000, 50000000, n),
        "trades": np.random.randint(1000, 50000, n),
        "taker_buy_base": np.random.uniform(50, 5000, n),
        "taker_buy_quote": np.random.uniform(2500000, 25000000, n),
    }
    return pd.DataFrame(data)


@pytest.fixture
def sample_ohlcv_bearish() -> pd.DataFrame:
    """Generate a bearish trending OHLCV DataFrame for testing."""
    np.random.seed(123)
    n = 200
    base_price = 53000.0
    trend = np.linspace(0, -3000, n)  # Downward trend
    noise = np.random.normal(0, 200, n)
    closes = base_price + trend + noise

    data = {
        "open_time": pd.date_range("2024-01-01", periods=n, freq="1h"),
        "open": closes + np.random.uniform(50, 200, n),
        "high": closes + np.random.uniform(100, 500, n),
        "low": closes - np.random.uniform(100, 500, n),
        "close": closes,
        "volume": np.random.uniform(100, 10000, n),
        "close_time": pd.date_range("2024-01-01", periods=n, freq="1h") + pd.Timedelta(hours=1),
        "quote_volume": np.random.uniform(5000000, 50000000, n),
        "trades": np.random.randint(1000, 50000, n),
        "taker_buy_base": np.random.uniform(50, 5000, n),
        "taker_buy_quote": np.random.uniform(2500000, 25000000, n),
    }
    return pd.DataFrame(data)


@pytest.fixture
def sample_ohlcv_sideways() -> pd.DataFrame:
    """Generate a sideways/ranging OHLCV DataFrame for testing."""
    np.random.seed(77)
    n = 200
    base_price = 51000.0
    noise = np.random.normal(0, 300, n)
    closes = base_price + noise

    data = {
        "open_time": pd.date_range("2024-01-01", periods=n, freq="1h"),
        "open": closes - np.random.uniform(50, 200, n),
        "high": closes + np.random.uniform(100, 400, n),
        "low": closes - np.random.uniform(100, 400, n),
        "close": closes,
        "volume": np.random.uniform(100, 10000, n),
        "close_time": pd.date_range("2024-01-01", periods=n, freq="1h") + pd.Timedelta(hours=1),
        "quote_volume": np.random.uniform(5000000, 50000000, n),
        "trades": np.random.randint(1000, 50000, n),
        "taker_buy_base": np.random.uniform(50, 5000, n),
        "taker_buy_quote": np.random.uniform(2500000, 25000000, n),
    }
    return pd.DataFrame(data)


@pytest.fixture
def default_config() -> dict:
    """Load default config for testing."""
    import yaml
    config_path = Path(__file__).parent.parent / "config" / "default.yaml"
    with open(config_path) as f:
        return yaml.safe_load(f)
