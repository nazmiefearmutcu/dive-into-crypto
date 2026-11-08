"""Utility helper functions for the trading bot."""

import math


def clamp(value: float, min_val: float, max_val: float) -> float:
    """Clamp a value between min and max.

    NaN maps to ``min_val``: ``min(max_val, nan)`` returns ``max_val`` in Python,
    which would turn an undefined value into the upper bound (e.g. confidence 100).
    """
    if math.isnan(value):
        return min_val
    return max(min_val, min(max_val, value))
