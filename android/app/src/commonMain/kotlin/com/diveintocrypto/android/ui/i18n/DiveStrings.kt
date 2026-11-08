package com.diveintocrypto.android.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.diveintocrypto.android.data.KeyValueStore
import com.diveintocrypto.android.platform.deviceLanguageTag
import com.diveintocrypto.android.ui.nav.NavRoute

/**
 * TR/EN string catalog for the shared Compose UI (LANE-7 i18n layer).
 *
 *   - [DiveStrings]  — the interface every screen consumes via [LocalDiveStrings].
 *   - [TrStrings]    — DEFAULT catalog: values are today's on-screen Turkish text
 *     copied VERBATIM, so TR mode renders pixel-identically to pre-i18n.
 *   - [EnStrings]    — English equivalents.
 *
 * The active catalog is resolved from the KeyValueStore key "language" via
 * [languageFor]: an explicit "tr"/"en" value wins; without one, a Turkish device
 * locale gives TR and any other locale gives EN. [com.diveintocrypto.android.AppContainer]
 * exposes it as a StateFlow + a persisting setter. Long-tail screens (Scanner
 * microcopy, About, Appearance, page-header timestamps…) still render literals.
 */
interface DiveStrings {

    /** BCP-47-ish code of this catalog ("tr" / "en"). */
    val code: String

    // ── Navigation / shell ──────────────────────────────────────────────────
    val navPanel: String
    val navScanner: String
    val navPositions: String
    val navSignals: String
    val navAlerts: String
    val navPortfolio: String
    val navPerformance: String
    val navLogs: String
    val navAppearance: String
    val navSettings: String
    val navMore: String
    val moreSheetTitle: String
    val tabPanel: String
    val tabOiLs: String
    val tabSignals: String

    // ── A11y / state words ─────────────────────────────────────────────────
    val selected: String
    val notSelected: String
    val switchOn: String
    val switchOff: String
    val active: String
    val passive: String

    // ── Pulse strip ────────────────────────────────────────────────────────
    val funding: String

    // ── Settings ───────────────────────────────────────────────────────────
    val setTitleFavorites: String
    val setTitleConsensus: String
    val setTitleWeights: String
    val setTitleScanner: String
    val setTitleQuantChart: String
    val setTitleQuantBias: String
    val setTitleNotifications: String
    val setTitleBackgroundScans: String
    val setTitleTheme: String
    val setTitleAbout: String
    val setTitleLanguage: String
    val tglBackgroundScan: String
    val tglUnmetered: String
    val lblCore: String
    val lblExtended: String
    val btnResetWeights: String
    val btnRequestNotifPermission: String
    val langTr: String
    val langEn: String

    // ── Alerts ─────────────────────────────────────────────────────────────
    val alertsTitle: String
    val tabRules: String
    val tabDigest: String
    val rulesCondNote: String
    val btnAddRule: String
    val cardRules: String
    val noRulesYet: String
    val badgeConditions: String
    val badgeOneShot: String
    val badgeMuted: String
    val btnDelete: String
    val cardFilters: String
    val chipAllSymbols: String
    val chipAllKinds: String
    val cardTriggers: String
    val noFiredYet: String
    val noneMatchFilter: String
    val a11yAddRule: String
    val a11yDismissBanner: String
    val alarmPrefix: String
    val sheetAddRule: String
    val lblSymbol: String
    val symbolHint: String
    val livePricePrefix: String
    val lblConditions: String
    val andConnector: String
    val btnAddCondition: String
    val lblCooldown: String
    val cooldownNote: String
    val btnAdd: String
    val errThresholdPositive: String
    val a11yAddCondition: String
    val a11yRemoveCondition: String
    val a11yConfirmAddRule: String
    val stateAddable: String
    val stateDisabled: String
    val kindVerdict: String
    val kindConfidenceAbove: String
    val kindPriceAbove: String
    val kindPriceBelow: String
    val kindOiSpike: String
    val dirAny: String
    val dirLong: String
    val dirShort: String

    /** Localized cooldown chip label (mirrors AlertLabels.COOLDOWN_CHIPS presets). */
    fun cooldownChipLabel(oneShot: Boolean, coalesceMs: Long): String

    /** "${symbol} kuralını sil" — delete-rule content description. */
    fun a11yDeleteRule(symbol: String): String

    /** "$symbol panelini aç" — open-panel content description. */
    fun a11yOpenPanel(symbol: String): String

    // ── Portfolio ──────────────────────────────────────────────────────────
    val portfolioTitle: String
    val portfolioLocalOnly: String
    val noPositions: String
    val noPositionsHint: String
    val btnAddPosition: String
    val cardPositions: String
    val cardTotals: String
    val entryPrefix: String
    val sizePrefix: String
    val a11yAddPosition: String
    val a11yDeletePosition: String
    val sheetEditPosition: String
    val lblSymbolImmutable: String
    val lblDirection: String
    val lblEntryPrice: String
    val lblSize: String
    val hintPositiveNumber: String
    val hintPositiveSize: String
    /** Grouped number such as "65.000" that could mean 65 or 65000: rejected, with this hint. */
    val portAmbiguousNumber: String
    /** Add-position symbol is not a USDT-M futures symbol in the live ticker universe. */
    val portSymbolInvalid: String
    val btnFillLive: String
    val btnUpdate: String
    val btnCancel: String
    val dlgDeletePositionTitle: String
    val dlgDeletePositionSuffix: String
    val a11yUpdatePosition: String
    val a11yAddPositionConfirm: String
    val agreeTag: String
    val againstTag: String
    val unclearTag: String

    /** Row-2 secondary line: "giriş $x · miktar $y" (TR verbatim). */
    fun entrySizeLine(entry: String, size: String): String

    /** Totals footer: complete rows + excluded-count honesty note. */
    fun totalsLine(complete: Int, excluded: Int): String

    /** Confirm-dialog body: "$symbol pozisyonu cihazdan silinecek. …" */
    fun deletePositionBody(symbol: String): String

    // ── Performance / evidence ─────────────────────────────────────────────
    val leaderboardTitle: String
    val cardScannedUniverse: String
    val cardGainers: String
    val cardLosers: String
    val cardVolume: String
    val noData: String
    val cardLoading: String
    val evidenceTitle: String
    val btnRegrade: String
    val gradingBusy: String
    val chipPartial: String
    val errorPrefix: String
    val noEvidence: String
    val lblConfidenceBands: String
    val lblScore: String
    val medianPrefix: String
    val binsCaption: String
    val window7d: String
    val window30d: String
    val windowAll: String
    val horizon1h: String
    val horizon4h: String
    val horizon24h: String

    /** Coverage line: "arşiv {0} · notalı {1} · ufuk {2} sa" (TR verbatim). */
    fun coverageLine(archived: Int, graded: Int, hours: Long): String

    // ── Common buttons ─────────────────────────────────────────────────────
    val btnRetry: String

    // ── Positions / leaders (data state and universe line) ─────────────────
    /** Positions card while the first load is still running. */
    val posPreparing: String
    /** Positions card when a finished load has no series for the symbol and period. */
    val posNoDataYet: String
    /** Leaders universe line: "$count USDT-M futures symbols · stablecoins removed". */
    fun perfUniverseLine(count: Int): String

    // ── Shared Play-compliance copy (disclaimer, data-source errors, neutral bias wording) ──
    /** First-run / About disclaimer. */
    val disclaimerTitle: String
    val disclaimerBody: String
    val disclaimerAccept: String
    /** One-line footer shown under verdict/signal surfaces. */
    val disclaimerShort: String
    /** "Not affiliated with Binance" statement (trademark/impersonation policy). */
    val notAffiliated: String
    /** Binance refused this region (HTTP 451): retrying cannot help. Never shows a status code. */
    val errRegionBlockedTitle: String
    val errRegionBlockedBody: String
    /** The data source refused the request (HTTP 403): not a confirmed region block; retry is offered. */
    val errForbiddenTitle: String
    val errForbiddenBody: String
    /** Generic network failure (no connectivity / timeout). */
    val errNetwork: String
    /** Upstream answered with an error that is neither a region block nor a refusal. Status stays in logs. */
    val errUpstreamGeneric: String
    /** Neutral, non-executional verdict wording (replaces OPEN_LONG / OPEN_SHORT / HOLD). */
    val biasLong: String
    val biasShort: String
    val biasNone: String
    /** Replaces "Should Trade: Yes/No" — describes the filters, not an instruction. */
    val filtersPassed: String
    val filtersNotPassed: String

    // ── Panel screen (panel*) ──────────────────────────────────────────────
    val panelTitleActiveCoin: String
    fun panelLastUpdate(timeText: String): String
    fun panelAgoSec(sec: Long): String
    fun panelAgoMin(minutes: Long): String
    fun panelAgoHour(hours: Long): String
    val panelRefresh: String
    val panelSearchTitle: String
    val panelSearchPlaceholder: String
    val panelNoMatch: String
    val panelActive: String
    val panelLabelSymbol: String
    val panelLabelPrice: String
    val panelLabelTf: String
    val panelLabelSignal: String
    val panelVerdictTitle: String
    val panelLabelConfidence: String
    val panelLabelThreshold: String
    val panelActionAboveUp: String
    val panelActionAboveDown: String
    val panelActionBelow: String
    val panelDistTitle: String
    fun panelIndicatorCount(count: Int): String
    fun panelTfGridTitle(symbol: String): String
    fun panelChartTitle(symbol: String, timeframe: String): String
    val panelNoData: String
    val panelCvdLabel: String
    val panelCvdFlowError: String
    val panelCvdUnavailable: String
    val panelCvdNoData: String
    val panelErrorTitle: String
    fun panelNoCandles(symbol: String, timeframe: String): String
    fun panelNotEnoughCandles(symbol: String, timeframe: String, count: Int): String
    /** A REST series (OI, ratios, funding) came back empty: no verdict is computed from placeholders. */
    fun panelNoSeriesData(symbol: String, timeframe: String): String
    val panelConsensusFailed: String
    val panelStrategyTitle: String
    val panelRegimeLabel: String
    val panelMicroLabel: String
    val panelMtfGateOpen: String
    val panelMtfGateClosed: String
    fun panelSignalsCount(count: Int): String
    val panelFundingTitle: String
    val panelFundingUnavailable: String
    val panelFundingPredicted: String
    val panelFundingLast: String
    val panelFundingTimeLabel: String
    fun panelRegimeTag(regime: String): String
    val panelBasisTitle: String
    val panelBasisUnavailable: String
    val panelBasisCaption: String
    val panelAnnFunding: String
    val panelVolTitle: String
    val panelPlanUnavailable: String
    val panelPlanNote: String
    val panelBandLow: String
    val panelBandHigh: String
    val panelConnecting: String
    fun panelDelayed(age: String): String

    // ── Alerts: labels, validation, a11y (alert*) ──────────────────────────
    val alertRuleDeleted: String
    val alertRuleVerdictLong: String
    val alertRuleVerdictShort: String
    val alertRuleVerdictAny: String
    fun alertRuleConfidenceAbove(threshold: String): String
    fun alertRulePriceAbove(price: String): String
    fun alertRulePriceBelow(price: String): String
    fun alertRuleOiSpike(threshold: String): String
    val alertCooldownDefault: String
    fun alertCooldownMinutes(minutes: Long): String
    fun alertConditionSummary(count: Int, shown: String, cooldown: String): String
    val alertUniverseAll: String
    fun alertEtaSeconds(seconds: Long): String
    fun alertEtaMinutes(minutes: Long): String
    fun alertEtaHours(hours: Long): String
    val alertErrAmbiguousThreshold: String
    val alertErrUnknownSymbol: String
    val alertSymbolChecking: String
    fun a11yToggleRule(symbol: String): String

    // ── Logs (log*) ────────────────────────────────────────────────────────
    val logClear: String
    val logSearchHint: String
    val logNoMatch: String
    val logNoRequests: String
    val logOpenHint: String

    // ── Appearance (app*) ──────────────────────────────────────────────────
    val appCustomization: String
    val appFontSize: String
    val appOled: String
    val appReset: String
    val appResetAll: String
    val appStoredNote: String
    val appCustomColors: String
    val appCustomColorsNote: String
    val appAccentColor: String

    // ── Settings (set*) ────────────────────────────────────────────────────
    val setConfThresholdLabel: String
    val setTradeThresholdLabel: String
    val setWeightsDesc: String
    val setExtendedDesc: String
    val setSurvivorsLabel: String
    val setParallelismLabel: String
    val setFavoritesHint: String
    val setNoFavorites: String
    val setSearchPlaceholder: String
    val setNoMatch: String
    val setAddFavorite: String
    fun setUniverseError(message: String): String
    val setWsSourceLabel: String
    val setWsFutures: String
    val setWsSpot: String
    val setCandleLimitLabel: String
    fun setCandles(count: Int): String
    val setBgNote: String
    val setUnmeteredOn: String
    val setUnmeteredOff: String
    val setBgResultsNote: String
    val setNotifDesc: String
    val setNotifNotNeeded: String
    val setNotifGranted: String
    val setNotifDenied: String
    val setNotifOpenSettings: String
    val setDarkTheme: String
    val setThemeFixed: String
    val setAboutApp: String
    val setAboutVersion: String
    val setAboutBuild: String
    val setAboutMode: String
    val setModeDebug: String
    val setModeRelease: String
    val setAboutDataSource: String
    val setAboutIndicators: String
    val setAboutIndicatorsValue: String
    val setAboutTimeframes: String
    val setPrivacyRow: String
    val setContactRow: String
    val setLicensesRow: String
    val setClearDataRow: String
    val setClearDataHint: String
    val setClearConfirm: String
    val setClearDone: String
    val setDialogClose: String
    val setLicensesTitle: String
    val setLicensesIntro: String
    val setPrivacyTitle: String
    val setPrivacyBody: String
    val setPrivacyOpenOnline: String

    // ── Android platform surfaces (widget, notification channel, share card, background scan) ──
    /** Home-screen widget title. */
    val platWidgetTitle: String
    /** Widget body when no background scan has produced rows yet. */
    val platWidgetNoData: String
    /** Widget data-age line, e.g. "12 dk önce". */
    fun platWidgetAgo(minutes: Long): String
    /** Widget coverage note when a background run could not fetch every planned symbol/timeframe. */
    fun platWidgetPartial(ok: Int, planned: Int): String
    /** Name of the alert notification channel shown in system settings. */
    val platChannelName: String
    /** Description of the alert notification channel. */
    val platChannelDesc: String
    /** Title of the system share sheet for the verdict card. */
    val platShareChooser: String
    /** Shown when the background scan could not be (re)scheduled by WorkManager. */
    val platBgSyncFailed: String

    // ── b2 (Android lane B: portfolio cap, first-alert permission rationale) ──
    /** Shown beside the disabled Add button when the portfolio holds [max] entries. */
    fun b2PortfolioCapReached(max: Int): String
    /** Short reason shown before the system notification-permission dialog on the first alert. */
    val b2AlertPermRationale: String
    /** Dismiss action of that rationale: the alert is saved without asking for the permission. */
    val b2AlertSaveWithoutPerm: String

