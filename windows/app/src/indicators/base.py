"""Base indicator interface and shared types."""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Any

import pandas as pd


class Signal(str, Enum):
    """Trading signal types."""
    STRONG_BUY = "STRONG_BUY"
    BUY = "BUY"
    NEUTRAL = "NEUTRAL"
    SELL = "SELL"
    STRONG_SELL = "STRONG_SELL"


SIGNAL_SCORES: dict[Signal, int] = {
    Signal.STRONG_BUY: 2,
    Signal.BUY: 1,
    Signal.NEUTRAL: 0,
    Signal.SELL: -1,
    Signal.STRONG_SELL: -2,
}


@dataclass
class IndicatorResult:
    """Standard result from any indicator calculation."""
    name: str
    signal: Signal
    score: int
    reason: str
    raw_values: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "signal": self.signal.value,
            "score": self.score,
            "reason": self.reason,
            "raw_values": self.raw_values or {},
        }


class BaseIndicator(ABC):
    """Abstract base class for all indicators."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.thresholds = config.get("indicator_thresholds", {}).get(self.name, {})

    @property
    @abstractmethod
    def name(self) -> str:
        """Unique name of the indicator."""
        ...

    @abstractmethod
    def calculate(self, df: pd.DataFrame) -> IndicatorResult:
        """Calculate the indicator and return a standardized result."""
        ...

    def _make_result(
        self, signal: Signal, reason: str, raw_values: dict[str, Any] | None = None
    ) -> IndicatorResult:
        return IndicatorResult(
            name=self.name,
            signal=signal,
            score=SIGNAL_SCORES[signal],
            reason=reason,
            raw_values=raw_values,
        )
