"""Signal service - orchestrates indicator calculation across all indicators."""

from typing import Any

import pandas as pd

from diveintocrypto_desktop.engine.indicators.base import BaseIndicator, IndicatorResult, Signal
from diveintocrypto_desktop.engine.indicators.rsi import RSIIndicator
from diveintocrypto_desktop.engine.indicators.macd import MACDIndicator
from diveintocrypto_desktop.engine.indicators.bollinger import BollingerBandsIndicator
from diveintocrypto_desktop.engine.indicators.sma_cross import SMACrossIndicator
from diveintocrypto_desktop.engine.indicators.ema_cross import EMACrossIndicator
from diveintocrypto_desktop.engine.indicators.stochastic import StochasticIndicator
from diveintocrypto_desktop.engine.indicators.adx_di import ADXDIIndicator
from diveintocrypto_desktop.engine.indicators.cci import CCIIndicator
from diveintocrypto_desktop.engine.indicators.williams_r import WilliamsRIndicator
from diveintocrypto_desktop.engine.indicators.roc import ROCIndicator
from diveintocrypto_desktop.engine.indicators.mfi import MFIIndicator
from diveintocrypto_desktop.engine.indicators.atr_filter import ATRFilterIndicator
from diveintocrypto_desktop.engine.indicators.ichimoku import IchimokuIndicator
from diveintocrypto_desktop.engine.indicators.psar import PSARIndicator
from diveintocrypto_desktop.engine.indicators.obv import OBVIndicator
from diveintocrypto_desktop.engine.indicators.supertrend import SupertrendIndicator
from diveintocrypto_desktop.engine.indicators.awesome_oscillator import AwesomeOscillatorIndicator
from diveintocrypto_desktop.engine.indicators.cmf import CMFIndicator
from diveintocrypto_desktop.engine.indicators.squeeze import TTMSqueezeIndicator
from diveintocrypto_desktop.engine.indicators.choppiness import ChoppinessIndexIndicator
from diveintocrypto_desktop.engine.indicators.vwap import VWAPIndicator
from diveintocrypto_desktop.engine.indicators.vortex import VortexIndicator
# --- New indicators (strategy-lab R&D fleet, 2026-07-19) ---
from diveintocrypto_desktop.engine.indicators.keltner_breakout import KeltnerBreakoutIndicator
from diveintocrypto_desktop.engine.indicators.donchian_breakout import DonchianBreakoutIndicator
from diveintocrypto_desktop.engine.indicators.chaikin_oscillator import ChaikinOscillatorIndicator
from diveintocrypto_desktop.engine.indicators.elder_ray import ElderRayIndicator
from diveintocrypto_desktop.engine.indicators.klinger_oscillator import KlingerOscillatorIndicator
from diveintocrypto_desktop.engine.indicators.trix import TRIXIndicator
from diveintocrypto_desktop.engine.indicators.coppock_curve import CoppockCurveIndicator
from diveintocrypto_desktop.engine.indicators.kst import KSTIndicator
from diveintocrypto_desktop.engine.indicators.dpo import DPOIndicator
from diveintocrypto_desktop.engine.indicators.fisher_transform import FisherTransformIndicator
from diveintocrypto_desktop.engine.indicators.connors_rsi import ConnorsRSIIndicator
from diveintocrypto_desktop.engine.indicators.stoch_rsi import StochRSIIndicator
from diveintocrypto_desktop.engine.indicators.ultimate_oscillator import UltimateOscillatorIndicator
from diveintocrypto_desktop.engine.indicators.aroon_oscillator import AroonOscillatorIndicator
from diveintocrypto_desktop.engine.indicators.schaff_trend_cycle import SchaffTrendCycleIndicator
from diveintocrypto_desktop.engine.indicators.wavetrend import WaveTrendIndicator
from diveintocrypto_desktop.engine.indicators.relative_vigor_index import RelativeVigorIndexIndicator
from diveintocrypto_desktop.engine.indicators.balance_of_power import BalanceOfPowerIndicator
from diveintocrypto_desktop.engine.indicators.accum_dist_line import AccumDistLineIndicator
from diveintocrypto_desktop.engine.indicators.mass_index import MassIndexIndicator
from diveintocrypto_desktop.engine.utils.logger import get_logger

logger = get_logger("services.signal_service")


class SignalService:
    """Runs all indicators and collects results."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.indicators: list[BaseIndicator] = self._build_indicators()

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
            SupertrendIndicator,
            AwesomeOscillatorIndicator,
            CMFIndicator,
            TTMSqueezeIndicator,
            ChoppinessIndexIndicator,
            VWAPIndicator,
            VortexIndicator,
            # --- New indicators (strategy-lab R&D fleet, 2026-07-19) ---
            KeltnerBreakoutIndicator,
            DonchianBreakoutIndicator,
            ChaikinOscillatorIndicator,
            ElderRayIndicator,
            KlingerOscillatorIndicator,
            TRIXIndicator,
            CoppockCurveIndicator,
            KSTIndicator,
            DPOIndicator,
            FisherTransformIndicator,
            ConnorsRSIIndicator,
            StochRSIIndicator,
            UltimateOscillatorIndicator,
            AroonOscillatorIndicator,
            SchaffTrendCycleIndicator,
            WaveTrendIndicator,
            RelativeVigorIndexIndicator,
            BalanceOfPowerIndicator,
            AccumDistLineIndicator,
            MassIndexIndicator,
        ]
        return [cls(self.config) for cls in indicator_classes]

    def calculate_all(self, df: pd.DataFrame) -> list[IndicatorResult]:
        """Calculate all indicators on the given OHLCV DataFrame.

        Returns list of IndicatorResult objects. Failed indicators return NEUTRAL.
        """
        results: list[IndicatorResult] = []

        for indicator in self.indicators:
            try:
                result = indicator.calculate(df)
                results.append(result)
                logger.debug(
                    f"  {indicator.name}: {result.signal.value} (score={result.score}) - {result.reason}"
                )
            except Exception as e:
                logger.error(f"Indicator {indicator.name} failed: {e}")
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
