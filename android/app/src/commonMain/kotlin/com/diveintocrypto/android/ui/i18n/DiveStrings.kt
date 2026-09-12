package com.diveintocrypto.android.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.diveintocrypto.android.data.KeyValueStore
import com.diveintocrypto.android.ui.nav.NavRoute

/**
 * TR/EN string catalog for the shared Compose UI (LANE-7 i18n layer).
 *
 *   - [DiveStrings]  — the interface every screen consumes via [LocalDiveStrings].
 *   - [TrStrings]    — DEFAULT catalog: values are today's on-screen Turkish text
 *     copied VERBATIM, so TR mode renders pixel-identically to pre-i18n.
 *   - [EnStrings]    — English equivalents.
 *
 * The active catalog is resolved from the KeyValueStore key "language"
 * (default "tr") via [languageFor]; [com.diveintocrypto.android.AppContainer]
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
    val tglRegimeMatrix: String
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
    override val navSignals = "Signals"
    override val navAlerts = "Alarmlar"
    override val navPortfolio = "Portföy"
    override val navPerformance = "Leaders"
    override val navLogs = "Network Log"
    override val navAppearance = "Appearance"
    override val navSettings = "Settings"
    override val navMore = "More"
    override val moreSheetTitle = "MORE"
    override val tabPanel = "PANEL"
    override val tabOiLs = "OI · L/S"
    override val tabSignals = "SIGNALS"

    override val selected = "Seçili"
    override val notSelected = "Seçili değil"
    override val switchOn = "Açık"
    override val switchOff = "Kapalı"
    override val active = "Aktif"
    override val passive = "Pasif"

    override val funding = "FUNDING"

    override val setTitleFavorites = "FAVORITE COINS"
    override val setTitleConsensus = "ANALYSIS ALGORITHM SETTINGS"
    override val setTitleWeights = "INDICATOR CONSENSUS WEIGHTS"
    override val setTitleScanner = "SCANNER SETTINGS"
    override val setTitleQuantChart = "QUANTITATIVE DATA & CHART SETTINGS"
    override val setTitleQuantBias = "QUANT BIAS FORMULA WEIGHTS"
    override val setTitleNotifications = "BİLDİRİMLER"
    override val setTitleBackgroundScans = "ARKA PLAN TARAMALARI"
    override val setTitleTheme = "THEME"
    override val setTitleAbout = "ABOUT"
    override val setTitleLanguage = "DİL"
    override val tglBackgroundScan = "Arka plan taraması"
    override val tglUnmetered = "Sadece ölçülmeyen ağ (Wi-Fi)"
    override val tglRegimeMatrix = "Dynamic Regime Matrix (ADX-aware)"
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
    override val lblSymbol = "SEMMBOL"
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
    override val lblSymbolImmutable = "SEMMBOL (değiştirilemez)"
    override val lblDirection = "YÖN"
    override val lblEntryPrice = "GİRİŞ FİYATI"
    override val lblSize = "MİKTAR (baz varlık)"
    override val hintPositiveNumber = "0'dan büyük bir sayı gir"
    override val hintPositiveSize = "0'dan büyük bir miktar gir"
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

    override val leaderboardTitle = "24h Leaderboard"
    override val cardScannedUniverse = "SCANNED UNIVERSE"
    override val cardGainers = "🚀 TOP GAINERS"
    override val cardLosers = "📉 TOP LOSERS"
    override val cardVolume = "💧 HIGHEST VOLUME"
    override val noData = "No data"
    override val cardLoading = "LOADING"
    override val evidenceTitle = "MOTOR KANITI (kendini notlama)"
    override val btnRegrade = "YENİDEN NOTLA"
    override val gradingBusy = "NOTLANIYOR…"
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
}

/** English catalog. */
object EnStrings : DiveStrings {
    override val code = "en"

    override val navPanel = "Panel"
    override val navScanner = "Scanner"
    override val navPositions = "OI · L/S"
    override val navSignals = "Signals"
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
    override val tabSignals = "SIGNALS"

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
    override val tglRegimeMatrix = "Dynamic Regime Matrix (ADX-aware)"
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
    override val cardGainers = "🚀 TOP GAINERS"
    override val cardLosers = "📉 TOP LOSERS"
    override val cardVolume = "💧 HIGHEST VOLUME"
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
}

/** Compose-side accessor; defaults to TR until the app root provides the real catalog. */
val LocalDiveStrings = staticCompositionLocalOf<DiveStrings> { TrStrings }

/** KeyValueStore key carrying the language preference. */
const val KEY_LANGUAGE = "language"

/** Resolves the persisted language ("language" key, default "tr") into a catalog. */
fun languageFor(kv: KeyValueStore): DiveStrings =
    if (kv.getString(KEY_LANGUAGE, "tr") == "en") EnStrings else TrStrings
