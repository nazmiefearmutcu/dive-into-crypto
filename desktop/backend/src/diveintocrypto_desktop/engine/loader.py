"""Load the canonical engine configuration (indicator thresholds, weights, consensus)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml

_CONFIG_PATH = Path(__file__).parent / "config" / "default.yaml"


@lru_cache(maxsize=1)
def load_config() -> dict[str, Any]:
    """Return the full engine config dict.

    The same dict is consumed by both ``SignalService(config)`` (which reads
    ``indicator_thresholds[name]`` per indicator) and ``ConsensusEngine(config)``
    (which reads ``indicator_weights`` / ``consensus`` / ``no_trade``).
    """
    # Explicit UTF-8: the default locale codec is cp1254 on Windows and cannot
    # decode every UTF-8 byte sequence.
    with _CONFIG_PATH.open(encoding="utf-8") as f:
        return yaml.safe_load(f)
