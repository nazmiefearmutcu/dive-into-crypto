package com.diveintocrypto.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsStore(private val kv: KeyValueStore) {

    private val _settingsState = MutableStateFlow(loadSettings())
    val settingsState: StateFlow<SettingsData> = _settingsState

    fun getSettings(): SettingsData = _settingsState.value

    fun updateSettings(data: SettingsData) {
        kv.putInt("confidence_threshold", data.confidenceThreshold)
        kv.putInt("min_confidence_trade", data.minConfidenceForTrade)
        kv.putBoolean("enable_regime_matrix", data.enableRegimeMatrix)
        kv.putInt("scan_survivors", data.scanSurvivors)
        kv.putInt("scan_parallelism", data.scanParallelism)

        // New settings fields
        kv.putString("ws_data_source", data.wsDataSource)
        kv.putInt("chart_candle_count", data.chartCandleCount)
        kv.putFloat("weight_taker_ls", data.weightTakerLs.toFloat())
        kv.putFloat("weight_oi_momentum", data.weightOiMomentum.toFloat())
        kv.putFloat("weight_whale_ls", data.weightWhaleLs.toFloat())
        kv.putFloat("weight_account_ls", data.weightAccountLs.toFloat())

        // Save weights
        data.weights.forEach { (key, value) ->
            kv.putFloat("weight_$key", value.toFloat())
        }

        // Save favorites
        kv.putString("favorite_symbols", data.favorites.joinToString(","))

        _settingsState.value = data
    }

    private fun loadSettings(): SettingsData {
        val confidenceThreshold = kv.getInt("confidence_threshold", 25)
        val minConfidenceTrade = kv.getInt("min_confidence_trade", 30)
        val enableRegimeMatrix = kv.getBoolean("enable_regime_matrix", true)
        val scanSurvivors = kv.getInt("scan_survivors", 50)
        val scanParallelism = kv.getInt("scan_parallelism", 8)

        // New settings fields with defaults
        val wsDataSource = kv.getString("ws_data_source", "FUTURES") ?: "FUTURES"
        val chartCandleCount = kv.getInt("chart_candle_count", 30)
        val weightTakerLs = kv.getFloat("weight_taker_ls", 0.35f).toDouble()
        val weightOiMomentum = kv.getFloat("weight_oi_momentum", 0.30f).toDouble()
        val weightWhaleLs = kv.getFloat("weight_whale_ls", 0.20f).toDouble()
        val weightAccountLs = kv.getFloat("weight_account_ls", 0.15f).toDouble()

        val defaultWeights = mapOf(
            "rsi" to 1.5,
            "stochastic" to 1.2,
            "williams_r" to 1.0,
            "cci" to 1.0,
            "macd" to 2.0,
            "ema_cross" to 1.8,
            "sma_cross" to 1.5,
            "ichimoku" to 1.5,
            "psar" to 1.2,
            "bollinger" to 1.5,
            "mfi" to 1.2,
            "obv" to 1.5,
            "roc" to 1.0,
            "adx_di" to 1.5,
            "atr_filter" to 0.0
        )

        val weights = defaultWeights.mapValues { (key, defVal) ->
            kv.getFloat("weight_$key", defVal.toFloat()).toDouble()
        }

        val favStr = kv.getString("favorite_symbols", "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,LINKUSDT,AVAXUSDT") ?: ""
        val favorites = if (favStr.isEmpty()) emptyList() else favStr.split(",").map { it.trim() }

        return SettingsData(
            confidenceThreshold = confidenceThreshold,
            minConfidenceForTrade = minConfidenceTrade,
            enableRegimeMatrix = enableRegimeMatrix,
            scanSurvivors = scanSurvivors,
            scanParallelism = scanParallelism,
            weights = weights,
            favorites = favorites,
            wsDataSource = wsDataSource,
            chartCandleCount = chartCandleCount,
            weightTakerLs = weightTakerLs,
            weightOiMomentum = weightOiMomentum,
            weightWhaleLs = weightWhaleLs,
            weightAccountLs = weightAccountLs
        )
    }
}

data class SettingsData(
    val confidenceThreshold: Int,
    val minConfidenceForTrade: Int,
    val enableRegimeMatrix: Boolean,
    val scanSurvivors: Int,
    val scanParallelism: Int,
    val weights: Map<String, Double>,
    val favorites: List<String>,
    val wsDataSource: String,
    val chartCandleCount: Int,
    val weightTakerLs: Double,
    val weightOiMomentum: Double,
    val weightWhaleLs: Double,
    val weightAccountLs: Double
)
