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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.platform.AppInfo
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveFonts
import com.diveintocrypto.android.util.tr

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
            favorites = state.favorites,
            searchQuery = state.favoriteSearchQuery,
            filteredSymbols = state.filteredSymbols,
            onSearchChange = vm::setFavoriteSearchQuery,
            onAddFavorite = vm::addFavorite,
            onRemoveFavorite = vm::removeFavorite
        )

        // 2. Analysis threshold settings
        ConsensusSettingsCard(
            confidenceThreshold = state.confidenceThreshold,
            minConfidenceForTrade = state.minConfidenceForTrade,
            enableRegimeMatrix = state.enableRegimeMatrix,
            onConfThresholdChange = vm::updateConfidenceThreshold,
            onTradeThresholdChange = vm::updateMinConfidenceForTrade,
            onToggleRegime = vm::toggleRegimeMatrix
        )

        // 3. Indicator weight coefficients
        WeightsCard(
            weights = state.weights,
            onUpdateWeight = vm::updateIndicatorWeight
        )

        // 4. Scanner engine settings
        ScanningCard(
            survivors = state.scanSurvivors,
            parallelism = state.scanParallelism,
            onSurvivorsChange = vm::updateScanSurvivors,
            onParallelismChange = vm::updateScanParallelism
        )

        // 4.1 Quantitative chart settings
        QuantitativeChartSettingsCard(
            wsDataSource = state.wsDataSource,
            chartCandleCount = state.chartCandleCount,
            onSourceChange = vm::updateWsDataSource,
            onLimitChange = vm::updateChartCandleCount
        )

        // 4.2 Quant Bias weight settings
        QuantBiasSettingsCard(
            taker = state.weightTakerLs,
            oi = state.weightOiMomentum,
            whale = state.weightWhaleLs,
            account = state.weightAccountLs,
            onWeightsChange = vm::updateQuantBiasWeights
        )

        // 5. Language & Theme & About
        LanguageCard(currentLang = state.language, onLanguageChange = vm::updateLanguage)
        ThemeCard()
        AboutCard()

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun ConsensusSettingsCard(
    confidenceThreshold: Int,
    minConfidenceForTrade: Int,
    enableRegimeMatrix: Boolean,
    onConfThresholdChange: (Int) -> Unit,
    onTradeThresholdChange: (Int) -> Unit,
    onToggleRegime: (Boolean) -> Unit
) {
    SettingsCard(title = "ANALYSIS ALGORITHM SETTINGS") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Regime Matrix Toggle
            ToggleRow(
                label = "Dynamic Regime Matrix (ADX-aware)",
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

@Composable
private fun WeightsCard(
    weights: Map<String, Double>,
    onUpdateWeight: (String, Double) -> Unit
) {
    SettingsCard(title = "INDICATOR CONSENSUS WEIGHTS") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Contribution coefficients of each indicator to the final consensus vote (adjustable in 0.1 steps with the +/- buttons):",
                color = DiveColors.TextMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))

            val order = listOf("rsi", "macd", "bollinger", "ema_cross", "sma_cross", "ichimoku", "psar", "obv")
            order.forEach { key ->
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
        }
    }
}

@Composable
private fun ScanningCard(
    survivors: Int,
    parallelism: Int,
    onSurvivorsChange: (Int) -> Unit,
    onParallelismChange: (Int) -> Unit
) {
    SettingsCard(title = "SCANNER SETTINGS") {
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
    favorites: List<String>,
    searchQuery: String,
    filteredSymbols: List<String>,
    onSearchChange: (String) -> Unit,
    onAddFavorite: (String) -> Unit,
    onRemoveFavorite: (String) -> Unit
) {
    SettingsCard(title = "FAVORITE COINS") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Manage your quick-access list on the Panel tab.".tr(),
                color = DiveColors.TextMuted,
                fontSize = 11.sp
            )

            if (favorites.isEmpty()) {
                Text("No favorite coins added yet.".tr(), color = DiveColors.TextDim, fontSize = 12.sp)
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
                                Text("+ Add".tr(), color = DiveColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
        Text(text = label.tr(), color = DiveColors.Text, fontSize = 13.sp)
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
        Text(text = label.tr(), color = DiveColors.Text, fontSize = 13.sp)
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
private fun LanguageCard(
    currentLang: String,
    onLanguageChange: (String) -> Unit
) {
    SettingsCard(title = "LANGUAGE / DİL") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.Border),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .clickable { onLanguageChange("en") }
                    .background(if (currentLang == "en") DiveColors.Accent else Color.Transparent)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("English", color = if (currentLang == "en") Color.White else DiveColors.Text, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                    .clickable { onLanguageChange("tr") }
                    .background(if (currentLang == "tr") DiveColors.Accent else Color.Transparent)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Türkçe", color = if (currentLang == "tr") Color.White else DiveColors.Text, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ThemeCard() {
    SettingsCard(title = "THEME") {
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
                    text = "Dark".tr(),
                    color = DiveColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Dive Into Crypto brand — fixed".tr(),
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
private fun AboutCard() {
    SettingsCard(title = "ABOUT") {
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
        AboutRow("Indicators", "15 (Dive Into Crypto consensus engine)")
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
            text = label.tr(),
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
            text = title.tr(),
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
    wsDataSource: String,
    chartCandleCount: Int,
    onSourceChange: (String) -> Unit,
    onLimitChange: (Int) -> Unit
) {
    SettingsCard(title = "QUANTITATIVE DATA & CHART SETTINGS") {
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
    taker: Double,
    oi: Double,
    whale: Double,
    account: Double,
    onWeightsChange: (Double, Double, Double, Double) -> Unit
) {
    SettingsCard(title = "QUANT BIAS FORMULA WEIGHTS") {
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
