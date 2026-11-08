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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.common.UiLabels
import com.diveintocrypto.android.ui.i18n.LocalDiveStrings
import com.diveintocrypto.android.ui.panel.components.CandleChartCard
import com.diveintocrypto.android.ui.panel.components.ChartMath
import com.diveintocrypto.android.ui.panel.components.LiveTfGrid
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.panel.components.SignalDistributionCard
import com.diveintocrypto.android.ui.panel.components.FinalVerdictCard
import com.diveintocrypto.android.ui.panel.components.StatusBar
import com.diveintocrypto.android.ui.panel.components.StaleChip
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts
import kotlinx.coroutines.delay

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
    val strings = LocalDiveStrings.current
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
                title = strings.panelTitleActiveCoin,
                lastUpdateMs = state.lastUpdateMs,
                stale = state.lastUpdateMs?.let { (nowMillis() - it) > 30_000 } ?: false,
                onRefresh = { vm.refresh() },
            )

            // HONESTY: data-freshness chip from the VM's staleness fields —
            // "BAĞLANIYOR" until the first WS frame, amber "GECİKME · N sn önce" when quiet.
            StaleChip(isStale = state.isStale, dataAgeMs = state.dataAgeMs)

            SymbolSearchBar(
                state = state,
                onSearchChange = vm::setSearchQuery,
                onSelectSymbol = vm::selectSymbol
            )

            state.errorMessage?.let { message ->
                ErrorBanner(
                    message = message,
                    onRetry = { vm.refresh() }
                )
            }

            StatusBar(
                state = state,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            // CANDLESTICK CHART — engine-cached candles + EMA20/50 + Bollinger fill
            // + volume + last-price line + CVD delta strip + vol-cone ±envelope
            // (all from the SAME series the verdict was computed over). Empty data →
            // honest "VERİ YOK — yenile".
            CandleChartCard(
                state = state,
                cone = state.cone,
                onRefresh = { vm.refresh() },
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            DiveCard(
                title = strings.panelTfGridTitle(state.activeSymbol),
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            ) {
                LiveTfGrid(items = state.multiTf)
            }

            FinalVerdictCard(
                state = state,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            // STRATEGY OVERLAYS (README's "3 overlays") — ADDITIVE annotations from the
            // new PanelUiState fields. They NEVER change the verdict; "—" = not computable.
            StrategyOverlaysCard(
                state = state,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            // 0.3.0 PARITY BLOCKS — funding lens · basis · volatility band.
            // Every field is nullable-honest ("—" = the fetch/math could not be done).
            FundingCard(
                lens = state.fundingLens,
                regime = state.regime,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            BasisCard(
                block = state.basisBlock,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            VolatilityCard(
                plan = state.planning,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            SignalDistributionCard(
                buy = state.distBuy,
                sell = state.distSell,
                neutral = state.distNeutral,
                modifier = Modifier.alpha(if (state.isLoading) 0.5f else 1f)
            )

            // PERMANENT NOTICE — the Panel is an analytics view, never investment advice.
            Text(
                text = strings.disclaimerShort,
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = DiveFonts.body,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
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
    val strings = LocalDiveStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.RedTint15)
            .border(width = 1.dp, color = DiveColors.Red, shape = RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = strings.panelErrorTitle,
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
                text = strings.btnRetry,
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
    val strings = LocalDiveStrings.current
    val popularCoins = state.favorites.ifEmpty {
        listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "LINKUSDT", "AVAXUSDT")
    }

    DiveCard(title = strings.panelSearchTitle) {
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
                    Text(strings.panelSearchPlaceholder, color = DiveColors.TextDim, fontSize = 13.sp)
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
                    Text(strings.panelNoMatch, color = DiveColors.TextMuted, fontSize = 12.sp)
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
                                    Text(strings.panelActive, color = DiveColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

// ═══════════════════════════════════════════════════════════════════════
// Strategy overlay annotations — Rejim · MTF-Confluence · Mikroyapı.
// Rendered straight from the PanelUiState fields; nullable overlay values
// render an honest "—" (never an invented number).
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun StrategyOverlaysCard(state: PanelUiState, modifier: Modifier = Modifier) {
    val strings = LocalDiveStrings.current
    val dirArrow: (Int) -> String = { d -> when { d > 0 -> "▲"; d < 0 -> "▼"; else -> "·" } }
    val dirColor: (Int?) -> Color = { d -> when {
        d != null && d > 0 -> DiveColors.Green
        d != null && d < 0 -> DiveColors.Red
        else -> DiveColors.TextMuted
    } }

    val gate = if (state.mtfGate) strings.panelMtfGateOpen else strings.panelMtfGateClosed
    val mtfText = "${dirArrow(state.mtfDirection)} ${state.mtfScore.format(0, plus = true)} · " +
        "${state.mtfLabel} · $gate"

    val microText: String = state.microScore?.let { score ->
        val label = state.microLabel?.removePrefix("STRONG_") ?: "—"
        val active = state.microActive?.let { " · ${strings.panelSignalsCount(it)}" } ?: ""
        "${dirArrow(state.microDirection ?: 0)} ${score.format(0, plus = true)} · $label$active"
    } ?: "—"

    DiveCard(title = strings.panelStrategyTitle, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayRow(
                label = strings.panelRegimeLabel,
                value = "${state.regime} · ${state.regimeAdaptiveScore.format(1, plus = true)}",
                valueColor = when (state.regime) {
                    "TREND" -> DiveColors.Cyan
                    "RANGE" -> DiveColors.Purple
                    else -> DiveColors.TextMuted
                },
            )
            OverlayRow(label = "MTF", value = mtfText, valueColor = dirColor(state.mtfDirection))
            OverlayRow(label = strings.panelMicroLabel, value = microText, valueColor = dirColor(state.microDirection))
        }
    }
}

@Composable
private fun OverlayRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DiveColors.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.width(86.dp),
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 0.3.0 parity cards — FUNDING · BASIS · VOLATILITY. All inputs come from the
// PanelViewModel's parity bundle; null = honest "—", never a fabricated zero.
// ═══════════════════════════════════════════════════════════════════════

/** Volatility-band decimals: keeps the ChartMath decimals at or above 1, and significant digits below 1. */
private fun bandDecimals(price: Double): Int =
    com.diveintocrypto.android.ui.performance.sigDecimalsBelowOne(price, ChartMath.priceDecimalsFor(price))

/**
 * FUNDING card — predicted vs settled rate (% / 8h), simple APR, the regime
 * label (NÖTR/POZİTİF/NEGATİF from the predicted rate) and a LIVE ticking
 * countdown from [FundingAnalytics.FundingLens.secondsToFunding]; "—" when
 * the venue gave no settlement time (never a fake clock).
 */
@Composable
private fun FundingCard(
    lens: com.diveintocrypto.android.engine.analytics.FundingAnalytics.FundingLens?,
    regime: String,
    modifier: Modifier = Modifier,
) {
    val strings = LocalDiveStrings.current
    // Tick once per second, seeded on each new lens emission; the countdown
    // counts DOWN from the fetched secondsToFunding. null → static "—".
    var elapsedSec by remember(lens?.secondsToFunding) { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(lens?.secondsToFunding) {
        if (lens?.secondsToFunding != null) {
            while (true) {
                delay(1_000)
                elapsedSec += 1
            }
        }
    }
    val secondsLeft = lens?.secondsToFunding?.let { (it - elapsedSec).coerceAtLeast(0L) }

    val predictedRate = lens?.predictedRatePct ?: 0.0
    val regimeLabel = UiLabels.fundingRegimeLabel(predictedRate, strings)
    // Colour follows the sign of the predicted rate (same ±0.01 band as the label), never the localized text.
    val regimeColor = when {
        predictedRate > 0.01 -> DiveColors.Green
        predictedRate < -0.01 -> DiveColors.Red
        else -> DiveColors.TextMuted
    }

    DiveCard(title = strings.panelFundingTitle, modifier = modifier) {
        if (lens == null) {
            Text(
                text = strings.panelFundingUnavailable,
                color = DiveColors.TextDim,
                fontSize = 12.sp,
                fontFamily = DiveFonts.body,
            )
            return@DiveCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FundingCell(strings.panelFundingPredicted, "${lens.predictedRatePct.format(4, plus = true)}%", Modifier.weight(1f))
            // Per-cell dash when only the settled history is missing; predicted + APR stay real.
            FundingCell(
                strings.panelFundingLast,
                lens.lastSettledRatePct?.let { "${it.format(4, plus = true)}%" } ?: "—",
                Modifier.weight(1f),
            )
            FundingCell("APR", "${lens.aprPct.format(1, plus = true)}%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.panelFundingTimeLabel,
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = UiLabels.countdownLabel(secondsLeft),
                color = if (secondsLeft != null) DiveColors.Text else DiveColors.TextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.Mono,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = regimeLabel,
                color = regimeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                fontFamily = DiveFonts.body,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(regimeColor.copy(alpha = 0.12f))
                    .border(1.dp, regimeColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = strings.panelRegimeTag(regime),
                color = DiveColors.TextDim,
                fontSize = 9.sp,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

@Composable
private fun FundingCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            color = DiveColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.Mono,
        )
    }
}

/** BASIS card — perp basis in bps + annualised predicted funding; "—" when null. */
@Composable
private fun BasisCard(
    block: com.diveintocrypto.android.engine.analytics.BasisAnalytics.BasisBlock?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalDiveStrings.current
    DiveCard(title = strings.panelBasisTitle, modifier = modifier) {
        if (block == null) {
            Text(
                text = strings.panelBasisUnavailable,
                color = DiveColors.TextDim,
                fontSize = 12.sp,
                fontFamily = DiveFonts.body,
            )
            return@DiveCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.panelBasisTitle,
                    color = DiveColors.TextDim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = "${block.basisBps.format(1, plus = true)} bps",
                    color = if (block.basisBps >= 0) DiveColors.Green else DiveColors.Red,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.Mono,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.panelAnnFunding,
                    color = DiveColors.TextDim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = "${block.annFundingPct.format(1, plus = true)}%",
                    color = if (block.annFundingPct >= 0) DiveColors.Green else DiveColors.Red,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.Mono,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = strings.panelBasisCaption,
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * VOLATILITY card — ATR% and the ATR-based volatility band around the last price.
 * Descriptive only: no trade levels, no direction, no position guidance. The band
 * is an illustrative ATR envelope, labelled with the short not-advice notice.
 */
@Composable
private fun VolatilityCard(
    plan: com.diveintocrypto.android.engine.analytics.PlanningStrip.Plan?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalDiveStrings.current
    DiveCard(title = strings.panelVolTitle, modifier = modifier) {
        if (plan == null) {
            Text(
                text = strings.panelPlanUnavailable,
                color = DiveColors.TextDim,
                fontSize = 12.sp,
                fontFamily = DiveFonts.body,
            )
            return@DiveCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlanCell("ATR%", plan.atrPct.format(2), modifier = Modifier.weight(1f))
            PlanCell(strings.panelBandLow, plan.envLow.format(bandDecimals(plan.envLow)), modifier = Modifier.weight(1f))
            PlanCell(strings.panelBandHigh, plan.envHigh.format(bandDecimals(plan.envHigh)), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = strings.panelPlanNote,
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun PlanCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            color = DiveColors.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.Mono,
            maxLines = 1,
        )
    }
}
