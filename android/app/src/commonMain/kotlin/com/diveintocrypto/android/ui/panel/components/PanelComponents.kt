package com.diveintocrypto.android.ui.panel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.panel.PanelUiState
import com.diveintocrypto.android.ui.panel.ALL_TIMEFRAMES
import com.diveintocrypto.android.ui.panel.TfSignal
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

// ═══════════════════════════════════════════════════════════════════════
// PageHeader — title + last-updated row. Paper bot status badges removed;
// stale flag now derived from how old the last poll timestamp is.
// ═══════════════════════════════════════════════════════════════════════
@Composable
fun PageHeader(title: String, lastUpdateMs: Long?, stale: Boolean, onRefresh: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = DiveColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val timeText = lastUpdateMs?.let { agoString(it) } ?: "—"
            Text(
                text = "Last update: $timeText",
                color = if (stale) DiveColors.Orange else DiveColors.TextDim,
                fontSize = 10.sp,
            )
        }
        // Reliable refresh button — the swipe-to-refresh gesture is inconsistent on some devices.
        if (onRefresh != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(DiveDims.RadiusSm))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.RadiusSm))
                    .clickable { onRefresh() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("⟳ Refresh", color = DiveColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DiveFonts.body)
            }
        }
    }
}

private fun agoString(ms: Long): String {
    val diff = nowMillis() - ms
    val sec = diff / 1000
    return when {
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        else -> "${sec / 3600}h ago"
    }
}

// ═══════════════════════════════════════════════════════════════════════
// StatusBar — paper-free 4-cell grid: Symbol · Price · TF · Signal.
// Earlier 8-cell version exposed bot/leverage/cycle/mode/marketType — all
// removed with paper mode. The 4 remaining cells are the ones the user
// actually reads at a glance.
// ═══════════════════════════════════════════════════════════════════════
@Composable
fun StatusBar(state: PanelUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusItem("Symbol", state.activeSymbol, accent = true, modifier = Modifier.weight(1f))
        val priceColor = when (state.priceChangeDirection) {
            "UP" -> DiveColors.Green
            "DOWN" -> DiveColors.Red
            else -> DiveColors.Text
        }
        StatusItem(
            "Price",
            state.currentPrice?.let { "${'$'}${it.format(2, grouped = true)}" } ?: "—",
            accentColor = priceColor,
            modifier = Modifier.weight(1f),
        )
        StatusItem("TF", state.timeframe, modifier = Modifier.weight(1f))
        StatusItem(
            "Signal",
            state.latestSignal,
            accentColor = signalColor(state.latestSignal),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusItem(
    label: String,
    value: String,
    accent: Boolean = false,
    accentColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            letterSpacing = 0.4.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            color = accentColor ?: if (accent) DiveColors.Accent else DiveColors.Text,
            fontSize = 12.sp,
            fontWeight = if (accent || accentColor != null) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
            maxLines = 1,
        )
    }
}

private fun signalColor(name: String): Color = when {
    name.contains("BUY") -> DiveColors.Green
    name.contains("SELL") -> DiveColors.Red
    else -> DiveColors.TextMuted
}

// ═══════════════════════════════════════════════════════════════════════
// LiveTfGrid — 4×3 mini grid of 12 TF confidence cells.
// ═══════════════════════════════════════════════════════════════════════
@Composable
fun LiveTfGrid(items: List<TfSignal>) {
    val byTf = items.associateBy { it.tf }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
        ALL_TIMEFRAMES.chunked(4).forEach { rowTfs ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowTfs.forEach { tf ->
                    val item = byTf[tf] ?: TfSignal(tf, "NEUTRAL", 0)
                    LiveTfItem(item = item, modifier = Modifier.weight(1f))
                }
                repeat(4 - rowTfs.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LiveTfItem(item: TfSignal, modifier: Modifier = Modifier) {
    val color = signalColor(item.signal)
    val borderColor = color.copy(alpha = 0.4f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.Bg)
            .border(1.dp, borderColor, RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            item.tf.uppercase(),
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${item.confidence}%",
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            val pct = item.confidence.coerceIn(0, 100).toFloat() / 100f
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            item.signal,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// FinalVerdictCard — paper-free: signal/confidence/action/reason only.
// ═══════════════════════════════════════════════════════════════════════
@Composable
fun FinalVerdictCard(state: PanelUiState, modifier: Modifier = Modifier) {
    DiveCard(title = "Final Verdict", modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBadge(state.latestSignal)
            Spacer(Modifier.height(0.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DecisionKv("Confidence", "${state.confidence}%", accent = signalColor(state.latestSignal))
            DecisionKv("Action", state.action)
        }
        if (state.reason.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.reason,
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DecisionKv(label: String, value: String, accent: Color? = null) {
    Column {
        Text(
            label.uppercase(),
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            letterSpacing = 0.4.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            value,
            color = accent ?: DiveColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// SignalDistributionCard — 3 counters (buy/sell/neutral) + a single-row colored bar.
// ═══════════════════════════════════════════════════════════════════════
@Composable
fun SignalDistributionCard(buy: Int, sell: Int, neutral: Int, modifier: Modifier = Modifier) {
    val total = (buy + sell + neutral).coerceAtLeast(1)
    DiveCard(title = "Signal Distribution", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DistCell("BUY", buy, DiveColors.Green, modifier = Modifier.weight(1f))
            DistCell("SELL", sell, DiveColors.Red, modifier = Modifier.weight(1f))
            DistCell("NEUTRAL", neutral, DiveColors.TextMuted, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DiveColors.BgCardHover),
        ) {
            // Compose's Row.weight requires `weight > 0`. When all counts are 0
            // (boot / pre-fetch state) we collapse the bar to a single neutral
            // segment instead of trying to render three zero-weighted boxes.
            if (buy == 0 && sell == 0 && neutral == 0) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(DiveColors.BgCardHover),
                )
            } else {
                if (buy > 0) {
                    Box(
                        modifier = Modifier
                            .weight(buy.toFloat())
                            .background(DiveColors.Green),
                    )
                }
                if (sell > 0) {
                    Box(
                        modifier = Modifier
                            .weight(sell.toFloat())
                            .background(DiveColors.Red),
                    )
                }
                if (neutral > 0) {
                    Box(
                        modifier = Modifier
                            .weight(neutral.toFloat())
                            .background(DiveColors.TextMuted),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$total indicators",
            color = DiveColors.TextDim,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun DistCell(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DiveDims.RadiusSm))
            .background(color.copy(alpha = 0.10f))
            .padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), color = color, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = DiveFonts.body)
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

