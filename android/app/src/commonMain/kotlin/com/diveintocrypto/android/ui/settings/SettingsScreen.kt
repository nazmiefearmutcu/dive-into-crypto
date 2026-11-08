package com.diveintocrypto.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.consensus.DEFAULT_F2_WEIGHTS
import com.diveintocrypto.android.domain.consensus.DEFAULT_FULL_WEIGHTS
import com.diveintocrypto.android.platform.AppInfo
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.rememberBackgroundScanSync
import com.diveintocrypto.android.platform.rememberBackgroundScanSyncFailed
import com.diveintocrypto.android.platform.rememberClearLocalAppData
import com.diveintocrypto.android.platform.rememberOpenEmail
import com.diveintocrypto.android.platform.rememberOpenNotificationSettings
import com.diveintocrypto.android.platform.rememberOpenUrl
import com.diveintocrypto.android.ui.i18n.LocalDiveStrings
import com.diveintocrypto.android.ui.notifications.rememberNotificationPermission
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Settings screen.
 *   1. Favourite coins (search + add/remove, retry when the symbol list failed).
 *   2. Analysis settings: consensus thresholds and the ADX regime matrix.
 *   3. Indicator weights (+/- stepper; SIFIRLA restores defaults in one write).
 *   4. Scanner: phase-2 candidate count and concurrent-request limit.
 *   5. Chart, notifications, background scans, theme, language.
 *   6. About: version, data source (with the non-affiliation statement), privacy
 *      policy, contact, open-source licenses, the disclaimer and local-data deletion.
 *
 * The Quant Bias weight card is intentionally NOT rendered: its four weights are not
 * read by the Market Direction Score yet, so showing them would be a dead control.
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val state by vm.ui.collectAsStateWithLifecycle()
    val strings = LocalDiveStrings.current
    val openUrl = rememberOpenUrl()
    val openEmail = rememberOpenEmail()
    val clearLocalData = rememberClearLocalAppData()

    var showPrivacy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearDoneNotice by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.RootBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Favorite coin management
        FavoritesCard(
            title = strings.setTitleFavorites,
            favorites = state.favorites,
            searchQuery = state.favoriteSearchQuery,
            filteredSymbols = state.filteredSymbols,
            universeLoading = state.universeLoading,
            universeError = state.universeError,
            universeRegionBlocked = state.universeRegionBlocked,
            onRetryUniverse = vm::retryUniverse,
            onSearchChange = vm::setFavoriteSearchQuery,
            onAddFavorite = vm::addFavorite,
            onRemoveFavorite = vm::removeFavorite
        )

        // 2. Analysis threshold settings
        ConsensusSettingsCard(
            title = strings.setTitleConsensus,
            confidenceThreshold = state.confidenceThreshold,
            minConfidenceForTrade = state.minConfidenceForTrade,
            onConfThresholdChange = vm::updateConfidenceThreshold,
            onTradeThresholdChange = vm::updateMinConfidenceForTrade,
        )

        // 3. Indicator weight coefficients
        WeightsCard(
            title = strings.setTitleWeights,
            weights = state.weights,
            onUpdateWeight = vm::updateIndicatorWeight,
            onResetWeights = vm::resetWeights
        )

        // 4. Scanner engine settings
        ScanningCard(
            title = strings.setTitleScanner,
            survivors = state.scanDepthTop,
            parallelism = state.scanParallelism,
            onSurvivorsChange = vm::updateScanDepthTop,
            onParallelismChange = vm::updateScanParallelism
        )

        // 4.1 Quantitative chart settings
        QuantitativeChartSettingsCard(
            title = strings.setTitleQuantChart,
            wsDataSource = state.wsDataSource,
            chartCandleCount = state.chartCandleCount,
            onSourceChange = vm::updateWsDataSource,
            onLimitChange = vm::updateChartCandleCount
        )

        // 4.3 Notification permission (API 33+ runtime grant)
        NotificationsCard(title = strings.setTitleNotifications, onOpenAppSettings = null)

        // 4.4 Background scans (WorkManager, opt-in) — honest scheduling card
        val syncBackgroundScans = rememberBackgroundScanSync()
        BackgroundScansCard(
            title = strings.setTitleBackgroundScans,
            enabled = state.backgroundScansEnabled,
            unmeteredOnly = state.backgroundScansUnmetered,
            syncFailed = rememberBackgroundScanSyncFailed(),
            onToggleEnabled = { enabled ->
                vm.updateBackgroundScans(enabled = enabled)
                syncBackgroundScans()
            },
            onToggleUnmetered = { unmetered ->
                vm.updateBackgroundScans(unmetered = unmetered)
                syncBackgroundScans()
            },
        )

        // 5. Theme, Language & About
        ThemeCard(title = strings.setTitleTheme)
        LanguageCard(container = container)
        AboutCard(
            title = strings.setTitleAbout,
            onOpenPrivacy = { showPrivacy = true },
            onOpenLicenses = { showLicenses = true },
            onContact = { if (SUPPORT_EMAIL.isNotBlank()) openEmail(SUPPORT_EMAIL, "DVCRY") },
            onClearData = { showClearConfirm = true },
            clearDoneNotice = clearDoneNotice,
        )

        Spacer(modifier = Modifier.height(30.dp))
    }

    if (showPrivacy) {
        val onOpenOnline: (() -> Unit)? =
            if (PRIVACY_POLICY_URL.isNotBlank()) ({ openUrl(PRIVACY_POLICY_URL) }) else null
        PrivacyPolicyDialog(
            onDismiss = { showPrivacy = false },
            onOpenOnline = onOpenOnline,
        )
    }
    if (showLicenses) {
        OssLicensesDialog(onDismiss = { showLicenses = false })
    }
    if (showClearConfirm) {
        ClearLocalDataDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                showClearConfirm = false
                clearDoneNotice = clearLocalData()
            },
        )
    }
}