    // ── Scanner screen (scan*) ─────────────────────────────────────────────
    val scanHeroLabel: String
    fun scanSourceLine(timeframes: Int): String
    val scanStartButton: String
    val scanStopButton: String
    val scanHeroFetchingUniverse: String
    val scanHeroIdle: String
    val scanHeroPhase1: String
    val scanHeroPhase2: String
    val scanHeroRanking: String
    val scanPhaseUniverse: String
    val scanPhase1: String
    val scanPhase2: String
    val scanPhaseRanking: String
    val scanPhaseIdle: String
    val scanNowLabel: String
    fun scanResultSummary(universe: Int, kept: Int, eliminated: Int): String
    fun scanDivergenceSummary(eliminated: Int): String
    fun scanLastScan(timestamp: String): String
    fun scanNoSurvivors(eliminated: Int): String
    val scanNoCandidates: String
    val scanNoRunTitle: String
    val scanNoRunHint: String
    fun scanErrorLine(message: String): String
    fun scanFailedNote(count: Int): String
    val scanFailedNoteDismiss: String
    val scanTableSize: String
    val scanAllSize: String
    val scanDivSortOn: String
    val scanDivSortOff: String
    val scanDivSortNote: String
    val scanContinuousOn: String
    val scanContinuousOff: String
    fun scanContinuousCycle(cycle: Int): String
    val scanUniverseTitle: String
    fun scanUniverseSymbols(count: Int): String
    val scanUniverseWarning: String
    fun scanWatchFilter(count: Int): String
    val scanWatchEmpty: String
    val scanWatchNoResults: String
    val scanLensAll: String
    fun scanLensEliminated(count: Int): String
    fun scanNoDivergence(uncheckable: Int): String
    val scanDivInfoTitle: String
    val scanDivInfoBody: String
    val scanResearchNoteTitle: String
    val scanResearchNoteBody: String
    fun scanPriceWhaleLine(priceSign: String, risePct: String, whaleSign: String, whaleDrop: String): String
    val scanDistribution: String
    val scanAccumulation: String
    val scanContrarianUp: String
    val scanContrarianDown: String
    val scanConfidence: String
    val scanScore: String
    fun scanTfAgree(count: Int, total: Int): String
    val scanRiskLow: String
    val scanRiskMedium: String
    val scanRiskHigh: String
    val scanRiskNone: String
    fun scanTimeframesHeader(count: Int): String
    val scanSelect: String
    fun scanAlertCd(symbol: String): String
    fun scanShareCd(symbol: String): String
    val scanSelected: String
    val scanNotSelected: String
    val scanVolatilityTitle: String
    /** Volatility yardstick line: ATR percentage only (no stop, target, size or leverage). */
    fun scanVolatilityLine(atrPct: String): String
    val scanMtfPass: String
    val scanMtfFail: String
    fun scanMicroTag(arrow: String, label: String): String
    val microStrongBuy: String
    val microStrongSell: String
    val microNeutral: String
    fun scanEtaLabel(eta: String): String
    val scanVolatilityNote: String
    val scanUniverseEmpty: String
    val scanNoDataFetched: String
    val scanRateLimited: String
    val scanUnexpectedError: String

    // ── Signals screen (sig*) ──────────────────────────────────────────────
    val sigTitle: String
    val sigConsensusTitle: String
    val sigFinalSignal: String
    val sigConfidence: String
    val sigRisk: String
    val sigWeightedScore: String
    val sigAnalyticStatus: String
    val sigAboveThreshold: String
    val sigAboveBull: String
    val sigAboveBear: String
    val sigBelowThreshold: String
    val sigYes: String
    val sigNo: String
    val sigDistribution: String
    fun sigDistBuy(count: Int): String
    fun sigDistNeutral(count: Int): String
    fun sigDistSell(count: Int): String
    fun sigIndicatorDetails(count: Int): String
    val sigNoIndicators: String
    val sigColIndicator: String
    val sigColSignal: String
    val sigColScore: String
    val sigColWeight: String
    val sigColWeighted: String
    val sigColReason: String
    fun sigTimeframesTitle(count: Int): String
    val sigLoadFailedTitle: String
    fun sigLoadPartial(timeframes: String): String
    val sigNoDataTimeframe: String
    fun sigInsufficientHistory(count: Int, needed: Int): String

    // ── i18n* : consensus reason/risk text, alert messages, funding/cone captions, ──
    // ── Appearance / Positions / Performance literals ───────────────────────────
    fun i18nConsReasonLine(
        signal: String,
        confidence: Int,
        risk: String,
        buyCount: Int,
        sellCount: Int,
        neutralCount: Int,
        weightedScore: String,
        risks: List<String>,
    ): String
    val i18nConsHighAtr: String
    val i18nConsLowAtr: String
    fun i18nConsWeakTrend(adx: String, minAdx: String): String
    fun i18nConsHighConflict(ratio: String): String
    fun i18nConsModerateConflict(ratio: String): String
    fun i18nConsFewActive(active: Int, minActive: Int): String
    fun i18nConsWeakConviction(score: String): String
    val i18nConsWhaleRetail: String
    val i18nConsExtremeFunding: String
    /** Multimodal narrative, falling-price polarity: 27 cells (index = oi*9 + yState*3 + zState; DOWN=0, UP=1, FLAT=2). */
    val i18nNarrReading: List<String>
    /** The same 27 cells read for a rising price (bullish-perspective text). */
    val i18nNarrReadingMirror: List<String>
    val i18nNarrBias: List<String>
    val i18nNarrBiasMirror: List<String>
    val i18nNarrFlatReading: String
    val i18nNarrFlatBiasUp: String
    val i18nNarrFlatBiasDown: String
    val i18nNarrFlatBiasFlat: String
    fun i18nNarrRegime(regime: String): String
    fun i18nNarrExplanation(regime: String, confidence: Int, bias: String, reading: String, score: String): String

    fun i18nAlertPriceAbove(symbol: String, threshold: Double, price: Double): String
    fun i18nAlertPriceBelow(symbol: String, threshold: Double, price: Double): String
    fun i18nAlertVerdict(symbol: String, signal: String, confidence: Int): String
    fun i18nAlertConfidenceAbove(symbol: String, confidence: Int, threshold: Double): String
    fun i18nAlertOiCollapse(symbol: String, spike: Double, threshold: Double): String
    fun i18nAlertOiExpansion(symbol: String, spike: Double, threshold: Double): String
    /** Joiner between AND-ed alert conditions in one message. */
    val i18nAlertJoiner: String

    val i18nFundingPositive: String
    val i18nFundingNegative: String
    val i18nFundingNeutral: String
    val i18nConeCaption: String
    val i18nSmallSampleNote: String

    val i18nAppThemeTitle: String
    fun i18nAppPresetsSummary(count: Int, families: Int): String
    val i18nAppContrast: String
    val i18nAppNormal: String
    val i18nAppHigh: String
    val i18nAppSizeSmall: String
    val i18nAppSizeLarge: String
    val i18nAppSizeLargest: String
    val i18nAppCorner: String
    val i18nAppAuto: String
    val i18nAppSharp: String
    val i18nAppSoft: String
    val i18nAppCandleColors: String
    val i18nAppGreenRed: String
    val i18nAppRedGreen: String
    val i18nAppColorblind: String
    val i18nAppMonochrome: String
    val i18nAppScanlines: String
    val i18nAppDensity: String
    val i18nAppCompact: String
    val i18nAppCozy: String
    val i18nAppComfy: String
    val i18nAppFont: String
    val i18nAppChartType: String
    val i18nAppCandle: String
    /** Font-family chip names (typographic terms, the same in TR and EN). */
    val i18nAppFontMono: String
    val i18nAppFontSans: String
    val i18nAppFontSerif: String
    /** Heikin-Ashi chart type (a proper name, the same in TR and EN). */
    val i18nAppHeikin: String
    val i18nAppArea: String
    val i18nAppLine: String
    val i18nAppChartColor: String
    val i18nAppColorAccent: String
    val i18nAppColorUp: String
    val i18nAppColorViolet: String
    val i18nAppColorAmber: String
    val i18nAppColorCyan: String
    val i18nAppColorWhite: String
    val i18nAppColorTheme: String
    val i18nAppColorGreen: String
    val i18nAppColorRed: String
    val i18nAppColorBlue: String
    val i18nTokenBackground: String
    val i18nTokenCard: String
    val i18nTokenCardHover: String
    val i18nTokenBorder: String
    val i18nTokenText: String
    val i18nTokenMuted: String
    val i18nTokenAccent: String
    val i18nTokenAccent2: String
    val i18nTokenUp: String
    val i18nTokenDown: String
    val i18nTokenWarn: String

    val i18nPosMarketTitle: String
    fun i18nPosInspect(index: Int, total: Int): String
    val i18nPosIndicatorsTitle: String
    fun i18nPosUnifiedTitle(symbol: String): String
    val i18nPosLoadingAnalysis: String
    val i18nPosFetching3: String
    val i18nPosHistoricalTitle: String
    fun i18nPosCandleData(count: Int): String
    val i18nPosWhaleDist: String
    val i18nPosAccountDist: String
    fun i18nPosRatioDetails(selectedPoint: Boolean): String
    val i18nPosQuantBiasTitle: String
    val i18nPosTilePrice: String
    val i18nPosTileOi: String
    val i18nPosTileQuantBias: String
    val i18nPosTileWhale: String
    val i18nPosTileAccount: String
    val i18nPosTileGlobal: String
    val i18nPosTileTaker: String
    val i18nPosTileNetTaker: String
    val i18nPosTileFunding: String
    val i18nPosColTime: String
    val i18nPosColPrice: String
    val i18nPosColOi: String
    val i18nPosColGlobal: String
    val i18nPosColAccount: String
    val i18nPosColWhale: String
    val i18nPosColTaker: String
    val i18nPosColNetTaker: String
    val i18nPosColFunding: String
    val i18nPosColBias: String

    val i18nPerfFetchingTicker: String

    /** Localized display label for a nav route (bottom bar / More sheet / top bar). */
    fun routeLabel(route: NavRoute): String = when (route) {
        NavRoute.PANEL -> navPanel
        NavRoute.SCANNER -> navScanner
        NavRoute.POSITIONS -> navPositions
        NavRoute.SIGNALS -> navSignals
        NavRoute.ALERTS -> navAlerts
        NavRoute.PORTFOLIO -> navPortfolio
        NavRoute.PERFORMANCE -> navPerformance
        NavRoute.LOGS -> navLogs
        NavRoute.APPEARANCE -> navAppearance
        NavRoute.SETTINGS -> navSettings
    }

    /** Localized alert-kind chip label. */
    fun kindLabel(kindRaw: String): String = when (kindRaw) {
        "VERDICT" -> kindVerdict
        "CONFIDENCE_ABOVE" -> kindConfidenceAbove
        "PRICE_ABOVE" -> kindPriceAbove
        "PRICE_BELOW" -> kindPriceBelow
        "OI_SPIKE_PCT" -> kindOiSpike
        else -> kindRaw
    }
}

/** Coalesce windows, mirroring [com.diveintocrypto.android.domain.alerts.AlertRule]. */
private const val COALESCE_1M_MS = 60_000L
private const val COALESCE_15M_MS = 900_000L
private const val COALESCE_1H_MS = 3_600_000L

/**
 * DEFAULT catalog — every value is today's Turkish on-screen text, verbatim.
 */
object TrStrings : DiveStrings {
    override val code = "tr"

    override val navPanel = "Panel"
    override val navScanner = "Scanner"
    override val navPositions = "OI · L/S"
    override val navSignals = "Eğilimler"
    override val navAlerts = "Alarmlar"
    override val navPortfolio = "Portföy"
    override val navPerformance = "Liderler"
    override val navLogs = "Ağ Günlüğü"
    override val navAppearance = "Görünüm"
    override val navSettings = "Ayarlar"
    override val navMore = "Daha"
    override val moreSheetTitle = "DAHA FAZLA"
    override val tabPanel = "PANEL"
    override val tabOiLs = "OI · L/S"
    override val tabSignals = "EĞİLİMLER"

    override val selected = "Seçili"
    override val notSelected = "Seçili değil"
    override val switchOn = "Açık"
    override val switchOff = "Kapalı"
    override val active = "Aktif"
    override val passive = "Pasif"

    override val funding = "FONLAMA"

    override val setTitleFavorites = "FAVORİ KRİPTO PARALAR"
    override val setTitleConsensus = "ANALİZ ALGORİTMASI AYARLARI"
    override val setTitleWeights = "GÖSTERGE KONSENSÜS AĞIRLIKLARI"
    override val setTitleScanner = "SCANNER AYARLARI"
    override val setTitleQuantChart = "NİCEL VERİ VE GRAFİK AYARLARI"
    override val setTitleQuantBias = "NİCEL EĞİLİM FORMÜLÜ AĞIRLIKLARI"
    override val setTitleNotifications = "BİLDİRİMLER"
    override val setTitleBackgroundScans = "ARKA PLAN TARAMALARI"
    override val setTitleTheme = "TEMA"
    override val setTitleAbout = "HAKKINDA"
    override val setTitleLanguage = "DİL"
    override val tglBackgroundScan = "Arka plan taraması"
    override val tglUnmetered = "Sadece ölçülmeyen ağ (Wi-Fi)"
    override val lblCore = "ÇEKİRDEK"
    override val lblExtended = "GENİŞLETİLMİŞ"
    override val btnResetWeights = "SIFIRLA"
    override val btnRequestNotifPermission = "BİLDİRİM İZNİ İSTE"
    override val langTr = "TR"
    override val langEn = "EN"

    override val alertsTitle = "Alarmlar"
    override val tabRules = "KURALLAR"
    override val tabDigest = "DİJEST"
    override val rulesCondNote =
        "KOŞULLAR 'VE' ile birleşir; KARAR/GÜVEN/OI koşulları tarama döngüsünde, " +
            "FİYAT koşulları canlı mini-ticker akışında değerlendirilir."
    override val btnAddRule = "+ KURAL EKLE"
    override val cardRules = "KURALLAR"
    override val noRulesYet = "Henüz kural yok — KURAL EKLE ile oluştur."
    override val badgeConditions = "KOŞUL"
    override val badgeOneShot = "TEK ATIŞ"
    override val badgeMuted = "SESSİZ"
    override val btnDelete = "SİL"
    override val cardFilters = "FİLTRELER"
    override val chipAllSymbols = "TÜM SEMBOL"
    override val chipAllKinds = "TÜM TÜRLER"
    override val cardTriggers = "TETİKLENMELER"
    override val noFiredYet = "Henüz alarm tetiklenmedi."
    override val noneMatchFilter = "Bu filtreye uyan tetiklenme yok."
    override val a11yAddRule = "Yeni alarm kuralı ekle"
    override val a11yDismissBanner = "Alarm bildirimini kapat"
    override val alarmPrefix = "ALARM"
    override val sheetAddRule = "KURAL EKLE"
    override val lblSymbol = "SEMBOL"
    override val symbolHint = "örn. BTCUSDT"
    override val livePricePrefix = "canlı fiyat:"
    override val lblConditions = "KOŞULLAR (hepsi sağlandığında tetiklenir)"
    override val andConnector = "— VE —"
    override val btnAddCondition = "+ KOŞUL"
    override val lblCooldown = "TEKRAR ARALIĞI"
    override val cooldownNote =
        "Aynı kural bu aralıktan daha sık tetiklenmez; TEK ATIŞ ilk tetiklemede kuralı kapatır."
    override val btnAdd = "EKLE"
    override val errThresholdPositive = "Eşik > 0 olmalı"
    override val a11yAddCondition = "Koşul ekle"
    override val a11yRemoveCondition = "Koşulu kaldır"
    override val a11yConfirmAddRule = "Kuralı ekle"
    override val stateAddable = "Eklenebilir"
    override val stateDisabled = "Devre dışı"
    override val kindVerdict = "KARAR YÖNÜ"
    override val kindConfidenceAbove = "GÜVEN ÜSTÜ"
    override val kindPriceAbove = "FİYAT ÜSTÜ"
    override val kindPriceBelow = "FİYAT ALT"
    override val kindOiSpike = "OI ARTIŞI"
    override val dirAny = "TÜMÜ"
    override val dirLong = "LONG"
    override val dirShort = "SHORT"

