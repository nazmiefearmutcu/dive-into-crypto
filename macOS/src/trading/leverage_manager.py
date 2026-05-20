"""Dynamic leverage manager - scales leverage based on confidence and exchange limits.

Reference scale (from BTC):
  - BTC max leverage on Binance Futures = 125x
  - At 100% confidence -> 100x leverage (80% of max)
  - Scale ratio = 100/125 = 0.80
  - Formula: leverage = max_leverage * SCALE_RATIO * (confidence / 100)
  - Minimum leverage = 2x (no leverageless futures trading)
"""

from typing import Any, Optional

from src.utils.logger import get_logger

logger = get_logger("trading.leverage_manager")

# BTC reference: 100x at full confidence on 125x max = 80%
SCALE_RATIO = 0.80
MIN_LEVERAGE = 2
DEFAULT_MAX_LEVERAGE = 20  # Fallback if exchange info unavailable


class LeverageManager:
    """Calculates dynamic leverage based on confidence score and exchange limits."""

    def __init__(self, config: dict[str, Any], binance_client: Any = None) -> None:
        self.config = config
        self.binance_client = binance_client
        self._max_leverage_cache: dict[str, int] = {}
        leverage_config = config.get("leverage", {})
        self.scale_ratio = leverage_config.get("scale_ratio", SCALE_RATIO)
        self.min_leverage = leverage_config.get("min_leverage", MIN_LEVERAGE)
        self.enabled = leverage_config.get("enabled", True)

    def get_max_leverage(self, symbol: str) -> int:
        """Get the maximum leverage allowed for a symbol on Binance Futures.

        Uses cache to avoid repeated API calls.
        """
        if symbol in self._max_leverage_cache:
            return self._max_leverage_cache[symbol]

        max_lev = DEFAULT_MAX_LEVERAGE

        if self.binance_client:
            try:
                info = self.binance_client.get_symbol_info(symbol)
                if info:
                    # Binance futures symbol info contains bracket leverage info
                    # The 'maxLeverage' is not directly in symbol info for futures,
                    # but we can check leverage brackets via the API
                    brackets = self._fetch_leverage_brackets(symbol)
                    if brackets:
                        max_lev = brackets
                    else:
                        # Fallback: use common known values
                        max_lev = self._get_known_max_leverage(symbol)
            except Exception as e:
                logger.warning(f"Failed to fetch max leverage for {symbol}: {e}")
                max_lev = self._get_known_max_leverage(symbol)
        else:
            max_lev = self._get_known_max_leverage(symbol)

        self._max_leverage_cache[symbol] = max_lev
        logger.info(f"Max leverage for {symbol}: {max_lev}x")
        return max_lev

    def _fetch_leverage_brackets(self, symbol: str) -> Optional[int]:
        """Fetch leverage brackets from Binance Futures API."""
        try:
            client = self.binance_client.client
            # futures_leverage_bracket returns bracket info per symbol
            brackets = client.futures_leverage_bracket(symbol=symbol)
            if brackets and len(brackets) > 0:
                bracket_data = brackets[0]
                bracket_list = bracket_data.get("brackets", [])
                if bracket_list:
                    # First bracket has the highest leverage (smallest notional)
                    max_lev = bracket_list[0].get("initialLeverage", DEFAULT_MAX_LEVERAGE)
                    return int(max_lev)
        except Exception as e:
            logger.debug(f"Leverage bracket API call failed for {symbol}: {e}")
        return None

    def _get_known_max_leverage(self, symbol: str) -> int:
        """Fallback: return known max leverage for popular symbols."""
        known = {
            "BTCUSDT": 125,
            "ETHUSDT": 100,
            "BNBUSDT": 75,
            "XRPUSDT": 75,
            "ADAUSDT": 75,
            "DOGEUSDT": 75,
            "SOLUSDT": 75,
            "DOTUSDT": 75,
            "AVAXUSDT": 75,
            "MATICUSDT": 75,
            "LINKUSDT": 75,
            "LTCUSDT": 75,
            "TRXUSDT": 75,
            "ATOMUSDT": 50,
            "NEARUSDT": 50,
            "UNIUSDT": 50,
            "AAVEUSDT": 50,
            "APTUSDT": 50,
            "ARBUSDT": 50,
            "OPUSDT": 50,
            "SHIBUSDT": 50,
            "PEPEUSDT": 50,
            "WIFUSDT": 50,
            "BLUAIUSDT": 25,
            "BEATUSDT": 25,
        }
        return known.get(symbol, DEFAULT_MAX_LEVERAGE)

    def calculate_leverage(self, symbol: str, confidence: int) -> int:
        """Calculate dynamic leverage based on confidence and symbol max leverage.

        Formula:
          max_usable = max_exchange_leverage * scale_ratio
          leverage = max_usable * (confidence / 100)
          leverage = clamp(min_leverage, max_exchange_leverage)

        Example (BTC, 125x max, scale_ratio=0.80):
          confidence=100% -> 125 * 0.80 * 1.00 = 100x
          confidence=90%  -> 125 * 0.80 * 0.90 = 90x
          confidence=50%  -> 125 * 0.80 * 0.50 = 50x
          confidence=30%  -> 125 * 0.80 * 0.30 = 30x

        Example (altcoin, 50x max):
          confidence=100% -> 50 * 0.80 * 1.00 = 40x
          confidence=50%  -> 50 * 0.80 * 0.50 = 20x
        """
        if not self.enabled:
            return 1

        max_exchange = self.get_max_leverage(symbol)
        max_usable = max_exchange * self.scale_ratio
        raw_leverage = max_usable * (confidence / 100.0)

        # Clamp between min and max
        leverage = int(max(self.min_leverage, min(round(raw_leverage), max_exchange)))

        logger.info(
            f"Leverage calc | {symbol} | confidence={confidence}% | "
            f"max_exchange={max_exchange}x | calculated={leverage}x"
        )
        return leverage

    def clear_cache(self) -> None:
        """Clear the max leverage cache (e.g., on symbol change)."""
        self._max_leverage_cache.clear()
