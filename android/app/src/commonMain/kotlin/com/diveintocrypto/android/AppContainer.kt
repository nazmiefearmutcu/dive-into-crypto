package com.diveintocrypto.android

import com.diveintocrypto.android.data.KeyValueStore
import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.engine.MarketDataEngine
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
import com.diveintocrypto.android.domain.consensus.ConsensusConfig
import com.diveintocrypto.android.domain.consensus.ConsensusEngine
import com.diveintocrypto.android.domain.indicator.AdxDiIndicator
import com.diveintocrypto.android.domain.indicator.AtrFilterIndicator
import com.diveintocrypto.android.domain.indicator.BollingerIndicator
import com.diveintocrypto.android.domain.indicator.CciIndicator
import com.diveintocrypto.android.domain.indicator.EmaCrossIndicator
import com.diveintocrypto.android.domain.indicator.IchimokuIndicator
import com.diveintocrypto.android.domain.indicator.MacdIndicator
import com.diveintocrypto.android.domain.indicator.MfiIndicator
import com.diveintocrypto.android.domain.indicator.ObvIndicator
import com.diveintocrypto.android.domain.indicator.PsarIndicator
import com.diveintocrypto.android.domain.indicator.RocIndicator
import com.diveintocrypto.android.domain.indicator.RsiIndicator
import com.diveintocrypto.android.domain.indicator.SmaCrossIndicator
import com.diveintocrypto.android.domain.indicator.StochasticIndicator
import com.diveintocrypto.android.domain.indicator.WilliamsRIndicator
import com.diveintocrypto.android.domain.model.IndicatorConfig

/** Indicator weights — verbatim from the original Python reference implementation. */
val ALL_INDICATOR_WEIGHTS: Map<String, Double> = mapOf(
    "rsi" to 1.5,
    "macd" to 2.0,
    "bollinger" to 1.5,
    "ema_cross" to 1.8,
    "sma_cross" to 1.8,
    "stochastic" to 1.2,
    "adx_di" to 1.5,
    "cci" to 1.0,
    "williams_r" to 1.0,
    "roc" to 1.0,
    "mfi" to 1.2,
    "atr_filter" to 0.0,
    "ichimoku" to 2.0,
    "psar" to 1.3,
    "obv" to 1.2,
)

/**
 * Dependency container for the trimmed (no paper / no bot) app.
 *
 *   - `repository`   → unified market-data surface
 *   - `consensus`    → ConsensusEngine for indicator voting
 *   - `indicators`   → 15-indicator pipeline
 *
 * Every paper/bot dependency (BotEngine, PaperExecutionEngine, DecisionEngine,
 * PositionManager, LeverageManager, ConfigStore) was deleted.
 */
class AppContainer(kv: KeyValueStore) {

    init {
        // Load the persisted theme (preset + appearance axes) before first composition.
        com.diveintocrypto.android.ui.theme.DiveThemeController.init(kv)
    }

    val settingsStore = SettingsStore(kv)

    val activeSymbol = kotlinx.coroutines.flow.MutableStateFlow("BTCUSDT")
    val activeTimeframe = kotlinx.coroutines.flow.MutableStateFlow("1h")

    val repository: MarketDataEngine by lazy {
        MarketDataEngine(
            binance = BinanceConnector(
                spot = BinanceSpotClient(),
                futures = BinanceFuturesClient(),
                ws = BinanceWsClient(),
            ),
            settingsStore = settingsStore,
        )
    }

    val consensus: ConsensusEngine by lazy {
        ConsensusEngine(settingsStore)
    }

    /**
     * App-scoped scanner ViewModel — a SINGLE instance bound to the app lifetime.
     * Bound here (NOT to a screen lifetime via viewModel{}) so the scan does NOT
     * stop when navigating between screens (Panel/Signals/...) or when the app is
     * backgrounded. Unless the process is killed (process death), the scan and its
     * results are preserved.
     */
    val scannerViewModel: com.diveintocrypto.android.ui.scanner.ScannerViewModel by lazy {
        com.diveintocrypto.android.ui.scanner.ScannerViewModel(this)
    }

    val indicators by lazy {
        listOf(
            RsiIndicator(IndicatorConfig(mapOf(
                "period" to 14.0, "strong_buy" to 25.0, "buy" to 35.0,
                "sell" to 65.0, "strong_sell" to 80.0,
            ))),
            MacdIndicator(IndicatorConfig(mapOf(
                "fast_period" to 12.0, "slow_period" to 26.0,
                "signal_period" to 9.0, "strong_histogram_threshold" to 0.5,
            ))),
            BollingerIndicator(IndicatorConfig(mapOf(
                "period" to 20.0, "std_dev" to 2.0, "squeeze_threshold" to 0.02,
            ))),
            EmaCrossIndicator(IndicatorConfig(mapOf(
                "short_period" to 9.0, "long_period" to 21.0,
                "strong_divergence_pct" to 0.02,
            ))),
            SmaCrossIndicator(IndicatorConfig(mapOf(
                "short_period" to 10.0, "long_period" to 50.0,
                "strong_divergence_pct" to 0.02,
            ))),
            StochasticIndicator(IndicatorConfig(mapOf(
                "k_period" to 14.0, "d_period" to 3.0,
                "oversold" to 20.0, "overbought" to 80.0,
            ))),
            AdxDiIndicator(IndicatorConfig(mapOf(
                "period" to 14.0, "strong_trend" to 25.0, "weak_trend" to 15.0,
            ))),
            CciIndicator(IndicatorConfig(mapOf(
                "period" to 20.0, "strong_buy" to -200.0, "buy" to -100.0,
                "sell" to 100.0, "strong_sell" to 200.0,
            ))),
            WilliamsRIndicator(IndicatorConfig(mapOf(
                "period" to 14.0, "oversold" to -80.0, "overbought" to -20.0,
            ))),
            RocIndicator(IndicatorConfig(mapOf(
                "period" to 12.0, "weak_threshold" to 1.0, "strong_threshold" to 5.0,
            ))),
            MfiIndicator(IndicatorConfig(mapOf(
                "period" to 14.0, "strong_buy" to 20.0, "buy" to 30.0,
                "sell" to 70.0, "strong_sell" to 80.0,
            ))),
            AtrFilterIndicator(IndicatorConfig(mapOf(
                "period" to 14.0, "high_volatility_multiplier" to 2.0,
            ))),
            IchimokuIndicator(IndicatorConfig(mapOf(
                "tenkan_period" to 9.0, "kijun_period" to 26.0,
                "senkou_b_period" to 52.0,
            ))),
            PsarIndicator(IndicatorConfig(mapOf(
                "af_start" to 0.02, "af_increment" to 0.02, "af_max" to 0.2,
            ))),
            ObvIndicator(IndicatorConfig(mapOf(
                "sma_period" to 20.0, "divergence_lookback" to 10.0,
            ))),
        )
    }
}
