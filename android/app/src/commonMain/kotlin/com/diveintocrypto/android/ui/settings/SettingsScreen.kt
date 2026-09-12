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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.consensus.DEFAULT_F2_WEIGHTS
import com.diveintocrypto.android.domain.consensus.DEFAULT_FULL_WEIGHTS
import com.diveintocrypto.android.platform.AppInfo
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts
import com.diveintocrypto.android.ui.notifications.rememberNotificationPermission
import com.diveintocrypto.android.platform.rememberBackgroundScanSync

/**
 * Enriched Settings screen (2026-05-24).
 *   1. Analysis settings: consensus confidence thresholds and the ADX regime matrix.
 *   2. Indicator weights: coefficients for RSI, MACD, Bollinger, etc. (+/- stepper).
 *   3. Scanner settings: phase-2 candidate count and concurrent-request limit.
 *   4. Favorite coins: manage the coins listed on the Panel screen (search + add/remove).
 *   5. Theme & About: static version info.
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val state by vm.ui.collectAsStateWithLifecycle()
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current

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
            onSearchChange = vm::setFavoriteSearchQuery,
            onAddFavorite = vm::addFavorite,
            onRemoveFavorite = vm::removeFavorite
        )

        // 2. Analysis threshold settings
        ConsensusSettingsCard(
            title = strings.setTitleConsensus,
            confidenceThreshold = state.confidenceThreshold,
            minConfidenceForTrade = state.minConfidenceForTrade,
            enableRegimeMatrix = state.enableRegimeMatrix,
            onConfThresholdChange = vm::updateConfidenceThreshold,
            onTradeThresholdChange = vm::updateMinConfidenceForTrade,
            onToggleRegime = vm::toggleRegimeMatrix
        )

        // 3. Indicator weight coefficients
        WeightsCard(
            title = strings.setTitleWeights,
            weights = state.weights,
            onUpdateWeight = vm::updateIndicatorWeight,
            onResetWeights = {
                // SIFIRLA: restore EVERY indicator (core + extended) to its canonical
                // default from DEFAULT_FULL_WEIGHTS via the existing public VM method.
                DEFAULT_FULL_WEIGHTS.forEach { (key, default) ->
                    vm.updateIndicatorWeight(key, default)
                }
            }
        )

        // 4. Scanner engine settings
        ScanningCard(
            title = strings.setTitleScanner,
            survivors = state.scanSurvivors,
            parallelism = state.scanParallelism,
            onSurvivorsChange = vm::updateScanSurvivors,
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

        // 4.2 Quant Bias weight settings
        QuantBiasSettingsCard(
            title = strings.setTitleQuantBias,
            taker = state.weightTakerLs,
            oi = state.weightOiMomentum,
            whale = state.weightWhaleLs,
            account = state.weightAccountLs,
            onWeightsChange = vm::updateQuantBiasWeights
        )

        // 4.3 Notification permission (API 33+ runtime grant) — Task 2d
        NotificationsCard(title = strings.setTitleNotifications)

        // 4.4 Background scans (WorkManager, opt-in) — honest scheduling card
        val syncBackgroundScans = rememberBackgroundScanSync()
        BackgroundScansCard(
            title = strings.setTitleBackgroundScans,
            enabled = state.backgroundScansEnabled,
            unmeteredOnly = state.backgroundScansUnmetered,
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
        AboutCard(title = strings.setTitleAbout)

        Spacer(modifier = Modifier.height(30.dp))
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
    onToggleEnabled: (Boolean) -> Unit,
    onToggleUnmetered: (Boolean) -> Unit,
) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    SettingsCard(title = title) {        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(
                label = strings.tglBackgroundScan,
                value = enabled,
                onToggle = onToggleEnabled
            )
            Text(
                text = "uygulama kapalıyken ~15 dk'da bir kaba tarama · Doze gecikebilir",
                color = DiveColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
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
                        text = if (enabled) "Mobil veri kullanımı olmadan çalışır"
                        else "Ana anahtar kapalıyken etkisiz",
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
                text = "Sonuçlar Alarm geçmişine ve tarayıcı önbelleğine düşer; uygulama açıldığında oradan okunur.",
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
    enableRegimeMatrix: Boolean,
    onConfThresholdChange: (Int) -> Unit,
    onTradeThresholdChange: (Int) -> Unit,
    onToggleRegime: (Boolean) -> Unit
) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Regime Matrix Toggle
            ToggleRow(
                label = strings.tglRegimeMatrix,
                value = enableRegimeMatrix,
                onToggle = onToggleRegime
            )
            Text(
                text = "When enabled, the weights of oscillators or trend-following indicators are automatically optimized based on the trend strength (ADX).",
                color = DiveColors.TextMuted,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(4.dp))

            // Consensus Confidence Threshold
            StepperRow(
                label = "Min Consensus Threshold (Confidence)",
                value = "$confidenceThreshold%",
                onDecrease = { onConfThresholdChange((confidenceThreshold - 5).coerceAtLeast(10)) },
                onIncrease = { onConfThresholdChange((confidenceThreshold + 5).coerceAtMost(90)) }
            )

            // Min Confidence for Trade
            StepperRow(
                label = "Min Trade Threshold (Trade Signal)",
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
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    SettingsCard(title = title) {
        // Collapsed by default so first paint composes the 15 core steppers only;
        // expanding adds the 42 extended rows to the (already scrollable) column.
        var extendedOpen by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Contribution coefficients of each indicator to the final consensus vote (adjustable in 0.1 steps with the +/- buttons):",
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
                        .clip(RoundedCornerShape(DiveDims.RadiusSm))
                        .background(DiveColors.RedTint15)
                        .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(DiveDims.RadiusSm))
                        .semantics { role = Role.Button }
                        .clickable(onClick = onResetWeights)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
                    .clip(RoundedCornerShape(DiveDims.RadiusSm))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.RadiusSm))
                    .semantics {
                        role = Role.Button
                        stateDescription = if (extendedOpen) strings.switchOn else strings.switchOff
                    }
                    .clickable { extendedOpen = !extendedOpen }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    text = "Desktop-reference parity indicators. Lower the weight (or 0.0) to effectively silence one in the consensus.",
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
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Phase 2 Candidate Count (Survivors)", color = DiveColors.TextMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 50, 75).forEach { count ->
                        val active = count == survivors
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (active) DiveColors.Accent else DiveColors.BgCardHover)
                                .border(1.dp, if (active) DiveColors.Accent else DiveColors.Border, RoundedCornerShape(20.dp))
                                .clickable { onSurvivorsChange(count) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = count.toString(),
                                color = if (active) Color.White else DiveColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Concurrent Request Limit (Parallelism)", color = DiveColors.TextMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4, 8, 12).forEach { limit ->
                        val active = limit == parallelism
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (active) DiveColors.Accent else DiveColors.BgCardHover)
                                .border(1.dp, if (active) DiveColors.Accent else DiveColors.Border, RoundedCornerShape(20.dp))
                                .clickable { onParallelismChange(limit) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = limit.toString(),
                                color = if (active) Color.White else DiveColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesCard(
    title: String,
    favorites: List<String>,
    searchQuery: String,
    filteredSymbols: List<String>,
    onSearchChange: (String) -> Unit,
    onAddFavorite: (String) -> Unit,
    onRemoveFavorite: (String) -> Unit
) {
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Manage your quick-access list on the Panel tab.",
                color = DiveColors.TextMuted,
                fontSize = 11.sp
            )

            if (favorites.isEmpty()) {
                Text("No favorite coins added yet.", color = DiveColors.TextDim, fontSize = 12.sp)
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
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onRemoveFavorite(symbol) }
                                    .padding(4.dp)
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
                    Text("Search for a coin to add to favorites...", color = DiveColors.TextDim, fontSize = 12.sp)
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

            if (searchQuery.isNotEmpty()) {
                if (filteredSymbols.isEmpty()) {
                    Text("No matching coin found.", color = DiveColors.TextMuted, fontSize = 11.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filteredSymbols.take(4).forEach { symbol ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onAddFavorite(symbol) }
                                    .padding(vertical = 6.dp, horizontal = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = symbol,
                                    color = DiveColors.Text,
                                    fontSize = 12.sp,
                                    fontFamily = DiveFonts.body
                                )
                                Text("+ Add", color = DiveColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            .clickable { onToggle(!value) }
            .padding(vertical = 8.dp),
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
        Text(text = label, color = DiveColors.Text, fontSize = 13.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onDecrease() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onIncrease() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = DiveColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ThemeCard(title: String) {
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
                    text = "Dark",
                    color = DiveColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Dive Into Crypto brand — fixed",
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
private fun AboutCard(title: String) {
    SettingsCard(title = title) {
        AboutRow("App", "Dive Into Crypto")
        Spacer(Modifier.height(8.dp))
        AboutRow("Version", AppInfo.versionName)
        Spacer(Modifier.height(8.dp))
        AboutRow("Build", AppInfo.versionCode.toString())
        Spacer(Modifier.height(8.dp))
        AboutRow("Mode", if (AppInfo.isDebug) "Debug" else "Release")
        Spacer(Modifier.height(8.dp))
        AboutRow("Data source", "Binance USDT-M Futures")
        Spacer(Modifier.height(8.dp))
        AboutRow("Indicators", "60 (Dive Into Crypto consensus engine)")
        Spacer(Modifier.height(8.dp))
        AboutRow("Timeframe count", "12")
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
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    val current by container.language.collectAsStateWithLifecycle()
    SettingsCard(title = strings.setTitleLanguage) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("tr" to strings.langTr, "en" to strings.langEn).forEach { (code, label) ->
                val active = current.code == code
                Box(
                    modifier = Modifier
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
                        .padding(horizontal = 18.dp, vertical = 8.dp),
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
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Live Price Data Source (WS)", color = DiveColors.TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("FUTURES", "SPOT").forEach { source ->
                        val active = source == wsDataSource
                        val displayName = if (source == "FUTURES") "Futures WS (Default)" else "Spot WS (Fallback - Fast)"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (active) DiveColors.Accent else DiveColors.BgCardHover)
                                .border(1.dp, if (active) DiveColors.Accent else DiveColors.Border, RoundedCornerShape(20.dp))
                                .clickable { onSourceChange(source) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = displayName,
                                color = if (active) Color.White else DiveColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Chart Candle Limit (Width)", color = DiveColors.TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 50, 75, 100).forEach { limit ->
                        val active = limit == chartCandleCount
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (active) DiveColors.Accent else DiveColors.BgCardHover)
                                .border(1.dp, if (active) DiveColors.Accent else DiveColors.Border, RoundedCornerShape(20.dp))
                                .clickable { onLimitChange(limit) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "$limit Candles",
                                color = if (active) Color.White else DiveColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuantBiasSettingsCard(
    title: String,
    taker: Double,
    oi: Double,
    whale: Double,
    account: Double,
    onWeightsChange: (Double, Double, Double, Double) -> Unit
) {
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val total = taker + oi + whale + account
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Weighted components of the Market Direction Score:",
                    color = DiveColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Total: " + total.format(2),
                    color = if (kotlin.math.abs(total - 1.0) < 0.001) DiveColors.Green else DiveColors.Orange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body
                )
            }
            Spacer(Modifier.height(6.dp))

            StepperRow(
                label = "TAKER L/S MOMENTUM (Buyer/Seller Ratio)",
                value = taker.format(2),
                onDecrease = {
                    val newVal = (taker - 0.05).coerceAtLeast(0.0)
                    onWeightsChange(newVal, oi, whale, account)
                },
                onIncrease = {
                    val newVal = (taker + 0.05).coerceAtMost(1.0)
                    onWeightsChange(newVal, oi, whale, account)
                }
            )

            StepperRow(
                label = "OI MOMENTUM (Open Interest & Price Alignment)",
                value = oi.format(2),
                onDecrease = {
                    val newVal = (oi - 0.05).coerceAtLeast(0.0)
                    onWeightsChange(taker, newVal, whale, account)
                },
                onIncrease = {
                    val newVal = (oi + 0.05).coerceAtMost(1.0)
                    onWeightsChange(taker, newVal, whale, account)
                }
            )

            StepperRow(
                label = "WHALE BIAS (Whale Position L/S)",
                value = whale.format(2),
                onDecrease = {
                    val newVal = (whale - 0.05).coerceAtLeast(0.0)
                    onWeightsChange(taker, oi, newVal, account)
                },
                onIncrease = {
                    val newVal = (whale + 0.05).coerceAtMost(1.0)
                    onWeightsChange(taker, oi, newVal, account)
                }
            )

            StepperRow(
                label = "ACCOUNT BIAS (Account L/S)",
                value = account.format(2),
                onDecrease = {
                    val newVal = (account - 0.05).coerceAtLeast(0.0)
                    onWeightsChange(taker, oi, whale, newVal)
                },
                onIncrease = {
                    val newVal = (account + 0.05).coerceAtMost(1.0)
                    onWeightsChange(taker, oi, whale, newVal)
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// BİLDİRİMLER (Task 2d) — honest POST_NOTIFICATIONS runtime-permission card.
// The actual request is wired platform-side (rememberNotificationPermission:
// ActivityResultContracts.RequestPermission on Android, API 33+). In-app
// banners + alarm history work regardless of the OS grant — said explicitly.
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun NotificationsCard(title: String) {
    val perm = rememberNotificationPermission()
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    SettingsCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Alarm kuralları tetiklendiğinde sistem bildirimi gönderilir " +
                    "(FİYAT kuralları canlı akışta, KARAR/GÜVEN/OI kuralları tarama döngüsünde).",
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )

            when {
                // Platform doesn't require a runtime grant (API < 33): nothing to ask.
                !perm.needed -> Text(
                    text = "✓ Bu cihazda bildirim izni zorunlu değil — bildirimler çalışır.",
                    color = DiveColors.Green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                perm.granted -> Text(
                    text = "✓ İZİN VERİLDİ — sistem bildirimleri açık.",
                    color = DiveColors.Green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                else -> {
                    Text(
                        text = "⚠ İZİN YOK — sistem bildirimleri sessiz. Uygulama içi alarm " +
                            "bandı ve tetiklenme geçmişi yine de çalışır.",
                        color = DiveColors.Warn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                    )
                    // Request button — the OS dialog fires from the platform launcher.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(DiveColors.Accent)
                            .semantics { role = Role.Button }
                            .clickable { perm.request() }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
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
                }
            }
        }
    }
}
