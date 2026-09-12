"""Planning strip — informational position-planning distances derived from the
ATR the engine already computed. NO order semantics: no entries, exits, sizing
or leverage advice — just the geometry (stop distance at 1.5×ATR, the 1R/2R/3R
ladder, and the expected-move envelope) so a human can plan without switching
tools.

Pure function over the primary-timeframe ATR percent (``atr_filter``'s
``atr_pct``). ``None``/missing ATR → an honest ``{"unavailable":
"atr_missing"}``; nothing is imputed. Envelope horizons assume the ATR percent
is measured on hourly bars (the primary TF); longer horizons scale with
√hours — a diffusion assumption, labeled as such.
"""

from __future__ import annotations

SL_ATR_MULT = 1.5  # stop distance = 1.5 × ATR%
_Z = 1.0           # envelope width in σ


def planning_strip(atr_pct: float | None, atr_tf_hours: float = 1.0) -> dict:
    """``planning`` block from one ATR-percentage reading.

    ``sl_distance_pct`` = 1.5·ATR%; ``tp_1r/2r/3r_pct`` are the same distance
    ×1/2/3; ``envelope`` is the ±1σ expected move over 1h/4h/24h
    (ATR%·√(hours/atr_tf_hours)). Informational only.
    """
    if atr_pct is None or atr_pct <= 0:
        return {"unavailable": "atr_missing"}
    atr = float(atr_pct)
    scale = lambda h: atr * ((h / atr_tf_hours) ** 0.5)  # noqa: E731
    return {
        "sl_distance_pct": round(atr * SL_ATR_MULT, 4),
        "tp_1r_pct": round(atr, 4),
        "tp_2r_pct": round(atr * 2.0, 4),
        "tp_3r_pct": round(atr * 3.0, 4),
        "envelope": {
            "h1": round(scale(1.0), 4),
            "h4": round(scale(4.0), 4),
            "h24": round(scale(24.0), 4),
        },
        "atr_pct": round(atr, 4),
        "atr_tf_hours": atr_tf_hours,
        "note": "informational geometry only — no order semantics",
    }
