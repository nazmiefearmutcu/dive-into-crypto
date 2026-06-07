package com.diveintocrypto.android.data.binance.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsKlinePayload(
    @SerialName("t") val openTime: Long,
    @SerialName("T") val closeTime: Long,
    @SerialName("o") val open: String,
    @SerialName("c") val close: String,
    @SerialName("h") val high: String,
    @SerialName("l") val low: String,
    @SerialName("v") val volume: String,
    @SerialName("x") val isClosed: Boolean,
)

@Serializable
data class WsKlineEnvelope(
    @SerialName("e") val eventType: String,
    @SerialName("s") val symbol: String,
    @SerialName("k") val kline: WsKlinePayload,
)
