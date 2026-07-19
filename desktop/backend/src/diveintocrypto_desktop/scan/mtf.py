"""Multi-Timeframe Confluence — strategy overlay.

Scores agreement across the per-timeframe verdicts, weighting higher timeframes more and
scaling by each TF's confidence. A strong, aligned higher-TF stack = high-confidence
confluence; a split stack = low. Surfaces a signed score, the dominant direction, the
higher-TF agreement fraction, and a boolean gate (whether the higher-TF stack agrees) so
the UI/ranking can require confluence before acting. Additive — does not change any verdict.
"""

from __future__ import annotations

from typing import Any

_TF_WEIGHT = {
    "1m": 1, "3m": 1, "5m": 2, "15m": 3, "30m": 4, "1h": 6,
    "2h": 7, "4h": 9, "6h": 10, "8h": 11, "12h": 12, "1d": 14,
}
_HTF = {"1h", "2h", "4h", "6h", "8h", "12h", "1d"}


def _dir(sig: str) -> int:
    if "BUY" in sig:
        return 1
    if "SELL" in sig:
        return -1
    return 0


def confluence(multi_tf: list[dict], min_gate: float = 0.6) -> dict[str, Any]:
    wsum = 0.0
    acc = 0.0
    htf_dirs: list[int] = []
    for m in multi_tf:
        tf = m.get("tf")
        d = _dir(m.get("signal", "NEUTRAL"))
        conf = m.get("confidence", 0) or 0
        w = _TF_WEIGHT.get(tf, 1)
        acc += d * w * (0.5 + 0.5 * min(float(conf), 100.0) / 100.0)
        wsum += w
        if tf in _HTF:
            htf_dirs.append(d)

    if wsum == 0.0:
        return {"score": 0.0, "direction": 0, "gate": False, "htf_agree": 0.0, "label": "NEUTRAL"}

    score = round(acc / wsum * 100.0, 1)
    dom = 1 if score > 0 else -1 if score < 0 else 0
    nonzero = [d for d in htf_dirs if d != 0]
    htf_agree = (sum(1 for d in nonzero if d == dom) / len(nonzero)) if nonzero else 0.0
    gate = bool(htf_agree >= min_gate and dom != 0)
    mag = abs(score)
    label = "STRONG" if mag >= 55 else "WEAK" if mag >= 20 else "NEUTRAL"
    return {
        "score": score,
        "direction": dom,
        "gate": gate,
        "htf_agree": round(htf_agree, 2),
        "label": label,
    }