    override fun cooldownChipLabel(oneShot: Boolean, coalesceMs: Long): String = when {
        oneShot -> "TEK ATIŞ"
        coalesceMs == COALESCE_1M_MS -> "1 DK"
        coalesceMs == COALESCE_15M_MS -> "15 DK"
        coalesceMs == COALESCE_1H_MS -> "1 SA"
        else -> "${coalesceMs / 60_000} dk"
    }

    override fun a11yDeleteRule(symbol: String) = "${symbol} kuralını sil"
    override fun a11yOpenPanel(symbol: String) = "${symbol} panelini aç"

    override val portfolioTitle = "Portföy"
    override val portfolioLocalOnly = "giriş fiyatı cihazında saklanır · borsa bağlantısı yok"
    override val noPositions = "Henüz pozisyon yok."
    override val noPositionsHint =
        "Pozisyonlarını elle ekle — kâr/zarar canlı fiyata göre burada hesaplanır."
    override val btnAddPosition = "POZİSYON EKLE"
    override val cardPositions = "POZİSYONLAR"
    override val cardTotals = "TOPLAM"
    override val entryPrefix = "giriş"
    override val sizePrefix = "miktar"
    override val a11yAddPosition = "Yeni pozisyon ekle"
    override val a11yDeletePosition = "pozisyonunu sil"
    override val sheetEditPosition = "POZİSYONU DÜZENLE"
    override val lblSymbolImmutable = "SEMBOL (değiştirilemez)"
    override val lblDirection = "YÖN"
    override val lblEntryPrice = "GİRİŞ FİYATI"
    override val lblSize = "MİKTAR (baz varlık)"
    override val hintPositiveNumber = "0'dan büyük bir sayı gir"
    override val hintPositiveSize = "0'dan büyük bir miktar gir"
    override val portAmbiguousNumber =
        "Binlik ayırıcı kullanılamaz. Ondalık için virgül kullanın (ör. 12,5) ya da yalnızca rakam yazın (ör. 65000)."
    override val portSymbolInvalid = "USDT-M vadeli sembolü girin (ör. BTCUSDT)"
    override val btnFillLive = "doldur:"
    override val btnUpdate = "GÜNCELLE"
    override val btnCancel = "VAZGEÇ"
    override val dlgDeletePositionTitle = "Pozisyonu sil"
    override val dlgDeletePositionSuffix = "pozisyonu cihazdan silinecek. Bu işlem geri alınamaz."
    override val a11yUpdatePosition = "Pozisyonu güncelle"
    override val a11yAddPositionConfirm = "Pozisyonu ekle"
    override val agreeTag = "UYUMLU"
    override val againstTag = "KARŞIT"
    override val unclearTag = "BELİRSİZ"

    override fun entrySizeLine(entry: String, size: String) = "giriş ${entry} · ${sizePrefix} ${size}"
    override fun totalsLine(complete: Int, excluded: Int): String = if (excluded > 0) {
        "${complete} tam satır · ${excluded} hariç (canlı fiyat yok)"
    } else {
        "${complete} tam satır · hariç yok"
    }
    override fun deletePositionBody(symbol: String) = "${symbol} ${dlgDeletePositionSuffix}"

    override val leaderboardTitle = "24s Liderler"
    override val cardScannedUniverse = "TARANAN EVREN"
    override val cardGainers = "EN ÇOK YÜKSELEN"
    override val cardLosers = "EN ÇOK DÜŞEN"
    override val cardVolume = "EN YÜKSEK HACİM"
    override val noData = "Veri yok"
    override val cardLoading = "YÜKLENİYOR"
    override val evidenceTitle = "MOTOR KANITI (kendini notlama)"
    override val btnRegrade = "YENİDEN NOTLA"
    override val gradingBusy = "NOTLANDIRILIYOR…"
    override val chipPartial = "KISMİ — eski kayıtlar notalı değil"
    override val errorPrefix = "hata:"
    override val noEvidence = "Kanıt yok — taramalar arşive biriktikçe notlanır."
    override val lblConfidenceBands = "GÜVEN BANTLARI"
    override val lblScore = "SKOR"
    override val medianPrefix = "medyan"
    override val binsCaption = "10 güven bandı · yükseklik = örnek, renk = sapma"
    override val window7d = "7G"
    override val window30d = "30G"
    override val windowAll = "TÜMÜ"
    override val horizon1h = "1s"
    override val horizon4h = "4s"
    override val horizon24h = "24s"

    override fun coverageLine(archived: Int, graded: Int, hours: Long) =
        "arşiv ${archived} · notalı ${graded} · ufuk ${hours} sa"

    override val btnRetry = "⟳ TEKRAR DENE"
    override val posPreparing = "Veriler hazırlanıyor…"
    override val posNoDataYet = "Bu sembol ve periyot için veri yok."
    override fun perfUniverseLine(count: Int) = "$count USDT-M vadeli sembol · stablecoin'ler çıkarıldı"

    override val disclaimerTitle = "Önemli uyarı"
    override val disclaimerBody =
        "DVCRY yalnızca bilgi ve eğitim amaçlı bir piyasa analiz aracıdır. " +
            "Gösterilen göstergeler, eğilimler ve istatistikler yatırım tavsiyesi değildir; " +
            "uygulama hiçbir alım-satım emri vermez ve hiçbir borsa hesabına bağlanmaz.\n\n" +
            "Kripto vadeli işlemler kaldıraç nedeniyle çok yüksek risk içerir; yatırdığınız " +
            "sermayenin tamamını kaybedebilirsiniz. Geçmiş performans gelecekteki sonuçların " +
            "göstergesi değildir. Kararlarınızın sorumluluğu size aittir.\n\n" +
            "Veriler Binance'in herkese açık piyasa verisinden alınır. DVCRY, " +
            "Binance ile bağlantılı değildir ve Binance tarafından onaylanmamıştır."
    override val disclaimerAccept = "Anladım"
    override val disclaimerShort = "Yatırım tavsiyesi değildir · yalnızca bilgi amaçlıdır"
    override val notAffiliated = "Binance ile bağlantılı değildir ve Binance tarafından onaylanmamıştır."
    override val errRegionBlockedTitle = "Bu bölgede veri alınamıyor"
    override val errRegionBlockedBody =
        "Binance, bulunduğunuz bölgeden gelen piyasa verisi isteklerini engelliyor. " +
            "DVCRY Binance'in herkese açık verisini kullandığı için bu bölgede tarama " +
            "yapılamaz. Bu bir uygulama arızası değildir; tekrar denemek sonucu değiştirmez."
    override val errForbiddenTitle = "Veri kaynağı isteği reddetti"
    override val errForbiddenBody =
        "Veri kaynağı isteği reddetti — bu bölgesel bir kısıtlamadan kaynaklanabilir. Tekrar deneyebilirsiniz."
    override val errNetwork = "Ağ hatası — internet bağlantınızı kontrol edip tekrar deneyin."
    override val errUpstreamGeneric = "Veri kaynağı beklenen yanıtı vermedi. Biraz sonra tekrar deneyin."
    override val biasLong = "LONG eğilim"
    override val biasShort = "SHORT eğilim"
    override val biasNone = "Belirgin eğilim yok"
    override val filtersPassed = "Filtreleri geçti"
    override val filtersNotPassed = "Filtreleri geçmedi"

    // ── Scanner (TR) ───────────────────────────────────────────────────────
    override val scanHeroLabel = "MANUEL TARAMA"
    override fun scanSourceLine(timeframes: Int) = "Binance USDT-M Futures · $timeframes zaman dilimi"
    override val scanStartButton = "Taramayı başlat"
    override val scanStopButton = "Durdur"
    override val scanHeroFetchingUniverse = "Sembol listesi alınıyor..."
    override val scanHeroIdle = "Tüm Binance USDT-M · 12 zaman dilimi"
    override val scanHeroPhase1 = "Faz 1 · 1d/12h/8h taranıyor"
    override val scanHeroPhase2 = "Faz 2 · kalan 9 zaman dilimi"
    override val scanHeroRanking = "Çapraz sıralama hesaplanıyor..."
    override val scanPhaseUniverse = "SEMBOL LİSTESİ ALINIYOR"
    override val scanPhase1 = "FAZ 1 · 1d/12h/8h"
    override val scanPhase2 = "FAZ 2 · 9 ZD"
    override val scanPhaseRanking = "SIRALAMA"
    override val scanPhaseIdle = "HAZIR"
    override val scanNowLabel = "ŞİMDİ"
    override fun scanResultSummary(universe: Int, kept: Int, eliminated: Int) =
        "$universe sembol · ✓ $kept tutuldu · ✕ $eliminated elendi"
    override fun scanDivergenceSummary(eliminated: Int) =
        "Balina uyumsuzluğu gösterge yönüyle çelişiyor: $eliminated coin elendi"
    override fun scanLastScan(timestamp: String) = "Son tarama: $timestamp"
    override fun scanNoSurvivors(eliminated: Int) = "Bu taramada uygun coin kalmadı · $eliminated coin elendi"
    override val scanNoCandidates = "Bu taramada coin bulunamadı"
    override val scanNoRunTitle = "Henüz tarama yapılmadı"
    override val scanNoRunHint = "Yukarıdaki \"Taramayı başlat\" düğmesine dokunun"
    override fun scanErrorLine(message: String) = "Hata: $message"
    override fun scanFailedNote(count: Int) = "⚠ $count sembol taranamadı — kısmi sonuç"
    override val scanFailedNoteDismiss = "Kısmi sonuç uyarısını kapat"
    override val scanTableSize = "TABLO BOYUTU"
    override val scanAllSize = "Tümü"
    override val scanDivSortOn = "◆ UYUMSUZLUK SIRALAMASI AÇIK"
    override val scanDivSortOff = "UYUMSUZLUK SIRALAMASI KAPALI"
    override val scanDivSortNote =
        "Görünüm |uyumsuzluk skoru| azalan sıralıdır. Skor, sıralama ve eleme DEĞİŞMEZ; yalnızca görüntü sırası."
    override val scanContinuousOn = "● SÜREKLİ AÇIK"
    override val scanContinuousOff = "SÜREKLİ KAPALI"
    override fun scanContinuousCycle(cycle: Int) = "● SÜREKLİ · tur #$cycle"
    override val scanUniverseTitle = "TARAMA EVRENİ"
    override fun scanUniverseSymbols(count: Int) = " · $count sembol"
    override val scanUniverseWarning = "⚠ ~500 sembol · birkaç dk sürer · yeni taramada geçerli"
    override fun scanWatchFilter(count: Int) = "★ İZLEME ($count)"
    override val scanWatchEmpty = "★ İzleme listen boş — Ayarlar > Favori Coinler'e ekle."
    override val scanWatchNoResults = "★ İzleme listesinde bu taramadan sonuç yok."
    override val scanLensAll = "Tümü"
    override fun scanLensEliminated(count: Int) = if (count > 0) "✕ Elenen ($count)" else "✕ Elenen"
    override fun scanNoDivergence(uncheckable: Int) =
        "Bu taramada balina L/S uyumsuzluğu bulunamadı." +
            if (uncheckable > 0) "  ($uncheckable sembolde balina verisi yok — kısmi kontrol.)" else ""
    override val scanDivInfoTitle = "⚠ BALİNA L/S UYUMSUZLUĞU"
    override val scanDivInfoBody =
        "Etiket = balinanın HAM hareketi; SKOR = karşıtsal bir sıralama sezgisidir. " +
            "▼ DAĞITIM (kırmızı): fiyat ↑ + balina L/S ↓ (balinalar yükselişte satıyor). " +
            "Bu durumda skor POZİTİF (+) olur ve coin yukarı sıralanır. " +
            "▲ BİRİKİM (yeşil): fiyat ↓ + balina L/S ↑ → skor NEGATİF (−), aşağı sıralanır. " +
            "Daha yüksek zaman dilimi (1d), daha düşük bir zaman diliminden (5m) daha fazla ağırlık taşır."
    override val scanResearchNoteTitle = "ARAŞTIRMA NOTU"
    override val scanResearchNoteBody =
        "Bu bir tahmin değildir. Uyumsuzluk sinyalleri yanlış çıkabilir; geçmiş davranış gelecekteki sonuçların garantisi değildir."
    override fun scanPriceWhaleLine(priceSign: String, risePct: String, whaleSign: String, whaleDrop: String) =
        "Fiyat $priceSign$risePct% · Balina $whaleSign$whaleDrop%"
    override val scanDistribution = "DAĞITIM"
    override val scanAccumulation = "BİRİKİM"
    override val scanContrarianUp = "Karşıtsal sıralama: ↑ (skor +)"
    override val scanContrarianDown = "Karşıtsal sıralama: ↓ (skor −)"
    override val scanConfidence = "GÜVEN"
    override val scanScore = "SKOR"
    override fun scanTfAgree(count: Int, total: Int) = "$count/$total TF UYUM"
    override val scanRiskLow = "DÜŞÜK"
    override val scanRiskMedium = "ORTA"
    override val scanRiskHigh = "YÜKSEK"
    override val scanRiskNone = "N/A"
    override fun scanTimeframesHeader(count: Int) = "TÜM $count ZAMAN DİLİMİ"
    override val scanSelect = "SEÇ →"
    override fun scanAlertCd(symbol: String) = "$symbol için alarm ekle"
    override fun scanShareCd(symbol: String) = "$symbol karar kartını paylaş"
    override val scanSelected = "Seçili"
    override val scanNotSelected = "Seçili değil"
    override val scanVolatilityTitle = "VOLATİLİTE (ATR)"
    override fun scanVolatilityLine(atrPct: String) =
        "ATR ≈ %$atrPct — tipik fiyat oynaklığı göstergesidir; öneri değildir."
    override val scanMtfPass = "MTF ✓"
    override val scanMtfFail = "MTF –"
    override fun scanMicroTag(arrow: String, label: String) = "MİKRO $arrow $label"
    override val microStrongBuy = "G.YÜK"
    override val microStrongSell = "G.DÜŞ"
    override val microNeutral = "NÖTR"
    override fun scanEtaLabel(eta: String) = "Kalan süre $eta"
    override val scanVolatilityNote = "Yalnızca volatilite bilgisidir; pozisyon büyüklüğü veya kaldıraç önerisi değildir."
    override val scanUniverseEmpty = "Binance vadeli sembol listesi boş döndü."
    override val scanNoDataFetched = "Hiçbir sembol için veri alınamadı."
    override val scanRateLimited =
        "Binance istek sınırına ulaşıldı; birkaç dakika sonra tekrar deneyin. Sürekli tarama durduruldu."
    override val scanUnexpectedError = "Tarama beklenmeyen bir hatayla durdu."

