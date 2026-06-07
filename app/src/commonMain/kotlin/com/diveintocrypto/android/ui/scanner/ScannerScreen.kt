package com.diveintocrypto.android.ui.scanner

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.formatTime
import kotlin.math.abs
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Scanner — mobile-first redesign.
 *
 * The previous incarnation forced a 16-column cross-ranking table into a
 * 360dp viewport, hidden behind a horizontalScroll that cut off everything
 * past column 5 (Direction/NetNSS/TF/1m). The user could NOT see all the
 * info on a phone like that.
 *
 * New layout, top to bottom:
 *   1. ScanHeroCard       — manual start/stop, scan stats, primary CTA
 *   2. (scanning)         — ProgressBlock with current symbol + bar
 *   3. (after scan)       — TfPillBar (active TF selector, horizontal pills)
 *   4. (after scan)       — CommonInfoChip (cross-TF agreement banner)
 *   5. (after scan)       — Vertical list of CoinResultCards.
 *
 *   Each CoinResultCard packs ALL info for one symbol into a single card,
 *   visible without horizontal scroll:
 *     - rank + symbol + signal pill + live price (row 1)
 *     - confidence bar (0..100%) + risk badge (row 2)
 *     - 4×3 mini-grid showing all 12 timeframes, color-tinted by signal,
 *       label = TF + confidence% (rows 3-5)
 *     - cross-TF agreement chip + "Select" CTA (row 6)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(container: AppContainer, onSelectSymbol: (String) -> Unit = {}) {
    // App-scoped: uses the single instance from AppContainer instead of a screen-scoped
    // viewModel{} so the scan does not stop on navigation between screens or in the background.
    val vm: ScannerViewModel = container.scannerViewModel
    val state by vm.ui.collectAsStateWithLifecycle()

    // Result lens: "All" (consensus order, first TOP_N) vs "Divergence" (only rows flagged
    // with a whale-L/S bearish divergence, by divergence score). Presents divergence as a
    // discoverable lens without polluting the consensus order (reviewer P0).
    var resultFilter by remember { mutableStateOf(ResultFilter.ALL) }
    // ALL = survivors after elimination (as many as the table size); DIVERGENCE = eliminated.
    val eliminatedCount = state.eliminated.size
    // OPT-IN "Divergence Sort": when ON, the SAME survivor pool (state.survivors) is re-sorted
    // by |divergenceScore| desc — the cross-sectional top-K validated by backtest (rows with the
    // highest |divergence|) is surfaced into the visible window. Rows below the threshold
    // (DIVERGENCE_MIN_SHOWN) keep their default (netNss+0.35×div) order and sink to the bottom.
    // When OFF (default), the list stays bit-for-bit identical to today's. This is ONLY a
    // presentation re-sort: the pool/elimination/score are UNCHANGED (sortedByDescending is
    // stable → the relative order of sub-threshold rows is preserved).
    val orderedSurvivors = if (state.divergenceSort) {
        state.survivors.sortedByDescending { row ->
            if (abs(row.divergenceScore) >= ScannerViewModel.DIVERGENCE_MIN_SHOWN)
                abs(row.divergenceScore) else Double.NEGATIVE_INFINITY
        }
    } else {
        state.survivors
    }
    val displayedRows = when (resultFilter) {
        ResultFilter.ALL -> orderedSurvivors.take(state.displaySize)
        ResultFilter.DIVERGENCE -> state.eliminated
    }
    val resultSummary = when (resultFilter) {
        ResultFilter.ALL -> "${state.universeSize} symbols · ✓ ${state.survivors.size} kept · ✕ $eliminatedCount eliminated"
        ResultFilter.DIVERGENCE -> "Whale divergence contradicts the indicator: $eliminatedCount coins eliminated"
    }
    // Number of candidate symbols tried but with no whale data available (coverage==0) — added
    // to the empty message to honestly surface silent false-negatives.
    val uncheckableCount = state.crossRanking.count { it.divergenceCoverage == 0 }

    PullToRefreshBox(
        isRefreshing = state.scanning,
        onRefresh = { vm.startDemoScan() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DiveColors.RootBg),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        // ── 1) Hero / Control card ────────────────────────────────────────
        item("hero") {
            ScanHeroCard(
                state = state,
                resultSummary = resultSummary,
                onStart = vm::startDemoScan,
                onStop = vm::stopDemoScan,
            )
        }

        // ── 1b) Scan controls: table size button + continuous (back-to-back) mode ──
        item("controls") {
            ScanControlsRow(
                displaySize = state.displaySize,
                continuous = state.continuous,
                cycle = state.cycle,
                scanning = state.scanning,
                divergenceSort = state.divergenceSort,
                onSize = vm::setDisplaySize,
                onContinuous = vm::setContinuous,
                onDivergenceSort = vm::setDivergenceSort,
            )
        }

        // ── 2) Live progress while scanning ───────────────────────────────
        if (state.scanning) {
            item("progress") { ScanProgressBlock(state = state) }
        }

        // Results (survivors after elimination). Also visible during a scan in continuous mode.
        if (state.survivors.isNotEmpty()) {
            // ── Result lens toggle ─────────────────────────────────────────
            item("filter") {
                ResultFilterToggle(
                    selected = resultFilter,
                    flaggedCount = eliminatedCount,
                    onSelect = { resultFilter = it },
                )
            }

            // ── Divergence lens explanation (only when there are results; one message when empty) ──
            if (resultFilter == ResultFilter.DIVERGENCE && displayedRows.isNotEmpty()) {
                item("divinfo") { DivergenceLensInfo() }
            }

            if (resultFilter == ResultFilter.DIVERGENCE && displayedRows.isEmpty()) {
                item("nodiv") { NoDivergenceCard(uncheckable = uncheckableCount) }
            } else {
                // ── Coin result cards ─────────────────────────────────────
                itemsIndexed(
                    items = displayedRows,
                    key = { _, row -> row.symbol },
                ) { idx, row ->
                    CoinResultCard(row = row, rank = idx + 1, allTfs = state.timeframes, onSelect = { onSelectSymbol(row.symbol) })
                }
            }

            // ── Footer with scan time ──────────────────────────────────────
            state.lastScanAtMs?.let {
                item("ts") {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(
                            text = "Last scan: ${formatTs(it)}",
                            color = DiveColors.TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Initial empty state — before the user has ever pressed Start
        if (state.survivors.isEmpty() && !state.scanning) {
            item("empty") { EmptyStateCard() }
        }

        state.error?.let { err ->
            item("err") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DiveDims.Radius))
                        .background(DiveColors.RedTint15)
                        .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(DiveDims.Radius))
                        .padding(12.dp),
                ) {
                    Text("Error: $err", color = DiveColors.Red, fontSize = 13.sp)
                }
            }
        }

        // Bottom spacer so the last card isn't kissed by the bottom nav bar
        item("spacer") { Spacer(modifier = Modifier.height(16.dp)) }
    }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 1) Scan hero card — primary control surface
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun ScanHeroCard(
    state: ScannerUiState,
    resultSummary: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        DiveColors.BgCard,
                        Color(0xFF1E2333),
                    ),
                ),
            )
            .border(1.dp, DiveColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 3px accent strip (cyan) — keeps the brand element
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DiveColors.Cyan),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MANUAL SCAN",
                    color = DiveColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = heroSubtitle(state, resultSummary),
                    color = DiveColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Binance USDT-M Futures · ${state.timeframes.size} timeframes",
                    color = DiveColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Primary CTA — full-width pill button
        PrimaryCtaButton(
            scanning = state.scanning,
            onStart = onStart,
            onStop = onStop,
        )
    }
}

