package com.diveintocrypto.android.engine.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical market-data record — Kotlin port of Crypcodile `schema/records.py`.
 * Polymorphic (tagged) so the engine can stream heterogeneous records. Each
 * concrete type exposes its [channel]. Timestamps are nanoseconds UTC.
 */
@Serializable
sealed interface Record {
    val venue: Venue
    val symbol: String        // canonical "{venue}:{raw}"
    val symbolRaw: String
    val exchangeTs: Long?      // ns, nullable (some venues omit)
    val localTs: Long          // ns, receive time
    val channel: Channel
}

/** A single price level `(price, amount)`. amount==0.0 in a delta ⇒ remove level. */
@Serializable
data class Level(val price: Double, val amount: Double)

@Serializable
@SerialName("trade")
data class Trade(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val id: String? = null,
    val price: Double,
    val amount: Double,
    val side: Side,
    val liquidation: Boolean = false,
) : Record { override val channel get() = Channel.TRADE }

@Serializable
@SerialName("book_snapshot")
data class BookSnapshot(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val bids: List<Level>,
    val asks: List<Level>,
    val depth: Int? = null,
    val seqId: Long? = null,
) : Record { override val channel get() = Channel.BOOK_SNAPSHOT }

@Serializable
@SerialName("book_delta")
data class BookDelta(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val bids: List<Level>,
    val asks: List<Level>,
    val seqId: Long? = null,
    val prevSeqId: Long? = null,
) : Record { override val channel get() = Channel.BOOK_DELTA }

@Serializable
@SerialName("book_ticker")
data class BookTicker(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val bidPx: Double,
    val bidSz: Double,
    val askPx: Double,
    val askSz: Double,
    val updateId: Long? = null,
) : Record { override val channel get() = Channel.BOOK_TICKER }

@Serializable
@SerialName("derivative_ticker")
data class DerivativeTicker(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val lastPrice: Double? = null,
    val markPrice: Double? = null,
    val indexPrice: Double? = null,
    val fundingRate: Double? = null,
    val predictedFundingRate: Double? = null,
    val fundingTs: Long? = null,
    val openInterest: Double? = null,
) : Record { override val channel get() = Channel.DERIVATIVE_TICKER }

@Serializable
@SerialName("options_chain")
data class OptionsChain(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val underlying: String,
    val underlyingPrice: Double? = null,
    val strike: Double,
    val expiry: Long,                 // ns UTC
    val optType: OptType,
    val markPrice: Double? = null,
    val bidPx: Double? = null,
    val bidSz: Double? = null,
    val askPx: Double? = null,
    val askSz: Double? = null,
    val lastPrice: Double? = null,
    val markIv: Double? = null,
    val bidIv: Double? = null,
    val askIv: Double? = null,
    val delta: Double? = null,
    val gamma: Double? = null,
    val vega: Double? = null,
    val theta: Double? = null,
    val rho: Double? = null,
    val openInterest: Double? = null,
) : Record { override val channel get() = Channel.OPTIONS_CHAIN }

@Serializable
@SerialName("funding")
data class Funding(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val fundingRate: Double,
    val predictedFundingRate: Double? = null,
    val fundingTs: Long,
    val intervalHours: Int = 8,
) : Record { override val channel get() = Channel.FUNDING }

@Serializable
@SerialName("open_interest")
data class OpenInterest(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val openInterest: Double,
    val openInterestValue: Double? = null,
) : Record { override val channel get() = Channel.OPEN_INTEREST }

@Serializable
@SerialName("liquidation")
data class Liquidation(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val price: Double,
    val amount: Double,
    val side: Side,
    val id: String? = null,
) : Record { override val channel get() = Channel.LIQUIDATION }

@Serializable
@SerialName("ohlcv")
data class OHLCV(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val buyVolume: Double = 0.0,
    val sellVolume: Double = 0.0,
    val numTrades: Int = 0,
    val interval: String,
) : Record { override val channel get() = Channel.OHLCV }
