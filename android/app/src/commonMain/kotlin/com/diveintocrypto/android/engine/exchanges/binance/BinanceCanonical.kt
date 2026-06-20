package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.engine.schema.Funding
import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.OpenInterest
import com.diveintocrypto.android.engine.schema.Venue

/** Binance ms epoch → ns. */
private const val MS_TO_NS = 1_000_000L

/** Canonical symbol for a USDT-M perp raw symbol. */
internal fun usdmSymbol(raw: String): String = "${Venue.BINANCE_USDM.wire}:$raw"

/** [Candle] → canonical [OHLCV] (USD-M venue; ms→ns timestamps). */
fun Candle.toOhlcv(symbolRaw: String, interval: String, localTsMs: Long): OHLCV = OHLCV(
    venue = Venue.BINANCE_USDM,
    symbol = usdmSymbol(symbolRaw),
    symbolRaw = symbolRaw,
    exchangeTs = openTime * MS_TO_NS,
    localTs = localTsMs * MS_TO_NS,
    open = open, high = high, low = low, close = close, volume = volume,
    interval = interval,
)

/** [OpenInterestPoint] → canonical [OpenInterest]. */
fun OpenInterestPoint.toOpenInterest(symbolRaw: String, localTsMs: Long): OpenInterest = OpenInterest(
    venue = Venue.BINANCE_USDM,
    symbol = usdmSymbol(symbolRaw),
    symbolRaw = symbolRaw,
    exchangeTs = timestamp * MS_TO_NS,
    localTs = localTsMs * MS_TO_NS,
    openInterest = sumOpenInterest,
    openInterestValue = sumOpenInterestValue,
)

/** [FundingRatePoint] → canonical [Funding] (Binance USD-M funds every 8h). */
fun FundingRatePoint.toFunding(symbolRaw: String, localTsMs: Long): Funding = Funding(
    venue = Venue.BINANCE_USDM,
    symbol = usdmSymbol(symbolRaw),
    symbolRaw = symbolRaw,
    exchangeTs = timestamp * MS_TO_NS,
    localTs = localTsMs * MS_TO_NS,
    fundingRate = fundingRate,
    fundingTs = timestamp * MS_TO_NS,
    intervalHours = 8,
)
