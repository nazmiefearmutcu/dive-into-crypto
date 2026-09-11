package com.diveintocrypto.android.ui.performance

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.domain.evidence.EvidenceBucketStats
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(container: AppContainer) {
    val vm: PerformanceViewModel = viewModel { PerformanceViewModel(container) }
    val state by vm.ui.collectAsStateWithLifecycle()

    // Pull-to-refresh wired to the VM's PUBLIC refresh() (loads once on init otherwise).
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
                title = "24h Leaderboard",
                lastUpdateMs = state.lastUpdateMs,
                stale = state.lastUpdateMs?.let { (nowMillis() - it) > 60_000 } ?: false,
                onRefresh = { vm.refresh() },
            )

            // ── MOTOR KANITI (Task 5): the engine grades its own archived verdicts
            //    against forward returns — hit-rate + median return per verdict and
            //    confidence band. Honest chips for partial coverage / errors. ──
            EvidenceCard(
                evidence = state.evidence,
                onHorizon = vm::setEvidenceHorizon,
                onGrade = vm::refreshEvidence,
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

// ═══════════════════════════════════════════════════════════════════════
// MOTOR KANITI (kendini notlama) — verdict-evidence self-grading section.
// Renders EvidenceState straight from the VM; nothing here recomputes grades,
// invents buckets or hides a failed pass (stale/error honest chips).
// ═══════════════════════════════════════════════════════════════════════

/** Fixed verdict-bucket display order: LONG (BUY) · SHORT (SELL) · NEUTRAL. */
private val VERDICT_BUCKETS: List<Pair<String, String>> = listOf(
    "BUY" to "LONG",
    "SELL" to "SHORT",
    "NEUTRAL" to "NEUTRAL",
)

@Composable
fun EvidenceCard(
    evidence: EvidenceState,
    onHorizon: (Long) -> Unit,
    onGrade: () -> Unit,
) {
    DiveCard(title = "MOTOR KANITI (kendini notlama)") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── Horizon chips + GRADE button ──────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(1L to "1s", 4L to "4s", 24L to "24s").forEach { (hours, label) ->
                    HorizonChip(
                        label = label,
                        selected = evidence.horizonHours == hours,
                        enabled = !evidence.isGrading,
                        onClick = { onHorizon(hours) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            GradeButton(isGrading = evidence.isGrading, onGrade = onGrade)

            // ── Coverage line + honest chips ──────────────────────────
            Text(
                text = "arşiv ${evidence.archived} · notalı ${evidence.graded} · ufuk ${evidence.horizonHours} sa",
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = DiveFonts.body,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (evidence.stale) {
                    HonestChip(text = "KISMİ — eski kayıtlar notalı değil", color = DiveColors.Warn)
                }
                if (evidence.error != null) {
                    HonestChip(text = "hata: ${evidence.error}", color = DiveColors.Red)
                }
            }

            // ── Verdict bucket cards ──────────────────────────────────
            if (evidence.archived == 0 && evidence.graded == 0) {
                Text(
                    text = "Kanıt yok — taramalar arşive biriktikçe notlanır.",
                    color = DiveColors.TextDim,
                    fontSize = 12.sp,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VERDICT_BUCKETS.forEach { (key, label) ->
                        val stats = evidence.byVerdict[key]
                        VerdictBucketCard(
                            label = label,
                            stats = stats,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // ── byConfidence mini-table ───────────────────────────
                if (evidence.byConfidence.isNotEmpty()) {
                    Text(
                        text = "GÜVEN BANTLARI",
                        color = DiveColors.TextDim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontFamily = DiveFonts.body,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        evidence.byConfidence.entries
                            .sortedBy { (k, _) -> k.split("-").firstOrNull()?.toIntOrNull() ?: 0 }
                            .forEach { (band, stats) ->
                                ConfidenceRow(band = band, stats = stats)
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizonChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) DiveColors.Accent.copy(alpha = 0.18f) else DiveColors.BgCardHover
    val border = if (selected) DiveColors.Accent.copy(alpha = 0.6f) else DiveColors.Border
    val fg = if (selected) DiveColors.Accent else DiveColors.TextMuted
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                stateDescription = if (selected) "Seçili" else "Seçili değil"
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun GradeButton(isGrading: Boolean, onGrade: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isGrading) DiveColors.BgCardHover else DiveColors.Accent)
            .semantics { role = Role.Button }
            .clickable(enabled = !isGrading, onClick = onGrade)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isGrading) "NOTLANIYOR…" else "YENİDEN NOTLA",
            // Preset-aware on-accent color; dim text while grading (disabled).
            color = if (isGrading) DiveColors.TextMuted else MaterialTheme.colorScheme.onPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun VerdictBucketCard(label: String, stats: EvidenceBucketStats?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = when (label) {
                "LONG" -> DiveColors.Green
                "SHORT" -> DiveColors.Red
                else -> DiveColors.TextMuted
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
        )
        if (stats == null || stats.samples == 0) {
            // Honest absence — a bucket with no graded samples renders "—", not zeros.
            Text("n=0 · —", color = DiveColors.TextDim, fontSize = 11.sp, fontFamily = DiveFonts.body)
        } else {
            Text(
                text = "n=${stats.samples} · ${(stats.hitRate * 100).format(0)}%",
                color = DiveColors.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
            )
            // Hit-rate bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(DiveColors.Bg),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(stats.hitRate.toFloat().coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(DiveColors.Accent),
                )
            }
            Text(
                text = "medyan ${stats.medianReturnPct.format(2, plus = true)}%",
                color = if (stats.medianReturnPct >= 0) DiveColors.Green else DiveColors.Red,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

@Composable
private fun ConfidenceRow(band: String, stats: EvidenceBucketStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(DiveColors.BgCardHover)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = band,
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = DiveFonts.Mono,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = "n=${stats.samples}",
            color = DiveColors.TextDim,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = "%${(stats.hitRate * 100).format(0)}",
            color = DiveColors.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${stats.medianReturnPct.format(2, plus = true)}%",
            color = if (stats.medianReturnPct >= 0) DiveColors.Green else DiveColors.Red,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun HonestChip(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = DiveFonts.body,
        lineHeight = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