    // ── Signals (TR) ───────────────────────────────────────────────────────
    override val sigTitle = "Eğilimler"
    override val sigConsensusTitle = "Konsensüs sonucu"
    override val sigFinalSignal = "Nihai eğilim"
    override val sigConfidence = "Güven"
    override val sigRisk = "Risk"
    override val sigWeightedScore = "W. skor"
    override val sigAnalyticStatus = "Analitik durum"
    override val sigAboveThreshold = "Eşik üstü"
    override val sigAboveBull = "Eşik üstü · yükseliş eğilimi (analitik)"
    override val sigAboveBear = "Eşik üstü · düşüş eğilimi (analitik)"
    override val sigBelowThreshold = "Eşik altı · yalnızca gözlem"
    override val sigYes = "Evet"
    override val sigNo = "Hayır"
    override val sigDistribution = "Sinyal dağılımı"
    override fun sigDistBuy(count: Int) = "YÜKSELİŞ $count"
    override fun sigDistNeutral(count: Int) = "NÖTR $count"
    override fun sigDistSell(count: Int) = "DÜŞÜŞ $count"
    override fun sigIndicatorDetails(count: Int) = "Gösterge detayları ($count)"
    override val sigNoIndicators = "Henüz gösterge hesaplanmadı"
    override val sigColIndicator = "Gösterge"
    override val sigColSignal = "Sinyal"
    override val sigColScore = "Skor"
    override val sigColWeight = "Ağırlık"
    override val sigColWeighted = "W. skor"
    override val sigColReason = "Gerekçe"
    override fun sigTimeframesTitle(count: Int) = "Zaman dilimi sinyalleri ($count TF)"
    override val sigLoadFailedTitle = "Geçmiş veri yüklenemedi"
    override fun sigLoadPartial(timeframes: String) = "Şu zaman dilimleri yüklenemedi: $timeframes"
    override val sigNoDataTimeframe = "Bu zaman dilimi için veri alınamadı"
    override fun sigInsufficientHistory(count: Int, needed: Int) =
        "Yetersiz geçmiş: $count mum (en az $needed gerekli)"

    override val panelTitleActiveCoin = "Aktif coin"
    override fun panelLastUpdate(timeText: String) = "Son güncelleme: $timeText"
    override fun panelAgoSec(sec: Long) = "$sec sn önce"
    override fun panelAgoMin(minutes: Long) = "$minutes dk önce"
    override fun panelAgoHour(hours: Long) = "$hours sa önce"
    override val panelRefresh = "⟳ Yenile"
    override val panelSearchTitle = "Coin seçimi ve arama"
    override val panelSearchPlaceholder = "ör. BTCUSDT, SOLUSDT..."
    override val panelNoMatch = "Eşleşen coin bulunamadı."
    override val panelActive = "Aktif"
    override val panelLabelSymbol = "Sembol"
    override val panelLabelPrice = "Fiyat"
    override val panelLabelTf = "TF"
    override val panelLabelSignal = "Sinyal"
    override val panelVerdictTitle = "Nihai karar"
    override val panelLabelConfidence = "Güven"
    override val panelLabelThreshold = "Eşik durumu"
    override val panelActionAboveUp = "Eşik üstü · yükseliş eğilimi (analitik)"
    override val panelActionAboveDown = "Eşik üstü · düşüş eğilimi (analitik)"
    override val panelActionBelow = "Eşik altı · gözlem"
    override val panelDistTitle = "Sinyal dağılımı"
    override fun panelIndicatorCount(count: Int) = "$count gösterge"
    override fun panelTfGridTitle(symbol: String) = "$symbol · 12 zaman dilimi konsensüs güveni"
    override fun panelChartTitle(symbol: String, timeframe: String) = "$symbol · ${timeframe.uppercase()} · FİYAT"
    override val panelNoData = "VERİ YOK — yenile"
    override val panelCvdLabel = "CVD DELTA · 1 dk"
    override val panelCvdFlowError = "VERİ YOK (akış hatası)"
    override val panelCvdUnavailable = "CVD alınamadı"
    override val panelCvdNoData = "CVD verisi yok"
    override val panelErrorTitle = "HATA"
    override fun panelNoCandles(symbol: String, timeframe: String) = "$symbol · $timeframe için mum verisi alınamadı"
    override fun panelNotEnoughCandles(symbol: String, timeframe: String, count: Int) =
        "$symbol · $timeframe için yetersiz mum verisi ($count)"
    override fun panelNoSeriesData(symbol: String, timeframe: String) =
        "$symbol · $timeframe için türev verisi (açık pozisyon, oran, fonlama) alınamadı; karar hesaplanmadı"
    override val panelConsensusFailed = "Konsensüs hesaplanamadı"
    override val panelStrategyTitle = "STRATEJİ KATMANLARI (verdict'e dokunmaz)"
    override val panelRegimeLabel = "REJİM"
    override val panelMicroLabel = "MİKROYAPI"
    override val panelMtfGateOpen = "kapı AÇIK ✓"
    override val panelMtfGateClosed = "kapı KAPALI –"
    override fun panelSignalsCount(count: Int) = "$count sinyal"
    override val panelFundingTitle = "FONLAMA"
    override val panelFundingUnavailable = "— (premiumIndex / funding geçmişi alınamadı)"
    override val panelFundingPredicted = "TAHMİNİ"
    override val panelFundingLast = "SON"
    override val panelFundingTimeLabel = "FONLAMA ZAMANI"
    override fun panelRegimeTag(regime: String) = "REJİM $regime"
    override val panelBasisTitle = "BASİS"
    override val panelBasisUnavailable = "— (mark/index fiyatı alınamadı)"
    override val panelBasisCaption = "pozitif bps = perp pahalı (mark > index)"
    override val panelAnnFunding = "YILLIK FONLAMA"
    override val panelVolTitle = "VOLATİLİTE (ATR)"
    override val panelPlanUnavailable = "— (ATR%/fiyat yok ya da yön nötr)"
    override val panelPlanNote = "yalnızca bilgi · ATR tabanlı örnek seviyeler · emir kurulmaz"
    override val panelBandLow = "ALT BANT"
    override val panelBandHigh = "ÜST BANT"
    override val panelConnecting = "BAĞLANIYOR"
    override fun panelDelayed(age: String) = "GECİKME · $age"

    override val alertRuleDeleted = "kural silindi"
    override val alertRuleVerdictLong = "LONG kararı"
    override val alertRuleVerdictShort = "SHORT kararı"
    override val alertRuleVerdictAny = "karar · tümü"
    override fun alertRuleConfidenceAbove(threshold: String) = "güven ≥ $threshold"
    override fun alertRulePriceAbove(price: String) = "fiyat ≥ $price"
    override fun alertRulePriceBelow(price: String) = "fiyat ≤ $price"
    override fun alertRuleOiSpike(threshold: String) = "OI ≥ +$threshold%"
    override val alertCooldownDefault = "varsayılan"
    override fun alertCooldownMinutes(minutes: Long) = "$minutes dk"
    override fun alertConditionSummary(count: Int, shown: String, cooldown: String) =
        "$count koşul · $shown · $cooldown"
    override val alertUniverseAll = "TÜMÜ"
    override fun alertEtaSeconds(seconds: Long) = "~$seconds sn"
    override fun alertEtaMinutes(minutes: Long) = "~$minutes dk"
    override fun alertEtaHours(hours: Long) = "~$hours sa"
    override val alertErrAmbiguousThreshold =
        "Binlik ayracı kullanma (65,000 ve 65.000 belirsiz): 65000 ya da ondalık için 65,5 yaz"
    override val alertErrUnknownSymbol = "Bilinmeyen sembol — USDT ile yaz (örn. BTCUSDT)"
    override val alertSymbolChecking = "Sembol kontrol ediliyor…"
    override fun a11yToggleRule(symbol: String) = "$symbol kuralını aç/kapat"

    override val logClear = "Temizle"
    override val logSearchHint = "Host / yol / yöntem ara…"
    override val logNoMatch = "Bu filtreye uyan istek yok"
    override val logNoRequests = "Henüz API isteği yapılmadı"
    override val logOpenHint = "Scanner, Panel veya OI · L/S sekmesini açın"

    override val appCustomization = "ÖZELLEŞTİRME"
    override val appFontSize = "Yazı boyutu"
    override val appOled = "OLED saf siyah (pikselleri kapatır)"
    override val appReset = "SIFIRLA"
    override val appResetAll = "TÜMÜNÜ SIFIRLA"
    override val appStoredNote = "Tüm tercihler bu cihazda saklanır."
    override val appCustomColors = "ÖZEL RENKLER"
    override val appCustomColorsNote =
        "Her rengi kendiniz seçin — bir örneğe dokunun, R/G/B kaydırın. Cihazda saklanır."
    override val appAccentColor = "Vurgu rengi"

    override val setConfThresholdLabel = "Minimum Konsensüs Eşiği (Güven)"
    override val setTradeThresholdLabel = "Gösterim için minimum güven"
    override val setWeightsDesc =
        "Her göstergenin nihai konsensüs oyuna katkı katsayısı (+/- ile 0,1 adımlarla ayarlanır):"
    override val setExtendedDesc =
        "Masaüstü referans uyum göstergeleri. Ağırlığı düşürmek (ya da 0,0 yapmak) göstergeyi konsensüste fiilen etkisiz kılar."
    override val setSurvivorsLabel = "2. Faz Aday Sayısı"
    override val setParallelismLabel = "Eşzamanlı İstek Sınırı"
    override val setFavoritesHint = "Panel sekmesindeki hızlı erişim listenizi yönetin."
    override val setNoFavorites = "Henüz favori coin eklenmedi."
    override val setSearchPlaceholder = "Favoriye eklemek için coin ara…"
    override val setNoMatch = "Eşleşen coin bulunamadı."
    override val setAddFavorite = "+ Ekle"
    override fun setUniverseError(message: String) = "Sembol listesi alınamadı: $message"
    override val setWsSourceLabel = "Canlı Fiyat Veri Kaynağı (WS)"
    override val setWsFutures = "Vadeli WS (Varsayılan)"
    override val setWsSpot = "Spot WS (Yedek · Hızlı)"
    override val setCandleLimitLabel = "Grafik Mum Sınırı (Genişlik)"
    override fun setCandles(count: Int) = "$count Mum"
    override val setBgNote = "Uygulama kapalıyken yaklaşık 15 dk'da bir kaba tarama · Doze gecikebilir"
    override val setUnmeteredOn = "Mobil veri kullanımı olmadan çalışır"
    override val setUnmeteredOff = "Ana anahtar kapalıyken etkisiz"
    override val setBgResultsNote =
        "Sonuçlar alarm geçmişine ve uygulama önbelleğine düşer; uygulama açıldığında oradan okunur."
    override val setNotifDesc =
        "Alarm kuralları tetiklendiğinde sistem bildirimi gönderilir (FİYAT kuralları canlı akışta, KARAR/GÜVEN/OI kuralları tarama döngüsünde)."
    override val setNotifNotNeeded = "✓ Bu cihazda bildirim izni zorunlu değil — bildirimler çalışır."
    override val setNotifGranted = "✓ İZİN VERİLDİ — sistem bildirimleri açık."
    override val setNotifDenied =
        "⚠ İZİN YOK — sistem bildirimleri sessiz. Uygulama içi alarm bandı ve tetiklenme geçmişi yine de çalışır."
    override val setNotifOpenSettings = "BİLDİRİM AYARLARINI AÇ"
    override val setDarkTheme = "Koyu"
    override val setThemeFixed = "DVCRY markası · sabit"
    override val setAboutApp = "Uygulama"
    override val setAboutVersion = "Sürüm"
    override val setAboutBuild = "Derleme"
    override val setAboutMode = "Mod"
    override val setModeDebug = "Hata ayıklama"
    override val setModeRelease = "Yayın"
    override val setAboutDataSource = "Veri kaynağı"
    override val setAboutIndicators = "Göstergeler"
    override val setAboutIndicatorsValue = "60 (DVCRY konsensüs motoru)"
    override val setAboutTimeframes = "Zaman dilimi sayısı"
    override val setPrivacyRow = "Gizlilik politikası"
    override val setContactRow = "İletişim"
    override val setLicensesRow = "Açık kaynak lisansları"
    override val setClearDataRow = "Tüm yerel verileri temizle"
    override val setClearDataHint =
        "Ayarlar, favoriler, alarmlar, portföy ve önbellek bu cihazdan silinir. Bu işlem geri alınamaz."
    override val setClearConfirm = "SİL"
    override val setClearDone =
        "Yerel veriler silindi. Tüm değişikliklerin tam olarak uygulanması için uygulamayı yeniden başlatın."
    override val setDialogClose = "KAPAT"
    override val setLicensesTitle = "AÇIK KAYNAK LİSANSLARI"
    override val setLicensesIntro =
        "Bu uygulama aşağıdaki açık kaynak yazılımları kullanır. Her kütüphane kendi lisansının koşulları altında dağıtılır."
    override val setPrivacyTitle = "GİZLİLİK POLİTİKASI"
    override val setPrivacyOpenOnline = "Çevrimiçi aç"
    override val setPrivacyBody =
        "DVCRY hesap açmanızı istemez. Ad, e-posta, telefon, konum veya kişisel kimlik bilgisi toplamaz. Uygulamada reklam ya da analitik SDK'sı yoktur.\n\n" +
            "Uygulamanın kendi sunucusu yoktur. Piyasa verisi (fiyatlar, vadeli işlem verileri, açık pozisyon ve benzeri istatistikler) Binance'in herkese açık API'lerinden doğrudan cihazınıza çekilir. Bu isteklerde cihazınızın IP adresi Binance tarafından görülebilir; Binance'in gizlilik politikası o taraf için geçerlidir.\n\n" +
            "Cihazınızda saklananlar: ayarlarınız, favori coin listeniz, alarm kurallarınız ve tetiklenme geçmişiniz, portföy kayıtlarınız (giriş fiyatı ve miktar), tarama önbelleği ve görünüm tercihleriniz. Bu veriler yalnızca cihazınızda tutulur; geliştiriciye gönderilmez.\n\n" +
            "Yedekleme: Android Otomatik Yedekleme açıksa cihazınızın Google yedeklemesi uygulama verilerini Google hesabınızda saklayabilir. Bu yedeklemeyi Google hesap ayarlarınızdan yönetebilirsiniz.\n\n" +
            "Verilerinizi silme: Ayarlar > Tüm yerel verileri temizle ile uygulama verilerini silebilirsiniz. Uygulamayı kaldırmak da verileri siler. Bildirim izni cihaz ayarlarından değiştirilebilir.\n\n" +
            "Bu politika değiştiğinde uygulama içinde güncellenir. Sorularınız için Ayarlar > İletişim satırını kullanın."

    override val platWidgetTitle = "En İyi Kararlar"
    override val platWidgetNoData = "veri yok"
    override fun platWidgetAgo(minutes: Long) = "$minutes dk önce"
    override fun platWidgetPartial(ok: Int, planned: Int) = "kısmi $ok/$planned"
    override val platChannelName = "Uyarılar"
    override val platChannelDesc = "Fiyat ve karar uyarı bildirimleri"
    override val platShareChooser = "Karar kartını paylaş"
    override val platBgSyncFailed = "Arka plan taraması zamanlanamadı; bu cihazda çalışmayabilir."
    override fun b2PortfolioCapReached(max: Int) =
        "Portföy en fazla $max pozisyonla sınırlı. Yeni eklemek için bir pozisyonu silin."
    override val b2AlertPermRationale =
        "Tetiklenen uyarıların uygulama kapalıyken de ulaşması için bildirimlere izin ver. Bunu daha sonra Ayarlar'dan değiştirebilirsin."
    override val b2AlertSaveWithoutPerm = "Bildirimsiz kaydet"

