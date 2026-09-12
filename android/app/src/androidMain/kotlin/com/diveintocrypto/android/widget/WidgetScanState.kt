package com.diveintocrypto.android.widget

import com.diveintocrypto.android.data.SettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One widget row: last scan's survivor head. */
@Serializable
data class WidgetVerdictRow(
    val symbol: String,
    /** Signal name (BUY / SELL / STRONG_BUY / STRONG_SELL / NEUTRAL). */
    val verdict: String,
    /** 0..100. */
    val confidence: Int,
)

/**
 * What the TopVerdicts widget renders: the last scan's top survivors + when
 * the scan ran (the data-age line keeps a deferred Doze run HONEST).
 * Persisted through [SettingsStore.putRaw] so the widget (which may wake in a
 * fresh process) reads exactly what the worker wrote.
 */
@Serializable
data class WidgetScanState(
    val scannedAtMs: Long,
    val items: List<WidgetVerdictRow> = emptyList(),
) {
    companion object {
        const val KEY = "widget_last_scan_v1"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun save(store: SettingsStore, state: WidgetScanState) {
            runCatching { store.putRaw(KEY, json.encodeToString(state)) }
        }

        /** null = no widget state ever written (widget shows the honest "veri yok"). */
        fun load(store: SettingsStore): WidgetScanState? = runCatching {
            val raw = store.getRaw(KEY) ?: return null
            if (raw.isBlank()) null else json.decodeFromString<WidgetScanState>(raw)
        }.getOrNull()
    }
}