/**
 * BACKGROUND SCANS card — opt-in periodic WorkManager scan. Persisting flips
 * [SettingsData.backgroundScansEnabled]/[backgroundScansUnmetered] AND calls
 * the platform sync ([rememberBackgroundScanSync] → WorkManager enqueue/cancel
 * with fresh constraints). The description is deliberately honest: WorkManager's
 * 15-minute minimum + Doze means the OS can and will defer runs.
 */
@Composable
private fun BackgroundScansCard(
    title: String,
    enabled: Boolean,
    unmeteredOnly: Boolean,
    syncFailed: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleUnmetered: (Boolean) -> Unit,
) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(
                label = strings.tglBackgroundScan,
                value = enabled,
                onToggle = onToggleEnabled
            )
            Text(
                text = strings.setBgNote,
                color = DiveColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            if (enabled && syncFailed) {
                Text(
                    text = strings.platBgSyncFailed,
                    color = DiveColors.Warn,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onToggleUnmetered(!unmeteredOnly) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.tglUnmetered,
                        color = if (enabled) DiveColors.Text else DiveColors.TextDim,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = if (enabled) strings.setUnmeteredOn else strings.setUnmeteredOff,
                        color = DiveColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (enabled && unmeteredOnly) DiveColors.Accent else DiveColors.BgCardHover)
                        .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
                        .semantics {
                            role = Role.Switch
                            stateDescription = if (enabled && unmeteredOnly) strings.switchOn else strings.switchOff
                        }
                        .clickable(enabled = enabled) { onToggleUnmetered(!unmeteredOnly) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = if (enabled && unmeteredOnly) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                    )
                }
            }
            Text(
                text = strings.setBgResultsNote,
                color = DiveColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun ConsensusSettingsCard(
    title: String,
    confidenceThreshold: Int,
    minConfidenceForTrade: Int,
    onConfThresholdChange: (Int) -> Unit,
    onTradeThresholdChange: (Int) -> Unit,
) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The Dynamic Regime Matrix toggle was removed: the regime overlay is
            // observational and never changes the verdict, so no control is shown for it.
            Spacer(Modifier.height(4.dp))

            // Consensus Confidence Threshold
            StepperRow(
                label = strings.setConfThresholdLabel,
                value = "$confidenceThreshold%",
                onDecrease = { onConfThresholdChange((confidenceThreshold - 5).coerceAtLeast(10)) },
                onIncrease = { onConfThresholdChange((confidenceThreshold + 5).coerceAtMost(90)) }
            )

            // Min Confidence for Trade
            StepperRow(
                label = strings.setTradeThresholdLabel,
                value = "$minConfidenceForTrade%",
                onDecrease = { onTradeThresholdChange((minConfidenceForTrade - 5).coerceAtLeast(15)) },
                onIncrease = { onTradeThresholdChange((minConfidenceForTrade + 5).coerceAtMost(95)) }
            )
        }
    }
}

/**
 * Indicator weight keys, derived from the engine's canonical maps (never hardcoded):
 *   - CORE (15)      = [DEFAULT_F2_WEIGHTS] keys — the F2 consensus matrix the
 *                      production engine has always applied.
 *   - EXTENDED (42)  = [DEFAULT_FULL_WEIGHTS] keys minus the core set — the
 *                      desktop-reference parity indicators.
 */
