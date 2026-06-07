package com.diveintocrypto.android.ui.panel

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.panel.components.LiveTfGrid
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.panel.components.SignalDistributionCard
import com.diveintocrypto.android.ui.panel.components.FinalVerdictCard
import com.diveintocrypto.android.ui.panel.components.StatusBar
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Panel screen (paper-free). Top to bottom:
 *   1. PageHeader (title + last update)
 *   2. StatusBar (symbol + price + TF + signal)
 *   3. LiveTfGrid (12-TF mini confidence cards)
 *   4. FinalVerdictCard (consensus output)
 *   5. SignalDistributionCard (indicator vote distribution)
 *
 * The old BotControlBar / MetricCardRow / PerformanceSummaryCard / ToastSlot /
 * AlertOverlay were deleted along with all paper-mode dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(container: AppContainer) {
    val vm: PanelViewModel = viewModel { PanelViewModel(container) }
    val state by vm.ui.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DiveColors.RootBg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeader(
                title = "Active Coin",
                lastUpdateMs = state.lastUpdateMs,
                stale = state.lastUpdateMs?.let { (nowMillis() - it) > 30_000 } ?: false,
                onRefresh = { vm.refresh() },
            )

            SymbolSearchBar(
                state = state,
                onSearchChange = vm::setSearchQuery,
                onSelectSymbol = vm::selectSymbol
            )

            if (state.errorMessage != null) {
                ErrorBanner(
                    message = state.errorMessage!!,
                    onRetry = { vm.refresh() }
                )
            }

            StatusBar(
                state = state,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            DiveCard(
                title = "${state.activeSymbol} · 12-Timeframe Consensus Confidence",
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            ) {
                LiveTfGrid(items = state.multiTf)
            }

            FinalVerdictCard(
                state = state,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            SignalDistributionCard(
                buy = state.distBuy,
                sell = state.distSell,
                neutral = state.distNeutral,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.RedTint15)
            .border(width = 1.dp, color = DiveColors.Red, shape = RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = "ERROR",
            color = DiveColors.Red,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = message,
            color = DiveColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(DiveDims.RadiusSm))
                .background(DiveColors.Red)
                .clickable { onRetry() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Retry",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SymbolSearchBar(
    state: PanelUiState,
    onSearchChange: (String) -> Unit,
    onSelectSymbol: (String) -> Unit
) {
    val popularCoins = state.favorites.ifEmpty {
        listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "LINKUSDT", "AVAXUSDT")
    }

    DiveCard(title = "Coin Selection and Search") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DiveDims.Radius))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (state.searchQuery.isEmpty()) {
                    Text("e.g. BTCUSDT, SOLUSDT...", color = DiveColors.TextDim, fontSize = 13.sp)
                }
                BasicTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle = TextStyle(color = DiveColors.Text, fontSize = 13.sp, fontFamily = DiveFonts.body),
                    cursorBrush = SolidColor(DiveColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.searchQuery.isNotEmpty()) {
                val results = state.filteredSymbols
                if (results.isEmpty()) {
                    Text("No matching coin found.", color = DiveColors.TextMuted, fontSize = 12.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        results.take(6).forEach { symbol ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onSelectSymbol(symbol) }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = symbol,
                                    color = if (symbol == state.activeSymbol) DiveColors.Accent else DiveColors.Text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = DiveFonts.body
                                )
                                if (symbol == state.activeSymbol) {
                                    Text("Active", color = DiveColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    popularCoins.forEach { symbol ->
                        val active = symbol == state.activeSymbol
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (active) DiveColors.Accent else DiveColors.BgCard)
                                .border(
                                    1.dp,
                                    if (active) DiveColors.Accent else DiveColors.Border,
                                    RoundedCornerShape(20.dp),
                                )
                                .clickable { onSelectSymbol(symbol) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = symbol,
                                color = if (active) Color.White else DiveColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = DiveFonts.body,
                            )
                        }
                    }
                }
            }
        }
    }
}
