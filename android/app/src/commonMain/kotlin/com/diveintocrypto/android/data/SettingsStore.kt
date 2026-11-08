package com.diveintocrypto.android.data

import com.diveintocrypto.android.domain.consensus.DEFAULT_FULL_WEIGHTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Scanner universe selector for phase 1.
 *
 * [limit] is how many of the volume-sorted futures symbols enter phase 1
 * (`null` = the whole universe). The label doubles as the persisted string.
 */
enum class ScanUniverseMode(val label: String, val limit: Int?) {
    TOP20("TOP20", 20),
    TOP50("TOP50", 50),
    TOP100("TOP100", 100),
    TOP250("TOP250", 250),
    ALL("ALL", null);

    companion object {
        fun fromLabel(label: String): ScanUniverseMode =
            entries.firstOrNull { it.label == label } ?: TOP50
    }
}

class SettingsStore(private val kv: KeyValueStore) {

    init {
        migrateLegacyWeightDefaults()
    }

    private val _settingsState = MutableStateFlow(loadSettings())
    val settingsState: StateFlow<SettingsData> = _settingsState

    fun getSettings(): SettingsData = _settingsState.value

    /**
     * Persists every field in ONE batch (one SharedPreferences edit on Android) so a settings
     * change never lands half-written.
     */
    fun updateSettings(data: SettingsData) {
        kv.edit {
            putInt("confidence_threshold", data.confidenceThreshold)
            putInt("min_confidence_trade", data.minConfidenceForTrade)
            putInt("scan_survivors", data.scanSurvivors)
            putInt("scan_parallelism", data.scanParallelism)

            // New settings fields
            putString("ws_data_source", data.wsDataSource)
            putInt("chart_candle_count", data.chartCandleCount)
            putFloat("weight_taker_ls", data.weightTakerLs.toFloat())
            putFloat("weight_oi_momentum", data.weightOiMomentum.toFloat())
            putFloat("weight_whale_ls", data.weightWhaleLs.toFloat())
            putFloat("weight_account_ls", data.weightAccountLs.toFloat())

            // Scanner universe mode + phase-2 depth
            putString("scan_universe", data.scanUniverse)
            putInt("scan_depth_top", data.scanDepthTop)

            // Background WorkManager scans (opt-in; the worker reads these on every run)
            putBoolean("background_scans_enabled", data.backgroundScansEnabled)
            putBoolean("background_scans_unmetered", data.backgroundScansUnmetered)

            // Save weights
            data.weights.forEach { (key, value) ->
                putFloat("weight_$key", value.toFloat())
            }

            // Save favorites
            putString("favorite_symbols", data.favorites.joinToString(","))
        }

        _settingsState.value = data
    }

    /**
     * One-time, versioned migration of the four indicator weights whose FULL defaults changed
     * (sma_cross, ichimoku, psar, obv). Installs before the change persisted every weight on each
     * save, so a stored value cannot be told apart from a user choice; a stored value that is
     * EXACTLY the old default is therefore moved to the new default, and any other value is kept.
     * Runs once: [WEIGHTS_MIGRATION_KEY] records that it has been applied.
     */
    private fun migrateLegacyWeightDefaults() {
        if (kv.getInt(WEIGHTS_MIGRATION_KEY, 0) >= WEIGHTS_MIGRATION_VERSION) return
        kv.edit {
            for ((key, oldDefault) in LEGACY_WEIGHT_DEFAULTS) {
                val storedKey = "weight_$key"
                val stored = kv.getFloat(storedKey, Float.NaN)
                if (stored == oldDefault.toFloat()) {
                    val newDefault = DEFAULT_FULL_WEIGHTS.getValue(key)
                    putFloat(storedKey, newDefault.toFloat())
                }
            }
            putInt(WEIGHTS_MIGRATION_KEY, WEIGHTS_MIGRATION_VERSION)
        }
    }

    companion object {
        /** Versioned marker for [migrateLegacyWeightDefaults]; bump only with a new migration. */
        internal const val WEIGHTS_MIGRATION_KEY = "weights_migration_version"
        internal const val WEIGHTS_MIGRATION_VERSION = 1

        /** The full defaults of the previous release, for the four weights that changed since. */
        internal val LEGACY_WEIGHT_DEFAULTS: Map<String, Double> = mapOf(
            "sma_cross" to 1.5,
            "ichimoku" to 1.5,
            "psar" to 1.2,
            "obv" to 1.5,
        )
    }