@Composable
private fun PrimaryCtaButton(scanning: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val bg = if (scanning) DiveColors.Red else DiveColors.Accent
    val label = if (scanning) "Stop" else "Start Scan"
    val icon = if (scanning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { if (scanning) onStop() else onStart() }
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 2) Progress block while scanning
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun ScanProgressBlock(state: ScannerUiState) {
    val pct = if (state.totalCount > 0) state.completedCount.toFloat() / state.totalCount else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(DiveColors.Cyan),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = phaseLabel(state.currentPhase),
                    color = DiveColors.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    fontFamily = DiveFonts.body,
                )
            }
            Text(
                text = "${state.completedCount}/${state.totalCount}",
                color = DiveColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(DiveColors.BgCardHover),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(DiveColors.Cyan, DiveColors.Green),
                        ),
                    ),
            )
        }
        if (state.currentSymbol != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "NOW",
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.currentSymbol,
                    color = DiveColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
            }
        }
    }
}

private fun phaseLabel(p: ScanPhase): String = when (p) {
    ScanPhase.UNIVERSE -> "FETCHING UNIVERSE"
    ScanPhase.PHASE1 -> "PHASE 1 · 1d/12h/8h"
    ScanPhase.PHASE2 -> "PHASE 2 · 9 TF"
    ScanPhase.FINALIZING -> "RANKING"
    ScanPhase.IDLE -> "SCANNING"
}

