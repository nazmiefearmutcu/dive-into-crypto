package com.diveintocrypto.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.domain.consensus.DEFAULT_FULL_WEIGHTS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        // Collect dynamic updates from settingsStore
        viewModelScope.launch {
            container.settingsStore.settingsState.collect { settings ->
                _ui.update {
                    it.copy(
                        confidenceThreshold = settings.confidenceThreshold,
                        minConfidenceForTrade = settings.minConfidenceForTrade,
                        scanDepthTop = settings.scanDepthTop,
                        scanParallelism = settings.scanParallelism,
                        weights = settings.weights,
                        favorites = settings.favorites,
                        wsDataSource = settings.wsDataSource,
                        chartCandleCount = settings.chartCandleCount,
                        weightTakerLs = settings.weightTakerLs,
                        weightOiMomentum = settings.weightOiMomentum,
                        weightWhaleLs = settings.weightWhaleLs,
                        weightAccountLs = settings.weightAccountLs,
                        backgroundScansEnabled = settings.backgroundScansEnabled,
                        backgroundScansUnmetered = settings.backgroundScansUnmetered
                    )
                }
            }
        }

        loadUniverse()
    }

    /** Fetches the futures symbol list used by the favorites search. Retry via [retryUniverse]. */
    private fun loadUniverse() {
        viewModelScope.launch {
            _ui.update { it.copy(universeLoading = true, universeError = null, universeRegionBlocked = false) }
            try {
                val symbols = container.repository.futuresUniverse()
                _ui.update { state ->
                    state.copy(
                        allSymbols = symbols,
                        universeLoading = false,
                        filteredSymbols = filterSymbols(state.favoriteSearchQuery, symbols, state.favorites),
                    )
                }
            } catch (ce: CancellationException) {
                // Never swallow structured-concurrency cancellation.
                throw ce
            } catch (e: Exception) {
                // Surface a retryable, localized error to the UI instead of failing silently.
                _ui.update {
                    it.copy(
                        universeLoading = false,
                        universeError = e.message ?: "unknown",
                        universeRegionBlocked = e.isRegionBlocked(),
                    )
                }
            }
        }
    }

    fun retryUniverse() = loadUniverse()

    fun updateConfidenceThreshold(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(confidenceThreshold = value))
    }

    fun updateMinConfidenceForTrade(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(minConfidenceForTrade = value))
    }

    /**
     * Phase-2 candidate count (survivor pool N). Writes `scanDepthTop`, the field the
     * scanner actually sizes phase 2 from (ScannerViewModel reads settings.scanDepthTop).
     */
    fun updateScanDepthTop(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(scanDepthTop = value))
    }

    fun updateScanParallelism(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(scanParallelism = value))
    }

    fun updateWsDataSource(value: String) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(wsDataSource = value))
    }

    fun updateChartCandleCount(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(chartCandleCount = value))
    }

    fun updateQuantBiasWeights(taker: Double, oi: Double, whale: Double, account: Double) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(
            current.copy(
                weightTakerLs = taker,
                weightOiMomentum = oi,
                weightWhaleLs = whale,
                weightAccountLs = account
            )
        )
    }

    fun updateIndicatorWeight(name: String, weight: Double) {
        val current = container.settingsStore.getSettings()
        val updatedWeights = current.weights.toMutableMap().apply {
            this[name] = weight
        }
        container.settingsStore.updateSettings(current.copy(weights = updatedWeights))
    }

    /** SIFIRLA: restores every default indicator weight in ONE settings write. */
    fun resetWeights() {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(weights = current.weights + DEFAULT_FULL_WEIGHTS))
    }

    /**
     * Persists the background-scan toggles (WorkManager card). The CALLER is
     * responsible for invoking the platform sync hook afterwards
     * ([com.diveintocrypto.android.platform.rememberBackgroundScanSync]) so the
     * periodic work is enqueued/cancelled with the fresh constraints.
     */
    fun updateBackgroundScans(enabled: Boolean? = null, unmetered: Boolean? = null) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(
            current.copy(
                backgroundScansEnabled = enabled ?: current.backgroundScansEnabled,
                backgroundScansUnmetered = unmetered ?: current.backgroundScansUnmetered,
            )
        )
    }

    fun setFavoriteSearchQuery(query: String) {
        _ui.update { state ->
            state.copy(
                favoriteSearchQuery = query,
                filteredSymbols = filterSymbols(query, state.allSymbols, state.favorites),
            )
        }
    }

    fun addFavorite(symbol: String) {
        val current = container.settingsStore.getSettings()
        if (symbol in current.favorites) return
        val updatedFavorites = current.favorites + symbol
        container.settingsStore.updateSettings(current.copy(favorites = updatedFavorites))
        // Clear search query
        setFavoriteSearchQuery("")
    }

    fun removeFavorite(symbol: String) {
        val current = container.settingsStore.getSettings()
        val updatedFavorites = current.favorites - symbol
        container.settingsStore.updateSettings(current.copy(favorites = updatedFavorites))
    }

    private fun filterSymbols(query: String, all: List<String>, favorites: List<String>): List<String> {
        val sanitized = query.uppercase().trim()
        if (sanitized.isEmpty()) return emptyList()
        return all.filter { it.contains(sanitized) && it !in favorites }
    }
}

data class SettingsUiState(
    val confidenceThreshold: Int = 25,
    val minConfidenceForTrade: Int = 30,
    /** Phase-2 candidate count (survivor pool N) — the scanner's scanDepthTop. */
    val scanDepthTop: Int = 50,
    val scanParallelism: Int = 8,
    val weights: Map<String, Double> = emptyMap(),
    val favorites: List<String> = emptyList(),
    val wsDataSource: String = "FUTURES",
    val chartCandleCount: Int = 30,
    val weightTakerLs: Double = 0.35,
    val weightOiMomentum: Double = 0.30,
    val weightWhaleLs: Double = 0.20,
    val weightAccountLs: Double = 0.15,

    // Background WorkManager scans (opt-in; sync handled platform-side).
    val backgroundScansEnabled: Boolean = false,
    val backgroundScansUnmetered: Boolean = true,

    // Favorite searching
    val favoriteSearchQuery: String = "",
    val allSymbols: List<String> = emptyList(),
    val filteredSymbols: List<String> = emptyList(),
    val universeLoading: Boolean = false,

    // Non-null when the futures-universe fetch failed (retryable error surfaced to UI).
    val universeError: String? = null,
    /** True when the failure is an upstream region block (HTTP 451): retrying cannot help. */
    val universeRegionBlocked: Boolean = false,
)