private val CORE_WEIGHT_KEYS: List<String> = DEFAULT_F2_WEIGHTS.keys.toList()
private val EXTENDED_WEIGHT_KEYS: List<String> =
    DEFAULT_FULL_WEIGHTS.keys.filter { it !in DEFAULT_F2_WEIGHTS }

@Composable
private fun WeightsCard(
    title: String,
    weights: Map<String, Double>,
    onUpdateWeight: (String, Double) -> Unit,
    onResetWeights: () -> Unit,
) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        // Collapsed by default so first paint composes the 15 core steppers only;
        // expanding adds the 42 extended rows to the (already scrollable) column.
        var extendedOpen by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.setWeightsDesc,
                color = DiveColors.TextMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))

            // ── CORE (15) ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${strings.lblCore} (${CORE_WEIGHT_KEYS.size})",
                    color = DiveColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    fontFamily = DiveFonts.body,
                    modifier = Modifier.weight(1f),
                )
                // SIFIRLA — resets ALL indicators (core + extended) to defaults.
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(DiveDims.RadiusSm))
                        .background(DiveColors.RedTint15)
                        .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(DiveDims.RadiusSm))
                        .semantics { role = Role.Button }
                        .clickable(onClick = onResetWeights)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = strings.btnResetWeights,
                        color = DiveColors.Red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        fontFamily = DiveFonts.body,
                    )
                }
            }
            CORE_WEIGHT_KEYS.forEach { key ->
                WeightStepperRow(key = key, weights = weights, onUpdateWeight = onUpdateWeight)
            }

            // ── EXTENDED (42) — collapsible ───────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(DiveDims.RadiusSm))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.RadiusSm))
                    .semantics {
                        role = Role.Button
                        stateDescription = if (extendedOpen) strings.switchOn else strings.switchOff
                    }
                    .clickable { extendedOpen = !extendedOpen }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = if (extendedOpen) "▾ ${strings.lblExtended} (${EXTENDED_WEIGHT_KEYS.size})" else "▸ ${strings.lblExtended} (${EXTENDED_WEIGHT_KEYS.size})",
                    color = DiveColors.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontFamily = DiveFonts.body,
                )
            }
            if (extendedOpen) {
                Text(
                    text = strings.setExtendedDesc,
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                )
                EXTENDED_WEIGHT_KEYS.forEach { key ->
                    WeightStepperRow(key = key, weights = weights, onUpdateWeight = onUpdateWeight)
                }
            }
        }
    }
}

/** One indicator weight stepper row (reuse of the shared StepperRow). */
@Composable
private fun WeightStepperRow(
    key: String,
    weights: Map<String, Double>,
    onUpdateWeight: (String, Double) -> Unit,
) {
    val currentWeight = weights[key] ?: 1.0
    val displayName = key.replace("_", " ").uppercase()
    StepperRow(
        label = displayName,
        value = currentWeight.format(1),
        onDecrease = {
            val newVal = (currentWeight - 0.1).coerceAtLeast(0.0)
            onUpdateWeight(key, newVal)
        },
        onIncrease = {
            val newVal = (currentWeight + 0.1).coerceAtMost(5.0)
            onUpdateWeight(key, newVal)
        }
    )
}

@Composable
private fun ScanningCard(
    title: String,
    survivors: Int,
    parallelism: Int,
    onSurvivorsChange: (Int) -> Unit,
    onParallelismChange: (Int) -> Unit
) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.setSurvivorsLabel, color = DiveColors.TextMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 50, 75).forEach { count ->
                        ChoiceChip(label = count.toString(), active = count == survivors) {
                            onSurvivorsChange(count)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.setParallelismLabel, color = DiveColors.TextMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4, 8, 12).forEach { limit ->
                        ChoiceChip(label = limit.toString(), active = limit == parallelism) {
                            onParallelismChange(limit)
                        }
                    }
                }
            }
        }
    }
}

