"""Utility helper functions for the trading bot."""

import time
from datetime import datetime, timezone
from decimal import Decimal, ROUND_DOWN
from typing import Any, Optional


def timestamp_now() -> float:
    """Return current UTC timestamp in seconds."""
    return time.time()


def datetime_now() -> datetime:
    """Return current UTC datetime."""
    return datetime.now(timezone.utc)


def iso_now() -> str:
    """Return current UTC time as ISO 8601 string."""
    return datetime_now().isoformat()


def round_down(value: float, decimals: int) -> float:
    """Round a float down to a given number of decimal places (for quantity/price)."""
    d = Decimal(str(value))
    factor = Decimal(10) ** -decimals
    return float(d.quantize(factor, rounding=ROUND_DOWN))


def safe_float(value: Any, default: float = 0.0) -> float:
    """Safely convert a value to float."""
    try:
        return float(value)
    except (ValueError, TypeError):
        return default


def pct_change(old_val: float, new_val: float) -> float:
    """Calculate percentage change between two values."""
    if old_val == 0:
        return 0.0
    return (new_val - old_val) / abs(old_val)


def clamp(value: float, min_val: float, max_val: float) -> float:
    """Clamp a value between min and max."""
    return max(min_val, min(max_val, value))


def format_price(price: float, precision: int = 8) -> str:
    """Format a price with given precision."""
    return f"{price:.{precision}f}"


def retry_with_backoff(
    func,
    max_retries: int = 3,
    base_delay: float = 1.0,
    max_delay: float = 30.0,
    exceptions: tuple = (Exception,),
) -> Any:
    """Execute a function with exponential backoff retry."""
    last_exception: Optional[Exception] = None
    for attempt in range(max_retries):
        try:
            return func()
        except exceptions as e:
            last_exception = e
            if attempt < max_retries - 1:
                delay = min(base_delay * (2 ** attempt), max_delay)
                time.sleep(delay)
    raise last_exception  # type: ignore
