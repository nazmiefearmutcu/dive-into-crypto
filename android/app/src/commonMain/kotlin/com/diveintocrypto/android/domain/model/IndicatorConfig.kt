package com.diveintocrypto.android.domain.model

data class IndicatorConfig(
    val thresholds: Map<String, Double> = emptyMap(),
) {
    fun getDouble(key: String, default: Double): Double = thresholds[key] ?: default
    fun getInt(key: String, default: Int): Int = thresholds[key]?.toInt() ?: default
}