    // ── i18n* (TR) ─────────────────────────────────────────────────────────
    override fun i18nConsReasonLine(
        signal: String,
        confidence: Int,
        risk: String,
        buyCount: Int,
        sellCount: Int,
        neutralCount: Int,
        weightedScore: String,
        risks: List<String>,
    ): String {
        val riskText = when (risk) {
            "LOW" -> scanRiskLow
            "MEDIUM" -> scanRiskMedium
            "HIGH" -> scanRiskHigh
            else -> risk
        }
        val base = "Sinyal=$signal Güven=${confidence}% Risk=$riskText " +
            "Al=$buyCount/Sat=$sellCount/Nötr=$neutralCount WSkor=$weightedScore"
        return if (risks.isNotEmpty()) "$base Riskler: ${risks.joinToString("; ")}" else base
    }
    override val i18nConsHighAtr = "Yüksek ATR volatilitesi"
    override val i18nConsLowAtr = "Düşük ATR volatilitesi (sıkışma potansiyeli)"
    override fun i18nConsWeakTrend(adx: String, minAdx: String) = "Zayıf trend (ADX=$adx < $minAdx)"
    override fun i18nConsHighConflict(ratio: String) = "Yüksek sinyal çatışması (oran=$ratio)"
    override fun i18nConsModerateConflict(ratio: String) = "Orta düzeyde sinyal çatışması (oran=$ratio)"
    override fun i18nConsFewActive(active: Int, minActive: Int) = "Az sayıda aktif sinyal ($active < $minActive)"
    override fun i18nConsWeakConviction(score: String) = "Zayıf inanç (skor=$score)"
    override val i18nConsWhaleRetail = "Balina-perakende pozisyon uyumsuzluğu"
    override val i18nConsExtremeFunding = "Aşırı carry/fonlama oranı"

    override val i18nNarrReading: List<String> = listOf(
        "Klasik kaldıraç çözülmesi (washout); uzun tasfiyesi ve düşüş yönlü devamla en tutarlı görünüm",
        "Düşüş yönlü kalabalık pozisyonlanma sürerken akış karşıtı alım; çoğunlukla short kapanışı, yeni trend dönüşü değil",
        "Kalabalık pozisyonlanma kötüleşirken kaldıraç azalıyor, ama agresiflik yok",
        "Zayıf uzunlar hâlâ tasfiye olurken kalabalık daha yükseliş yönlü olmaya çalışıyor",
        "Açık pozisyonun düştüğü ailede en güçlü ayı tuzağı / short kapanışı toparlanma profili",
        "Kaldıraç çekilirken pozisyonlanma iyileşiyor",
        "Zorunlu çıkışlar, yeni inançtan daha baskın",
        "Kaldıraç azalması ve alıcı agresifliğinin artması",
        "Her iki tarafta da risk çekiliyor ve inanç azalıyor",
        "Düşen piyasada klasik yeni short birikimi",
        "Agresif alıcılar emilirken yeni kaldıraç hâlâ aleyhlerine giriyor",
        "Sessiz short birikimi veya korunma pozisyonu açılıyor",
        "Satıcılar agresif kalırken, düşen piyasaya kalabalık daha yükseliş yönlü giriyor",
        "Düşüşte alıcılar agresif ve kaldıraç artıyor, yine de fiyat düşüyor",
        "Yükselen kaldıraçla kalabalık yükseliş yönlü, ama düşüşü durduracak yeterli alım akışı yok",
        "Yeni kaldıraç ve aktif satış hâkimiyeti",
        "Fiyat hâlâ düşerken her iki taraf da risk ekliyor",
        "Katılım artıyor ama belirleyici tetikleyici akış yok",
        "Önemli yeni kaldıraç olmadan düşüş yönlü emilim veya dağıtım",
        "Alıcılar deniyor, ama kalabalık pozisyonlanma kötüleşirken piyasa hâlâ yükselemiyor",
        "Duyarlılık kötüleşiyor ama agresiflik sınırlı",
        "Kalabalık ve carry iyileşiyor, ama aktif satıcılar hâlâ üstün",
        "Açık pozisyonun yatay olduğu ailede hafif short kapanışıyla birikim",
        "Tetikleyici akış olmadan pozisyonlanma iyileşiyor",
        "Değişmeyen kaldıraç karşısında aktif satıcılar hâkim",
        "Değişmeyen kaldıraçla pozitif akış",
        "Sıkışma, transfer veya gürültü",
    )

    override val i18nNarrReadingMirror: List<String> = listOf(
        "Klasik kaldıraç çözülmesi (washout); short kapanışı ve yükseliş yönlü devamla en tutarlı görünüm",
        "Yükseliş yönlü kalabalık pozisyonlanma sürerken akış karşıtı satış; çoğunlukla uzun tasfiyesi, yeni trend dönüşü değil",
        "Kalabalık pozisyonlanma iyileşirken kaldıraç birikiyor, ama agresiflik yok",
        "Zayıf shortlar hâlâ sıkışırken kalabalık daha düşüş yönlü olmaya çalışıyor",
        "Açık pozisyonun yükseldiği ailede en güçlü boğa tuzağı / uzun tasfiyesi düşüş profili",
        "Kaldıraç çekilirken pozisyonlanma kötüleşiyor",
        "Zorunlu short çıkışları, yeni inançtan daha baskın",
        "Kaldıraç birikimi ve satıcı agresifliğinin azalması",
        "Her iki tarafta da risk geliyor ve inanç artıyor",
        "Yükselen piyasada klasik yeni long birikimi",
        "Agresif satıcılar emilirken yeni kaldıraç hâlâ aleyhlerine giriyor",
        "Sessiz long birikimi veya korunma pozisyonu açılıyor",
        "Alıcılar agresif kalırken, yükselen piyasaya kalabalık daha düşüş yönlü giriyor",
        "Yükselişte satıcılar agresif ve kaldıraç artıyor, yine de fiyat yükseliyor",
        "Yükselen kaldıraçla kalabalık düşüş yönlü, ama yükselişi durduracak yeterli satış akışı yok",
        "Yeni kaldıraç ve aktif alış hâkimiyeti",
        "Fiyat hâlâ yükselirken her iki taraf da risk ekliyor",
        "Katılım artıyor ama belirleyici tetikleyici akış yok",
        "Önemli yeni kaldıraç olmadan yükseliş yönlü emilim veya birikim",
        "Satıcılar deniyor, ama kalabalık pozisyonlanma kötüleşirken piyasa hâlâ düşemiyor",
        "Duyarlılık iyileşiyor ama agresiflik sınırlı",
        "Kalabalık ve carry kötüleşiyor, ama aktif alıcılar hâlâ üstün",
        "Açık pozisyonun yükseldiği ailede hafif long tasfiyesiyle dağıtım",
        "Tetikleyici akış olmadan pozisyonlanma kötüleşiyor",
        "Değişmeyen kaldıraç karşısında aktif alıcılar hâkim",
        "Değişmeyen kaldıraçla negatif akış",
        "Sıkışma, transfer veya gürültü",
    )

    override val i18nNarrBias: List<String> = listOf(
        "Şimdilik düşüş yönlü, ama hareketin geç aşamasında tükenme riski artıyor",
        "Kısa süreli toparlanma olası; yapısal inanç zayıf",
        "Yavaş düşüş yönlü sürüklenme",
        "Toparlanmadan önce bir tasfiye daha olası",
        "Açık pozisyonun düştüğü yapıda short kapanışı akışı öne çıkıyor",
        "Hafifçe pozitif; toparlanma henüz teyit edilmedi",
        "Düşüş yönlü, ama momentum yaşlanıyor",
        "Yerel toparlanma olası",
        "Geçiş hâli; net bir yön yok",
        "En güçlü düşüş yönlü devam",
        "Boğa tuzağı / düşüş yönlü emilim",
        "Düşüş yönlü devam",
        "Kırılgan uzun birikimi, long sıkışma riski",
        "İki taraflı savaş; ya sert dönüş ya da ciddi tuzak",
        "Dağıtım / gizli zayıflık",
        "Temiz düşüş yönlü devam",
        "Oynaklık genişlemesi; kırılım teyit edilene kadar yön belirsiz",
        "Kırılım öncesi izleme durumu",
        "Yavaş düşüş",
        "Başarısız toparlanma riski",
        "Kırılgan düşüş yönlü sürüklenme",
        "Düşüşte alım emiliyor",
        "Yeni dipler yapılmadığında dönüş olası",
        "Nötrden hafifçe yükseliş yönlü, ama eksik",
        "Düşüş yönlü dağıtım",
        "Erken toparlanma olası, düşük güven",
        "Net bir kurulum yok",
    )

    override val i18nNarrBiasMirror: List<String> = listOf(
        "Şimdilik yükseliş yönlü, ama hareketin geç aşamasında tükenme riski artıyor",
        "Kısa süreli geri çekilme olası; yapısal inanç zayıf",
        "Yavaş yükseliş yönlü sürüklenme",
        "Geri çekilmeden önce bir sıkışma daha olası",
        "Açık pozisyonun yükseldiği yapıda long tasfiyesi akışı öne çıkıyor",
        "Hafifçe negatif; geri çekilme henüz teyit edilmedi",
        "Yükseliş yönlü, ama momentum yaşlanıyor",
        "Yerel geri çekilme olası",
        "Geçiş hâli; net bir yön yok",
        "En güçlü yükseliş yönlü devam",
        "Ayı tuzağı / yükseliş yönlü emilim",
        "Yükseliş yönlü devam",
        "Kırılgan short birikimi, short sıkışma riski",
        "İki taraflı savaş; ya sert dönüş ya da ciddi tuzak",
        "Birikim / gizli güç",
        "Temiz yükseliş yönlü devam",
        "Oynaklık genişlemesi; kırılım teyit edilene kadar yön belirsiz",
        "Kırılım öncesi izleme durumu",
        "Yavaş yükseliş",
        "Başarısız düşüş riski",
        "Kırılgan yükseliş yönlü sürüklenme",
        "Yükselişte satış emiliyor",
        "Yeni zirveler yapılmadığında dönüş olası",
        "Nötrden hafifçe düşüş yönlü, ama eksik",
        "Yükseliş yönlü birikim",
        "Erken geri çekilme olası, düşük güven",
        "Net bir kurulum yok",
    )

    override val i18nNarrFlatReading = "Fiyat yatay (konsolidasyon veya ortalamaya dönüş)"

    override val i18nNarrFlatBiasUp = "Aralık içi birikim, alıcılar kırılım denemesinde"

    override val i18nNarrFlatBiasDown = "Aralık içi dağıtım, satıcılar talebi emiyor"

    override val i18nNarrFlatBiasFlat = "Ortalamaya dönen yatay seyir veya hareketsiz piyasa"

    override fun i18nNarrRegime(regime: String): String = when (regime) {
        "CONTINUATION_TREND" -> "trend devamı"
        "MANIPULATION_ANOMALY" -> "manipülasyon anomalisi"
        "SHORT_COVERING_REBOUND" -> "short kapanışı toparlanması"
        "LONG_LIQUIDATION" -> "uzun tasfiyesi"
        "MEAN_REVERTING_CHOP" -> "ortalamaya dönen yatay seyir"
        else -> regime
    }

    override fun i18nNarrExplanation(regime: String, confidence: Int, bias: String, reading: String, score: String) =
        "Rejim=${i18nNarrRegime(regime)} Güven=$confidence% Eğilim=$bias Okuma=$reading Skor=$score"

    override fun i18nAlertPriceAbove(symbol: String, threshold: Double, price: Double) =
        "$symbol fiyat $threshold üzerinde (şimdi $price)"
    override fun i18nAlertPriceBelow(symbol: String, threshold: Double, price: Double) =
        "$symbol fiyat $threshold altında (şimdi $price)"
    override fun i18nAlertVerdict(symbol: String, signal: String, confidence: Int) =
        "$symbol ${when (signal) { "STRONG_BUY", "BUY" -> biasLong; "STRONG_SELL", "SELL" -> biasShort; else -> biasNone }} (güven ${confidence}%)"
    override fun i18nAlertConfidenceAbove(symbol: String, confidence: Int, threshold: Double) =
        "$symbol güven ${confidence}% · eşik $threshold üzerinde"
    override fun i18nAlertOiCollapse(symbol: String, spike: Double, threshold: Double) =
        "$symbol OI $spike% (eşik $threshold%)"
    override fun i18nAlertOiExpansion(symbol: String, spike: Double, threshold: Double) =
        "$symbol OI +$spike% (eşik +$threshold%)"
    override val i18nAlertJoiner = " VE "

    override val i18nFundingPositive = "POZİTİF"
    override val i18nFundingNegative = "NEGATİF"
    override val i18nFundingNeutral = "NÖTR"
    override val i18nConeCaption = "√t ölçekli vol projeksiyonu · geçmiş σ'dan — yön/fiyat tahmini değil"
    override val i18nSmallSampleNote = "yetersiz örnek (n<20)"

    override val i18nAppThemeTitle = "TEMA"
    override fun i18nAppPresetsSummary(count: Int, families: Int) =
        "$count hazır tema · $families aile. Dokunun, anında uygulanır."
    override val i18nAppContrast = "Kontrast"
    override val i18nAppNormal = "Normal"
    override val i18nAppHigh = "Yüksek"
    override val i18nAppSizeSmall = "Küçük"
    override val i18nAppSizeLarge = "Büyük"
    override val i18nAppSizeLargest = "En büyük"
    override val i18nAppCorner = "Köşe yuvarlaklığı"
    override val i18nAppAuto = "Otomatik"
    override val i18nAppSharp = "Keskin"
    override val i18nAppSoft = "Yumuşak"
    override val i18nAppCandleColors = "Mum renkleri"
    override val i18nAppGreenRed = "Yeşil/Kırmızı"
    override val i18nAppRedGreen = "Kırmızı/Yeşil"
    override val i18nAppColorblind = "Renk körü"
    override val i18nAppMonochrome = "Tek renk"
    override val i18nAppScanlines = "Tarama çizgileri (CRT)"
    override val i18nAppDensity = "Yoğunluk"
    override val i18nAppCompact = "Sıkı"
    override val i18nAppCozy = "Rahat"
    override val i18nAppComfy = "Ferah"
    override val i18nAppFont = "Yazı tipi"
    override val i18nAppChartType = "Grafik türü"
    override val i18nAppCandle = "Mum"
    override val i18nAppFontMono = "Mono"
    override val i18nAppFontSans = "Sans"
    override val i18nAppFontSerif = "Serif"
    override val i18nAppHeikin = "Heikin"
    override val i18nAppArea = "Alan"
    override val i18nAppLine = "Çizgi"
    override val i18nAppChartColor = "Grafik rengi"
    override val i18nAppColorAccent = "Vurgu"
    override val i18nAppColorUp = "Yükseliş"
    override val i18nAppColorViolet = "Mor"
    override val i18nAppColorAmber = "Kehribar"
    override val i18nAppColorCyan = "Camgöbeği"
    override val i18nAppColorWhite = "Beyaz"
    override val i18nAppColorTheme = "Tema"
    override val i18nAppColorGreen = "Yeşil"
    override val i18nAppColorRed = "Kırmızı"
    override val i18nAppColorBlue = "Mavi"
    override val i18nTokenBackground = "Arka plan"
    override val i18nTokenCard = "Kart"
    override val i18nTokenCardHover = "Kart (üzerinde)"
    override val i18nTokenBorder = "Kenarlık"
    override val i18nTokenText = "Metin"
    override val i18nTokenMuted = "Soluk metin"
    override val i18nTokenAccent = "Vurgu"
    override val i18nTokenAccent2 = "Vurgu 2"
    override val i18nTokenUp = "Yükseliş"
    override val i18nTokenDown = "Düşüş"
    override val i18nTokenWarn = "Uyarı"

