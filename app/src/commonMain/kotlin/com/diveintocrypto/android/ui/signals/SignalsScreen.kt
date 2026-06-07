package com.diveintocrypto.android.ui.signals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.panel.components.RiskBadge
import com.diveintocrypto.android.ui.panel.components.SignalBadge
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Signals and Indicator Details — a faithful port of
 * dashboard/templates/signals.html.
 *
 * Top-down structure:
 *   1. PageHeader  — signals.html:11-14 (.page-header h1 + .update-time)
 *   2. Consensus Result card                       — signals.html:17-59
 *      - 2×2 metric box grid (Final Signal / Confidence / Risk / Weighted Score)
 *      - Action + Should Trade row                — signals.html:46-55
 *      - reason-text                              — signals.html:56-58
 *   3. Signal Distribution card                    — signals.html:62-76
 *      - dist-bar with BUY / NEUTRAL / SELL segments
 *   4. Indicator Details ({count}) card            — signals.html:79-125
 *      - mobile: vertical stack of DataCard items, one per indicator.
 *        Same 6 fields as desktop's `<tr>` (name + signal + score + weight +
 *        weighted + reason), just stacked instead of side-by-side. Desktop's
 *        6-column horizontally-scrollable table did not fit a 360dp phone.
 *      - empty-state when votes is empty          — signals.html:122-124
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalsScreen(container: AppContainer) {
    val vm: SignalsViewModel = viewModel { SignalsViewModel(container) }
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
            // from dashboard/templates/signals.html:11-14 (.page-header)
            PageHeader(
                title = "Signals",
                lastUpdateMs = state.lastUpdateMs,
                stale = false,
                onRefresh = { vm.refresh() },
            )

            // 12-Timeframe Grid Selector
            TimeframeGrid(
                activeTf = state.timeframe,
                tfSignals = state.tfSignals,
                onTfSelected = { tf ->
                    container.activeTimeframe.value = tf
                }
            )

            val tfState = state.tfSignals[state.timeframe] ?: TfSignalState(timeframe = state.timeframe)

            // from dashboard/templates/signals.html:17-59 (.card "Consensus Result")
            ConsensusResultCard(state = tfState)

            // from dashboard/templates/signals.html:62-76 (.card "Signal Distribution" + .dist-bar)
            DistributionCard(buy = tfState.buyCount, neutral = tfState.neutralCount, sell = tfState.sellCount)

            // from dashboard/templates/signals.html:79-125 (.card "Indicator Details")
            IndicatorTableCard(votes = tfState.votes)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// Consensus Result — from dashboard/templates/signals.html:17-59
// ════════════════════════════════════════════════════════════════════════
@Composable
private fun ConsensusResultCard(state: TfSignalState) {
    DiveCard(title = "Consensus Result") {
        // .grid .grid-4 — signals.html:19-45. Mobile keeps the 4-up row on a
        // single line (was 2×2 on previous mobile pass) since dropping each
        // big-number from 28→16sp lets all four fit at ~80dp per cell.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // .metric-box "Final Signal" — signals.html:20-25
            MetricBoxCol(label = "Final Signal", modifier = Modifier.weight(1f)) {
                SignalBadge(state.finalSignal)
            }
            // .metric-box "Confidence" — signals.html:26-32
            MetricBoxCol(label = "Confidence", modifier = Modifier.weight(1f)) {
                // .big-number — style.css:147 (desktop is 28px; mobile 16sp).
                Text(
                    text = "${state.confidence}%",
                    color = DiveColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.height(3.dp))
                // .progress-bar + .progress-fill — style.css:202-215
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DiveColors.Bg)) {
                    val pct = state.confidence.coerceIn(0, 100).toFloat() / 100f
                    Box(modifier = Modifier
                        .fillMaxWidth(pct)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DiveColors.Accent))
                }
            }
            // .metric-box "Risk Level" — signals.html:33-38
            MetricBoxCol(label = "Risk", modifier = Modifier.weight(1f)) {
                RiskBadge(state.riskLevel)
            }
            // .metric-box "Weighted Score" — signals.html:39-44
            MetricBoxCol(label = "W. Score", modifier = Modifier.weight(1f)) {
                val ws = state.weightedScore
                val color = when {
                    ws > 0 -> DiveColors.Green        // .positive — style.css:149
                    ws < 0 -> DiveColors.Red          // .negative — style.css:150
                    else -> DiveColors.Text
                }
                Text(
                    text = "${ws.format(3)}",
                    color = color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                    maxLines = 1,
                )
            }
        }

        // .grid .grid-2 Action + Should Trade — signals.html:46-55
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabelValueCol(label = "Action", value = state.action, modifier = Modifier.weight(1f))
            LabelValueCol(
                label = "Should Trade",
                value = if (state.shouldTrade) "Yes" else "No",
                modifier = Modifier.weight(1f),
            )
        }

        // .reason-text — signals.html:56-58 + style.css:151-159
        if (state.reason.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DiveDims.Radius))
                    .background(DiveColors.Bg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = state.reason,
                    color = DiveColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = DiveFonts.body,
                )
            }
        }
    }
}