    /** Generic JSON-blob persistence (alert rules, fired history, evidence log). */
    fun putRaw(key: String, value: String) { kv.putString(key, value) }
    fun getRaw(key: String): String? = kv.getString(key, null)

    private fun loadSettings(): SettingsData {
        val confidenceThreshold = kv.getInt("confidence_threshold", 25)
        val minConfidenceTrade = kv.getInt("min_confidence_trade", 30)
        val scanSurvivors = kv.getInt("scan_survivors", 50)
        val scanParallelism = kv.getInt("scan_parallelism", 8)

        // New settings fields with defaults
        val wsDataSource = kv.getString("ws_data_source", "FUTURES") ?: "FUTURES"
        val chartCandleCount = kv.getInt("chart_candle_count", 30)
        val weightTakerLs = kv.getFloat("weight_taker_ls", 0.35f).toDouble()
        val weightOiMomentum = kv.getFloat("weight_oi_momentum", 0.30f).toDouble()
        val weightWhaleLs = kv.getFloat("weight_whale_ls", 0.20f).toDouble()
        val weightAccountLs = kv.getFloat("weight_account_ls", 0.15f).toDouble()

        // Scanner universe (phase-1 size) + phase-2 depth
        val scanUniverse = kv.getString("scan_universe", ScanUniverseMode.TOP50.label)
            ?: ScanUniverseMode.TOP50.label
        val scanDepthTop = kv.getInt("scan_depth_top", 50)

        // Background WorkManager scans — OPT-IN (default off), unmetered-only
        // default ON so a periodic scan never burns mobile data unnoticed.
        val backgroundScansEnabled = kv.getBoolean("background_scans_enabled", false)
        val backgroundScansUnmetered = kv.getBoolean("background_scans_unmetered", true)

        // FULL 60-name default weights (15 core F2 values + 45 extended
        // desktop-reference values). Previously only 15 names were listed here,
        // so the 45 extended indicators silently scored at weight 1.0 in the
        // engine. User overrides persist per key ("weight_<name>") and win.
        val weights = DEFAULT_FULL_WEIGHTS.mapValues { (key, defVal) ->
            kv.getFloat("weight_$key", defVal.toFloat()).toDouble()
        }

        val favStr = kv.getString("favorite_symbols", "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,LINKUSDT,AVAXUSDT") ?: ""
        val favorites = if (favStr.isEmpty()) emptyList() else favStr.split(",").map { it.trim() }

        return SettingsData(
            confidenceThreshold = confidenceThreshold,
            minConfidenceForTrade = minConfidenceTrade,
            scanSurvivors = scanSurvivors,
            scanParallelism = scanParallelism,
            weights = weights,
            favorites = favorites,
            wsDataSource = wsDataSource,
            chartCandleCount = chartCandleCount,
            weightTakerLs = weightTakerLs,
            weightOiMomentum = weightOiMomentum,
            weightWhaleLs = weightWhaleLs,
            weightAccountLs = weightAccountLs,
            scanUniverse = scanUniverse,
            scanDepthTop = scanDepthTop,
            backgroundScansEnabled = backgroundScansEnabled,
            backgroundScansUnmetered = backgroundScansUnmetered,
        )
    }
}

data class SettingsData(
    val confidenceThreshold: Int,
    val minConfidenceForTrade: Int,
    val scanSurvivors: Int,
    val scanParallelism: Int,
    val weights: Map<String, Double>,
    val favorites: List<String>,
    val wsDataSource: String,
    val chartCandleCount: Int,
    val weightTakerLs: Double,
    val weightOiMomentum: Double,
    val weightWhaleLs: Double,
    val weightAccountLs: Double,
    /**
     * Phase-1 universe size: one of [ScanUniverseMode] labels
     * (TOP20/TOP50/TOP100/TOP250/ALL). Default TOP50.
     */
    val scanUniverse: String = ScanUniverseMode.TOP50.label,
    /** Phase-2 survivor pool size N (default 50). */
    val scanDepthTop: Int = 50,
    /**
     * OPT-IN periodic WorkManager background scan (default FALSE — a background
     * scan costs real battery/data; nothing runs until the user enables it).
     */
    val backgroundScansEnabled: Boolean = false,
    /** When true (default) the periodic work is constrained to unmetered networks. */
    val backgroundScansUnmetered: Boolean = true,
)
