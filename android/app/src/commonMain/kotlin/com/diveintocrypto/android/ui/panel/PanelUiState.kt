package com.diveintocrypto.android.ui.panel

/**
 * Panel screen state — paper / bot couplings removed (2026-05-23).
 * Feeds only the active symbol's live view:
 *   - status row (symbol + price + TF + last update)
 *   - 12-TF mini confidence grid
 *   - consensus result (signal + confidence + distribution + reason)
 *   - strategy-overlay annotations (regime · MTF-confluence · microstructure)
 *   - honesty fields (isStale / dataAgeMs — no data is ever fabricated)
 *
 * NOTE for UI work: the 0.2.0 additions at the bottom are ADDITIVE — existing
 * fields and their semantics are unchanged.
 */
data class PanelUiState(
    val activeSymbol: String = "BTCUSDT",
    val timeframe: String = "1h",
    val currentPrice: Double? = null,
    val priceChangeDirection: String = "NONE",

    val latestSignal: String = "NEUTRAL",
    val confidence: Int = 0,
    val action: String = "HOLD",
    val reason: String = "",

    val distBuy: Int = 0,
    val distSell: Int = 0,
    val distNeutral: Int = 0,

    /** 12-row per-TF mini grid. Populated once (at load time). */
    val multiTf: List<TfSignal> = emptyList(),

    val isLoading: Boolean = true,
    val lastUpdateMs: Long? = null,
    val errorMessage: String? = null,

    // Coin search/select area
    val searchQuery: String = "",
    val allSymbols: List<String> = emptyList(),
    val filteredSymbols: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),

    // ── STRATEGY-OVERLAY ANNOTATIONS (ADDITIVE in 0.2.0 — the README's "3 overlays"
    //    are now surfaced next to the verdict; none of them changes the verdict). ──

    /** [Regime] label for the active timeframe: TREND / RANGE / MIXED. */
    val regime: String = "MIXED",
    /** [Regime] adaptively-weighted observational score (consensus verdict untouched). */
    val regimeAdaptiveScore: Double = 0.0,

    /** [MtfConfluence] agreement score across the 12-TF grid, −100..+100. */
    val mtfScore: Double = 0.0,
    /** [MtfConfluence] dominant direction: +1 bull, −1 bear, 0 none. */
    val mtfDirection: Int = 0,
    /** [MtfConfluence] gate: the higher-TF (≥1h) stack agrees with the dominant direction. */
    val mtfGate: Boolean = false,
    /** [MtfConfluence] strength label: STRONG / WEAK / NEUTRAL. */
    val mtfLabel: String = "NEUTRAL",

    /** [Microstructure] bundle score, −100..+100; null = not computable from current data. */
    val microScore: Double? = null,
    /** [Microstructure] direction: +1 bull, −1 bear, 0 none; null = not computable. */
    val microDirection: Int? = null,
    /** [Microstructure] label: STRONG_BUY / BUY / NEUTRAL / SELL / STRONG_SELL; null = not computable. */
    val microLabel: String? = null,
    /** [Microstructure] number of individual signals that had enough data to fire; null = not computable. */
    val microActive: Int? = null,

    // ── HONESTY FIELDS (ADDITIVE in 0.2.0 — replaces the old random-tick fallback) ──

    /** true once no WebSocket frame has arrived for more than [PanelViewModel.STALE_AFTER_MS]
     *  (or none ever arrived this session). The displayed data is the last REAL data. */
    val isStale: Boolean = false,
    /** ms since the last WS frame; null = no frame received yet in this session. */
    val dataAgeMs: Long? = null,
)

/** Per-TF signal cell. */
data class TfSignal(
    val tf: String,
    val signal: String,
    val confidence: Int,
)

/** 12 timeframes — same order as the Scanner. */
val ALL_TIMEFRAMES: List<String> = listOf(
    "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d",
)
