package com.diveintocrypto.android.domain.model

data class IndicatorResult(
    val name: String,
    val signal: Signal,
    val reason: String,
    val rawValues: Map<String, Double?> = emptyMap(),
) {
    val score: Int get() = signal.score
}
