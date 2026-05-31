"""Binance API client abstraction layer.

Provides a clean interface for market data retrieval and order execution.
Supports both spot and futures (architecture-ready), paper and live modes.
"""

import os
from typing import Any, Optional

from binance.client import Client
from binance.exceptions import BinanceAPIException, BinanceRequestException

from src.utils.logger import get_logger
from src.utils.helpers import retry_with_backoff

logger = get_logger("api.binance_client")


class BinanceClient:
    """Wrapper around python-binance Client with retry and error handling."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        mode = config.get("mode", "paper")
        self.mode = mode.strip().lower() if isinstance(mode, str) else "paper"
        market_type = config.get("market_type", "spot")
        self.market_type = market_type.strip().lower() if isinstance(market_type, str) else "spot"
        if self.mode not in {"paper", "live"}:
            raise ValueError(f"Invalid mode: {self.mode}")
        if self.market_type not in {"spot", "futures"}:
            raise ValueError(f"Invalid market_type: {self.market_type}")
        self._client: Optional[Client] = None
        self._initialized = False

    def initialize(self) -> None:
        """Initialize the Binance client connection."""
        if self.mode == "paper":
            api_key = os.getenv("BINANCE_API_KEY", "")
            api_secret = os.getenv("BINANCE_API_SECRET", "")
            if not api_key or not api_secret:
                logger.warning(
                    "No API keys found. Paper mode will use public endpoints for market data."
                )
                self._client = Client("", "")
            else:
                self._client = Client(api_key, api_secret)
        elif self.mode == "live":
            use_testnet = os.getenv("USE_TESTNET", "false").lower() == "true"
            if use_testnet:
                api_key = os.getenv("BINANCE_TESTNET_API_KEY", "")
                api_secret = os.getenv("BINANCE_TESTNET_API_SECRET", "")
                self._client = Client(api_key, api_secret, testnet=True)
                logger.info("Connected to Binance TESTNET")
            else:
                api_key = os.getenv("BINANCE_API_KEY", "")
                api_secret = os.getenv("BINANCE_API_SECRET", "")
                if not api_key or not api_secret:
                    raise ValueError(
                        "BINANCE_API_KEY and BINANCE_API_SECRET must be set for live mode"
                    )
                self._client = Client(api_key, api_secret)
                logger.info("Connected to Binance LIVE")

        self._initialized = True
        logger.info(f"BinanceClient initialized | mode={self.mode} | market={self.market_type}")

    @property
    def client(self) -> Client:
        """Get the underlying binance Client instance."""
        if not self._initialized or self._client is None:
            raise RuntimeError("BinanceClient not initialized. Call initialize() first.")
        return self._client

    def get_klines(
        self,
        symbol: str,
        interval: str,
        limit: int = 200,
    ) -> list[list]:
        """Fetch kline/candlestick data with retry logic.

        Uses futures endpoint when market_type is 'futures'.
        """
        def _fetch():
            if self.market_type == "futures":
                return self.client.futures_klines(
                    symbol=symbol,
                    interval=interval,
                    limit=limit,
                )
            return self.client.get_klines(
                symbol=symbol,
                interval=interval,
                limit=limit,
            )

        try:
            return retry_with_backoff(
                _fetch,
                max_retries=3,
                base_delay=2.0,
                exceptions=(BinanceAPIException, BinanceRequestException, Exception),
            )
        except Exception as e:
            logger.error(f"Failed to fetch klines for {symbol}: {e}")
            return []

    def get_ticker_price(self, symbol: str) -> Optional[float]:
        """Get the current price for a symbol."""
        try:
            if self.market_type == "futures":
                ticker = self.client.futures_symbol_ticker(symbol=symbol)
            else:
                ticker = self.client.get_symbol_ticker(symbol=symbol)
            return float(ticker["price"])
        except Exception as e:
            logger.error(f"Failed to get ticker price for {symbol}: {e}")
            return None

    def get_account_balance(self, asset: str = "USDT") -> float:
        """Get account balance for a specific asset."""
        try:
            if self.market_type == "futures":
                account = self.client.futures_account_balance()
                for balance in account:
                    if balance.get("asset") == asset:
                        amount = balance.get("availableBalance")
                        if amount is None:
                            amount = balance.get("balance")
                        return float(amount)
            else:
                account = self.client.get_account()
                for balance in account["balances"]:
                    if balance["asset"] == asset:
                        return float(balance["free"])
            return 0.0
        except Exception as e:
            logger.error(f"Failed to get account balance: {e}")
            return 0.0

    def get_symbol_info(self, symbol: str) -> Optional[dict]:
        """Get exchange info for a symbol (precision, filters, etc.)."""
        try:
            if self.market_type == "futures":
                info = self.client.futures_exchange_info()
                for s in info.get("symbols", []):
                    if s["symbol"] == symbol:
                        return s
                return None
            return self.client.get_symbol_info(symbol)
        except Exception as e:
            logger.error(f"Failed to get symbol info for {symbol}: {e}")
            return None

    def place_market_buy(self, symbol: str, quantity: float) -> Optional[dict]:
        """Place a market buy order."""
        if self.mode == "paper":
            logger.warning("place_market_buy called in paper mode - should use paper engine")
            return None
        try:
            if self.market_type == "futures":
                order = self.client.futures_create_order(
                    symbol=symbol,
                    side="BUY",
                    type="MARKET",
                    quantity=quantity,
                )
            else:
                order = self.client.order_market_buy(
                    symbol=symbol,
                    quantity=quantity,
                )
            logger.info(f"MARKET BUY executed | {symbol} | qty={quantity} | order_id={order['orderId']}")
            return order
        except BinanceAPIException as e:
            logger.error(f"Binance API error on market buy {symbol}: {e}")
            return None
        except Exception as e:
            logger.error(f"Unexpected error on market buy {symbol}: {e}")
            return None

    def place_market_sell(self, symbol: str, quantity: float) -> Optional[dict]:
        """Place a market sell order."""
        if self.mode == "paper":
            logger.warning("place_market_sell called in paper mode - should use paper engine")
            return None
        try:
            if self.market_type == "futures":
                order = self.client.futures_create_order(
                    symbol=symbol,
                    side="SELL",
                    type="MARKET",
                    quantity=quantity,
                )
            else:
                order = self.client.order_market_sell(
                    symbol=symbol,
                    quantity=quantity,
                )
            logger.info(f"MARKET SELL executed | {symbol} | qty={quantity} | order_id={order['orderId']}")
            return order
        except BinanceAPIException as e:
            logger.error(f"Binance API error on market sell {symbol}: {e}")
            return None
        except Exception as e:
            logger.error(f"Unexpected error on market sell {symbol}: {e}")
            return None

    def place_limit_buy(self, symbol: str, quantity: float, price: float) -> Optional[dict]:
        """Place a limit buy order."""
        if self.mode == "paper":
            return None
        try:
            if self.market_type == "futures":
                order = self.client.futures_create_order(
                    symbol=symbol,
                    side="BUY",
                    type="LIMIT",
                    timeInForce="GTC",
                    quantity=quantity,
                    price=str(price),
                )
            else:
                order = self.client.order_limit_buy(
                    symbol=symbol,
                    quantity=quantity,
                    price=str(price),
                )
            logger.info(f"LIMIT BUY placed | {symbol} | qty={quantity} | price={price}")
            return order
        except Exception as e:
            logger.error(f"Error placing limit buy {symbol}: {e}")
            return None

    def place_limit_sell(self, symbol: str, quantity: float, price: float) -> Optional[dict]:
        """Place a limit sell order."""
        if self.mode == "paper":
            return None
        try:
            if self.market_type == "futures":
                order = self.client.futures_create_order(
                    symbol=symbol,
                    side="SELL",
                    type="LIMIT",
                    timeInForce="GTC",
                    quantity=quantity,
                    price=str(price),
                )
            else:
                order = self.client.order_limit_sell(
                    symbol=symbol,
                    quantity=quantity,
                    price=str(price),
                )
            logger.info(f"LIMIT SELL placed | {symbol} | qty={quantity} | price={price}")
            return order
        except Exception as e:
            logger.error(f"Error placing limit sell {symbol}: {e}")
            return None

    def cancel_order(self, symbol: str, order_id: int) -> Optional[dict]:
        """Cancel an open order."""
        if self.mode == "paper":
            return None
        try:
            if self.market_type == "futures":
                result = self.client.futures_cancel_order(
                    symbol=symbol,
                    orderId=order_id,
                )
            else:
                result = self.client.cancel_order(
                    symbol=symbol,
                    orderId=order_id,
                )
            logger.info(f"Order cancelled | {symbol} | order_id={order_id}")
            return result
        except Exception as e:
            logger.error(f"Error cancelling order {order_id} for {symbol}: {e}")
            return None

    def get_futures_symbols(self, quote_asset: str = "USDT") -> list[str]:
        """Get all actively trading futures symbols for a quote asset."""
        try:
            info = self.client.futures_exchange_info()
            symbols = []
            for s in info.get("symbols", []):
                if (
                    s.get("quoteAsset") == quote_asset
                    and s.get("status") == "TRADING"
                    and s.get("contractType") == "PERPETUAL"
                ):
                    symbols.append(s["symbol"])
            logger.info(f"Fetched {len(symbols)} futures symbols for {quote_asset}")
            return sorted(symbols)
        except Exception as e:
            logger.error(f"Failed to fetch futures symbols: {e}")
            return []

    def get_order_status(self, symbol: str, order_id: int) -> Optional[dict]:
        """Check the status of an order."""
        try:
            if self.market_type == "futures":
                return self.client.futures_get_order(
                    symbol=symbol,
                    orderId=order_id,
                )
            return self.client.get_order(
                symbol=symbol,
                orderId=order_id,
            )
        except Exception as e:
            logger.error(f"Error getting order status {order_id}: {e}")
            return None
