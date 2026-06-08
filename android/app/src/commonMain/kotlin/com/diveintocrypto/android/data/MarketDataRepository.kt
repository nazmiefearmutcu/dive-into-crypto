package com.diveintocrypto.android.data

import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.domain.model.Candle
import kotlinx.coroutines.flow.Flow

/**
 * Single market-data surface for the trimmed app. Wraps three Binance clients:
 *   - [BinanceSpotClient]    → live ticker stream consumers (Panel uses spot
 *     candles for the active-symbol live view because spot WS is the canonical
 *     "true" tape; futures WS is layered on next).
 *   - [BinanceFuturesClient] → universe + per-symbol futures klines + 24h
 *     ticker + Open Interest history + Top Long/Short ratios.
 *   - [BinanceWsClient]      → live kline stream subscription.
 *
 * After paper-mode removal these are the ONLY data-source APIs the screens
 * consume — every paper/bot indirection is gone.
 */
class MarketDataRepository(
    private val rest: BinanceSpotClient,
    private val futures: BinanceFuturesClient,
    private val ws: BinanceWsClient,
    private val settingsStore: SettingsStore
) {
    // ── Spot ── (Panel + Signals live view)
    suspend fun history(symbol: String, interval: String, limit: Int = 300): List<Candle> =
        rest.klines(symbol = symbol, interval = interval, limit = limit)

    fun liveKlines(symbol: String, interval: String): Flow<BinanceWsClient.KlineUpdate> {
        val settings = settingsStore.getSettings()
        val customWsUrl = if (settings.wsDataSource == "SPOT") {
            "wss://stream.binance.com:9443"
        } else {
            "wss://fstream.binance.com"
        }
        return ws.klineStream(symbol = symbol, interval = interval, customBaseUrl = customWsUrl)
    }

    // ── Futures ── (Scanner + Performance leaderboard + Positions OI/LS)
    suspend fun futuresHistory(symbol: String, interval: String, limit: Int = 300): List<Candle> =
        futures.klines(symbol = symbol, interval = interval, limit = limit)

    suspend fun futuresUniverse(): List<String> = futures.universe24hSortedByVolume()

    suspend fun ticker24hAll(): List<Ticker24h> = futures.ticker24hAll()

    suspend fun openInterestHist(symbol: String, period: String = "1h", limit: Int = 30): List<OpenInterestPoint> =
        futures.openInterestHist(symbol, period, limit)

    suspend fun topLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        futures.topLongShortAccountRatio(symbol, period, limit)

    suspend fun topLongShortPositionRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        futures.topLongShortPositionRatio(symbol, period, limit)

    suspend fun globalLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        futures.globalLongShortAccountRatio(symbol, period, limit)

    suspend fun takerLongShortRatio(symbol: String, period: String = "1h", limit: Int = 30): List<TakerLongShortRatioPoint> =
        futures.takerLongShortRatio(symbol, period, limit)

    suspend fun fundingRate(symbol: String, limit: Int = 30): List<FundingRatePoint> =
        futures.fundingRate(symbol, limit)
}