/** Pill chip with a 48dp touch target (PS-30). */
@Composable
private fun ChoiceChip(label: String, active: Boolean, onClick: () -> Unit) {
    val strings = LocalDiveStrings.current
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) DiveColors.Accent else DiveColors.BgCardHover)
            .border(1.dp, if (active) DiveColors.Accent else DiveColors.Border, RoundedCornerShape(20.dp))
            .semantics {
                role = Role.Button
                stateDescription = if (active) strings.selected else strings.notSelected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Color.White else DiveColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FavoritesCard(
    title: String,
    favorites: List<String>,
    searchQuery: String,
    filteredSymbols: List<String>,
    universeLoading: Boolean,
    universeError: String?,
    universeRegionBlocked: Boolean,
    onRetryUniverse: () -> Unit,
    onSearchChange: (String) -> Unit,
    onAddFavorite: (String) -> Unit,
    onRemoveFavorite: (String) -> Unit
) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = strings.setFavoritesHint,
                color = DiveColors.TextMuted,
                fontSize = 11.sp
            )

            if (favorites.isEmpty()) {
                Text(strings.setNoFavorites, color = DiveColors.TextDim, fontSize = 12.sp)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    favorites.forEach { symbol ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(DiveColors.BgCardHover)
                                .border(1.dp, DiveColors.Border, RoundedCornerShape(20.dp))
                                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = symbol,
                                color = DiveColors.Text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = DiveFonts.body
                            )
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .height(48.dp)
                                    .width(48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onRemoveFavorite(symbol) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("×", color = DiveColors.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (searchQuery.isEmpty()) {
                    Text(strings.setSearchPlaceholder, color = DiveColors.TextDim, fontSize = 12.sp)
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle = TextStyle(color = DiveColors.Text, fontSize = 13.sp, fontFamily = DiveFonts.body),
                    cursorBrush = SolidColor(DiveColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (universeError != null) {
                Text(
                    text = if (universeRegionBlocked) {
                        strings.errRegionBlockedTitle + " — " + strings.errRegionBlockedBody
                    } else {
                        strings.errNetwork
                    },
                    color = DiveColors.Warn,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                if (!universeRegionBlocked) {
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DiveColors.BgCardHover)
                            .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
                            .semantics { role = Role.Button }
                            .clickable(onClick = onRetryUniverse)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = strings.btnRetry,
                            color = DiveColors.Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = DiveFonts.body,
                        )
                    }
                }
            }

            if (searchQuery.isNotEmpty()) {
                if (filteredSymbols.isEmpty()) {
                    Text(
                        text = if (universeLoading) strings.alertSymbolChecking else strings.setNoMatch,
                        color = DiveColors.TextMuted,
                        fontSize = 11.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filteredSymbols.take(4).forEach { symbol ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onAddFavorite(symbol) }
                                    .padding(horizontal = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = symbol,
                                    color = DiveColors.Text,
                                    fontSize = 12.sp,
                                    fontFamily = DiveFonts.body
                                )
                                Text(strings.setAddFavorite, color = DiveColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onToggle(!value) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = DiveColors.Text, fontSize = 13.sp)
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (value) DiveColors.Accent else DiveColors.BgCardHover)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 4.dp),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = DiveColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // "−" / "+" steppers: 48dp touch targets (PS-30).
            Box(
                modifier = Modifier
                    .size48()
                    .clip(RoundedCornerShape(6.dp))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onDecrease() },
                contentAlignment = Alignment.Center
            ) {
                Text("-", color = DiveColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = value,
                color = DiveColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .size48()
                    .clip(RoundedCornerShape(6.dp))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onIncrease() },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = DiveColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun Modifier.size48(): Modifier = this.then(Modifier.width(48.dp).height(48.dp))

@Composable
private fun ThemeCard(title: String) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Swatch(DiveColors.Bg); Swatch(DiveColors.BgCard); Swatch(DiveColors.BgCardHover)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Swatch(DiveColors.Accent); Swatch(DiveColors.Green); Swatch(DiveColors.Red)
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.setDarkTheme,
                    color = DiveColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = strings.setThemeFixed,
                    color = DiveColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Spacer(
        modifier = Modifier
            .width(16.dp)
            .height(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

@Composable
private fun AboutCard(
    title: String,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
    onContact: () -> Unit,
    onClearData: () -> Unit,
    clearDoneNotice: Boolean,
) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        AboutRow(strings.setAboutApp, "DVCRY")
        Spacer(Modifier.height(8.dp))
        AboutRow(strings.setAboutVersion, AppInfo.versionName)
        Spacer(Modifier.height(8.dp))
        AboutRow(strings.setAboutBuild, AppInfo.versionCode.toString())
        Spacer(Modifier.height(8.dp))
        AboutRow(strings.setAboutMode, if (AppInfo.isDebug) strings.setModeDebug else strings.setModeRelease)
        Spacer(Modifier.height(8.dp))
        AboutRow(strings.setAboutDataSource, "Binance USDT-M Futures")
        Spacer(Modifier.height(8.dp))
        AboutRow(strings.setAboutIndicators, strings.setAboutIndicatorsValue)
        Spacer(Modifier.height(8.dp))
        AboutRow(strings.setAboutTimeframes, "12")

        // Non-affiliation statement, next to the data-source name (trademark policy).
        Spacer(Modifier.height(10.dp))
        Text(strings.notAffiliated, color = DiveColors.TextDim, fontSize = 11.sp, lineHeight = 15.sp)

        Spacer(Modifier.height(12.dp))
        Text(strings.disclaimerTitle, color = DiveColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(strings.disclaimerBody, color = DiveColors.TextDim, fontSize = 11.sp, lineHeight = 15.sp)

        Spacer(Modifier.height(12.dp))
        LinkRow(label = strings.setPrivacyRow, onClick = onOpenPrivacy)
        if (SUPPORT_EMAIL.isNotBlank()) {
            LinkRow(label = strings.setContactRow, onClick = onContact)
        }
        LinkRow(label = strings.setLicensesRow, onClick = onOpenLicenses)
        LinkRow(label = strings.setClearDataRow, onClick = onClearData, destructive = true)
        if (clearDoneNotice) {
            Text(strings.setClearDone, color = DiveColors.Warn, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (destructive) DiveColors.Red else DiveColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DiveColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = DiveColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * TR/EN language switch (LANE-7 i18n) — two chips persisting the "language"
 * KeyValueStore key via [AppContainer.setLanguage]; the active catalog flows
 * back through AppContainer.language → LocalDiveStrings app-wide.
 */
@Composable
private fun LanguageCard(container: AppContainer) {
    val strings = LocalDiveStrings.current
    val current by container.language.collectAsStateWithLifecycle()
    SettingsCard(title = strings.setTitleLanguage) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("tr" to strings.langTr, "en" to strings.langEn).forEach { (code, label) ->
                val active = current.code == code
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) DiveColors.Accent else DiveColors.BgCardHover)
                        .border(
                            1.dp,
                            if (active) DiveColors.Accent else DiveColors.Border,
                            RoundedCornerShape(20.dp),
                        )
                        .semantics {
                            role = Role.Button
                            stateDescription = if (active) strings.selected else strings.notSelected
                        }
                        .clickable { container.setLanguage(code) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (active) Color.White else DiveColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun QuantitativeChartSettingsCard(
    title: String,
    wsDataSource: String,
    chartCandleCount: Int,
    onSourceChange: (String) -> Unit,
    onLimitChange: (Int) -> Unit
) {
    val strings = LocalDiveStrings.current
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.setWsSourceLabel, color = DiveColors.TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("FUTURES", "SPOT").forEach { source ->
                        val displayName = if (source == "FUTURES") strings.setWsFutures else strings.setWsSpot
                        ChoiceChip(label = displayName, active = source == wsDataSource) {
                            onSourceChange(source)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.setCandleLimitLabel, color = DiveColors.TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 50, 75, 100).forEach { limit ->
                        ChoiceChip(label = strings.setCandles(limit), active = limit == chartCandleCount) {
                            onLimitChange(limit)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// BİLDİRİMLER — honest POST_NOTIFICATIONS runtime-permission card.
// The request is wired platform-side (rememberNotificationPermission). In-app
// banners + alarm history work regardless of the OS grant — said explicitly.
// After a request that did not grant the permission, a second action opens the
// system notification settings (a permanently denied permission cannot re-prompt).
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun NotificationsCard(title: String, onOpenAppSettings: (() -> Unit)?) {
    val perm = rememberNotificationPermission()
    val strings = LocalDiveStrings.current
    val openNotificationSettings = rememberOpenNotificationSettings()
    var requestedOnce by remember { mutableStateOf(false) }
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = strings.setNotifDesc,
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )

            when {
                // Platform doesn't require a runtime grant (API < 33): nothing to ask.
                !perm.needed -> Text(
                    text = strings.setNotifNotNeeded,
                    color = DiveColors.Green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                perm.granted -> Text(
                    text = strings.setNotifGranted,
                    color = DiveColors.Green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                else -> {
                    Text(
                        text = strings.setNotifDenied,
                        color = DiveColors.Warn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                    )
                    // Request button — the OS dialog fires from the platform launcher.
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DiveColors.Accent)
                            .semantics { role = Role.Button }
                            .clickable {
                                requestedOnce = true
                                perm.request()
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = strings.btnRequestNotifPermission,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            fontFamily = DiveFonts.body,
                        )
                    }
                    // Still denied after asking: the OS will not show the dialog again.
                    if (requestedOnce) {
                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DiveColors.BgCardHover)
                                .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
                                .semantics { role = Role.Button }
                                .clickable { (onOpenAppSettings ?: openNotificationSettings)() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = strings.setNotifOpenSettings,
                                color = DiveColors.Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                fontFamily = DiveFonts.body,
                            )
                        }
                    }
                }
            }
        }
    }
}
