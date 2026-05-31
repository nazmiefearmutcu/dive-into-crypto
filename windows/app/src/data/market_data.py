"""Market data retrieval and OHLCV DataFrame construction."""

from typing import Any, Optional

import pandas as pd
import numpy as np

from src.api.binance_client import BinanceClient
from src.utils.logger import get_logger
from src.utils.validators import validate_timeframe

logger = get_logger("data.market_data")

KLINE_COLUMNS = [
    "open_time", "open", "high", "low", "close", "volume",
    "close_time", "quote_volume", "trades", "taker_buy_base",
    "taker_buy_quote", "ignore",
]


class MarketDataProvider:
    """Fetches and transforms market data into OHLCV DataFrames."""

    def __init__(self, binance_client: BinanceClient, config: dict[str, Any]) -> None:
        self.client = binance_client
        self.config = config
        timeframe = config.get("timeframe", "1h")
        if not isinstance(timeframe, str):
            raise ValueError(f"Invalid timeframe type: {type(timeframe).__name__}")
        timeframe = timeframe.strip()
        self.timeframe = "1M" if timeframe.upper() == "1M" else timeframe.lower()
        if not validate_timeframe(self.timeframe):
            raise ValueError(f"Invalid timeframe: {timeframe}")
        self.candle_limit = config.get("candle_limit", 200)

    def get_ohlcv(self, symbol: str) -> Optional[pd.DataFrame]:
        """Fetch OHLCV data for a symbol and return a clean DataFrame."""
        raw_klines = self.client.get_klines(
            symbol=symbol,
            interval=self.timeframe,
            limit=self.candle_limit,
        )

        if not raw_klines:
            logger.error(f"No kline data returned for {symbol}")
            return None

        try:
            df = pd.DataFrame(raw_klines, columns=KLINE_COLUMNS)

            for col in ["open", "high", "low", "close", "volume", "quote_volume",
                         "taker_buy_base", "taker_buy_quote"]:
                df[col] = pd.to_numeric(df[col], errors="coerce")

            df["trades"] = pd.to_numeric(df["trades"], errors="coerce").astype(int)
            df["open_time"] = pd.to_datetime(df["open_time"], unit="ms")
            df["close_time"] = pd.to_datetime(df["close_time"], unit="ms")

            df = df.drop(columns=["ignore"])
            df = df.dropna(subset=["open", "high", "low", "close", "volume"])
            df = df.reset_index(drop=True)

            logger.info(
                f"OHLCV loaded | {symbol} | {self.timeframe} | {len(df)} candles | "
                f"latest close={df['close'].iloc[-1]:.2f}"
            )
            return df

        except Exception as e:
            logger.error(f"Error processing OHLCV data for {symbol}: {e}")
            return None

    def get_current_price(self, symbol: str) -> Optional[float]:
        """Get the latest price for a symbol."""
        return self.client.get_ticker_price(symbol)

    def get_symbol_info(self, symbol: str) -> Optional[dict]:
        """Get symbol exchange info (filters, precision, etc.)."""
        return self.client.get_symbol_info(symbol)
