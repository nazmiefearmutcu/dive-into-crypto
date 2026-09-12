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
import com.diveintocrypto.android.domain.consensus.DEFAULT_FULL_WEIGHTS
import com.diveintocrypto.android.domain.indicator.*
import com.diveintocrypto.android.domain.model.IndicatorConfig

// NOTE: the full 60-name indicator weight map previously lived here as
// `ALL_INDICATOR_WEIGHTS` but was never consumed — Scorer fell back to `?: 1.0`
// and every extended indicator scored at weight 1.0. The canonical map now lives
// in `domain/consensus/Weights.kt` ([DEFAULT_FULL_WEIGHTS]) and IS wired into
// [SettingsStore] defaults + [ConsensusEngine] construction below.

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

    private val kvStore: KeyValueStore = kv

    val settingsStore = SettingsStore(kv)

    // ── LANGUAGE (TR/EN, LANE-7 i18n) ─────────────────────────────────────────
    // Active string catalog, resolved once from the persisted "language" key
    // (default "tr") and swapped via [setLanguage] (persists immediately).
    // The Compose root provides it as LocalDiveStrings.
    private val languageFlow = kotlinx.coroutines.flow.MutableStateFlow(
        com.diveintocrypto.android.ui.i18n.languageFor(kvStore),
    )

    /** Active [com.diveintocrypto.android.ui.i18n.DiveStrings] catalog (StateFlow). */
    val language: kotlinx.coroutines.flow.StateFlow<com.diveintocrypto.android.ui.i18n.DiveStrings>
        get() = languageFlow

    /** Persists the language ("tr"/"en") and swaps the active catalog. */
    fun setLanguage(code: String) {
        kvStore.putString(com.diveintocrypto.android.ui.i18n.KEY_LANGUAGE, code)
        languageFlow.value = com.diveintocrypto.android.ui.i18n.languageFor(kvStore)
    }

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

    /**
     * App-scoped live last-price engine: ONE all-market mini-ticker socket
     * (reconnecting) + a 60s REST 24h refresh, merged into a single
     * symbol→[com.diveintocrypto.android.engine.LiveTickerEngine.LiveTicker] map.
     * Lazy + idempotent [ensureStarted] — the socket comes up on first use
     * (watchlist rows, scanner, price alerts).
     */
    val liveTickerEngine: com.diveintocrypto.android.engine.LiveTickerEngine by lazy {
        val connector = repository.binanceConnector()
        com.diveintocrypto.android.engine.LiveTickerEngine(
            futures = connector.futuresClient(),
            spot = connector.spotClient(),
            ws = connector.wsClient(),
            settingsStore = settingsStore,
        )
    }

    /**
     * Verdict-evidence archive (bounded JSONL in KeyValueStore) + the pure
     * grader live in domain/evidence; the Performance screen drives grading.
     */
    val evidenceStore: com.diveintocrypto.android.domain.evidence.EvidenceStore by lazy {
        com.diveintocrypto.android.domain.evidence.EvidenceStore(kvStore)
    }

    /**
     * Local alert engine. lazily created; the ticker-observation loop is armed
     * only when a PRICE_* rule exists (created or restored), so a user who never
     * sets alerts pays nothing.
     */
    val alertEngine: com.diveintocrypto.android.domain.alerts.AlertEngine by lazy {
        com.diveintocrypto.android.domain.alerts.AlertEngine(
            settingsStore = settingsStore,
            notifier = com.diveintocrypto.android.domain.alerts.createAlertNotifier(),
            tickerSource = liveTickerEngine.tickers,
        )
    }

    // ── PUBLIC ALERT API (the UI lane renders rules/history/banner from these) ──

    /** All alert rules (persisted). */
    val rules: kotlinx.coroutines.flow.StateFlow<List<com.diveintocrypto.android.domain.alerts.AlertRule>>
        get() = alertEngine.rules

    /** Fired-alert ring, newest first (last [com.diveintocrypto.android.domain.alerts.AlertEngine.HISTORY_CAP] events). */
    val firedHistory: kotlinx.coroutines.flow.StateFlow<List<com.diveintocrypto.android.domain.alerts.FiredAlert>>
        get() = alertEngine.firedHistory

    /** Latest fired alert for the in-app banner (null = nothing pending). */
    val alertBanner: kotlinx.coroutines.flow.StateFlow<com.diveintocrypto.android.domain.alerts.FiredAlert?>
        get() = alertEngine.banner

    fun addRule(
        symbol: String,
        kind: com.diveintocrypto.android.domain.alerts.AlertKind,
        direction: String = com.diveintocrypto.android.domain.alerts.AlertRule.DIRECTION_ANY,
        threshold: Double = 0.0,
        oneShot: Boolean = false,
    ): com.diveintocrypto.android.domain.alerts.AlertRule =
        alertEngine.addRule(symbol, kind, direction, threshold, oneShot)

    /**
     * v2 add: a rule from an AND-ed condition group (chips for coalescing:
     * 1m/15m/1h → [AlertRule.COALESCE_1M]/[COALESCE_15M]/[COALESCE_1H];
     * "once" → [oneShot]). The old single-condition signature keeps working.
     */
    fun addRule(
        symbol: String,
        conditions: List<com.diveintocrypto.android.domain.alerts.AlertCondition>,
        oneShot: Boolean = false,
        coalesceMs: Long = com.diveintocrypto.android.domain.alerts.AlertRule.COALESCE_DEFAULT_MS,
    ): com.diveintocrypto.android.domain.alerts.AlertRule =
        alertEngine.addRule(symbol, conditions, oneShot, coalesceMs)

    /** Per-rule re-fire coalescing window (ms); 0 = engine default. */
    fun setRuleCoalesceMs(id: String, coalesceMs: Long) =
        alertEngine.setRuleCoalesceMs(id, coalesceMs)

    fun removeRule(id: String) = alertEngine.removeRule(id)

    fun toggleRule(id: String, enabled: Boolean? = null) = alertEngine.toggleRule(id, enabled)

    fun dismissAlertBanner() = alertEngine.dismissBanner()

    // ── PORTFOLIO TRACKER (local-only; never networked) ────────────────────────

    /**
     * Local portfolio store: persisted entries ([PortfolioStore.KEY] blob) +
     * P&L recomputed on every live-ticker tick against the engine's REAL mark
     * prices. Constructing it wires the (lazy) ticker engine as the mark source
     * and the evidence archive as the engine-agreement verdict source.
     */
    val portfolioStore: com.diveintocrypto.android.domain.portfolio.PortfolioStore by lazy {
        com.diveintocrypto.android.domain.portfolio.PortfolioStore(
            settingsStore = settingsStore,
            verdictProvider = com.diveintocrypto.android.domain.portfolio
                .EvidenceStoreVerdictProvider(evidenceStore),
            tickerSource = liveTickerEngine.tickers,
        )
    }

    /** All local portfolio entries (persisted). */
    val portfolioEntries: kotlinx.coroutines.flow.StateFlow<List<com.diveintocrypto.android.domain.portfolio.PortfolioEntry>>
        get() = portfolioStore.entries

    /** Direction-adjusted P&L per entry, recomputed on ticker ticks. */
    val portfolioPnl: kotlinx.coroutines.flow.StateFlow<List<com.diveintocrypto.android.domain.portfolio.PositionPnl>>
        get() = portfolioStore.pnl

    fun addPortfolioEntry(
        symbol: String,
        entryPrice: Double,
        size: Double,
        direction: String,
    ): com.diveintocrypto.android.domain.portfolio.PortfolioEntry =
        portfolioStore.add(symbol, entryPrice, size, direction)

    fun removePortfolioEntry(id: String) = portfolioStore.remove(id)

    fun updatePortfolioEntry(
        id: String,
        entryPrice: Double? = null,
        size: Double? = null,
        direction: String? = null,
    ) = portfolioStore.update(id, entryPrice, size, direction)

    val consensus: ConsensusEngine by lazy {
        // Explicit full-weights wiring: settingsStore carries all 60 default weights
        // (user overrides on top); DEFAULT_FULL_WEIGHTS is the no-store fallback.
        ConsensusEngine(settingsStore, DEFAULT_FULL_WEIGHTS)
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
            // ── Extended set (desktop-reference parity, 2026-07-20). Empty config
            //    → each indicator's in-code defaults, which mirror the Python
            //    reference (`self.thresholds.get(key, default)`). ──────────────
            SupertrendIndicator(IndicatorConfig()),
            AwesomeOscillatorIndicator(IndicatorConfig()),
            CmfIndicator(IndicatorConfig()),
            SqueezeIndicator(IndicatorConfig()),
            ChoppinessIndicator(IndicatorConfig()),
            VwapIndicator(IndicatorConfig()),
            VortexIndicator(IndicatorConfig()),
            KeltnerBreakoutIndicator(IndicatorConfig()),
            DonchianBreakoutIndicator(IndicatorConfig()),
            ElderRayIndicator(IndicatorConfig()),
            TrixIndicator(IndicatorConfig()),
            CoppockCurveIndicator(IndicatorConfig()),
            KstIndicator(IndicatorConfig()),
            SchaffTrendCycleIndicator(IndicatorConfig()),
            FisherTransformIndicator(IndicatorConfig()),
            ConnorsRsiIndicator(IndicatorConfig()),
            StochRsiIndicator(IndicatorConfig()),
            UltimateOscillatorIndicator(IndicatorConfig()),
            WavetrendIndicator(IndicatorConfig()),
            DpoIndicator(IndicatorConfig()),
            AroonOscillatorIndicator(IndicatorConfig()),
            ChaikinOscillatorIndicator(IndicatorConfig()),
            KlingerOscillatorIndicator(IndicatorConfig()),
            AccumDistLineIndicator(IndicatorConfig()),
            BalanceOfPowerIndicator(IndicatorConfig()),
            RelativeVigorIndexIndicator(IndicatorConfig()),
            MassIndexIndicator(IndicatorConfig()),
            CmoIndicator(IndicatorConfig()),
            TsiIndicator(IndicatorConfig()),
            VwmaCrossIndicator(IndicatorConfig()),
            QstickIndicator(IndicatorConfig()),
            ForceIndexIndicator(IndicatorConfig()),
            BollingerPercentBIndicator(IndicatorConfig()),
            ZscoreReversionIndicator(IndicatorConfig()),
            LinregSlopeIndicator(IndicatorConfig()),
            AtrPercentileIndicator(IndicatorConfig()),
            HistVolPercentileIndicator(IndicatorConfig()),
            HurstIndicator(IndicatorConfig()),
            RangeExpansionIndicator(IndicatorConfig()),
            KalmanTrendIndicator(IndicatorConfig()),
            HalfLifeReversionIndicator(IndicatorConfig()),
            RollingSharpeIndicator(IndicatorConfig()),
            // ── Price-action pattern library (desktop-reference parity, 2026-09-12) ──
            EngulfingIndicator(IndicatorConfig()),
            LiquiditySweepIndicator(IndicatorConfig()),
            PivotStructureIndicator(IndicatorConfig()),
        )
    }
}
