package com.diveintocrypto.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.SettingsData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
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
                        enableRegimeMatrix = settings.enableRegimeMatrix,
                        scanSurvivors = settings.scanSurvivors,
                        scanParallelism = settings.scanParallelism,
                        weights = settings.weights,
                        favorites = settings.favorites,
                        wsDataSource = settings.wsDataSource,
                        chartCandleCount = settings.chartCandleCount,
                        weightTakerLs = settings.weightTakerLs,
                        weightOiMomentum = settings.weightOiMomentum,
                        weightWhaleLs = settings.weightWhaleLs,
                        weightAccountLs = settings.weightAccountLs,
                        language = settings.language
                    )
                }
            }
        }

        // Fetch universe list for adding favorites
        viewModelScope.launch {
            try {
                val symbols = container.repository.futuresUniverse()
                _ui.update {
                    it.copy(allSymbols = symbols, universeError = null)
                }
            } catch (ce: CancellationException) {
                // Never swallow structured-concurrency cancellation.
                throw ce
            } catch (e: Exception) {
                // Surface a retryable error to the UI instead of failing silently.
                _ui.update {
                    it.copy(universeError = e.message ?: "Failed to fetch symbol list")
                }
            }
        }
    }

    fun updateConfidenceThreshold(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(confidenceThreshold = value))
    }

    fun updateLanguage(value: String) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(language = value))
    }

    fun updateMinConfidenceForTrade(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(minConfidenceForTrade = value))
    }

    fun toggleRegimeMatrix(value: Boolean) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(enableRegimeMatrix = value))
    }

    fun updateScanSurvivors(value: Int) {
        val current = container.settingsStore.getSettings()
        container.settingsStore.updateSettings(current.copy(scanSurvivors = value))
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

    fun setFavoriteSearchQuery(query: String) {
        val sanitized = query.uppercase().trim()
        _ui.update { state ->
            val filtered = if (sanitized.isEmpty()) {
                emptyList()
            } else {
                state.allSymbols.filter { it.contains(sanitized) && it !in state.favorites }
            }
            state.copy(
                favoriteSearchQuery = query,
                filteredSymbols = filtered
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
}

data class SettingsUiState(
    val confidenceThreshold: Int = 25,
    val minConfidenceForTrade: Int = 30,
    val enableRegimeMatrix: Boolean = true,
    val scanSurvivors: Int = 50,
    val scanParallelism: Int = 8,
    val weights: Map<String, Double> = emptyMap(),
    val favorites: List<String> = emptyList(),
    val wsDataSource: String = "FUTURES",
    val chartCandleCount: Int = 30,
    val weightTakerLs: Double = 0.35,
    val weightOiMomentum: Double = 0.30,
    val weightWhaleLs: Double = 0.20,
    val weightAccountLs: Double = 0.15,
    val language: String = "en",

    // Favorite searching
    val favoriteSearchQuery: String = "",
    val allSymbols: List<String> = emptyList(),
    val filteredSymbols: List<String> = emptyList(),

    // Non-null when the futures-universe fetch failed (retryable error surfaced to UI).
    val universeError: String? = null,
)
