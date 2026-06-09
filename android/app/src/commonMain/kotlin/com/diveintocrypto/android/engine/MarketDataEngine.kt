package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
import com.diveintocrypto.android.engine.exchanges.binance.toOhlcv
import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single on-device market-data source — the Crypcodile-KMP engine façade.
 *
 * Exposes the exact method surface the screens already use (so existing
 * ViewModels are untouched) PLUS canonical [OHLCV] flows that deep-data
 * features build on. Backed by [BinanceConnector] in M1; the Deribit connector
 * and cross-channel canonical streams arrive in later milestones.
 */
class MarketDataEngine(
    private val binance: BinanceConnector,
    private val settingsStore: SettingsStore,
) {
    suspend fun history(symbol: String, interval: String, limit: Int = 300): List<Candle> =
        binance.spotClient().klines(symbol = symbol, interval = interval, limit = limit)

    fun liveKlines(symbol: String, interval: String): Flow<BinanceWsClient.KlineUpdate> {
        val settings = settingsStore.getSettings()
        val wsUrl = if (settings.wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        return binance.wsClient().klineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
    }

    suspend fun futuresHistory(symbol: String, interval: String, limit: Int = 300): List<Candle> =
        binance.futuresClient().klines(symbol = symbol, interval = interval, limit = limit)

    suspend fun futuresUniverse(): List<String> = binance.futuresClient().universe24hSortedByVolume()
    suspend fun ticker24hAll(): List<Ticker24h> = binance.futuresClient().ticker24hAll()
    suspend fun openInterestHist(symbol: String, period: String = "1h", limit: Int = 30): List<OpenInterestPoint> =
        binance.futuresClient().openInterestHist(symbol, period, limit)
    suspend fun topLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        binance.futuresClient().topLongShortAccountRatio(symbol, period, limit)
    suspend fun topLongShortPositionRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        binance.futuresClient().topLongShortPositionRatio(symbol, period, limit)
    suspend fun globalLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        binance.futuresClient().globalLongShortAccountRatio(symbol, period, limit)
    suspend fun takerLongShortRatio(symbol: String, period: String = "1h", limit: Int = 30): List<TakerLongShortRatioPoint> =
        binance.futuresClient().takerLongShortRatio(symbol, period, limit)
    suspend fun fundingRate(symbol: String, limit: Int = 30): List<FundingRatePoint> =
        binance.futuresClient().fundingRate(symbol, limit)

    /** Live canonical OHLCV for the active symbol/interval (futures WS). */
    fun liveOhlcv(symbol: String, interval: String): Flow<OHLCV> {
        val settings = settingsStore.getSettings()
        val wsUrl = if (settings.wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        return binance.wsClient().klineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
            .map { it.candle.toOhlcv(symbol, interval, nowMillis()) }
    }
}
