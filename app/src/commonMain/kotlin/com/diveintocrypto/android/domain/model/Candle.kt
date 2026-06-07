package com.diveintocrypto.android.domain.model

/** OHLCV candle. Timestamps are epoch millis. */
data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
)