// ═════════════════════════════════════════════════════════════════════════
// Coin result card — ONE coin, ALL info, no horizontal scroll
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun CoinResultCard(row: CrossRankingRow, rank: Int, allTfs: List<String>, onSelect: () -> Unit) {
    val isCommonAll = row.countHit == row.totalTfs
    val accentColor = when {
        row.dominantDir.score > 0 -> DiveColors.Green
        row.dominantDir.score < 0 -> DiveColors.Red
        else -> DiveColors.TextMuted
    }
    val cardBg = DiveColors.BgCard
    val borderColor = when {
        isCommonAll -> Color(0x6600FF80)
        row.countHit >= row.totalTfs - 1 -> Color(0x66EAB308)
        else -> DiveColors.Border
    }
    // Best per-TF confidence — the actual 0-100 number from the consensus engine.
    // Distinct from `row.netNss`, which is the time-weighted cross-rank sort key.
    val bestConfidence = row.perTf.values.maxOfOrNull { it.confidence } ?: 0
    val riskLevel = riskFromAgreement(row.countHit, row.totalTfs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
    ) {
        // 3px top accent strip — color-coded by signal
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(accentColor),
        )

        Column(modifier = Modifier.padding(14.dp)) {
            // ── Row 1: Rank · Symbol · Signal · Price ─────────────────────
            CoinCardHeader(
                row = row,
                rank = rank,
                isCommonAll = isCommonAll,
                accentColor = accentColor,
            )

            Spacer(Modifier.height(12.dp))

            // ── Row 2: CONFIDENCE bar (real per-TF max confidence) + Risk ──────
            ConfidenceBlock(
                confidence = bestConfidence,
                accentColor = accentColor,
                risk = riskLevel,
            )

            Spacer(Modifier.height(8.dp))

            // ── Row 3: SCORE (net_NSS) + agreement count ───────────────────
            ScoreBlock(netNss = row.netNss, countHit = row.countHit, totalTfs = row.totalTfs)

            // ── Row 3b: Whale L/S DIRECTIONAL divergence alert (if detected) ──
            if (abs(row.divergenceScore) >= ScannerViewModel.DIVERGENCE_MIN_SHOWN) {
                Spacer(Modifier.height(8.dp))
                WhaleDivergenceBlock(
                    score = row.divergenceScore,
                    tf = row.divergenceTf,
                    risePct = row.divergenceRisePct,
                    whaleDrop = row.divergenceWhaleDrop,
                    patternDirection = row.divergencePatternDirection,
                )
            }

            // ── Row 3c: RISK advisor (INFORMATIONAL — not investment advice) ──
            // ATR-adaptive bracket suggestion; does NOT affect score/elimination/ranking.
            row.atrPct?.let { atrPct ->
                if (atrPct > 0.0) {
                    Spacer(Modifier.height(8.dp))
                    RiskAdvisorBlock(atrPct = atrPct)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Rows 4-6: 4×3 TF grid showing ALL 12 timeframes ───────────
            TfGrid(perTf = row.perTf, allTfs = allTfs)

            Spacer(Modifier.height(12.dp))

            // ── Row 7: Select CTA ─────────────────────────────────────────
            CoinCardFooterCta(onSelect = onSelect)
        }
    }
}

/**
 * Whale L/S divergence alert block. **The label/color/arrow = DESCRIPTIVE raw whale
 * movement (patternDirection), NOT sign(score).** The score is the EMPIRICAL CONTRARIAN
 * predictive ranking score: a DISTRIBUTION pattern (price ↑ + whale ↓) now ranks
 * UP (+score) because top-trader L/S is contrarian.
 *   • Distribution (patternDirection<0): red ▼ "DISTRIBUTION" — Price +, Whale −.
 *   • Accumulation  (patternDirection≥0): green ▲ "ACCUMULATION" — Price −, Whale +.
 * The score number is ALWAYS shown with its sign; the contrarian note explains why
 * distribution ranks up. The score scales with the TF resolution (1d → high).
 */
@Composable
private fun WhaleDivergenceBlock(
    score: Double,
    tf: String?,
    risePct: Double,
    whaleDrop: Double,
    patternDirection: Int,
) {
    // Descriptive pattern: did the whale SELL (distribution) or BUY (accumulation)? NOT sign(score).
    val isDistribution = patternDirection < 0
    // Distribution = red ▼ (whales selling), accumulation = green ▲ (whales accumulating).
    val color = if (isDistribution) DiveColors.Red else DiveColors.Green
    val arrow = if (isDistribution) "▼" else "▲"
    val title = if (isDistribution) "DISTRIBUTION" else "ACCUMULATION"
    // Distribution: price rose (+), whale fell (−). Accumulation: price fell (−), whale rose (+).
    val priceSign = if (isDistribution) "+" else "−"
    val whaleSign = if (isDistribution) "−" else "+"
    // The score is the EMPIRICAL CONTRARIAN ranking score — show its sign AS IS (distribution = +).
    val scoreSign = if (score >= 0.0) "+" else "−"
    // An honest note when the ranking direction (contrarian prediction) is OPPOSITE the descriptive pattern.
    val contrarianNote = if (isDistribution)
        "Contrarian: price has historically continued upward → RANK ↑"
    else
        "Contrarian: price has historically continued downward → RANK ↓"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = color, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = title + (tf?.let { " · $it" } ?: ""),
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.body,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = "Price $priceSign${(abs(risePct) * 100).format(1)}% · Whale $whaleSign${(whaleDrop * 100).format(1)}%",
                    color = DiveColors.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = DiveFonts.body,
                )
                Text(
                    text = contrarianNote,
                    color = DiveColors.TextMuted,
                    fontSize = 8.sp,
                    fontFamily = DiveFonts.body,
                    lineHeight = 11.sp,
                )
                // Honest holding-horizon hint (descriptive text — does NOT affect signal/ranking logic).
                Text(
                    text = "Suggested hold: 24–48 hours",
                    color = DiveColors.TextMuted,
                    fontSize = 8.sp,
                    fontFamily = DiveFonts.body,
                    lineHeight = 11.sp,
                )
            }
        }
        Text(
            text = "$scoreSign${abs(score).format(0)}",
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * RISK ADVISOR — INFORMATIONAL only, NOT investment advice.
 *
 * Shows the ATR-adaptive bracket validated in docs/DIVERGENCE_VALIDATION.md §8
 * (a real strategy backtest, 120 trades), scaled to each coin's volatility:
 *   • Stop  = 2.5 × ATR(1h)  (as a price-%) — reduces noise-driven stop-outs
 *     (≈ 65-68% hit rate, vs ≈ 59.7% for a fixed -1.6% stop).
 *   • Target = 1.5 × Stop      (reward:risk = 1.5).
 *   • Max leverage ≈ riskPct / stopPct (risking 5% of the account). At 50x, full-account
 *     margin ≈ 100% liquidation risk — even with an edge. This row is PURELY explanatory;
 *     it does NOT touch the score/elimination/ranking/auto-trade logic.
 *
 *  Position: risk 5-10% of the account (per-trade). Max leverage is derived with riskPct = 5%.
 */
@Composable
private fun RiskAdvisorBlock(atrPct: Double) {
    // All values are in price-%. ATR% comes from the indicator layer (atr_filter → atr_pct).
    val stopPct = 2.5 * atrPct          // 2.5 × ATR(1h) — adaptive stop
    val targetPct = 1.5 * stopPct       // reward:risk = 1.5
    val riskPct = 5.0                   // 5% of the account (conservative end of the 5-10 range)
    val maxLev = if (stopPct > 0.0) riskPct / stopPct else 0.0
    val amber = Color(0xFFF59E0B)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "RISK",
                color = amber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "suggestion · not investment advice",
                color = DiveColors.TextDim,
                fontSize = 8.sp,
                fontFamily = DiveFonts.body,
            )
        }
        Text(
            text = "Stop ≈ −${stopPct.format(2)}% (2.5×ATR) · Target +${targetPct.format(2)}% " +
                "· Max ~${maxLev.format(1)}x · Size: 5-10% of account",
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 13.sp,
        )
        Text(
            text = "⚠ 50x + full-account margin ≈ 100% liquidation risk (even with an edge) — " +
                "cap leverage relative to the stop %.",
            color = DiveColors.Red,
            fontSize = 9.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 12.sp,
        )
    }
}

