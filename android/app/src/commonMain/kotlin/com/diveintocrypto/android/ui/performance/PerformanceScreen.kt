package com.diveintocrypto.android.ui.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * "Leaders" screen — live 24h market moves instead of paper PnL history:
 *   - Top 10 gainers
 *   - Top 10 losers
 *   - Top 10 symbols by 24h volume
 *
 * A single `/fapi/v1/ticker/24hr` call → 3 different rankings. SKIP_SYMBOLS
 * stablecoins are removed from the universe.
 */
@Composable
fun PerformanceScreen(container: AppContainer) {
    val vm: PerformanceViewModel = viewModel { PerformanceViewModel(container) }
    val state by vm.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.RootBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader(
            title = "24h Leaderboard",
            lastUpdateMs = state.lastUpdateMs,
            stale = state.lastUpdateMs?.let { (nowMillis() - it) > 60_000 } ?: false,
            onRefresh = { vm.refresh() },
        )

        if (state.totalSymbols > 0) {
            DiveCard(title = "SCANNED UNIVERSE") {
                Text(
                    text = "${state.totalSymbols} USDT-M futures symbols · stablecoins removed",
                    color = DiveColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }

        if (state.isLoading) {
            LoadingCard()
        }
        state.error?.let { ErrorCard(it) }

        LeaderboardCard(title = "🚀 TOP GAINERS", rows = state.gainers, valueColor = DiveColors.Green)
        LeaderboardCard(title = "📉 TOP LOSERS", rows = state.losers, valueColor = DiveColors.Red)
        LeaderboardCard(
            title = "💧 HIGHEST VOLUME",
            rows = state.byVolume,
            valueColor = DiveColors.Accent,
            showVolume = true,
        )
    }
}

@Composable
private fun LeaderboardCard(
    title: String,
    rows: List<Ticker24h>,
    valueColor: Color,
    showVolume: Boolean = false,
) {
    DiveCard(title = title) {
        if (rows.isEmpty()) {
            Text("No data", color = DiveColors.TextDim, fontSize = 11.sp)
            return@DiveCard
        }
        rows.forEachIndexed { idx, t ->
            LeaderRow(rank = idx + 1, ticker = t, valueColor = valueColor, showVolume = showVolume)
            if (idx < rows.size - 1) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun LeaderRow(rank: Int, ticker: Ticker24h, valueColor: Color, showVolume: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.RadiusSm))
            .background(DiveColors.BgCardHover)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(DiveColors.Bg)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(
                text = "#$rank",
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.body,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ticker.symbol,
                color = DiveColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
            )
            Text(
                "${'$'}${ticker.lastPrice.format(4, grouped = true)}",
                color = DiveColors.TextDim,
                fontSize = 11.sp,
                fontFamily = DiveFonts.body,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (showVolume) {
                Text(
                    text = "$" + formatVolume(ticker.quoteVolume),
                    color = valueColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.body,
                )
                val sign = if (ticker.priceChangePercent > 0) "+" else ""
                val pctColor = if (ticker.priceChangePercent >= 0) DiveColors.Green else DiveColors.Red
                Text(
                    text = "${sign}${ticker.priceChangePercent.format(2)}%",
                    color = pctColor,
                    fontSize = 11.sp,
                    fontFamily = DiveFonts.body,
                )
            } else {
                val sign = if (ticker.priceChangePercent > 0) "+" else ""
                Text(
                    text = "${sign}${ticker.priceChangePercent.format(2)}%",
                    color = valueColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.body,
                )
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    DiveCard(title = "LOADING") {
        Text("Fetching /fapi/v1/ticker/24hr...", color = DiveColors.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.RedTint15)
            .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(DiveDims.Radius))
            .padding(12.dp),
    ) {
        Text("Error: $msg", color = DiveColors.Red, fontSize = 13.sp)
    }
}

private fun formatVolume(v: Double): String = when {
    v >= 1_000_000_000 -> "${(v / 1_000_000_000).format(2)}B"
    v >= 1_000_000 -> "${(v / 1_000_000).format(2)}M"
    v >= 1_000 -> "${(v / 1_000).format(2)}K"
    else -> "${v.format(0)}"
}
