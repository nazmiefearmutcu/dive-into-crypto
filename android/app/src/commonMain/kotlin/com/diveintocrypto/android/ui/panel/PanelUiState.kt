package com.diveintocrypto.android.ui.panel

/**
 * Panel screen state — paper / bot couplings removed (2026-05-23).
 * Feeds only the active symbol's live view:
 *   - status row (symbol + price + TF + last update)
 *   - 12-TF mini confidence grid
 *   - consensus result (signal + confidence + distribution + reason)
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