@Composable
private fun CoinCardHeader(
    row: CrossRankingRow,
    rank: Int,
    isCommonAll: Boolean,
    accentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Rank chip — always visible so the user can read "#1, #2, …" at a glance.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(DiveColors.BgCardHover)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = "#$rank",
                color = DiveColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.body,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isCommonAll) {
            Text(
                "★",
                color = DiveColors.Green,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = row.symbol,
            color = DiveColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.width(8.dp))
        // Signal pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(accentColor.copy(alpha = 0.15f))
                .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = row.dominantDir.name,
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Price
        Text(
            text = "${'$'}${row.price.format(2, grouped = true)}",
            color = DiveColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun ConfidenceBlock(confidence: Int, accentColor: Color, risk: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CONFIDENCE",
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$confidence%",
            color = DiveColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(10.dp))
        // Progress fill
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(DiveColors.BgCardHover),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(confidence / 100f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor),
            )
        }
        Spacer(Modifier.width(10.dp))
        RiskChip(risk = risk)
    }
}

/**
 * SCORE block — surfaces the time-weighted net NSS that actually drives the
 * cross-rank sort. Previously hidden inside `row.netNss` and miscast as
 * "confidence" → the user could not see WHY one coin outranked another.
 *
 *   `net_nss = Σ final_score (dominant side) − Σ final_score (opposite side)`
 *   `final_score = confidence² × timeWeight / 100`
 *
 * The full integer score is shown alongside its "K-format" abbreviation; both
 * are nice on the eye but the integer is the canonical value.
 */