    override val i18nPosMarketTitle = "Piyasa pozisyon verisi"
    override fun i18nPosInspect(index: Int, total: Int) = "İnceleme modu (nokta: $index/$total)"
    override val i18nPosIndicatorsTitle = "Nicel göstergeler ve duyarlılık"
    override fun i18nPosUnifiedTitle(symbol: String) = "$symbol BİRLEŞİK NİCEL ANALİZ"
    override val i18nPosLoadingAnalysis = "ANALİZ YÜKLENİYOR"
    override val i18nPosFetching3 = "Binance genel API'sinden 3 seri alınıyor..."
    override val i18nPosHistoricalTitle = "GEÇMİŞ OI · L/S VERİ TABLOSU"
    override fun i18nPosCandleData(count: Int) = "Geçmiş mum verisi ($count mum)"
    override val i18nPosWhaleDist = "Balina L/S dağılımı (pozisyon ağırlıklı)"
    override val i18nPosAccountDist = "Hesap L/S dağılımı (kullanıcı sayısı)"
    override fun i18nPosRatioDetails(selectedPoint: Boolean) =
        "L/S oran detayı (${if (selectedPoint) "Seçili nokta" else "Güncel"})"
    override val i18nPosQuantBiasTitle = "NİCEL PİYASA YÖN SKORU (NİCEL EĞİLİM)"
    override val i18nPosTilePrice = "FİYAT"
    override val i18nPosTileOi = "AÇIK POZİSYON"
    override val i18nPosTileQuantBias = "NİCEL EĞİLİM"
    override val i18nPosTileWhale = "BALİNA L/S"
    override val i18nPosTileAccount = "HESAP L/S"
    override val i18nPosTileGlobal = "KÜRESEL L/S"
    override val i18nPosTileTaker = "TAKER L/S"
    override val i18nPosTileNetTaker = "NET TAKER HACMİ"
    override val i18nPosTileFunding = "FONLAMA ORANI"
    override val i18nPosColTime = "Zaman"
    override val i18nPosColPrice = "Fiyat"
    override val i18nPosColOi = "Açık poz."
    override val i18nPosColGlobal = "Küresel L/S%"
    override val i18nPosColAccount = "Hesap L/S%"
    override val i18nPosColWhale = "Balina L/S%"
    override val i18nPosColTaker = "Taker L/S%"
    override val i18nPosColNetTaker = "Net Taker H."
    override val i18nPosColFunding = "Fonlama"
    override val i18nPosColBias = "Nicel eğilim"

    override val i18nPerfFetchingTicker = "/fapi/v1/ticker/24hr verisi alınıyor..."
}

/** English catalog. */
object EnStrings : DiveStrings {
    override val code = "en"

    override val navPanel = "Panel"
    override val navScanner = "Scanner"
    override val navPositions = "OI · L/S"
    override val navSignals = "Bias"
    override val navAlerts = "Alerts"
    override val navPortfolio = "Portfolio"
    override val navPerformance = "Leaders"
    override val navLogs = "Network Log"
    override val navAppearance = "Appearance"
    override val navSettings = "Settings"
    override val navMore = "More"
    override val moreSheetTitle = "MORE"
    override val tabPanel = "PANEL"
    override val tabOiLs = "OI · L/S"
    override val tabSignals = "BIAS"

    override val selected = "Selected"
    override val notSelected = "Not selected"
    override val switchOn = "On"
    override val switchOff = "Off"
    override val active = "Active"
    override val passive = "Inactive"

    override val funding = "FUNDING"

    override val setTitleFavorites = "FAVORITE COINS"
    override val setTitleConsensus = "ANALYSIS ALGORITHM SETTINGS"
    override val setTitleWeights = "INDICATOR CONSENSUS WEIGHTS"
    override val setTitleScanner = "SCANNER SETTINGS"
    override val setTitleQuantChart = "QUANTITATIVE DATA & CHART SETTINGS"
    override val setTitleQuantBias = "QUANT BIAS FORMULA WEIGHTS"
    override val setTitleNotifications = "NOTIFICATIONS"
    override val setTitleBackgroundScans = "BACKGROUND SCANS"
    override val setTitleTheme = "THEME"
    override val setTitleAbout = "ABOUT"
    override val setTitleLanguage = "LANGUAGE"
    override val tglBackgroundScan = "Background scan"
    override val tglUnmetered = "Unmetered network only (Wi-Fi)"
    override val lblCore = "CORE"
    override val lblExtended = "EXTENDED"
    override val btnResetWeights = "RESET ALL"
    override val btnRequestNotifPermission = "REQUEST NOTIFICATION PERMISSION"
    override val langTr = "TR"
    override val langEn = "EN"

    override val alertsTitle = "Alerts"
    override val tabRules = "RULES"
    override val tabDigest = "DIGEST"
    override val rulesCondNote =
        "Conditions combine with 'AND'; VERDICT/CONFIDENCE/OI conditions are evaluated in the " +
            "scan loop, PRICE conditions on the live mini-ticker stream."
    override val btnAddRule = "+ ADD RULE"
    override val cardRules = "RULES"
    override val noRulesYet = "No rules yet — create one with ADD RULE."
    override val badgeConditions = "CONDS"
    override val badgeOneShot = "ONE-SHOT"
    override val badgeMuted = "MUTED"
    override val btnDelete = "DELETE"
    override val cardFilters = "FILTERS"
    override val chipAllSymbols = "ALL SYMBOLS"
    override val chipAllKinds = "ALL KINDS"
    override val cardTriggers = "TRIGGERS"
    override val noFiredYet = "No alerts fired yet."
    override val noneMatchFilter = "No triggers match this filter."
    override val a11yAddRule = "Add a new alert rule"
    override val a11yDismissBanner = "Dismiss alert banner"
    override val alarmPrefix = "ALERT"
    override val sheetAddRule = "ADD RULE"
    override val lblSymbol = "SYMBOL"
    override val symbolHint = "e.g. BTCUSDT"
    override val livePricePrefix = "live price:"
    override val lblConditions = "CONDITIONS (fires when all are met)"
    override val andConnector = "— AND —"
    override val btnAddCondition = "+ CONDITION"
    override val lblCooldown = "RE-FIRE WINDOW"
    override val cooldownNote =
        "The same rule never fires more often than this window; ONE-SHOT disables the rule after the first fire."
    override val btnAdd = "ADD"
    override val errThresholdPositive = "Threshold must be > 0"
    override val a11yAddCondition = "Add condition"
    override val a11yRemoveCondition = "Remove condition"
    override val a11yConfirmAddRule = "Add rule"
    override val stateAddable = "Can be added"
    override val stateDisabled = "Disabled"
    override val kindVerdict = "VERDICT DIRECTION"
    override val kindConfidenceAbove = "CONFIDENCE ABOVE"
    override val kindPriceAbove = "PRICE ABOVE"
    override val kindPriceBelow = "PRICE BELOW"
    override val kindOiSpike = "OI SPIKE"
    override val dirAny = "ALL"
    override val dirLong = "LONG"
    override val dirShort = "SHORT"

    override fun cooldownChipLabel(oneShot: Boolean, coalesceMs: Long): String = when {
        oneShot -> "ONE-SHOT"
        coalesceMs == COALESCE_1M_MS -> "1 MIN"
        coalesceMs == COALESCE_15M_MS -> "15 MIN"
        coalesceMs == COALESCE_1H_MS -> "1 H"
        else -> "${coalesceMs / 60_000} min"
    }

    override fun a11yDeleteRule(symbol: String) = "delete ${symbol} rule"
    override fun a11yOpenPanel(symbol: String) = "open ${symbol} panel"

    override val portfolioTitle = "Portfolio"
    override val portfolioLocalOnly = "entries live on this device · no exchange connection"
    override val noPositions = "No positions yet."
    override val noPositionsHint =
        "Add positions manually — P&L is computed here against the live price."
    override val btnAddPosition = "ADD POSITION"
    override val cardPositions = "POSITIONS"
    override val cardTotals = "TOTAL"
    override val entryPrefix = "entry"
    override val sizePrefix = "size"
    override val a11yAddPosition = "Add a new position"
    override val a11yDeletePosition = "delete position"
    override val sheetEditPosition = "EDIT POSITION"
    override val lblSymbolImmutable = "SYMBOL (immutable)"
    override val lblDirection = "DIRECTION"
    override val lblEntryPrice = "ENTRY PRICE"
    override val lblSize = "SIZE (base asset)"
    override val hintPositiveNumber = "Enter a number greater than 0"
    override val hintPositiveSize = "Enter a size greater than 0"
    override val portAmbiguousNumber =
        "Thousands separators are not accepted. Use a comma for decimals (e.g. 12,5) or digits only (e.g. 65000)."
    override val portSymbolInvalid = "Enter a USDT-M futures symbol (e.g. BTCUSDT)"
    override val btnFillLive = "fill:"
    override val btnUpdate = "UPDATE"
    override val btnCancel = "CANCEL"
    override val dlgDeletePositionTitle = "Delete position"
    override val dlgDeletePositionSuffix =
        "position will be deleted from this device. This cannot be undone."
    override val a11yUpdatePosition = "Update position"
    override val a11yAddPositionConfirm = "Add position"
    override val agreeTag = "AGREE"
    override val againstTag = "AGAINST"
    override val unclearTag = "UNCLEAR"

    override fun entrySizeLine(entry: String, size: String) = "entry ${entry} · ${sizePrefix} ${size}"
    override fun totalsLine(complete: Int, excluded: Int): String = if (excluded > 0) {
        "${complete} complete rows · ${excluded} excluded (no live price)"
    } else {
        "${complete} complete rows · none excluded"
    }
    override fun deletePositionBody(symbol: String) = "${symbol} ${dlgDeletePositionSuffix}"

    override val leaderboardTitle = "24h Leaderboard"
    override val cardScannedUniverse = "SCANNED UNIVERSE"
    override val cardGainers = "TOP MOVERS UP"
    override val cardLosers = "TOP MOVERS DOWN"
    override val cardVolume = "TOP BY VOLUME"
    override val noData = "No data"
    override val cardLoading = "LOADING"
    override val evidenceTitle = "ENGINE EVIDENCE (self-grading)"
    override val btnRegrade = "REGRADE"
    override val gradingBusy = "GRADING…"
    override val chipPartial = "PARTIAL — old records not graded"
    override val errorPrefix = "error:"
    override val noEvidence = "No evidence yet — grades accrue as scans archive."
    override val lblConfidenceBands = "CONFIDENCE BANDS"
    override val lblScore = "SCORE"
    override val medianPrefix = "median"
    override val binsCaption = "10 confidence bands · height = samples, color = deviation"
    override val window7d = "7D"
    override val window30d = "30D"
    override val windowAll = "ALL"
    override val horizon1h = "1h"
    override val horizon4h = "4h"
    override val horizon24h = "24h"

    override fun coverageLine(archived: Int, graded: Int, hours: Long) =
        "archive ${archived} · graded ${graded} · horizon ${hours}h"

    override val btnRetry = "⟳ RETRY"
    override val posPreparing = "Preparing data…"
    override val posNoDataYet = "No data for this symbol and period."
    override fun perfUniverseLine(count: Int) = "$count USDT-M futures symbols · stablecoins removed"

    override val disclaimerTitle = "Important notice"
    override val disclaimerBody =
        "DVCRY is a market-analysis tool for information and education only. " +
            "The indicators, biases and statistics it shows are not investment advice; the app " +
            "places no orders and never connects to an exchange account.\n\n" +
            "Leveraged crypto futures carry a very high risk and you can lose all of the capital " +
            "you deposit. Past performance does not indicate future results. You are solely " +
            "responsible for your decisions.\n\n" +
            "Data comes from Binance's public market data. DVCRY is not affiliated " +
            "with or endorsed by Binance."
    override val disclaimerAccept = "I understand"
    override val disclaimerShort = "Not investment advice · for information only"
    override val notAffiliated = "Not affiliated with or endorsed by Binance."
    override val errRegionBlockedTitle = "Data unavailable in this region"
    override val errRegionBlockedBody =
        "Binance blocks market-data requests from your region. Because Dive Into " +
            "Crypto relies on Binance's public data, scans cannot run here. This is not an app " +
            "fault; retrying will not change the result."
    override val errForbiddenTitle = "The data source refused the request"
    override val errForbiddenBody =
        "The data source refused the request — this can be a regional restriction. You can retry."
    override val errNetwork = "Network error — check your internet connection and retry."
    override val errUpstreamGeneric = "The data source did not respond as expected. Please retry shortly."
    override val biasLong = "LONG bias"
    override val biasShort = "SHORT bias"
    override val biasNone = "No clear bias"
    override val filtersPassed = "Passed filters"
    override val filtersNotPassed = "Did not pass filters"

    // ── Scanner (EN) ───────────────────────────────────────────────────────
    override val scanHeroLabel = "MANUAL SCAN"
    override fun scanSourceLine(timeframes: Int) = "Binance USDT-M Futures · $timeframes timeframes"
    override val scanStartButton = "Start scan"
    override val scanStopButton = "Stop"
    override val scanHeroFetchingUniverse = "Fetching symbol list..."
    override val scanHeroIdle = "All Binance USDT-M · 12 timeframes"
    override val scanHeroPhase1 = "Phase 1 · scanning 1d/12h/8h"
    override val scanHeroPhase2 = "Phase 2 · remaining 9 timeframes"
    override val scanHeroRanking = "Computing cross-ranking..."
    override val scanPhaseUniverse = "FETCHING SYMBOL LIST"
    override val scanPhase1 = "PHASE 1 · 1d/12h/8h"
    override val scanPhase2 = "PHASE 2 · 9 TF"
    override val scanPhaseRanking = "RANKING"
    override val scanPhaseIdle = "READY"
    override val scanNowLabel = "NOW"
    override fun scanResultSummary(universe: Int, kept: Int, eliminated: Int) =
        "$universe symbols · ✓ $kept kept · ✕ $eliminated eliminated"
    override fun scanDivergenceSummary(eliminated: Int) =
        "Whale divergence contradicts the indicator: $eliminated coins eliminated"
    override fun scanLastScan(timestamp: String) = "Last scan: $timestamp"
    override fun scanNoSurvivors(eliminated: Int) = "No coin survived this scan · $eliminated eliminated"
    override val scanNoCandidates = "This scan found no coins"
    override val scanNoRunTitle = "No scan run yet"
    override val scanNoRunHint = "Tap the \"Start scan\" button above"
    override fun scanErrorLine(message: String) = "Error: $message"
    override fun scanFailedNote(count: Int) = "⚠ $count symbols could not be scanned — partial result"
    override val scanFailedNoteDismiss = "Dismiss the partial-result warning"
    override val scanTableSize = "TABLE SIZE"
    override val scanAllSize = "All"
    override val scanDivSortOn = "◆ DIVERGENCE SORT ON"
    override val scanDivSortOff = "DIVERGENCE SORT OFF"
    override val scanDivSortNote =
        "Display sorted by |divergence score| descending. Score, ranking and elimination are UNCHANGED; display order only."
    override val scanContinuousOn = "● CONTINUOUS ON"
    override val scanContinuousOff = "CONTINUOUS OFF"
    override fun scanContinuousCycle(cycle: Int) = "● CONTINUOUS · cycle #$cycle"
    override val scanUniverseTitle = "SCAN UNIVERSE"
    override fun scanUniverseSymbols(count: Int) = " · $count symbols"
    override val scanUniverseWarning = "⚠ ~500 symbols · takes a few minutes · applies to the next scan"
    override fun scanWatchFilter(count: Int) = "★ WATCHLIST ($count)"
    override val scanWatchEmpty = "★ Your watchlist is empty — add coins under Settings > Favorite coins."
    override val scanWatchNoResults = "★ No watchlist coin appears in this scan."
    override val scanLensAll = "All"
    override fun scanLensEliminated(count: Int) = if (count > 0) "✕ Eliminated ($count)" else "✕ Eliminated"
    override fun scanNoDivergence(uncheckable: Int) =
        "No whale L/S divergence was found in this scan." +
            if (uncheckable > 0) "  ($uncheckable symbols had no whale data — partial check.)" else ""
    override val scanDivInfoTitle = "⚠ WHALE L/S DIVERGENCE"
    override val scanDivInfoBody =
        "Label = the whale's RAW movement; SCORE = a contrarian ranking heuristic. " +
            "▼ DISTRIBUTION (red): price ↑ + whale L/S ↓ (whales selling into a rally). " +
            "The score is POSITIVE (+) and the coin ranks UP. " +
            "▲ ACCUMULATION (green): price ↓ + whale L/S ↑ → score NEGATIVE (−), ranks down. " +
            "A higher timeframe (1d) carries more weight than a lower one (5m)."
    override val scanResearchNoteTitle = "RESEARCH NOTE"
    override val scanResearchNoteBody =
        "This is not a forecast. Divergence signals can be wrong; past behavior does not guarantee future results."
    override fun scanPriceWhaleLine(priceSign: String, risePct: String, whaleSign: String, whaleDrop: String) =
        "Price $priceSign$risePct% · Whale $whaleSign$whaleDrop%"
    override val scanDistribution = "DISTRIBUTION"
    override val scanAccumulation = "ACCUMULATION"
    override val scanContrarianUp = "Contrarian ranking: ↑ (score +)"
    override val scanContrarianDown = "Contrarian ranking: ↓ (score −)"
    override val scanConfidence = "CONFIDENCE"
    override val scanScore = "SCORE"
    override fun scanTfAgree(count: Int, total: Int) = "$count/$total TF AGREE"
    override val scanRiskLow = "LOW"
    override val scanRiskMedium = "MEDIUM"
    override val scanRiskHigh = "HIGH"
    override val scanRiskNone = "N/A"
    override fun scanTimeframesHeader(count: Int) = "ALL $count TIMEFRAMES"
    override val scanSelect = "SELECT →"
    override fun scanAlertCd(symbol: String) = "Add alert for $symbol"
    override fun scanShareCd(symbol: String) = "Share $symbol verdict card"
    override val scanSelected = "Selected"
    override val scanNotSelected = "Not selected"
    override val scanVolatilityTitle = "VOLATILITY (ATR)"
    override fun scanVolatilityLine(atrPct: String) =
        "ATR ≈ $atrPct% — a typical price-movement gauge; not a recommendation."
    override val scanMtfPass = "MTF ✓"
    override val scanMtfFail = "MTF –"
    override fun scanMicroTag(arrow: String, label: String) = "MICRO $arrow $label"
    override val microStrongBuy = "S.BUY"
    override val microStrongSell = "S.SELL"
    override val microNeutral = "NEUT"
    override fun scanEtaLabel(eta: String) = "ETA $eta"
    override val scanVolatilityNote = "Volatility information only; not a position-size or leverage recommendation."
    override val scanUniverseEmpty = "The Binance futures symbol list came back empty."
    override val scanNoDataFetched = "No symbol data could be fetched."
    override val scanRateLimited = "Binance rate limit reached; try again in a few minutes. Continuous scan has stopped."
    override val scanUnexpectedError = "The scan stopped on an unexpected error."

