package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.engine.exchanges.Connector
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.Record
import com.diveintocrypto.android.engine.schema.Venue
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Binance venue connector. In M1 it wraps the proven REST/WS clients and
 * exposes a canonical live OHLCV stream + OHLCV backfill. Depth/trade/liquidation
 * canonical streams arrive in a later milestone; for unsupported channels
 * [stream] emits nothing and [backfill] returns empty.
 *
 * Defaults to USD-M futures (the app's primary market).
 */
class BinanceConnector(
    private val spot: BinanceSpotClient = BinanceSpotClient(),
    private val futures: BinanceFuturesClient = BinanceFuturesClient(),
    private val ws: BinanceWsClient = BinanceWsClient(),
) : Connector {
    override val venue: Venue = Venue.BINANCE_USDM

    /** Live OHLCV stream for one symbol/interval from the futures WS, mapped to canonical [OHLCV]. */
    fun ohlcvStream(symbolRaw: String, interval: String, wsBaseUrl: String? = null): Flow<OHLCV> =
        ws.klineStream(symbol = symbolRaw, interval = interval, customBaseUrl = wsBaseUrl)
            .map { update -> update.candle.toOhlcv(symbolRaw, interval, nowMillis()) }

    override fun stream(channels: Set<Channel>, symbols: Set<String>): Flow<Record> =
        kotlinx.coroutines.flow.flow {
            // M1: only OHLCV streaming is wired here, exposed via [ohlcvStream] which the
            // engine uses directly. Generic multi-channel streaming is added with Deribit (M3).
        }

    override suspend fun backfill(
        channel: Channel,
        symbolRaw: String,
        startNs: Long,
        endNs: Long,
        limit: Int,
    ): List<Record> = when (channel) {
        Channel.OHLCV -> {
            val now = nowMillis()
            futures.klines(symbol = symbolRaw, interval = "1m", limit = limit)
                .map { it.toOhlcv(symbolRaw, interval = "1m", localTsMs = now) }
        }
        else -> emptyList()
    }

    // Passthrough accessors used by the façade for the existing screen surface.
    internal fun spotClient() = spot
    internal fun futuresClient() = futures
    internal fun wsClient() = ws
}
