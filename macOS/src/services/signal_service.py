"""Signal service - orchestrates indicator calculation across all indicators."""

from typing import Any

import pandas as pd

from src.indicators.base import BaseIndicator, IndicatorResult
from src.indicators.rsi import RSIIndicator
from src.indicators.macd import MACDIndicator
from src.indicators.bollinger import BollingerBandsIndicator
from src.indicators.sma_cross import SMACrossIndicator
from src.indicators.ema_cross import EMACrossIndicator
from src.indicators.stochastic import StochasticIndicator
from src.indicators.adx_di import ADXDIIndicator
from src.indicators.cci import CCIIndicator
from src.indicators.williams_r import WilliamsRIndicator
from src.indicators.roc import ROCIndicator
from src.indicators.mfi import MFIIndicator
from src.indicators.atr_filter import ATRFilterIndicator
from src.indicators.ichimoku import IchimokuIndicator
from src.indicators.psar import PSARIndicator
from src.indicators.obv import OBVIndicator
from src.utils.logger import get_logger

logger = get_logger("services.signal_service")


class SignalService:
    """Runs all indicators and collects results."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.indicators: list[BaseIndicator] = self._build_indicators()
        self.last_errors: list[str] = []

    def _build_indicators(self) -> list[BaseIndicator]:
        """Instantiate all indicator objects."""
        indicator_classes = [
            RSIIndicator,
            MACDIndicator,
            BollingerBandsIndicator,
            SMACrossIndicator,
            EMACrossIndicator,
            StochasticIndicator,
            ADXDIIndicator,
            CCIIndicator,
            WilliamsRIndicator,
            ROCIndicator,
            MFIIndicator,
            ATRFilterIndicator,
            IchimokuIndicator,
            PSARIndicator,
            OBVIndicator,
        ]
        return [cls(self.config) for cls in indicator_classes]

    def calculate_all(self, df: pd.DataFrame) -> list[IndicatorResult]:
        """Calculate all indicators on the given OHLCV DataFrame.

        Returns list of IndicatorResult objects. Failed indicators return NEUTRAL.
        """
        results: list[IndicatorResult] = []
        self.last_errors = []

        for indicator in self.indicators:
            try:
                result = indicator.calculate(df)
                results.append(result)
                logger.debug(
                    f"  {indicator.name}: {result.signal.value} (score={result.score}) - {result.reason}"
                )
            except Exception as e:
                logger.error(f"Indicator {indicator.name} failed: {e}")
                from src.indicators.base import Signal, SIGNAL_SCORES
                self.last_errors.append(f"{indicator.name}: {str(e)[:100]}")
                fallback = IndicatorResult(
                    name=indicator.name,
                    signal=Signal.NEUTRAL,
                    score=0,
                    reason=f"Calculation error: {str(e)[:100]}",
                )
                results.append(fallback)

        active = sum(1 for r in results if r.signal.value != "NEUTRAL")
        logger.info(f"Signals calculated: {len(results)} total, {active} active (non-neutral)")

        return results

    def get_indicator_names(self) -> list[str]:
        """Return list of all indicator names."""
        return [ind.name for ind in self.indicators]