    // ── Signals (EN) ───────────────────────────────────────────────────────
    override val sigTitle = "Bias"
    override val sigConsensusTitle = "Consensus result"
    override val sigFinalSignal = "Final signal"
    override val sigConfidence = "Confidence"
    override val sigRisk = "Risk"
    override val sigWeightedScore = "W. score"
    override val sigAnalyticStatus = "Analytic status"
    override val sigAboveThreshold = "Above threshold"
    override val sigAboveBull = "Above threshold · upward bias (analytics)"
    override val sigAboveBear = "Above threshold · downward bias (analytics)"
    override val sigBelowThreshold = "Below threshold · observe only"
    override val sigYes = "Yes"
    override val sigNo = "No"
    override val sigDistribution = "Signal distribution"
    override fun sigDistBuy(count: Int) = "BUY $count"
    override fun sigDistNeutral(count: Int) = "NEUTRAL $count"
    override fun sigDistSell(count: Int) = "SELL $count"
    override fun sigIndicatorDetails(count: Int) = "Indicator details ($count)"
    override val sigNoIndicators = "No indicators computed yet"
    override val sigColIndicator = "Indicator"
    override val sigColSignal = "Signal"
    override val sigColScore = "Score"
    override val sigColWeight = "Weight"
    override val sigColWeighted = "W. score"
    override val sigColReason = "Reason"
    override fun sigTimeframesTitle(count: Int) = "Timeframe signals ($count TF)"
    override val sigLoadFailedTitle = "Price history could not be loaded"
    override fun sigLoadPartial(timeframes: String) = "These timeframes could not be loaded: $timeframes"
    override val sigNoDataTimeframe = "No data for this timeframe"
    override fun sigInsufficientHistory(count: Int, needed: Int) =
        "Insufficient history: $count candles (at least $needed needed)"

    override val panelTitleActiveCoin = "Active coin"
    override fun panelLastUpdate(timeText: String) = "Last update: $timeText"
    override fun panelAgoSec(sec: Long) = "${sec}s ago"
    override fun panelAgoMin(minutes: Long) = "${minutes}m ago"
    override fun panelAgoHour(hours: Long) = "${hours}h ago"
    override val panelRefresh = "⟳ Refresh"
    override val panelSearchTitle = "Coin selection and search"
    override val panelSearchPlaceholder = "e.g. BTCUSDT, SOLUSDT..."
    override val panelNoMatch = "No matching coin found."
    override val panelActive = "Active"
    override val panelLabelSymbol = "Symbol"
    override val panelLabelPrice = "Price"
    override val panelLabelTf = "TF"
    override val panelLabelSignal = "Signal"
    override val panelVerdictTitle = "Final verdict"
    override val panelLabelConfidence = "Confidence"
    override val panelLabelThreshold = "Threshold status"
    override val panelActionAboveUp = "Above threshold · upward bias (analytics)"
    override val panelActionAboveDown = "Above threshold · downward bias (analytics)"
    override val panelActionBelow = "Below threshold · observe only"
    override val panelDistTitle = "Signal distribution"
    override fun panelIndicatorCount(count: Int) = "$count indicators"
    override fun panelTfGridTitle(symbol: String) = "$symbol · 12-timeframe consensus confidence"
    override fun panelChartTitle(symbol: String, timeframe: String) = "$symbol · ${timeframe.uppercase()} · PRICE"
    override val panelNoData = "NO DATA — tap to refresh"
    override val panelCvdLabel = "CVD DELTA · 1 min"
    override val panelCvdFlowError = "NO DATA (feed error)"
    override val panelCvdUnavailable = "CVD unavailable"
    override val panelCvdNoData = "No CVD data"
    override val panelErrorTitle = "ERROR"
    override fun panelNoCandles(symbol: String, timeframe: String) = "No candle data for $symbol · $timeframe"
    override fun panelNotEnoughCandles(symbol: String, timeframe: String, count: Int) =
        "Not enough candle data for $symbol · $timeframe ($count)"
    override fun panelNoSeriesData(symbol: String, timeframe: String) =
        "Derivatives data (open interest, ratios, funding) is unavailable for $symbol · $timeframe; no verdict was computed"
    override val panelConsensusFailed = "Consensus could not be computed"
    override val panelStrategyTitle = "STRATEGY LAYERS (does not change the verdict)"
    override val panelRegimeLabel = "REGIME"
    override val panelMicroLabel = "MICROSTRUCTURE"
    override val panelMtfGateOpen = "gate OPEN ✓"
    override val panelMtfGateClosed = "gate CLOSED –"
    override fun panelSignalsCount(count: Int) = "$count signals"
    override val panelFundingTitle = "FUNDING"
    override val panelFundingUnavailable = "— (premiumIndex / funding history unavailable)"
    override val panelFundingPredicted = "PREDICTED"
    override val panelFundingLast = "LAST"
    override val panelFundingTimeLabel = "FUNDING TIME"
    override fun panelRegimeTag(regime: String) = "REGIME $regime"
    override val panelBasisTitle = "BASIS"
    override val panelBasisUnavailable = "— (mark/index price unavailable)"
    override val panelBasisCaption = "positive bps = perp rich (mark > index)"
    override val panelAnnFunding = "ANNUALISED FUNDING"
    override val panelVolTitle = "VOLATILITY (ATR)"
    override val panelPlanUnavailable = "— (no ATR%/price, or neutral direction)"
    override val panelPlanNote = "information only · ATR-based illustrative levels · no order is placed"
    override val panelBandLow = "LOWER BAND"
    override val panelBandHigh = "UPPER BAND"
    override val panelConnecting = "CONNECTING"
    override fun panelDelayed(age: String) = "DELAYED · $age"

    override val alertRuleDeleted = "rule deleted"
    override val alertRuleVerdictLong = "LONG verdict"
    override val alertRuleVerdictShort = "SHORT verdict"
    override val alertRuleVerdictAny = "verdict · all"
    override fun alertRuleConfidenceAbove(threshold: String) = "confidence ≥ $threshold"
    override fun alertRulePriceAbove(price: String) = "price ≥ $price"
    override fun alertRulePriceBelow(price: String) = "price ≤ $price"
    override fun alertRuleOiSpike(threshold: String) = "OI ≥ +$threshold%"
    override val alertCooldownDefault = "default"
    override fun alertCooldownMinutes(minutes: Long) = "$minutes min"
    override fun alertConditionSummary(count: Int, shown: String, cooldown: String) =
        "$count conditions · $shown · $cooldown"
    override val alertUniverseAll = "ALL"
    override fun alertEtaSeconds(seconds: Long) = "~$seconds s"
    override fun alertEtaMinutes(minutes: Long) = "~$minutes min"
    override fun alertEtaHours(hours: Long) = "~$hours h"
    override val alertErrAmbiguousThreshold =
        "Don't use a thousands separator (65,000 and 65.000 are ambiguous): enter 65000, or 65.5 for a decimal"
    override val alertErrUnknownSymbol = "Unknown symbol — include the quote asset (e.g. BTCUSDT)"
    override val alertSymbolChecking = "Checking symbol…"
    override fun a11yToggleRule(symbol: String) = "toggle $symbol rule"

    override val logClear = "Clear"
    override val logSearchHint = "Search host / path / method…"
    override val logNoMatch = "No requests match the filter"
    override val logNoRequests = "No API requests made yet"
    override val logOpenHint = "Open the Scanner, Panel, or OI · L/S tab"

    override val appCustomization = "CUSTOMIZATION"
    override val appFontSize = "Font size"
    override val appOled = "OLED pure black (turns pixels off)"
    override val appReset = "RESET"
    override val appResetAll = "RESET ALL"
    override val appStoredNote = "All preferences are stored on this device."
    override val appCustomColors = "CUSTOM COLORS"
    override val appCustomColorsNote = "Set each color yourself — tap a swatch, slide R/G/B. Stored on-device."
    override val appAccentColor = "Accent color"

    override val setConfThresholdLabel = "Min Consensus Threshold (Confidence)"
    override val setTradeThresholdLabel = "Minimum confidence for display"
    override val setWeightsDesc =
        "Contribution coefficient of each indicator to the final consensus vote (adjust in 0.1 steps with +/-):"
    override val setExtendedDesc =
        "Desktop-reference parity indicators. Lower the weight (or set 0.0) to effectively silence one in the consensus."
    override val setSurvivorsLabel = "Phase 2 Candidate Count"
    override val setParallelismLabel = "Concurrent Request Limit"
    override val setFavoritesHint = "Manage your quick-access list on the Panel tab."
    override val setNoFavorites = "No favorite coins added yet."
    override val setSearchPlaceholder = "Search for a coin to add to favorites…"
    override val setNoMatch = "No matching coin found."
    override val setAddFavorite = "+ Add"
    override fun setUniverseError(message: String) = "Could not load the symbol list: $message"
    override val setWsSourceLabel = "Live Price Data Source (WS)"
    override val setWsFutures = "Futures WS (default)"
    override val setWsSpot = "Spot WS (fallback · fast)"
    override val setCandleLimitLabel = "Chart Candle Limit (Width)"
    override fun setCandles(count: Int) = "$count Candles"
    override val setBgNote = "Rough scan about every 15 min while the app is closed · Doze may delay it"
    override val setUnmeteredOn = "Runs without using mobile data"
    override val setUnmeteredOff = "No effect while the main switch is off"
    override val setBgResultsNote =
        "Results go to the alarm history and app cache, and are read from there when the app opens."
    override val setNotifDesc =
        "When an alarm rule fires, a system notification is sent (PRICE rules on the live stream, VERDICT/CONFIDENCE/OI rules in the scan loop)."
    override val setNotifNotNeeded = "✓ This device needs no notification permission — notifications work."
    override val setNotifGranted = "✓ PERMISSION GRANTED — system notifications are on."
    override val setNotifDenied =
        "⚠ NO PERMISSION — system notifications are silent. The in-app alarm banner and trigger history still work."
    override val setNotifOpenSettings = "OPEN NOTIFICATION SETTINGS"
    override val setDarkTheme = "Dark"
    override val setThemeFixed = "DVCRY brand · fixed"
    override val setAboutApp = "App"
    override val setAboutVersion = "Version"
    override val setAboutBuild = "Build"
    override val setAboutMode = "Mode"
    override val setModeDebug = "Debug"
    override val setModeRelease = "Release"
    override val setAboutDataSource = "Data source"
    override val setAboutIndicators = "Indicators"
    override val setAboutIndicatorsValue = "60 (DVCRY consensus engine)"
    override val setAboutTimeframes = "Timeframe count"
    override val setPrivacyRow = "Privacy policy"
    override val setContactRow = "Contact"
    override val setLicensesRow = "Open-source licenses"
    override val setClearDataRow = "Clear all local data"
    override val setClearDataHint =
        "Settings, favorites, alarms, portfolio and cache are deleted from this device. This cannot be undone."
    override val setClearConfirm = "DELETE"
    override val setClearDone =
        "Local data deleted. Restart the app so every change takes full effect."
    override val setDialogClose = "CLOSE"
    override val setLicensesTitle = "OPEN-SOURCE LICENSES"
    override val setLicensesIntro =
        "This app uses the open-source software below. Each library is distributed under the terms of its license."
    override val setPrivacyTitle = "PRIVACY POLICY"
    override val setPrivacyOpenOnline = "Open online"
    override val setPrivacyBody =
        "DVCRY does not ask you to create an account. It does not collect your name, email, phone number, location or any other personal identifier. The app contains no advertising or analytics SDKs.\n\n" +
            "The app has no server of its own. Market data (prices, futures data, open interest and similar statistics) is requested directly from Binance's public APIs. In those requests your device's IP address is visible to Binance; Binance's own privacy policy governs what Binance does with it.\n\n" +
            "Data stored on your device: your settings, favorite coin list, alarm rules and trigger history, portfolio entries (entry price and size), scan cache and appearance preferences. This data stays on your device and is not sent to the developer.\n\n" +
            "Backup: if Android Auto Backup is enabled, your device's Google backup may include the app's data in your Google account. You manage that backup in your Google account settings.\n\n" +
            "Deleting your data: use Settings > Clear all local data to delete the app's data. Uninstalling the app also deletes it. Notification permission can be changed in your device settings.\n\n" +
            "When this policy changes, the app is updated to match. For questions, use Settings > Contact."

