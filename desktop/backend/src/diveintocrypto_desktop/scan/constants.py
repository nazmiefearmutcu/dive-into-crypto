"""Scanner constants — verbatim from the Android ScannerViewModel companion object
(itself ported from the original bot_service.py `_ZAK` time weights). Keeping these
identical preserves Android↔Desktop ranking parity.
"""

# Higher timeframe = higher weight (a 1d-confirmed signal outweighs a 1m one).
TIME_WEIGHTS: dict[str, int] = {
    "1d": 95, "12h": 90, "8h": 85, "6h": 80, "4h": 75,
    "2h": 65, "1h": 58, "30m": 48, "15m": 38, "5m": 25,
    "3m": 15, "1m": 8,
}

ALL_TFS: list[str] = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"]
PHASE1_TFS: list[str] = ["1d", "12h", "8h"]
PHASE2_TFS: list[str] = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h"]

# Binance futures-data publishes long/short for these 9 periods only (no 1m, 3m, 8h).
DIVERGENCE_TFS: list[str] = ["5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d"]

PHASE2_TOP_N = 50          # survivors carried from phase 1 into phase 2
DIVERGENCE_CANDIDATES = 40  # symbols divergence is computed for (rate-limit bound)
DIVERGENCE_RANK_WEIGHT = 0.35  # divergence share of the table ranking blend
DIVERGENCE_MIN_SHOWN = 5.0  # |divergence score| below this is treated as none

# TF → period duration (ms) — aligns price & whale-L/S series by timestamp bucket.
PERIOD_MS: dict[str, int] = {
    "1m": 60_000, "3m": 180_000, "5m": 300_000, "15m": 900_000,
    "30m": 1_800_000, "1h": 3_600_000, "2h": 7_200_000,
    "4h": 14_400_000, "6h": 21_600_000, "8h": 28_800_000,
    "12h": 43_200_000, "1d": 86_400_000,
}