// .metric-box — style.css:171 (text-align:center; padding 8px 0). Tight on mobile.
@Composable
private fun MetricBoxCol(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // .label — style.css:143 (11px on desktop; 9sp on mobile to keep
        // 4 boxes legible across a single 360dp row).
        Text(
            text = label.uppercase(),
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            letterSpacing = 0.3.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        content()
    }
}

// signals.html:46-54 — .label above, .value below (left aligned, not metric-box)
@Composable
private fun LabelValueCol(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = DiveColors.TextDim,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        // .value — style.css:144 (14px desktop; 13sp mobile).
        Text(value, color = DiveColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ════════════════════════════════════════════════════════════════════════
// Signal Distribution — from dashboard/templates/signals.html:62-76
// .dist-bar (style.css:218-235): height 36px, segments share width by ratio.
// ════════════════════════════════════════════════════════════════════════
@Composable
private fun DistributionCard(buy: Int, neutral: Int, sell: Int) {
    DiveCard(title = "Signal Distribution") {
        val total = (buy + neutral + sell).coerceAtLeast(1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(DiveDims.Radius)),
        ) {
            DistSegment(
                label = "BUY $buy",
                weight = buy.toFloat() / total,
                bg = DiveColors.GreenTint25, fg = DiveColors.Green,
            )
            DistSegment(
                label = "NEUTRAL $neutral",
                weight = neutral.toFloat() / total,
                bg = DiveColors.NeutralTint15, fg = DiveColors.TextMuted,
            )
            DistSegment(
                label = "SELL $sell",
                weight = sell.toFloat() / total,
                bg = DiveColors.RedTint25, fg = DiveColors.Red,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DistSegment(
    label: String,
    weight: Float,
    bg: Color,
    fg: Color,
) {
    if (weight <= 0.001f) return
    Box(
        modifier = Modifier
            .weight(weight)
            .height(36.dp)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ════════════════════════════════════════════════════════════════════════
// Indicator Details — from dashboard/templates/signals.html:79-125
// True horizontal-scroll table — a faithful port of desktop's .table-wrap
// (style.css:247-271). One <tr> per indicator with 6 columns:
//   Indicator | Signal | Score | Weight | W.Score | Reason
// The previous mobile pass wrapped each row into a 440px-tall DataCard
// stack; only 2 of the 15 indicators were visible per screen. Compact
// table rows (~34dp) fit all 15 inside one DiveCard without scroll, and
// long Reason text scrolls horizontally inside the row instead of pushing
// the row height.
// ════════════════════════════════════════════════════════════════════════
private val COL_NAME_WIDTH = 70.dp
private val COL_SIGNAL_WIDTH = 90.dp
private val COL_SCORE_WIDTH = 56.dp
private val COL_WEIGHT_WIDTH = 56.dp
private val COL_WSCORE_WIDTH = 60.dp
private val COL_REASON_WIDTH = 300.dp

@Composable
private fun IndicatorTableCard(votes: List<IndicatorVoteRow>) {
    DiveCard(title = "Indicator Details (${votes.size})") {
        if (votes.isEmpty()) {
            // .empty-state — style.css:272-277
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No indicators computed yet",
                    color = DiveColors.TextDim,
                    fontSize = 12.sp,
                )
            }
        } else {
            // Single horizontal-scroll surface shared by header + body so the
            // columns stay aligned when the user scrolls right to see Reason.
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                IndicatorTableHeader()
                // Thin divider between thead and tbody — style.css:255
                Box(modifier = Modifier
                    .width(totalRowWidth())
                    .height(1.dp)
                    .background(DiveColors.Border))
                votes.forEachIndexed { idx, row ->
                    IndicatorTableRow(row = row, zebra = idx % 2 == 1)
                }
            }
        }
    }
}

// Combined column width for the inner-row Box so the header + body share
// the same scroll surface and stay column-aligned.
private fun totalRowWidth(): Dp =
    COL_NAME_WIDTH + COL_SIGNAL_WIDTH + COL_SCORE_WIDTH +
        COL_WEIGHT_WIDTH + COL_WSCORE_WIDTH + COL_REASON_WIDTH

@Composable
private fun IndicatorTableHeader() {
    // thead — signals.html:84-92. 10sp uppercase letter-spaced labels.
    Row(
        modifier = Modifier
            .width(totalRowWidth())
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableHeadCell("Indicator", COL_NAME_WIDTH)
        TableHeadCell("Signal", COL_SIGNAL_WIDTH)
        TableHeadCell("Score", COL_SCORE_WIDTH, end = true)
        TableHeadCell("Weight", COL_WEIGHT_WIDTH, end = true)
        TableHeadCell("W. Score", COL_WSCORE_WIDTH, end = true)
        TableHeadCell("Reason", COL_REASON_WIDTH)
    }
}

@Composable
private fun TableHeadCell(label: String, width: Dp, end: Boolean = false) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp),
        contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = label.uppercase(),
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            letterSpacing = 0.4.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun IndicatorTableRow(row: IndicatorVoteRow, zebra: Boolean) {
    // Zebra striping — style.css:265 (tbody tr:nth-child(even) background)
    val bg = if (zebra) DiveColors.BgCardHover else Color.Transparent
    val scoreColor = when {
        row.score > 0 -> DiveColors.Green
        row.score < 0 -> DiveColors.Red
        else -> DiveColors.Text
    }
    val wsColor = when {
        row.weightedScore > 0 -> DiveColors.Green
        row.weightedScore < 0 -> DiveColors.Red
        else -> DiveColors.Text
    }
    val scoreText = (if (row.score >= 0) "+" else "") + row.score.toString()
    val wsText = "${row.weightedScore.format(2, plus = true)}"
    val weightText = "${row.weight.format(1)}"
    val reason = row.reason.ifBlank { "—" }

    Row(
        modifier = Modifier
            .width(totalRowWidth())
            .background(bg)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableBodyCell(COL_NAME_WIDTH) {
            Text(
                text = row.name,
                color = DiveColors.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = DiveFonts.body,
                maxLines = 1,
            )
        }
        TableBodyCell(COL_SIGNAL_WIDTH) {
            SignalBadge(row.signal)
        }
        TableBodyCell(COL_SCORE_WIDTH, end = true) {
            Text(scoreText, color = scoreColor, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = DiveFonts.body, maxLines = 1)
        }
        TableBodyCell(COL_WEIGHT_WIDTH, end = true) {
            Text(weightText, color = DiveColors.Text, fontSize = 12.sp,
                fontFamily = DiveFonts.body, maxLines = 1)
        }
        TableBodyCell(COL_WSCORE_WIDTH, end = true) {
            Text(wsText, color = wsColor, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = DiveFonts.body, maxLines = 1)
        }
        TableBodyCell(COL_REASON_WIDTH) {
            Text(reason, color = DiveColors.TextMuted, fontSize = 11.sp,
                fontFamily = DiveFonts.body, maxLines = 1)
        }
    }
}

@Composable
private fun TableBodyCell(
    width: Dp,
    end: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp),
        contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        content()
    }
}

// ════════════════════════════════════════════════════════════════════════
// 12-Timeframe Grid components
// ════════════════════════════════════════════════════════════════════════
@Composable
private fun TimeframeGrid(
    activeTf: String,
    tfSignals: Map<String, TfSignalState>,
    onTfSelected: (String) -> Unit
) {
    DiveCard(title = "Timeframe Signals (12 TF)") {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            com.diveintocrypto.android.ui.panel.ALL_TIMEFRAMES.chunked(4).forEach { rowTfs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowTfs.forEach { tf ->
                        val tfState = tfSignals[tf] ?: TfSignalState(timeframe = tf)
                        val isSelected = tf == activeTf
                        TimeframeCell(
                            state = tfState,
                            isSelected = isSelected,
                            onClick = { onTfSelected(tf) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(4 - rowTfs.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeframeCell(
    state: TfSignalState,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val signalColor = when {
        state.finalSignal.contains("BUY") -> DiveColors.Green
        state.finalSignal.contains("SELL") -> DiveColors.Red
        else -> DiveColors.TextMuted
    }

    val borderColor = if (isSelected) {
        DiveColors.Accent
    } else {
        signalColor.copy(alpha = 0.25f)
    }

    val borderThickness = if (isSelected) 2.dp else 1.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(if (isSelected) DiveColors.BgCardHover else DiveColors.Bg)
            .border(borderThickness, borderColor, RoundedCornerShape(DiveDims.Radius))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = state.timeframe.uppercase(),
            color = if (isSelected) DiveColors.Text else DiveColors.TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body
        )

        val displaySignal = when {
            state.finalSignal == "N/A" -> "N/A"
            state.finalSignal.startsWith("STRONG_") -> state.finalSignal.replace("STRONG_", "S.")
            else -> state.finalSignal
        }

        Text(
            text = displaySignal,
            color = signalColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )

        Text(
            text = "${state.confidence}%",
            color = DiveColors.Text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body
        )
    }
}

