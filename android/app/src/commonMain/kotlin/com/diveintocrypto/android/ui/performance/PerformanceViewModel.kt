package com.diveintocrypto.android.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 24h market leaderboard — replaced the paper PnL history.
 *
 * Uses the ALL-ticker payload returned by `/fapi/v1/ticker/24hr`
 * (BinanceFuturesClient.ticker24hAll). Stablecoins ([SKIP_SYMBOLS]) are dropped,
 * USDT-paired symbols are kept. Then:
 *   - Gainers: `priceChangePercent` DESC top N
 *   - Losers: `priceChangePercent` ASC top N
 *   - Highest volume: `quoteVolume` DESC top N
 */
class PerformanceViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(PerformanceUiState())
    val ui: StateFlow<PerformanceUiState> = _ui.asStateFlow()

    init { load() }

    fun refresh() {
        _ui.update { it.copy(isLoading = true, error = null) }
        load()
    }

    private fun load() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            val all = container.repository.ticker24hAll()
                .filter { it.symbol.endsWith("USDT") && it.symbol !in BinanceFuturesClient.SKIP_SYMBOLS }

            val gainers = all.sortedByDescending { it.priceChangePercent }.take(TOP_N)
            val losers = all.sortedBy { it.priceChangePercent }.take(TOP_N)
            val byVolume = all.sortedByDescending { it.quoteVolume }.take(TOP_N)

            _ui.update {
                it.copy(
                    totalSymbols = all.size,
                    gainers = gainers,
                    losers = losers,
                    byVolume = byVolume,
                    isLoading = false,
                    error = null,
                    lastUpdateMs = nowMillis(),
                )
            }
        } catch (t: Throwable) {
            _ui.update { it.copy(isLoading = false, error = t.message ?: "network error") }
        }
    }

    companion object {
        const val TOP_N = 10
    }
}

data class PerformanceUiState(
    val totalSymbols: Int = 0,
    val gainers: List<Ticker24h> = emptyList(),
    val losers: List<Ticker24h> = emptyList(),
    val byVolume: List<Ticker24h> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastUpdateMs: Long? = null,
)