@Composable
private fun ScoreBlock(netNss: Double, countHit: Int, totalTfs: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SCORE",
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatScore(netNss),
            color = DiveColors.Yellow,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "(${netNss.toInt()})",
            color = DiveColors.TextDim,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(modifier = Modifier.weight(1f))
        // Agreement chip — moved out of the footer so the user can see it
        // alongside the score that depends on it.
        val (chipBg, chipFg) = when {
            countHit >= 3 -> Color(0x2600FF80) to DiveColors.Green
            countHit == 2 -> Color(0x26EAB308) to DiveColors.Yellow
            else -> DiveColors.BgCardHover to DiveColors.TextMuted
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(chipBg)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$countHit/$totalTfs",
                color = chipFg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "TF AGREE",
                color = chipFg,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

@Composable
private fun RiskChip(risk: String) {
    val (bg, fg) = when (risk.uppercase()) {
        "LOW" -> DiveColors.GreenTint15 to DiveColors.Green
        "MEDIUM" -> DiveColors.YellowTint15 to DiveColors.Yellow
        "HIGH" -> DiveColors.RedTint15 to DiveColors.Red
        else -> DiveColors.NeutralTint15 to DiveColors.TextDim
    }
    Text(
        text = risk.uppercase(),
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        fontFamily = DiveFonts.body,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// 4×3 grid of TF cells — ALL 12 timeframes visible, no horizontal scroll.
// Cell = TF label + confidence%, tinted by signal direction.
@Composable
private fun TfGrid(perTf: Map<String, TfSlotState>, allTfs: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "ALL ${allTfs.size} TIMEFRAMES",
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.height(8.dp))
        // 3 rows × 4 columns = 12 cells
        val rows = allTfs.chunked(4)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { rowTfs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowTfs.forEach { tf ->
                        TfCell(tf = tf, slot = perTf[tf], modifier = Modifier.weight(1f))
                    }
                    // Fill remaining columns if last row is short
                    repeat(4 - rowTfs.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TfCell(tf: String, slot: TfSlotState?, modifier: Modifier = Modifier) {
    val (bg, fg, borderColor) = when {
        slot == null -> Triple(DiveColors.BgCardHover, DiveColors.TextDim, DiveColors.Border)
        slot.signal.score > 0 -> Triple(DiveColors.GreenTint15, DiveColors.Green, DiveColors.GreenTint25)
        slot.signal.score < 0 -> Triple(DiveColors.RedTint15, DiveColors.Red, DiveColors.RedTint25)
        else -> Triple(DiveColors.NeutralTint15, DiveColors.TextMuted, DiveColors.Border)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tf,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (slot != null) "${slot.confidence}%" else "—",
            color = if (slot != null) fg.copy(alpha = 0.85f) else DiveColors.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * Footer with just the Select CTA — the agreement chip has migrated into the
 * `ScoreBlock` row so that SCORE and `N/12 TF AGREE` sit side-by-side (the user
 * needs both to judge the score's quality).
 */
@Composable
private fun CoinCardFooterCta(onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.Accent)
                .clickable { onSelect() }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(
                text = "SELECT →",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Empty state — invites the user to start
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun EmptyStateCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔎", fontSize = 32.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "No Scan Run Yet",
            color = DiveColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap the \"Start Scan\" button above",
            color = DiveColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────
private fun formatTs(ms: Long): String {
    return formatTime(ms, "dd.MM.yyyy HH:mm:ss")
}

/**
 * Risk derived from cross-TF agreement count, NOT from the (mis-scaled) NSS.
 * Same thresholds as the ViewModel's `riskFromCount`:
 *   ≥ total-1  → LOW    (almost all TFs aligned)
 *   ≥ total/2  → MEDIUM (majority aligned)
 *   > 0        → HIGH   (only a few TFs aligned)
 *   else       → N/A
 */
private fun riskFromAgreement(countHit: Int, totalTfs: Int): String = when {
    countHit >= totalTfs - 1 -> "LOW"
    countHit >= totalTfs / 2 -> "MEDIUM"
    countHit > 0 -> "HIGH"
    else -> "N/A"
}

/**
 * Format the cross-rank net NSS for compact display. The raw integer is huge
 * (often 1k-30k after summing final_score across TFs), so we render a K-suffix
 * abbreviation in the SCORE headline and keep the raw int in parentheses so
 * detail-oriented users can still see the canonical value.
 */
private fun formatScore(s: Double): String = when {
    s >= 10000 -> "${(s / 1000).format(1)}K"
    s >= 1000 -> "${(s / 1000).format(2)}K"
    else -> s.toInt().toString()
}

private fun heroSubtitle(state: ScannerUiState, resultSummary: String): String = when (state.currentPhase) {
    ScanPhase.UNIVERSE -> "Fetching symbol list..."
    ScanPhase.PHASE1 ->
        "Phase 1 · scanning 1d/12h/8h"
    ScanPhase.PHASE2 ->
        "Phase 2 · remaining 9 timeframes"
    ScanPhase.FINALIZING -> "Computing cross-ranking..."
    ScanPhase.IDLE -> when {
        state.universeLoading -> "Fetching symbol list..."
        state.hotList.isNotEmpty() -> resultSummary // lens-aware (All/Divergence)
        else -> "All Binance USDT-M · 12 timeframes"
    }
}

/** Show only this many highest-confidence cards after a scan completes. */
/** Scanner result lens. */
private enum class ResultFilter { ALL, DIVERGENCE }

/** "All" / "⚠ Divergence (n)" two-pill lens selector. */
@Composable
private fun ResultFilterToggle(
    selected: ResultFilter,
    flaggedCount: Int,
    onSelect: (ResultFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(
            label = "All",
            selected = selected == ResultFilter.ALL,
            accent = DiveColors.Accent,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(ResultFilter.ALL) },
        )
        FilterPill(
            label = if (flaggedCount > 0) "✕ Eliminated ($flaggedCount)" else "✕ Eliminated",
            selected = selected == ResultFilter.DIVERGENCE,
            accent = Color(0xFFF59E0B),
            modifier = Modifier.weight(1f),
            onClick = { onSelect(ResultFilter.DIVERGENCE) },
        )
    }
}

/** Scan controls: table size (5/10/15/20/All) + Divergence Sort lens + CONTINUOUS (back-to-back) mode button. */
@Composable
private fun ScanControlsRow(
    displaySize: Int,
    continuous: Boolean,
    cycle: Int,
    scanning: Boolean,
    divergenceSort: Boolean,
    onSize: (Int) -> Unit,
    onContinuous: (Boolean) -> Unit,
    onDivergenceSort: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "TABLE SIZE",
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = DiveFonts.body,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (n in listOf(5, 10, 15, 20, Int.MAX_VALUE)) {
                FilterPill(
                    label = if (n >= Int.MAX_VALUE / 2) "All" else "$n",
                    selected = displaySize == n,
                    accent = DiveColors.Accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onSize(n) },
                )
            }
        }
        // ── OPT-IN "Divergence Sort" lens (default OFF) ─────────────
        // When ON, the SAME survivors are shown by |divergence score| desc (the cross-sectional
        // top-K validated by backtest). Only the display order changes; score/elimination are unchanged.
        FilterPill(
            label = if (divergenceSort) "◆ DIVERGENCE SORT ON" else "DIVERGENCE SORT OFF",
            selected = divergenceSort,
            accent = Color(0xFFF59E0B),
            modifier = Modifier.fillMaxWidth(),
            onClick = { onDivergenceSort(!divergenceSort) },
        )
        if (divergenceSort) {
            Text(
                text = "Display sorted by |divergence score| ↓ — backtest: top 3 divergences ≈ 61–76% (24–48h). " +
                    "Ranking/score/elimination are UNCHANGED; display order only.",
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontFamily = DiveFonts.body,
            )
        }
        FilterPill(
            label = if (continuous) {
                if (scanning) "● CONTINUOUS · cycle #$cycle" else "● CONTINUOUS ON"
            } else "CONTINUOUS OFF",
            selected = continuous,
            accent = DiveColors.Cyan,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onContinuous(!continuous) },
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) accent.copy(alpha = 0.18f) else DiveColors.BgCard
    val border = if (selected) accent.copy(alpha = 0.6f) else DiveColors.Border
    val fg = if (selected) accent else DiveColors.TextMuted
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
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

/** Info card that explains the "Divergence" lens concept once (discoverability). */
@Composable
private fun DivergenceLensInfo() {
    val amber = Color(0xFFF59E0B)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(amber.copy(alpha = 0.08f))
            .border(1.dp, amber.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "⚠ WHALE L/S DIVERGENCE",
            color = amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.4.sp,
            fontFamily = DiveFonts.body,
        )
        Text(
            text = "Label = the whale's RAW movement; SCORE = EMPIRICAL CONTRARIAN ranking. " +
                "▼ DISTRIBUTION (red): price ↑ + whale L/S ↓ (whales selling into a rally). " +
                "Top-trader L/S is contrarian → price has historically CONTINUED upward, " +
                "so the SCORE is POSITIVE (+) and the coin ranks UP. " +
                "▲ ACCUMULATION (green): price ↓ + whale L/S ↑ → contrarian expectation is a decline " +
                "→ SCORE NEGATIVE (−), ranks down. A higher timeframe (1d) scores stronger than a " +
                "lower one (5m).",
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 15.sp,
        )
        // ── Honest historical backtest + suggested holding horizon (descriptive text only) ──
        // This block is PURELY explanatory; it does NOT affect signal/ranking/elimination logic.
        Text(
            text = "HISTORICAL BACKTEST / SUGGESTED HOLD",
            color = amber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.4.sp,
            fontFamily = DiveFonts.body,
        )
        Text(
            text = "Suggested hold: 24–48 hours. " +
                "Most reliable: the divergence picks in the top 3 ranks. " +
                "Historical backtest: top 3 divergences ≈ 61–64% correct (24–48h, 2%+ move) — not guaranteed. " +
                "Contrarian: whale selling = DISTRIBUTION label, but historically price has " +
                "continued upward → rank ↑.",
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 15.sp,
        )
    }
}

/** Info shown when the "Divergence" lens is empty. If `uncheckable` > 0, honestly notes
 *  the number of symbols whose whale data could not be fetched (silent false-negative risk). */
@Composable
private fun NoDivergenceCard(uncheckable: Int) {
    val msg = buildString {
        append("No whale L/S divergence was found in this scan.")
        if (uncheckable > 0) append("  ($uncheckable symbols had no whale data — partial check.)")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = msg,
            color = DiveColors.TextMuted,
            fontSize = 13.sp,
            fontFamily = DiveFonts.body,
        )
    }
}
