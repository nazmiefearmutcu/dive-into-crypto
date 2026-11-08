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

    /**
     * Live OHLCV stream for one symbol/interval from the futures (or, with [venue] =
     * BINANCE_SPOT and a spot [wsBaseUrl], the spot) WS, mapped to canonical [OHLCV].
     * Survives disconnects via [BinanceWsClient.reconnectingKlineStream].
     */
    fun ohlcvStream(
        symbolRaw: String,
        interval: String,
        wsBaseUrl: String? = null,
        venue: Venue = Venue.BINANCE_USDM,
    ): Flow<OHLCV> =
        ws.reconnectingKlineStream(symbol = symbolRaw, interval = interval, customBaseUrl = wsBaseUrl)
            .map { update -> update.candle.toOhlcv(symbolRaw, interval, nowMillis(), venue) }

    override fun stream(channels: Set<Channel>, symbols: Set<String>): Flow<Record> =
        kotlinx.coroutines.flow.flow {
            // M1: only OHLCV streaming is wired here, exposed via [ohlcvStream] which the
            // engine uses directly. Generic multi-channel streaming is added with Deribit (M3).
        }

    /**
     * 1-minute OHLCV backfill over [startNs, endNs] (ns, UTC), at most [limit] bars.
     * A bound of 0 (start) or Long.MAX_VALUE / 0 (end) means "unbounded" on that side.
     * The [Connector] contract carries no interval, so the bars are always 1m.
     */
    override suspend fun backfill(
        channel: Channel,
        symbolRaw: String,
        startNs: Long,
        endNs: Long,
        limit: Int,
    ): List<Record> = when (channel) {
        Channel.OHLCV -> {
            val now = nowMillis()
            val startMs = if (startNs > 0L) startNs / NS_PER_MS else null
            val endMs = if (endNs > 0L && endNs < Long.MAX_VALUE) endNs / NS_PER_MS else null
            futures.klines(
                symbol = symbolRaw,
                interval = "1m",
                limit = limit,
                startTimeMs = startMs,
                endTimeMs = endMs,
            ).map { it.toOhlcv(symbolRaw, interval = "1m", localTsMs = now) }
        }
        else -> emptyList()
    }

    // Passthrough accessors used by the façade for the existing screen surface.
    internal fun spotClient() = spot
    internal fun futuresClient() = futures
    internal fun wsClient() = ws

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}
