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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.diveintocrypto.android.domain.evidence.EvidenceGrader
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.common.UiLabels
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
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current

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
                title = strings.leaderboardTitle,
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
                DiveCard(title = strings.cardScannedUniverse) {
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

            LeaderboardCard(title = strings.cardGainers, rows = state.gainers, valueColor = DiveColors.Green)
            LeaderboardCard(title = strings.cardLosers, rows = state.losers, valueColor = DiveColors.Red)
            LeaderboardCard(
                title = strings.cardVolume,
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
            Text(com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current.noData, color = DiveColors.TextDim, fontSize = 11.sp)
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
    DiveCard(title = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current.cardLoading) {
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
// MOTOR KANITI v2 (kendini notlama) — verdict-evidence self-grading section.
// Renders EvidenceState straight from the VM; nothing here recomputes grades,
// invents buckets or hides a failed pass (stale/error honest chips).
// v2 additions: Wilson hit-rate rendering ("57% [45–89] · n=214"), gated
// buckets, ECE 10-bin mini strip + Brier skill badges, and 7G/30G/TÜMÜ
// window chips switching the displayed bucket set from [EvidenceState.windows].
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
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    // Rolling-window lens: the displayed bucket set comes from state.windows.
    var windowKey by remember { mutableStateOf(EvidenceGrader.WINDOW_ALL) }

    /** Localized window label (7G/30G/TÜMÜ in TR; 7D/30D/ALL in EN). */
    fun windowLabel(key: String): String = when (key) {
        EvidenceGrader.WINDOW_7D -> strings.window7d
        EvidenceGrader.WINDOW_30D -> strings.window30d
        else -> strings.windowAll
    }

    DiveCard(title = strings.evidenceTitle) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── Horizon chips + GRADE button ──────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    1L to strings.horizon1h,
                    4L to strings.horizon4h,
                    24L to strings.horizon24h,
                ).forEach { (hours, label) ->
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
                text = strings.coverageLine(evidence.archived, evidence.graded, evidence.horizonHours),
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = DiveFonts.body,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (evidence.stale) {
                    HonestChip(text = strings.chipPartial, color = DiveColors.Warn)
                }
                if (evidence.error != null) {
                    HonestChip(text = "${strings.errorPrefix} ${evidence.error}", color = DiveColors.Red)
                }
            }

            // ── Calibration badges: ECE (10-bin strip) + Brier skill ──
            CalibrationBadgesRow(evidence = evidence)

            // ── Window chips: 7G / 30G / TÜMÜ ─────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    EvidenceGrader.WINDOW_7D,
                    EvidenceGrader.WINDOW_30D,
                    EvidenceGrader.WINDOW_ALL,
                ).forEach { key ->
                    HorizonChip(
                        label = windowLabel(key),
                        selected = windowKey == key,
                        enabled = true,
                        onClick = { windowKey = key },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Verdict bucket cards (from the SELECTED window) ───────
            val windowGrade = evidence.windows[windowKey]
            val byVerdict = windowGrade?.byVerdict ?: evidence.byVerdict
            val byConfidence = windowGrade?.byConfidence ?: evidence.byConfidence

            if (evidence.archived == 0 && evidence.graded == 0) {
                Text(
                    text = strings.noEvidence,
                    color = DiveColors.TextDim,
                    fontSize = 12.sp,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VERDICT_BUCKETS.forEach { (key, label) ->
                        val stats = byVerdict[key]
                        VerdictBucketCard(
                            label = label,
                            stats = stats,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // ── byConfidence mini-table ───────────────────────────
                if (byConfidence.isNotEmpty()) {
                    Text(
                        text = "${strings.lblConfidenceBands} · ${windowLabel(windowKey)}",
                        color = DiveColors.TextDim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontFamily = DiveFonts.body,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        byConfidence.entries
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

/**
 * ECE badge (with the 10-bin reliability mini strip) + Brier + Brier-skill
 * badges. All values come straight from the v2 state; null → "—" (no graded
 * samples yet — never a fabricated zero).
 */
@Composable
private fun CalibrationBadgesRow(evidence: EvidenceState) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ECE badge with the 10-bin mini bar strip.
        Column(
            modifier = Modifier
                .weight(1.6f)
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.BgCardHover)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ECE",
                    color = DiveColors.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = UiLabels.eceLabel(evidence.ece),
                    color = DiveColors.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.Mono,
                )
            }
            Spacer(Modifier.height(5.dp))
            // 10-bin strip: bar height = bin share, color = |pred − obs| gap.
            val fractions = UiLabels.binBarFractions(evidence.eceBins.map { it.samples })
            val heights = listOf(4.dp, 8.dp, 12.dp, 16.dp)
            Row(
                modifier = Modifier.fillMaxWidth().height(16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                evidence.eceBins.forEachIndexed { i, bin ->
                    val f = fractions.getOrElse(i) { 0f }
                    val h = heights[(f * (heights.size - 1)).toInt().coerceIn(0, heights.size - 1)]
                    val colorToken = UiLabels.binGapColorToken(bin.gap)
                    val color = when (colorToken) {
                        UiLabels.TOK_GREEN -> DiveColors.Green
                        UiLabels.TOK_WARN -> DiveColors.Warn
                        UiLabels.TOK_RED -> DiveColors.Red
                        else -> DiveColors.TextDim
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(h)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (bin.samples > 0) color else DiveColors.Border),
                    )
                }
                repeat((10 - evidence.eceBins.size).coerceAtLeast(0)) {
                    Box(modifier = Modifier.weight(1f).height(4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.binsCaption,
                color = DiveColors.TextDim,
                fontSize = 9.sp,
                fontFamily = DiveFonts.body,
            )
        }

        // Brier + skill badge.
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.BgCardHover)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "BRIER",
                color = DiveColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                fontFamily = DiveFonts.body,
            )
            Text(
                text = evidence.brier?.let { it.format(3) } ?: "—",
                color = DiveColors.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.Mono,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.lblScore,
                color = DiveColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                fontFamily = DiveFonts.body,
            )
            val skillToken = when {
                evidence.brierSkill == null -> UiLabels.TOK_MUTED
                evidence.brierSkill > 0 -> UiLabels.TOK_GREEN
                else -> UiLabels.TOK_RED
            }
            Text(
                text = UiLabels.brierSkillLabel(evidence.brierSkill),
                color = when (skillToken) {
                    UiLabels.TOK_GREEN -> DiveColors.Green
                    UiLabels.TOK_RED -> DiveColors.Red
                    else -> DiveColors.TextMuted
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.Mono,
            )
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
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                stateDescription = if (selected) strings.selected else strings.notSelected
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
            text = if (isGrading) com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current.gradingBusy
            else com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current.btnRegrade,
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
            // v2 Wilson rendering: "57% [45–89] · n=214"; n<5 → "—"; gated → dim + note.
            val dimmed = stats.gated && stats.samples < 5
            val note = UiLabels.smallSampleNote(stats.samples, stats.gated)
            Text(
                text = UiLabels.hitRateLabel(stats.samples, stats.hitRate, stats.hitRateLo, stats.hitRateHi),
                color = if (dimmed) DiveColors.TextMuted else DiveColors.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.Mono,
                maxLines = 2,
                lineHeight = 14.sp,
            )
            if (note != null) {
                Text(
                    text = note,
                    color = DiveColors.TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = DiveFonts.body,
                )
            }
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
                        .fillMaxWidth((stats.hitRate?.toFloat() ?: 0f).coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (stats.gated) DiveColors.TextMuted else DiveColors.Accent),
                )
            }
            Text(
                text = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current.medianPrefix +
                    " ${stats.medianReturnPct.format(2, plus = true)}%",
                color = if (stats.medianReturnPct >= 0) DiveColors.Green else DiveColors.Red,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

@Composable
private fun ConfidenceRow(band: String, stats: EvidenceBucketStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(DiveColors.BgCardHover)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
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
            // v2 Wilson hit-rate with interval; "—" below the n<5 display floor.
            Text(
                text = UiLabels.hitRateLabel(stats.samples, stats.hitRate, stats.hitRateLo, stats.hitRateHi)
                    .removeSuffix(" · n=${stats.samples}"),
                color = if (stats.gated) DiveColors.TextMuted else DiveColors.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.Mono,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${stats.medianReturnPct.format(2, plus = true)}%",
                color = if (stats.medianReturnPct >= 0) DiveColors.Green else DiveColors.Red,
                fontSize = 11.sp,
                fontFamily = DiveFonts.body,
            )
        }
        UiLabels.smallSampleNote(stats.samples, stats.gated)?.let { note ->
            Text(
                text = note,
                color = DiveColors.TextDim,
                fontSize = 9.sp,
                fontFamily = DiveFonts.body,
            )
        }
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