    override val platWidgetTitle = "Top Verdicts"
    override val platWidgetNoData = "no data"
    override fun platWidgetAgo(minutes: Long) = "$minutes min ago"
    override fun platWidgetPartial(ok: Int, planned: Int) = "partial $ok/$planned"
    override fun b2PortfolioCapReached(max: Int) =
        "The portfolio holds at most $max positions. Delete one to add another."
    override val b2AlertPermRationale =
        "Allow notifications so a triggered alert reaches you even when the app is closed. You can change this later in Settings."
    override val b2AlertSaveWithoutPerm = "Save without notifications"
    override val platChannelName = "Alerts"
    override val platChannelDesc = "Price and verdict alert notifications"
    override val platShareChooser = "Share verdict card"
    override val platBgSyncFailed = "Background scan could not be scheduled; it may not run on this device."

    // ── i18n* (EN) — English wording matches the historical UiLabels/ConsensusLabels output ──
    override fun i18nConsReasonLine(
        signal: String,
        confidence: Int,
        risk: String,
        buyCount: Int,
        sellCount: Int,
        neutralCount: Int,
        weightedScore: String,
        risks: List<String>,
    ): String {
        val base = "Signal=$signal Conf=${confidence}% Risk=$risk " +
            "Buy=$buyCount/Sell=$sellCount/Neut=$neutralCount WScore=$weightedScore"
        return if (risks.isNotEmpty()) "$base Risks: ${risks.joinToString("; ")}" else base
    }
    override val i18nConsHighAtr = "High ATR volatility"
    override val i18nConsLowAtr = "Low ATR volatility (squeeze potential)"
    override fun i18nConsWeakTrend(adx: String, minAdx: String) = "Weak trend (ADX=$adx < $minAdx)"
    override fun i18nConsHighConflict(ratio: String) = "High signal conflict (ratio=$ratio)"
    override fun i18nConsModerateConflict(ratio: String) = "Moderate signal conflict (ratio=$ratio)"
    override fun i18nConsFewActive(active: Int, minActive: Int) = "Few active signals ($active < $minActive)"
    override fun i18nConsWeakConviction(score: String) = "Weak conviction (score=$score)"
    override val i18nConsWhaleRetail = "Whale-Retail positioning divergence"
    override val i18nConsExtremeFunding = "Extreme carry/funding rate"

    override val i18nNarrReading: List<String> = listOf(
        "Classic deleveraging washout, most consistent with long liquidation and bearish follow-through",
        "Counterflow buying inside a still-bearish crowding regime; often short covering, not fresh trend reversal",
        "Deleveraging with worsening crowding but no rush of aggression",
        "Crowd tries to get more bullish while weak longs are still being flushed",
        "Strongest bear-trap / short-covering rebound profile in the falling-OI family",
        "Positioning improves while leverage drains",
        "Forced exits dominate more than new conviction",
        "Deleveraging plus improving buyer aggression",
        "Risk is leaving and conviction is fading on both sides",
        "Textbook new short build in a falling market",
        "Aggressive buyers are being absorbed while new leverage still enters against them",
        "Stealth short build or hedge opening",
        "Crowd gets more bullish into a falling market while sellers stay aggressive",
        "Dip buyers are aggressive and leverage is rising, yet price still falls",
        "Bullish crowding with rising leverage, but no flowing bid strong enough to stop the drop",
        "New leverage plus active sell dominance",
        "Both sides are adding risk while price still falls",
        "Participation rises without decisive trigger flow",
        "Bearish absorption or distribution without major fresh leverage",
        "Buyers are trying, but the tape still cannot lift while crowding worsens",
        "Sentiment worsens but aggression is muted",
        "Crowd and carry improve, but active sellers still win",
        "Accumulation with mild short covering in the flat-OI family",
        "Positioning improves without trigger flow",
        "Active sellers dominate against unchanged leverage",
        "Positive flow with unchanged leverage",
        "Compression, transfer, or noise",
    )

    override val i18nNarrReadingMirror: List<String> = listOf(
        "Classic deleveraging washout, most consistent with short covering and bullish follow-through",
        "Counterflow selling inside a still-bullish crowding regime; often long liquidation, not fresh trend reversal",
        "Leverage building with improving crowding, but no rush of aggression",
        "Crowd tries to get more bearish while weak shorts are still being squeezed",
        "Strongest bull-trap / long-liquidation decline profile in the rising-OI family",
        "Positioning deteriorates while leverage drains",
        "Forced short exits dominate more than new conviction",
        "Leverage building plus fading seller aggression",
        "Risk is arriving and conviction is building on both sides",
        "Textbook new long build in a rising market",
        "Aggressive sellers are being absorbed while new leverage still enters against them",
        "Stealth long build or hedge opening",
        "Crowd gets more bearish into a rising market while buyers stay aggressive",
        "Rally sellers are aggressive and leverage is rising, yet price still climbs",
        "Bearish crowding with rising leverage, but no flowing offer strong enough to stop the rally",
        "New leverage plus active buy dominance",
        "Both sides are adding risk while price still climbs",
        "Participation rises without decisive trigger flow",
        "Bullish absorption or accumulation without major fresh leverage",
        "Sellers are trying, but the tape still cannot sink while crowding worsens",
        "Sentiment improves but aggression is muted",
        "Crowd and carry worsen, but active buyers still win",
        "Distribution with mild long liquidation in the flat-OI family",
        "Positioning worsens without trigger flow",
        "Active buyers dominate against unchanged leverage",
        "Negative flow with unchanged leverage",
        "Compression, transfer, or noise",
    )

    override val i18nNarrBias: List<String> = listOf(
        "Bearish now, but exhaustion risk rises late in the move",
        "Short-lived rebound possible; structural conviction weak",
        "Slow bearish drift",
        "One more flush is possible before a bounce",
        "Short-covering flow leads inside falling open interest",
        "Mildly positive; bounce not yet confirmed",
        "Bearish, but energy is aging",
        "Local bounce possible",
        "Transition, no clear direction",
        "Strongest bearish continuation",
        "Bull trap / bearish absorption",
        "Bearish continuation",
        "Fragile long buildup, long-squeeze risk",
        "Two-sided war; either violent reversal or severe trap",
        "Distribution / hidden weakness",
        "Clean continuation lower",
        "Volatility expansion; direction unresolved until a breakout confirms",
        "Pre-breakdown watch state",
        "Grind lower",
        "Failed-bounce risk",
        "Fragile bearish drift",
        "Dip-buying is being absorbed",
        "Reversal possible if price stops making new lows",
        "Neutral-to-slightly bullish, but incomplete",
        "Bearish distribution",
        "Early bounce possible, low confidence",
        "No clear setup",
    )

    override val i18nNarrBiasMirror: List<String> = listOf(
        "Bullish now, but exhaustion risk rises late in the move",
        "Short-lived pullback possible; structural conviction weak",
        "Slow bullish drift",
        "One more squeeze is possible before a pullback",
        "Long-liquidation flow leads inside falling open interest",
        "Mildly negative; pullback not yet confirmed",
        "Bullish, but energy is aging",
        "Local pullback possible",
        "Transition, no clear direction",
        "Strongest bullish continuation",
        "Bear trap / bullish absorption",
        "Bullish continuation",
        "Fragile short buildup, short-squeeze risk",
        "Two-sided war; either violent reversal or severe trap",
        "Accumulation / hidden strength",
        "Clean continuation higher",
        "Volatility expansion; direction unresolved until a breakout confirms",
        "Pre-breakout watch state",
        "Grind higher",
        "Failed-dip risk",
        "Fragile bullish drift",
        "Rally-selling is being absorbed",
        "Reversal possible if price stops making new highs",
        "Neutral-to-slightly bearish, but incomplete",
        "Bullish accumulation",
        "Early pullback possible, low confidence",
        "No clear setup",
    )

    override val i18nNarrFlatReading = "Price flat (consolidation or mean reversion)"

    override val i18nNarrFlatBiasUp = "Range accumulation, buyers attempting breakout"

    override val i18nNarrFlatBiasDown = "Range distribution, sellers absorbing demand"

    override val i18nNarrFlatBiasFlat = "Mean-reverting chop or inactive tape"

    override fun i18nNarrRegime(regime: String): String = when (regime) {
        "CONTINUATION_TREND" -> "trend continuation"
        "MANIPULATION_ANOMALY" -> "manipulation anomaly"
        "SHORT_COVERING_REBOUND" -> "short-covering rebound"
        "LONG_LIQUIDATION" -> "long liquidation"
        "MEAN_REVERTING_CHOP" -> "mean-reverting chop"
        else -> regime
    }

    override fun i18nNarrExplanation(regime: String, confidence: Int, bias: String, reading: String, score: String) =
        "Regime=${i18nNarrRegime(regime)} Conf=$confidence% Bias=$bias Reading=$reading Score=$score"

    override fun i18nAlertPriceAbove(symbol: String, threshold: Double, price: Double) =
        "$symbol price above $threshold (now $price)"
    override fun i18nAlertPriceBelow(symbol: String, threshold: Double, price: Double) =
        "$symbol price below $threshold (now $price)"
    override fun i18nAlertVerdict(symbol: String, signal: String, confidence: Int) =
        "$symbol ${when (signal) { "STRONG_BUY", "BUY" -> biasLong; "STRONG_SELL", "SELL" -> biasShort; else -> biasNone }} (confidence ${confidence}%)"
    override fun i18nAlertConfidenceAbove(symbol: String, confidence: Int, threshold: Double) =
        "$symbol confidence ${confidence}% above $threshold"
    override fun i18nAlertOiCollapse(symbol: String, spike: Double, threshold: Double) =
        "$symbol OI $spike% (threshold $threshold%)"
    override fun i18nAlertOiExpansion(symbol: String, spike: Double, threshold: Double) =
        "$symbol OI +$spike% (threshold +$threshold%)"
    override val i18nAlertJoiner = " AND "

    override val i18nFundingPositive = "POSITIVE"
    override val i18nFundingNegative = "NEGATIVE"
    override val i18nFundingNeutral = "NEUTRAL"
    override val i18nConeCaption = "√t-scaled vol projection · from historical σ — not a direction or price forecast"
    override val i18nSmallSampleNote = "small sample (n<20)"

    override val i18nAppThemeTitle = "THEME"
    override fun i18nAppPresetsSummary(count: Int, families: Int) =
        "$count ready-made themes · $families families. Tap, applies instantly."
    override val i18nAppContrast = "Contrast"
    override val i18nAppNormal = "Normal"
    override val i18nAppHigh = "High"
    override val i18nAppSizeSmall = "Small"
    override val i18nAppSizeLarge = "Large"
    override val i18nAppSizeLargest = "Largest"
    override val i18nAppCorner = "Corner roundness"
    override val i18nAppAuto = "Auto"
    override val i18nAppSharp = "Sharp"
    override val i18nAppSoft = "Soft"
    override val i18nAppCandleColors = "Candle colors"
    override val i18nAppGreenRed = "Green/Red"
    override val i18nAppRedGreen = "Red/Green"
    override val i18nAppColorblind = "Colorblind"
    override val i18nAppMonochrome = "Monochrome"
    override val i18nAppScanlines = "Scanlines (CRT)"
    override val i18nAppDensity = "Density"
    override val i18nAppCompact = "Compact"
    override val i18nAppCozy = "Cozy"
    override val i18nAppComfy = "Comfy"
    override val i18nAppFont = "Font"
    override val i18nAppChartType = "Chart type"
    override val i18nAppCandle = "Candle"
    override val i18nAppFontMono = "Mono"
    override val i18nAppFontSans = "Sans"
    override val i18nAppFontSerif = "Serif"
    override val i18nAppHeikin = "Heikin"
    override val i18nAppArea = "Area"
    override val i18nAppLine = "Line"
    override val i18nAppChartColor = "Chart color"
    override val i18nAppColorAccent = "Accent"
    override val i18nAppColorUp = "Up"
    override val i18nAppColorViolet = "Violet"
    override val i18nAppColorAmber = "Amber"
    override val i18nAppColorCyan = "Cyan"
    override val i18nAppColorWhite = "White"
    override val i18nAppColorTheme = "Theme"
    override val i18nAppColorGreen = "Green"
    override val i18nAppColorRed = "Red"
    override val i18nAppColorBlue = "Blue"
    override val i18nTokenBackground = "Background"
    override val i18nTokenCard = "Card"
    override val i18nTokenCardHover = "Card (hover)"
    override val i18nTokenBorder = "Border"
    override val i18nTokenText = "Text"
    override val i18nTokenMuted = "Muted text"
    override val i18nTokenAccent = "Accent"
    override val i18nTokenAccent2 = "Accent 2"
    override val i18nTokenUp = "Up"
    override val i18nTokenDown = "Down"
    override val i18nTokenWarn = "Warning"

    override val i18nPosMarketTitle = "Market position data"
    override fun i18nPosInspect(index: Int, total: Int) = "Inspect mode (point: $index/$total)"
    override val i18nPosIndicatorsTitle = "Quantitative indicators & sentiment"
    override fun i18nPosUnifiedTitle(symbol: String) = "$symbol UNIFIED QUANTITATIVE ANALYSIS"
    override val i18nPosLoadingAnalysis = "LOADING ANALYSIS"
    override val i18nPosFetching3 = "Fetching 3 series from the Binance public API..."
    override val i18nPosHistoricalTitle = "HISTORICAL OI · L/S DATA TABLE"
    override fun i18nPosCandleData(count: Int) = "Historical candle data ($count candles)"
    override val i18nPosWhaleDist = "Whale L/S distribution (position-weighted)"
    override val i18nPosAccountDist = "Account L/S distribution (user count)"
    override fun i18nPosRatioDetails(selectedPoint: Boolean) =
        "L/S ratio details (${if (selectedPoint) "Selected point" else "Current"})"
    override val i18nPosQuantBiasTitle = "QUANTITATIVE MARKET DIRECTION SCORE (QUANT BIAS)"
    override val i18nPosTilePrice = "PRICE"
    override val i18nPosTileOi = "OPEN INTEREST"
    override val i18nPosTileQuantBias = "QUANT BIAS"
    override val i18nPosTileWhale = "WHALE L/S"
    override val i18nPosTileAccount = "ACCOUNT L/S"
    override val i18nPosTileGlobal = "GLOBAL L/S"
    override val i18nPosTileTaker = "TAKER L/S"
    override val i18nPosTileNetTaker = "NET TAKER VOLUME"
    override val i18nPosTileFunding = "FUNDING RATE"
    override val i18nPosColTime = "Time"
    override val i18nPosColPrice = "Price"
    override val i18nPosColOi = "Open Int."
    override val i18nPosColGlobal = "Global L/S%"
    override val i18nPosColAccount = "Account L/S%"
    override val i18nPosColWhale = "Whale L/S%"
    override val i18nPosColTaker = "Taker L/S%"
    override val i18nPosColNetTaker = "Net Taker V."
    override val i18nPosColFunding = "Funding"
    override val i18nPosColBias = "Quant Bias"

    override val i18nPerfFetchingTicker = "Fetching /fapi/v1/ticker/24hr..."
}

/** Compose-side accessor; defaults to TR until the app root provides the real catalog. */
val LocalDiveStrings = staticCompositionLocalOf<DiveStrings> { TrStrings }

/** KeyValueStore key carrying the language preference. */
const val KEY_LANGUAGE = "language"

/**
 * Resolves the active catalog. An explicit choice ("language" key = "tr"/"en")
 * wins; with no stored choice the device locale decides: Turkish devices get TR,
 * everything else EN.
 */
fun languageFor(kv: KeyValueStore): DiveStrings = when (kv.getString(KEY_LANGUAGE, null)) {
    "en" -> EnStrings
    "tr" -> TrStrings
    else -> if (deviceLanguageTag() == "tr") TrStrings else EnStrings
}
