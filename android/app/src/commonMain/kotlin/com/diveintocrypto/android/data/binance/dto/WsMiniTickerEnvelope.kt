package com.diveintocrypto.android.data.binance.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One element of the all-market `!miniTicker@arr` stream payload.
 *
 * Wire shape (a JSON ARRAY of these objects, pushed every ~1 second — one
 * socket covers EVERY symbol on the venue):
 *
 * ```
 * [{"e":"24hrMiniTicker","E":123456789,"s":"BTCUSDT","c":"0.0025","o":"0.0010",
 *   "h":"0.0025","l":"0.0010","v":"10000","q":"18"}]
 * ```
 *
 * All numeric fields arrive as strings, matching the kline payload convention.
 */
@Serializable
data class WsMiniTickerEnvelope(
    @SerialName("e") val eventType: String,
    @SerialName("E") val eventTime: Long = 0L,
    @SerialName("s") val symbol: String,
    @SerialName("o") val open: String,
    @SerialName("c") val close: String,
    @SerialName("h") val high: String,
    @SerialName("l") val low: String,
    @SerialName("v") val baseVolume: String = "0",
    @SerialName("q") val quoteVolume: String = "0",
)
